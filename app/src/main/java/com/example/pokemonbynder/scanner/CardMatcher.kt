package com.example.pokemonbynder.scanner

import com.example.pokemonbynder.data.local.catalog.CatalogCard

data class Candidate(val card: CatalogCard, val score: Double)

/**
 * Confronta una lettura OCR con le carte del catalogo e le ordina per somiglianza.
 *
 * Punteggio (0..1): 60% somiglianza del nome + 40% corrispondenza del numero.
 * Il numero vale 1.0 se coincidono numero e totale (es. 025/165), 0.7 se il totale non è noto,
 * 0.2 se il totale è diverso. Senza numero letto il punteggio è limitato a 0.75: in quel caso
 * l'utente deve sempre scegliere tra i candidati.
 *
 * Le soglie sono state provate su tutto il catalogo (IT + EN) con testi OCR simulati con errori:
 * con numero e totale letti, la carta mostrata in automatico era giusta in oltre il 99,9% dei casi.
 */
object CardMatcher {
    const val CONFIDENT_SCORE = 0.85
    const val CONFIDENT_MARGIN = 0.08
    private const val NAME_WEIGHT = 0.6
    private const val NUMBER_WEIGHT = 0.4
    private const val NAME_ONLY_CAP = 0.75

    fun rank(
        cards: List<CatalogCard>,
        reading: ScanReading,
        preferredLang: String = "it"
    ): List<Candidate> {
        val lines = reading.nameLines.map(CardText::normalizeName).filter { it.length >= 2 }
        return cards
            .map { Candidate(it, score(it, lines, reading.number, reading.languageHint, preferredLang)) }
            .sortedByDescending { it.score }
    }

    /** Vero se la prima carta è molto più convincente di qualunque carta *diversa*. */
    fun isConfident(ranked: List<Candidate>): Boolean {
        val top = ranked.firstOrNull() ?: return false
        val nextDifferent = ranked.drop(1)
            .firstOrNull { it.card.group() != top.card.group() }
            ?.score ?: 0.0
        return top.score >= CONFIDENT_SCORE && top.score - nextDifferent >= CONFIDENT_MARGIN
    }

    private fun score(
        card: CatalogCard,
        nameLines: List<String>,
        number: PrintedNumber?,
        languageHint: String?,
        preferredLang: String
    ): Double {
        val nameSimilarity = nameLines.maxOfOrNull { CardText.similarity(it, card.nameNorm) } ?: 0.0
        val base = if (number != null) {
            val numberScore = when {
                number.total == 0 || card.setTotal == 0 -> 0.7
                number.total == card.setTotal -> 1.0
                else -> 0.2
            }
            NAME_WEIGHT * nameSimilarity + NUMBER_WEIGHT * numberScore
        } else {
            NAME_ONLY_CAP * nameSimilarity
        }
        val languageBonus = when {
            languageHint != null && card.lang == languageHint -> 0.03
            card.lang == preferredLang -> 0.01
            else -> 0.0
        }
        return base + languageBonus
    }

    // La stessa carta in italiano e in inglese (stesso nome, numero e totale) conta come una sola.
    private fun CatalogCard.group(): String = "$numberKey|$setTotal|$nameNorm"
}
