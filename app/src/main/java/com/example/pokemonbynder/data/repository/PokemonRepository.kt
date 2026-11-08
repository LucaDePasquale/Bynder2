package com.example.pokemonbynder.data.repository

import com.example.pokemonbynder.data.local.BinderDao
import com.example.pokemonbynder.data.local.PokemonDao
import com.example.pokemonbynder.data.local.PriceDao
import com.example.pokemonbynder.data.local.WishlistDao
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.local.catalog.CatalogDao
import com.example.pokemonbynder.data.local.catalog.CatalogSetRow
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.CardPrice
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_NAME
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import com.example.pokemonbynder.data.remote.PriceRequest
import com.example.pokemonbynder.data.remote.TcgdexClient
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.CardMatcher
import com.example.pokemonbynder.scanner.CardText
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Esito di una ricerca: candidati in ordine di probabilità e se il primo è abbastanza sicuro. */
data class ScanMatch(val candidates: List<Candidate>, val confident: Boolean)

class PokemonRepository(
    private val pokemonDao: PokemonDao,
    private val catalogDao: CatalogDao,
    private val binderDao: BinderDao,
    private val wishlistDao: WishlistDao,
    private val priceDao: PriceDao,
    private val tcgdex: TcgdexClient = TcgdexClient()
) {
    companion object {
        private const val MAX_CANDIDATES = 6
        private const val NAME_MIN_SIMILARITY = 0.75
        private const val NAME_MAX_LENGTH_DIFF = 3
        private const val NAMES_PER_LINE = 8
        private const val MANUAL_SEARCH_LIMIT = 40
    }

    val allCards: Flow<List<PokemonCard>> = pokemonDao.getAllCards()
    val binders: Flow<List<Binder>> = binderDao.getAll()
    val wishlist: Flow<List<WishlistCard>> = wishlistDao.getAll()

    /** Ultimo prezzo noto in euro di ogni carta, per id del catalogo. */
    val prices: Flow<Map<String, Double>> = priceDao.getAll().map { list -> list.associate { it.catalogKey to it.priceEur } }

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

    /**
     * Aggiunge una carta al binder e restituisce quante copie ce ne sono ora.
     * Se la carta c'è già nello stesso binder non si crea un doppione: si aggiunge una copia.
     */
    suspend fun saveCardToBinder(
        card: CatalogCard,
        category: CardCategory,
        binderId: Long,
        condition: CardCondition = CardCondition.NEAR_MINT
    ): Int = addEntry(
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
            binderId = binderId,
            condition = condition
        )
    )

    private suspend fun addEntry(entry: PokemonCard): Int {
        val existing = pokemonDao.getById(entry.id)
        val quantity = (existing?.quantity ?: 0) + 1
        if (existing != null) {
            pokemonDao.updateDetails(existing.id, existing.category, quantity, existing.condition)
        } else {
            pokemonDao.insertCard(entry.copy(quantity = 1))
        }
        // Una carta che ora si possiede non è più "da trovare"
        wishlistDao.deleteById(CardEntryId.catalogKeyOf(entry.id))
        return quantity
    }

    /**
     * Cambia tipologia, quantità, condizione e/o binder di una carta già salvata.
     * Per spostarla si crea la riga nel nuovo binder e poi si toglie la vecchia: se nel nuovo binder
     * la stessa carta c'è già, le copie si sommano e non si crea un doppione.
     */
    suspend fun updateCard(
        card: PokemonCard,
        category: CardCategory,
        binderId: Long,
        quantity: Int,
        condition: CardCondition
    ) {
        val copies = quantity.coerceAtLeast(1)
        if (binderId == card.binderId) {
            pokemonDao.updateDetails(card.id, category, copies, condition)
            return
        }
        val newId = CardEntryId.of(binderId, CardEntryId.catalogKeyOf(card.id))
        val existing = pokemonDao.getById(newId)
        pokemonDao.insertCard(
            card.copy(
                id = newId,
                binderId = binderId,
                category = category,
                quantity = copies + (existing?.quantity ?: 0),
                condition = condition
            )
        )
        pokemonDao.deleteById(card.id)
    }

    /** Toglie una singola carta dal suo binder. */
    suspend fun deleteCard(card: PokemonCard) {
        pokemonDao.deleteById(card.id)
    }

    /** Toglie più carte insieme dai binder. */
    suspend fun deleteCards(ids: List<String>) {
        if (ids.isNotEmpty()) pokemonDao.deleteByIds(ids)
    }

    /** Mette la carta nella wishlist; false se c'era già. */
    suspend fun addToWishlist(card: CatalogCard): Boolean =
        wishlistDao.insert(
            WishlistCard(
                id = card.catalogKey,
                name = card.name,
                supertype = card.cardType,
                number = card.number,
                artist = card.artist,
                rarity = card.rarity,
                imageUrl = card.imageUrl,
                set = card.setName
            )
        ) != -1L

    suspend fun removeFromWishlist(card: WishlistCard) {
        wishlistDao.deleteById(card.id)
    }

    /** Carta trovata: passa dalla wishlist al binder (addEntry la toglie dalla wishlist). */
    suspend fun moveWishlistToBinder(
        wish: WishlistCard,
        category: CardCategory,
        binderId: Long,
        condition: CardCondition
    ): Int = addEntry(
        PokemonCard(
            id = CardEntryId.of(binderId, wish.id),
            name = wish.name,
            supertype = wish.supertype,
            subtypes = emptyList(),
            number = wish.number,
            artist = wish.artist,
            rarity = wish.rarity,
            imageUrl = wish.imageUrl,
            set = wish.set,
            category = category,
            binderId = binderId,
            condition = condition
        )
    )

    /**
     * Scarica da TCGdex i prezzi delle carte indicate (una richiesta per carta diversa) e li salva.
     * Restituisce quanti prezzi sono stati trovati. Lancia IOException se la rete non funziona.
     */
    suspend fun refreshPrices(requests: List<PriceRequest>, onProgress: (done: Int, total: Int) -> Unit): Int {
        val unique = requests.distinctBy { it.key }
        val done = AtomicInteger(0)
        return tcgdex.fetchPrices(unique) { request, price ->
            if (price != null) priceDao.upsert(CardPrice(request.key, price, System.currentTimeMillis()))
            onProgress(done.incrementAndGet(), unique.size)
        }
    }

    /** Ricerca manuale nel catalogo offline (per quando la scansione non riconosce la carta). */
    suspend fun searchCatalog(text: String): List<CatalogCard> {
        val query = CardText.normalizeName(text)
        if (query.length < 2) return emptyList()
        return catalogDao.searchByName(query, MANUAL_SEARCH_LIMIT)
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

    /** Tutti i set del catalogo offline. */
    suspend fun catalogSets(): List<CatalogSetRow> = catalogDao.sets()

    /** Le carte di un set, in ordine di numero (1, 2, 10... e poi i numeri con lettere). */
    suspend fun setCards(lang: String, setCode: String): List<CatalogCard> =
        catalogDao.cardsOfSet(lang, setCode).sortedWith(compareBy({ numberRank(it.number) }, { it.number }))

    /** Mette nella wishlist tutte le carte indicate; restituisce quante erano nuove. */
    suspend fun addAllToWishlist(cards: List<CatalogCard>): Int = cards.count { addToWishlist(it) }

    private fun numberRank(number: String): Int = number.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    fun getCardsByArtist(artist: String) = pokemonDao.getCardsByArtist(artist)
}

/** Dati per cercare il prezzo di una carta del binder. */
fun PokemonCard.toPriceRequest(): PriceRequest {
    val key = CardEntryId.catalogKeyOf(id)
    return PriceRequest(key, key.substringBefore(':'), name, number, set, imageUrl)
}

/** Dati per cercare il prezzo di una carta della wishlist. */
fun WishlistCard.toPriceRequest() = PriceRequest(id, id.substringBefore(':'), name, number, set, imageUrl)
