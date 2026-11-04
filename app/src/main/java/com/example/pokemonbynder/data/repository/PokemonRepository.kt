package com.example.pokemonbynder.data.repository

import com.example.pokemonbynder.data.local.PokemonDao
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.local.catalog.CatalogDao
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.CardMatcher
import com.example.pokemonbynder.scanner.CardText
import com.example.pokemonbynder.scanner.ScanReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Esito di una ricerca: candidati in ordine di probabilità e se il primo è abbastanza sicuro. */
data class ScanMatch(val candidates: List<Candidate>, val confident: Boolean)

class PokemonRepository(
    private val pokemonDao: PokemonDao,
    private val catalogDao: CatalogDao
) {
    companion object {
        private const val MAX_CANDIDATES = 6
        private const val NAME_MIN_SIMILARITY = 0.75
        private const val NAME_MAX_LENGTH_DIFF = 3
        private const val NAMES_PER_LINE = 8
    }

    val allCards: Flow<List<PokemonCard>> = pokemonDao.getAllCards()

    private var nameIndex: List<String>? = null

    /**
     * Cerca la carta nel catalogo locale (nessuna rete).
     * 1) Se è stato letto il numero (es. 025/165): prende solo le carte con quel numero e le ordina per nome.
     * 2) Altrimenti cerca i nomi più simili in tutto il catalogo.
     */
    suspend fun findCandidates(reading: ScanReading): ScanMatch {
        val nameLines = reading.nameLines.map(CardText::normalizeName).filter { it.length >= 3 }

        val pool: List<CatalogCard>
        val effectiveReading: ScanReading
        val number = reading.number
        val byNumber = if (number != null) catalogDao.byNumberKey(number.key) else emptyList()
        if (byNumber.isNotEmpty()) {
            pool = byNumber
            effectiveReading = reading
        } else {
            pool = cardsWithSimilarNames(nameLines)
            effectiveReading = reading.copy(number = null)
        }
        if (pool.isEmpty()) return ScanMatch(emptyList(), false)

        val ranked = CardMatcher.rank(pool, effectiveReading)
        return ScanMatch(ranked.take(MAX_CANDIDATES), CardMatcher.isConfident(ranked))
    }

    private suspend fun cardsWithSimilarNames(nameLines: List<String>): List<CatalogCard> {
        if (nameLines.isEmpty()) return emptyList()
        val names = withContext(Dispatchers.Default) {
            val index = nameIndex ?: catalogDao.distinctNames().also { nameIndex = it }
            nameLines.flatMap { line ->
                index.asSequence()
                    .filter { abs(it.length - line.length) <= NAME_MAX_LENGTH_DIFF }
                    .map { it to CardText.similarity(line, it) }
                    .filter { it.second >= NAME_MIN_SIMILARITY }
                    .sortedByDescending { it.second }
                    .take(NAMES_PER_LINE)
                    .map { it.first }
                    .toList()
            }.distinct()
        }
        return if (names.isEmpty()) emptyList() else catalogDao.byNameNorm(names)
    }

    suspend fun saveCardToBinder(card: CatalogCard, category: CardCategory) {
        pokemonDao.insertCard(
            PokemonCard(
                id = card.binderId,
                name = card.name,
                supertype = card.cardType,
                subtypes = emptyList(),
                number = card.number,
                artist = card.artist,
                rarity = card.rarity,
                imageUrl = card.imageUrl,
                set = card.setName,
                category = category
            )
        )
    }

    suspend fun updateCategory(cardId: String, category: CardCategory) {
        pokemonDao.updateCategory(cardId, category)
    }

    fun getCardsByArtist(artist: String) = pokemonDao.getCardsByArtist(artist)
}
