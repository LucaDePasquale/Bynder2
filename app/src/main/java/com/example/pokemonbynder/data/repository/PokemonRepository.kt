package com.example.pokemonbynder.data.repository

import com.example.pokemonbynder.data.local.BinderDao
import com.example.pokemonbynder.data.local.PokemonDao
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.local.catalog.CatalogDao
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_NAME
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.remote.TcgdexClient
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.CardMatcher
import com.example.pokemonbynder.scanner.CardText
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Esito di una ricerca: candidati in ordine di probabilità e se il primo è abbastanza sicuro. */
data class ScanMatch(val candidates: List<Candidate>, val confident: Boolean)

class PokemonRepository(
    private val pokemonDao: PokemonDao,
    private val catalogDao: CatalogDao,
    private val binderDao: BinderDao,
    private val tcgdex: TcgdexClient = TcgdexClient()
) {
    companion object {
        private const val MAX_CANDIDATES = 6
        private const val NAME_MIN_SIMILARITY = 0.75
        private const val NAME_MAX_LENGTH_DIFF = 3
        private const val NAMES_PER_LINE = 8
    }

    val allCards: Flow<List<PokemonCard>> = pokemonDao.getAllCards()
    val binders: Flow<List<Binder>> = binderDao.getAll()

    private var nameIndex: List<String>? = null

    /**
     * Cerca la carta nella fonte scelta dall'utente prima della scansione.
     * Con [ScanSource.TCGDEX] può lanciare IOException se la rete non è disponibile.
     */
    suspend fun findCandidates(reading: ScanReading, source: ScanSource = ScanSource.CATALOG): ScanMatch =
        when (source) {
            ScanSource.CATALOG -> findInCatalog(reading)
            ScanSource.TCGDEX -> findOnTcgdex(reading)
        }

    /** Cerca su TCGdex (online) e ordina i risultati con lo stesso confronto usato per il catalogo. */
    private suspend fun findOnTcgdex(reading: ScanReading): ScanMatch {
        val search = tcgdex.findCards(reading)
        if (search.cards.isEmpty()) return ScanMatch(emptyList(), false)

        val effectiveReading = if (search.numberMatched) reading else reading.copy(number = null)
        val ranked = CardMatcher.rank(search.cards, effectiveReading)
        return ScanMatch(ranked.take(MAX_CANDIDATES), CardMatcher.isConfident(ranked))
    }

    /**
     * Cerca la carta nel catalogo locale (nessuna rete).
     * 1) Se è stato letto il numero (es. 025/165): prende solo le carte con quel numero e le ordina per nome.
     * 2) Altrimenti cerca i nomi più simili in tutto il catalogo.
     */
    private suspend fun findInCatalog(reading: ScanReading): ScanMatch {
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

    suspend fun saveCardToBinder(card: CatalogCard, category: CardCategory, binderId: Long) {
        pokemonDao.insertCard(
            PokemonCard(
                id = CardEntryId.of(binderId, card.catalogKey),
                name = card.name,
                supertype = card.cardType,
                subtypes = emptyList(),
                number = card.number,
                artist = card.artist,
                rarity = card.rarity,
                imageUrl = card.imageUrl,
                set = card.setName,
                category = category,
                binderId = binderId
            )
        )
    }

    /**
     * Cambia tipologia e/o binder di una carta già salvata.
     * Per spostarla si crea la riga nel nuovo binder e poi si toglie la vecchia: se nel nuovo binder
     * la stessa carta c'è già, viene aggiornata e non duplicata.
     */
    suspend fun updateCard(card: PokemonCard, category: CardCategory, binderId: Long) {
        if (binderId == card.binderId) {
            pokemonDao.updateCategory(card.id, category)
            return
        }
        val newId = CardEntryId.of(binderId, CardEntryId.catalogKeyOf(card.id))
        pokemonDao.insertCard(card.copy(id = newId, binderId = binderId, category = category))
        pokemonDao.deleteById(card.id)
    }

    /** Con un'installazione nuova non c'è nessun binder: si crea quello di partenza. */
    suspend fun ensureDefaultBinder() {
        if (binderDao.count() == 0) {
            binderDao.insertIfAbsent(Binder(id = DEFAULT_BINDER_ID, name = DEFAULT_BINDER_NAME))
        }
    }

    /** Crea un binder e restituisce il suo id. */
    suspend fun addBinder(name: String): Long = binderDao.insert(Binder(name = name.trim()))

    suspend fun renameBinder(id: Long, name: String) {
        binderDao.rename(id, name.trim())
    }

    /** Elimina il binder insieme alle carte che contiene. */
    suspend fun deleteBinder(id: Long) {
        pokemonDao.deleteCardsInBinder(id)
        binderDao.delete(id)
    }

    fun getCardsByArtist(artist: String) = pokemonDao.getCardsByArtist(artist)
}
