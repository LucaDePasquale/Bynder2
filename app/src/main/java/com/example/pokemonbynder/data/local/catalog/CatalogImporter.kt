package com.example.pokemonbynder.data.local.catalog

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.pokemonbynder.scanner.CardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Importa il catalogo (assets/catalog.bin) nel database Room, una sola volta (la prima apertura
 * dell'app, pochi secondi) e di nuovo solo se [CATALOG_VERSION] aumenta, cioè quando rigeneri il file.
 *
 * Il file si chiama `catalog.bin` perché alcuni strumenti di build trattano in modo speciale
 * i file `.gz` negli assets. Per sicurezza si accettano anche i vecchi nomi, e il contenuto
 * viene decompresso solo se è davvero gzip.
 */
class CatalogImporter(
    private val context: Context,
    private val database: CatalogDatabase
) {
    companion object {
        const val ASSET_NAME = "catalog.bin"
        private val ASSET_NAMES = listOf(ASSET_NAME, "catalog.tsv.gz", "catalog.tsv")

        /** Incrementa questo numero ogni volta che sostituisci il file del catalogo. */
        const val CATALOG_VERSION = 1

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
                openCatalog().use { raw ->
                    BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).use { reader ->
                        val batch = ArrayList<CatalogCard>(BATCH_SIZE)
                        // La prima riga è l'intestazione ("lang<TAB>..."); se manca, è già una carta.
                        val first = reader.readLine()
                        if (first != null && !first.startsWith("lang\t")) parse(first)?.let { batch.add(it) }
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
                check(imported > 0) { "Il catalogo è vuoto o in un formato non riconosciuto" }
            }
            prefs.edit().putInt(KEY_VERSION, CATALOG_VERSION).apply()
            Log.d("CatalogImporter", "Carte importate: $imported")
        } catch (e: Exception) {
            Log.e("CatalogImporter", "Errore durante l'importazione del catalogo", e)
            throw e
        }
    }

    /**
     * Apre il catalogo con il primo nome disponibile tra [ASSET_NAMES].
     * Decomprime solo se il contenuto è gzip (primi due byte 1F 8B).
     */
    private fun openCatalog(): InputStream {
        for (name in ASSET_NAMES) {
            try {
                val stream = BufferedInputStream(context.assets.open(name))
                stream.mark(2)
                val first = stream.read()
                val second = stream.read()
                stream.reset()
                return if (first == 0x1f && second == 0x8b) GZIPInputStream(stream) else stream
            } catch (e: FileNotFoundException) {
                // prova il nome successivo
            }
        }
        val found = context.assets.list("")?.joinToString() ?: "nessuno"
        throw FileNotFoundException(
            "Nessun catalogo negli assets (cercati: ${ASSET_NAMES.joinToString()}). Presenti: $found"
        )
    }

    // lang, set_code, set_name, number, set_total, name, rarity, artist, card_type, img
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
            nameNorm = CardText.normalizeName(name),
            rarity = p[6].ifBlank { null },
            artist = p[7].ifBlank { null },
            cardType = p[8],
            imageUrl = p[9]
        )
    }
}
