package com.example.pokemonbynder

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Numero del Pokédex dal nome di una carta (italiano, inglese o giapponese), usando l'elenco
 * assets/dex.tsv.gz. Le carte che non sono Pokémon (Allenatori, Energia) non hanno numero.
 */
class PokedexIndex(private val context: Context) {

    companion object {
        /** Valore per le carte senza numero: finiscono in fondo all'elenco. */
        const val NONE = Int.MAX_VALUE
        private const val MAX_WORDS = 4
        private val marks = Regex("\\p{M}+")
        private val separators = Regex("[^\\p{L}\\p{N}]+")

        fun normalize(text: String): String =
            separators.replace(marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(), " ").trim()
    }

    private val names: Map<String, Int> by lazy { load() }
    private val cache = ConcurrentHashMap<String, Int>()

    /** Numero stampato su ogni carta (dai dati di TCGdex), per chiave "lingua:set:numero". 0 = non è un Pokémon. */
    private val perCard: Map<String, Int> by lazy { load("dexcards.tsv.gz", 48000) }

    /** Prima il numero della carta esatta; se non c'è (carta creata da foto, set mancante) si cerca dal nome. */
    fun dexOf(catalogKey: String, cardName: String): Int {
        perCard[catalogKey]?.let { return if (it > 0) it else NONE }
        return dexOf(cardName)
    }

    fun dexOf(cardName: String): Int = cache.getOrPut(cardName) { lookup(cardName) }

    private fun load(): Map<String, Int> = load("dex.tsv.gz", 8192)

    private fun load(asset: String, size: Int): Map<String, Int> {
        val map = HashMap<String, Int>(size)
        try {
            GZIPInputStream(context.assets.open(asset)).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) line.substring(tab + 1).trim().toIntOrNull()?.let { map[line.substring(0, tab)] = it }
                }
            }
        } catch (e: Exception) {
            Log.e("PokedexIndex", "Elenco del Pokédex non caricato: $asset", e)
        }
        return map
    }

    private fun lookup(cardName: String): Int {
        val phrase = normalize(cardName)
        if (phrase.isEmpty()) return NONE
        names[phrase]?.let { return it }
        val words = phrase.split(' ')
        // Prima le sequenze più lunghe ("dark tyranitar"), poi le singole parole, da sinistra a destra
        for (length in minOf(MAX_WORDS, words.size) downTo 1) {
            for (start in 0..words.size - length) {
                names[words.subList(start, start + length).joinToString(" ")]?.let { return it }
            }
        }
        // Giapponese: i nomi sono scritti senza spazi ("ピカチュウex")
        if (phrase.any { it.code in 0x3040..0x30FF }) {
            val compact = phrase.replace(" ", "")
            for (length in compact.length downTo 2) {
                names[compact.substring(0, length)]?.let { return it }
            }
        }
        return NONE
    }
}
