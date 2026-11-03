package com.example.pokemonbynder.scanner

import java.text.Normalizer

/**
 * Funzioni di normalizzazione e confronto testi, condivise da importazione del catalogo,
 * parser OCR e matcher. Devono restare identiche in tutti i punti: il catalogo salva
 * `nameNorm` e `numberKey` calcolati qui, e lo scanner confronta con gli stessi valori.
 */
object CardText {
    private val combiningMarks = Regex("\\p{M}+")
    private val notAlphanumeric = Regex("[^a-z0-9]")
    private val numberShape = Regex("^([a-z]*)(\\d+)([a-z]?)$")

    /** "Pokémon Ex" -> "pokemonex": minuscolo, senza accenti, senza spazi e simboli. */
    fun normalizeName(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val noMarks = combiningMarks.replace(decomposed, "")
        return notAlphanumeric.replace(noMarks.lowercase(), "")
    }

    /** "025" -> "25", "TG01" -> "tg1", "105a" -> "105a": toglie gli zeri iniziali della parte numerica. */
    fun normalizeNumber(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(" ", "")
        val match = numberShape.matchEntire(cleaned) ?: return cleaned
        val (prefix, digits, suffix) = match.destructured
        return prefix + digits.trimStart('0').ifEmpty { "0" } + suffix
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 1.0 = testi identici, 0.0 = completamente diversi. Si aspetta stringhe già normalizzate. */
    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }
}
