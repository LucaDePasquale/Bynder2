package com.example.pokemonbynder.data.remote

import android.util.Log
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.scanner.CardText
import com.example.pokemonbynder.scanner.ScanReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Esito di una ricerca su TCGdex.
 * @param numberMatched true se le carte trovate hanno il numero letto dalla fotocamera;
 *        false se nessuna lo aveva (numero letto male): in quel caso il numero va ignorato.
 */
data class TcgdexSearch(val cards: List<CatalogCard>, val numberMatched: Boolean)

/**
 * Cerca le carte su TCGdex (https://tcgdex.dev): API gratuita, senza chiave.
 *
 * Come funziona una ricerca:
 * 1) elenca le carte il cui nome contiene la parola più lunga letta dalla fotocamera
 *    (`/v2/{lingua}/cards?name=pikachu`), in italiano o inglese;
 * 2) se è stato letto il numero (es. 025/165) tiene solo le carte con quel numero, confrontandolo qui
 *    in locale: così non dipende da come il server scrive i numeri (25, 025...);
 * 3) chiede il dettaglio delle carte migliori (`/v2/{lingua}/cards/{id}`) per conoscere il totale
 *    del set, la rarità e l'illustratore, e le restituisce nello stesso formato del catalogo offline.
 *
 * Le risposte restano in memoria per non ripetere le stesse richieste durante una scansione.
 * Se la rete non funziona, i metodi lanciano [IOException].
 */
class TcgdexClient {

    private companion object {
        const val TAG = "TcgdexClient"
        const val BASE_URL = "https://api.tcgdex.net/v2"
        const val TIMEOUT_MS = 8_000
        const val MAX_WORDS = 2
        const val MAX_DETAILS_WITH_NUMBER = 15
        const val MAX_DETAILS_NAME_ONLY = 6
        const val MAX_PARALLEL_REQUESTS = 4
        const val MAX_CACHE_ENTRIES = 300
        const val PREFIX_FALLBACK_LENGTH = 4
        val wordPattern = Regex("""[\p{L}'’\-]{3,}""")
    }

    private data class Brief(val id: String, val localId: String, val name: String, val image: String)

    private val briefCache = ConcurrentHashMap<String, List<Brief>>()
    private val cardCache = ConcurrentHashMap<String, CatalogCard>()

    suspend fun findCards(reading: ScanReading): TcgdexSearch = withContext(Dispatchers.IO) {
        val words = reading.nameLines.mapNotNull { searchWord(it) }.distinct().take(MAX_WORDS)
        if (words.isEmpty()) return@withContext TcgdexSearch(emptyList(), false)

        val nameLines = reading.nameLines.map(CardText::normalizeName).filter { it.length >= 2 }
        val numberKey = reading.number?.key
        val languages = reading.languageHint?.let { listOf(it) } ?: listOf("it", "en")

        for (lang in languages) {
            val briefs = words.flatMap { search(lang, it) }.distinctBy { it.id }
            if (briefs.isEmpty()) continue

            val withNumber = if (numberKey != null) {
                briefs.filter { CardText.normalizeNumber(it.localId) == numberKey }
            } else {
                emptyList()
            }
            val numberMatched = withNumber.isNotEmpty()
            val pool = if (numberMatched) withNumber else briefs
            val chosen = pool
                .sortedByDescending { nameSimilarity(nameLines, it.name) }
                .take(if (numberMatched) MAX_DETAILS_WITH_NUMBER else MAX_DETAILS_NAME_ONLY)

            return@withContext TcgdexSearch(fetchCards(lang, chosen), numberMatched)
        }
        TcgdexSearch(emptyList(), false)
    }

    /** La parola più lunga del nome letto ("Charizard ex" -> "Charizard"), usata per la ricerca. */
    private fun searchWord(name: String): String? =
        wordPattern.findAll(name)
            .map { it.value.trim('-', '\'', '’') }
            .filter { it.length >= 3 }
            .maxByOrNull { it.length }

    private fun nameSimilarity(nameLines: List<String>, cardName: String): Double {
        val normalized = CardText.normalizeName(cardName)
        return nameLines.maxOfOrNull { CardText.similarity(it, normalized) } ?: 0.0
    }

    private fun search(lang: String, word: String): List<Brief> {
        val key = "$lang:${word.lowercase()}"
        briefCache[key]?.let { return it }

        var result = requestList(lang, word)
        // Se il nome è stato letto male (lettere sbagliate in fondo), riprova con l'inizio della parola.
        if (result.isEmpty() && word.length > PREFIX_FALLBACK_LENGTH + 1) {
            result = requestList(lang, word.take(PREFIX_FALLBACK_LENGTH))
        }
        if (briefCache.size >= MAX_CACHE_ENTRIES) briefCache.clear()
        briefCache[key] = result
        return result
    }

    private fun requestList(lang: String, word: String): List<Brief> {
        val body = try {
            get("$BASE_URL/$lang/cards?name=${encode(word)}")
        } catch (e: FileNotFoundException) {
            return emptyList()
        }
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            return emptyList()
        }
        val briefs = ArrayList<Brief>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.text("id") ?: continue
            briefs.add(
                Brief(
                    id = id,
                    localId = item.text("localId") ?: id.substringAfterLast('-'),
                    name = item.text("name").orEmpty(),
                    image = item.text("image").orEmpty()
                )
            )
        }
        return briefs
    }

    private suspend fun fetchCards(lang: String, briefs: List<Brief>): List<CatalogCard> = coroutineScope {
        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
        briefs.map { brief ->
            async {
                gate.withPermit {
                    val key = "$lang:${brief.id}"
                    cardCache[key] ?: loadDetails(lang, brief)?.also {
                        if (cardCache.size >= MAX_CACHE_ENTRIES) cardCache.clear()
                        cardCache[key] = it
                    } ?: fromBrief(lang, brief) // dettaglio non disponibile: si usano i dati dell'elenco
                }
            }
        }.awaitAll()
    }

    private fun loadDetails(lang: String, brief: Brief): CatalogCard? {
        return try {
            val json = JSONObject(get("$BASE_URL/$lang/cards/${encode(brief.id)}"))
            val set = json.optJSONObject("set")
            val name = json.text("name") ?: brief.name
            val number = json.text("localId") ?: brief.localId
            CatalogCard(
                lang = lang,
                setCode = set?.text("id") ?: setIdOf(brief.id),
                setName = set?.text("name").orEmpty(),
                number = number,
                numberKey = CardText.normalizeNumber(number),
                setTotal = set?.optJSONObject("cardCount")?.optInt("official", 0) ?: 0,
                name = name,
                nameNorm = CardText.normalizeName(name),
                rarity = json.text("rarity"),
                artist = json.text("illustrator"),
                cardType = json.text("category").orEmpty(),
                imageUrl = imageUrl(json.text("image") ?: brief.image)
            )
        } catch (e: IOException) {
            Log.w(TAG, "Dettaglio non disponibile per ${brief.id}", e)
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Risposta non valida per ${brief.id}", e)
            null
        }
    }

    private fun fromBrief(lang: String, brief: Brief) = CatalogCard(
        lang = lang,
        setCode = setIdOf(brief.id),
        setName = "",
        number = brief.localId,
        numberKey = CardText.normalizeNumber(brief.localId),
        setTotal = 0,
        name = brief.name,
        nameNorm = CardText.normalizeName(brief.name),
        rarity = null,
        artist = null,
        cardType = "",
        imageUrl = imageUrl(brief.image)
    )

    /** "sv03.5-025" -> "sv03.5" */
    private fun setIdOf(cardId: String) = cardId.substringBeforeLast('-')

    /** TCGdex dà l'indirizzo senza qualità né estensione: si aggiungono come da documentazione. */
    private fun imageUrl(base: String): String = when {
        base.isBlank() -> ""
        base.endsWith(".webp") || base.endsWith(".png") || base.endsWith(".jpg") -> base
        else -> "$base/high.webp"
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PokemonBynder/1.0")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw FileNotFoundException(url)
            if (code !in 200..299) throw IOException("TCGdex ha risposto con errore HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Come optString, ma un valore mancante o null diventa null (e non la stringa "null"). */
    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }
}
