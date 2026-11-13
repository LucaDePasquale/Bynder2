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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Esito di una ricerca su TCGdex.
 * @param numberMatched true se le carte trovate hanno il numero letto dalla fotocamera;
 *        false se nessuna lo aveva (numero letto male): in quel caso il numero va ignorato.
 */
data class TcgdexSearch(val cards: List<CatalogCard>, val numberMatched: Boolean)

/** Una carta di cui si vuole conoscere il prezzo. [key] è l'id della carta nel catalogo (lingua:set:numero). */
data class PriceRequest(
    val key: String,
    val lang: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String
)

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
        const val MAX_ONLINE_RESULTS = 60
        const val MAX_CACHE_ENTRIES = 300
        const val PREFIX_FALLBACK_LENGTH = 4
        val wordPattern = Regex("""[\p{L}'’\-]{3,}""")
        val imageIdPattern = Regex("""^https://assets\.tcgdex\.net/[^/]+/[^/]+/([^/]+)/([^/]+)/""")
    }

    private data class Brief(val id: String, val localId: String, val name: String, val image: String)

    private val briefCache = ConcurrentHashMap<String, List<Brief>>()
    private val cardCache = ConcurrentHashMap<String, CatalogCard>()

    /**
     * Ricerca libera online, per nome o per illustratore, nelle lingue indicate.
     * I risultati hanno solo i dati dell'elenco (nome, numero, immagine): per il resto vedi [completeCard].
     * Lancia [IOException] solo se la rete non ha risposto in nessuna lingua.
     */
    suspend fun searchOnline(text: String, byArtist: Boolean, languages: List<String>): List<CatalogCard> =
        withContext(Dispatchers.IO) {
            val term = text.trim()
            if (term.length < 2) return@withContext emptyList()
            val field = if (byArtist) "illustrator" else "name"
            val outcomes = coroutineScope {
                languages.map { lang ->
                    async {
                        try {
                            Result.success(requestList(lang, term, field).take(MAX_ONLINE_RESULTS).map { fromBrief(lang, it) })
                        } catch (e: IOException) {
                            Result.failure(e)
                        }
                    }
                }.awaitAll()
            }
            val failure = outcomes.firstOrNull { it.isFailure }?.exceptionOrNull()
            if (failure != null && outcomes.all { it.isFailure }) throw failure as IOException
            outcomes.flatMap { it.getOrNull().orEmpty() }
        }

    /** Completa una carta trovata con [searchOnline]: set, totale, rarità, illustratore e tipo. */
    suspend fun completeCard(card: CatalogCard): CatalogCard = withContext(Dispatchers.IO) {
        val id = "${card.setCode}-${card.number}"
        val key = "${card.lang}:$id"
        cardCache[key] ?: loadDetails(card.lang, Brief(id, card.number, card.name, card.imageUrl))?.also {
            if (cardCache.size >= MAX_CACHE_ENTRIES) cardCache.clear()
            cardCache[key] = it
        } ?: card
    }

    /**
     * Cerca online l'immagine di una carta (stesso set e numero): prima nella sua lingua, poi in
     * italiano, inglese e francese. Restituisce null se nessuna lingua ha l'immagine.
     * Lancia [IOException] se la rete non risponde.
     */
    suspend fun findImage(lang: String, setCode: String, number: String): String? = withContext(Dispatchers.IO) {
        val languages = if (lang == "ja") listOf("ja") else (listOf(lang, "it", "en", "fr")).distinct()
        val setCodes = listOf(setCode, setCode.lowercase()).distinct()
        for (language in languages) {
            for (code in setCodes) {
                val image = imageOf(language, "$code-$number")
                if (image != null) return@withContext image
            }
        }
        null
    }

    /**
     * Altre fonti di immagini (inglesi, per i set troppo nuovi per TCGdex): Scrydex e PokeScope.
     * Si usa la prima il cui indirizzo risponde con un'immagine.
     */
    suspend fun findExternalImage(lang: String, setCode: String, number: String): String? = withContext(Dispatchers.IO) {
        val digits = number.trimStart('0').ifEmpty { "0" }
        if (!digits.all { it.isLetterOrDigit() }) return@withContext null
        val code = setCode.lowercase()
        val codes = listOf(code, code.replace(Regex("([a-z]+)0+([0-9])")) { it.groupValues[1] + it.groupValues[2] }).distinct()
        val candidates = ArrayList<String>()
        for (c in codes) {
            candidates += if (lang == "ja") "https://images.scrydex.com/pokemon/${c}_ja-$digits/large"
            else "https://images.scrydex.com/pokemon/$c-$digits/large"
        }
        if (lang != "ja") for (c in codes) candidates += "https://assets.pokescope.app/cards/$c-$digits/hires.png"
        candidates.firstOrNull { isImage(it) }
    }

    private fun isImage(url: String): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.responseCode == HttpURLConnection.HTTP_OK &&
                (connection.contentType ?: "").startsWith("image")
        } catch (e: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun imageOf(lang: String, id: String): String? {
        return try {
            val json = JSONObject(get("$BASE_URL/$lang/cards/${encode(id)}"))
            json.text("image")?.let { imageUrl(it) }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

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

    /**
     * Cerca il prezzo (euro, tendenza Cardmarket) di ogni carta. Chiama [onPrice] per ogni carta
     * (con null se il prezzo non è stato trovato) e restituisce quante carte hanno ricevuto un prezzo.
     * Lancia [IOException] solo se la rete non ha risposto a nessuna richiesta.
     */
    suspend fun fetchPrices(
        requests: List<PriceRequest>,
        onPrice: suspend (PriceRequest, Double?) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
        val networkFailures = AtomicInteger(0)
        val found = AtomicInteger(0)
        coroutineScope {
            requests.map { request ->
                async {
                    gate.withPermit {
                        val price = try {
                            priceOf(request)
                        } catch (e: IOException) {
                            Log.w(TAG, "Prezzo non disponibile per ${request.key}", e)
                            networkFailures.incrementAndGet()
                            null
                        } catch (e: JSONException) {
                            Log.w(TAG, "Risposta non valida per ${request.key}", e)
                            null
                        }
                        if (price != null) found.incrementAndGet()
                        onPrice(request, price)
                    }
                }
            }.awaitAll()
        }
        if (requests.isNotEmpty() && networkFailures.get() == requests.size) {
            throw IOException("TCGdex non raggiungibile")
        }
        found.get()
    }

    private fun priceOf(request: PriceRequest): Double? {
        val id = idFromImage(request.imageUrl) ?: findId(request) ?: return null
        val body = try {
            get("$BASE_URL/${request.lang}/cards/${encode(id)}")
        } catch (e: FileNotFoundException) {
            return null
        }
        val cardmarket = JSONObject(body).optJSONObject("pricing")?.optJSONObject("cardmarket") ?: return null
        for (field in listOf("trend", "avg", "low")) {
            val value = cardmarket.optDouble(field, Double.NaN)
            if (!value.isNaN() && value > 0.0) return value
        }
        return null
    }

    /** Le immagini di TCGdex contengono l'id della carta: ".../it/sv/sv07/142/high.webp" -> "sv07-142". */
    private fun idFromImage(imageUrl: String): String? =
        imageIdPattern.find(imageUrl)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

    /** Per le carte senza id TCGdex (catalogo PTCG): si cerca per nome e numero e si sceglie in base al set. */
    private fun findId(request: PriceRequest): String? {
        val word = searchWord(request.name) ?: return null
        val numberKey = CardText.normalizeNumber(request.number)
        val sameNumber = search(request.lang, word).filter { CardText.normalizeNumber(it.localId) == numberKey }
        if (sameNumber.isEmpty()) return null
        if (sameNumber.size == 1) return sameNumber.first().id

        val wantedSet = CardText.normalizeName(request.setName)
        if (wantedSet.isEmpty()) return null
        return sameNumber.take(MAX_DETAILS_NAME_ONLY)
            .firstOrNull { brief ->
                val details = cardCache["${request.lang}:${brief.id}"] ?: loadDetails(request.lang, brief)
                details != null && CardText.normalizeName(details.setName) == wantedSet
            }
            ?.id
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

    private fun requestList(lang: String, word: String, field: String = "name"): List<Brief> {
        val body = try {
            get("$BASE_URL/$lang/cards?$field=${encode(word)}")
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
