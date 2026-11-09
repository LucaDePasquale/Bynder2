package com.example.pokemonbynder.data.local.catalog

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.pokemonbynder.scanner.CardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Importa assets/catalog.tsv.gz nel database Room, una sola volta (la prima apertura dell'app,
 * pochi secondi) e di nuovo solo se [CATALOG_VERSION] aumenta, cioè quando rigeneri il file.
 */
class CatalogImporter(
    private val context: Context,
    private val database: CatalogDatabase
) {
    companion object {
        const val ASSET_NAME = "catalog.tsv.gz"
        const val SET_PAIRS_ASSET = "set_pairs.tsv"

        /**
         * Set "gemelli" tra italiano e inglese (assets/set_pairs.tsv): da "lingua:codice set"
         * al codice dello stesso set nell'altra lingua. Serve a cambiare la lingua di una carta.
         */
        fun readSetPairs(context: Context): Map<String, String> {
            val pairs = HashMap<String, String>()
            try {
                context.assets.open(SET_PAIRS_ASSET).use { raw ->
                    BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val p = line.split('\t')
                            if (p.size >= 3) pairs["${p[0]}:${p[1]}"] = p[2]
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CatalogImporter", "Impossibile leggere $SET_PAIRS_ASSET", e)
            }
            return pairs
        }

        /** Incrementa questo numero ogni volta che sostituisci il file catalog.tsv.gz. */
        const val CATALOG_VERSION = 3

        private const val PREFS = "catalog_import"
        private const val KEY_VERSION = "version"
        private const val BATCH_SIZE = 1000
        private const val COLUMNS = 10
    }

    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dao = database.catalogDao()
        if (prefs.getInt(KEY_VERSION, 0) == CATALOG_VERSION && dao.count() > 0) return@withContext

        var imported = 0
        try {
            database.withTransaction {
                dao.clear()
                context.assets.open(ASSET_NAME).use { raw ->
                    BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8)).use { reader ->
                        reader.readLine() // intestazione
                        val batch = ArrayList<CatalogCard>(BATCH_SIZE)
                        while (true) {
                            val line = reader.readLine() ?: break
                            val card = parse(line) ?: continue
                            batch.add(card)
                            if (batch.size == BATCH_SIZE) {
                                dao.insertAll(batch)
                                imported += batch.size
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) {
                            dao.insertAll(batch)
                            imported += batch.size
                        }
                    }
                }
            }
            prefs.edit().putInt(KEY_VERSION, CATALOG_VERSION).apply()
            Log.d("CatalogImporter", "Carte importate: $imported")
        } catch (e: Exception) {
            Log.e("CatalogImporter", "Errore durante l'importazione del catalogo", e)
            throw e
        }
    }

    // lang, set_code, set_name, number, set_total, name, rarity, artist, card_type, img, [alias]
    // "alias" (facoltativo): nomi della specie in italiano e inglese, per cercare le carte giapponesi.
    private fun parse(line: String): CatalogCard? {
        val p = line.split('\t')
        if (p.size < COLUMNS) return null
        val name = p[5]
        val number = p[3]
        if (name.isBlank() || number.isBlank()) return null
        return CatalogCard(
            lang = p[0],
            setCode = p[1],
            setName = p[2],
            number = number,
            numberKey = CardText.normalizeNumber(number),
            setTotal = p[4].toIntOrNull() ?: 0,
            name = name,
            nameNorm = CardText.normalizeName(if (p.size > COLUMNS) name + " " + p[COLUMNS] else name),
            rarity = p[6].ifBlank { null },
            artist = p[7].ifBlank { null },
            cardType = p[8],
            imageUrl = p[9]
        )
    }
}
