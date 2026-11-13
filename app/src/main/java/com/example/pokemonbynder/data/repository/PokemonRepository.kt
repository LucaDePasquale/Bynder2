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
import com.example.pokemonbynder.scanner.CatalogQueryParser
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import com.example.pokemonbynder.data.stats.CardLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val tcgdex: TcgdexClient = TcgdexClient(),
    private val loadSetPairs: () -> Map<String, String> = { emptyMap() }
) {
    companion object {
        private const val MAX_CANDIDATES = 6
        private const val NAME_MIN_SIMILARITY = 0.75
        private const val NAME_MAX_LENGTH_DIFF = 3
        private const val NAMES_PER_LINE = 8
        private const val MANUAL_SEARCH_LIMIT = 60
        private const val ARTIST_SEARCH_LIMIT = 120
    }

    val allCards: Flow<List<PokemonCard>> = pokemonDao.getAllCards()
    val binders: Flow<List<Binder>> = binderDao.getAll()
    val wishlist: Flow<List<WishlistCard>> = wishlistDao.getAll()

    /** Ultimo prezzo noto in euro di ogni carta, per id del catalogo. */
    val prices: Flow<Map<String, Double>> = priceDao.getAll().map { list -> list.associate { it.catalogKey to it.priceEur } }

    private var nameIndex: List<String>? = null
    private var jaNameIndex: List<String>? = null

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
        val nameLines = reading.nameLines.map(CardText::normalizeName).filter { it.length >= 3 || CardText.hasJapanese(it) }
        val japanese = reading.languageHint == "ja"

        val pool: List<CatalogCard>
        val effectiveReading: ScanReading
        val number = reading.number
        val byNumber = if (number != null) {
            val western = catalogDao.byNumberKey(number.key)
            val asian = catalogDao.byNumberKeyJa(number.key)
            when {
                japanese -> asian.ifEmpty { western }
                number.total > 0 && western.none { it.setTotal == number.total } -> western + asian
                else -> western
            }
        } else emptyList()
        if (byNumber.isNotEmpty()) {
            pool = byNumber
            effectiveReading = reading
        } else {
            pool = cardsWithSimilarNames(nameLines, japanese)
            effectiveReading = reading.copy(number = null)
        }
        if (pool.isEmpty()) return ScanMatch(emptyList(), false)

        val ranked = CardMatcher.rank(pool, effectiveReading)
        return ScanMatch(ranked.take(MAX_CANDIDATES), CardMatcher.isConfident(ranked))
    }

    private suspend fun cardsWithSimilarNames(nameLines: List<String>, japanese: Boolean = false): List<CatalogCard> {
        if (nameLines.isEmpty()) return emptyList()
        val names = withContext(Dispatchers.Default) {
            val index = if (japanese) {
                jaNameIndex ?: catalogDao.distinctNamesJa().also { jaNameIndex = it }
            } else {
                nameIndex ?: catalogDao.distinctNames().also { nameIndex = it }
            }
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
        return when {
            names.isEmpty() -> emptyList()
            japanese -> catalogDao.byNameNormJa(names)
            else -> catalogDao.byNameNorm(names)
        }
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
            category = if (category == CardCategory.NORMAL) CardCategory.detect(card.name, card.rarity) else category,
            binderId = binderId,
            condition = condition
        )
    )

    /** Aggiunge molte carte a un binder (una copia ciascuna, tipologia Normale); restituisce quante. */
    suspend fun addAllToBinder(cards: List<CatalogCard>, binderId: Long, condition: CardCondition): Int {
        cards.forEach { saveCardToBinder(it, CardCategory.NORMAL, binderId, condition) }
        return cards.size
    }

    /**
     * Copia o sposta carte in un altro binder. Restituisce (carte copiate/spostate, carte saltate).
     * Copiando, una carta già presente nel binder di arrivo viene saltata; spostando le copie si sommano.
     */
    suspend fun transferCards(cards: List<PokemonCard>, targetBinderId: Long, move: Boolean): Pair<Int, Int> {
        var done = 0
        var skipped = 0
        for (card in cards) {
            if (card.binderId == targetBinderId) {
                skipped++
                continue
            }
            val newId = CardEntryId.of(targetBinderId, CardEntryId.catalogKeyOf(card.id))
            val existing = pokemonDao.getById(newId)
            if (existing != null) {
                if (move) {
                    pokemonDao.updateDetails(existing.id, existing.category, existing.quantity + card.quantity, existing.condition)
                    pokemonDao.deleteById(card.id)
                    done++
                } else {
                    skipped++
                }
            } else {
                pokemonDao.insertCard(card.copy(id = newId, binderId = targetBinderId))
                if (move) pokemonDao.deleteById(card.id)
                done++
            }
        }
        return done to skipped
    }

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
     * Cerca online (TCGdex) in italiano, inglese e giapponese, per nome (con numero opzionale) o autore.
     * Serve per le carte che non sono nel catalogo offline. Lancia IOException se manca la rete.
     */
    suspend fun searchOnline(text: String, byArtist: Boolean): List<CatalogCard> {
        val query = CatalogQueryParser.parse(text, byArtist)
        val term = if (byArtist) query.artist else query.rawName
        if (term.length < 2) return emptyList()
        val found = tcgdex.searchOnline(term, byArtist, listOf("it", "en", "ja"))
        return if (query.numberKey.isNotEmpty()) found.filter { it.numberKey == query.numberKey } else found
    }

    /** Aggiunge i dati mancanti a una carta trovata online (set, rarità, autore...). */
    suspend fun completeOnlineCard(card: CatalogCard): CatalogCard = tcgdex.completeCard(card)

    /**
     * Cerca l'immagine di una carta e la salva dove la carta compare; null se non esiste.
     * Ordine: catalogo aggiornato, TCGdex, stessa carta nell'altra lingua, altre fonti inglesi.
     * [failedUrl] è l'indirizzo che non si è caricato: non si restituisce di nuovo.
     */
    suspend fun findImageOnline(catalogKey: String, failedUrl: String? = null): String? {
        val lang = catalogKey.substringBefore(':')
        val setCode = catalogKey.substringAfter(':', "").substringBeforeLast(':', "")
        val number = catalogKey.substringAfterLast(':')
        if (setCode.isEmpty() || number.isEmpty()) return null

        fun usable(url: String?): String? =
            url?.takeIf { it.isNotBlank() && (failedUrl == null || it.substringBeforeLast('.') != failedUrl.substringBeforeLast('.')) }

        // 1) il catalogo del telefono (si aggiorna con l'app) può avere un indirizzo nuovo
        usable(catalogDao.cardsOfSet(lang, setCode).firstOrNull { it.number == number }?.imageUrl)?.let {
            setCardImage(catalogKey, it)
            return it
        }
        // 2) TCGdex
        usable(tcgdex.findImage(lang, setCode, number))?.let {
            setCardImage(catalogKey, it)
            return it
        }
        // 3) la stessa carta nell'altra lingua e 4) altre fonti (sono in inglese: non si salvano, così se
        // TCGdex avrà l'immagine giusta verrà usata quella)
        usable(twinImage(lang, setCode, number))?.let { return it }
        return usable(tcgdex.findExternalImage(lang, setCode, number))
    }

    /** Salva l'immagine di una carta ovunque compaia: catalogo, binder e wishlist. */
    suspend fun setCardImage(catalogKey: String, url: String) {
        val lang = catalogKey.substringBefore(':')
        val setCode = catalogKey.substringAfter(':', "").substringBeforeLast(':', "")
        val number = catalogKey.substringAfterLast(':')
        catalogDao.updateImage(lang, setCode, number, url)
        pokemonDao.updateImageByKey(catalogKey, url)
        wishlistDao.updateImage(catalogKey, url)
    }

    private suspend fun twinImage(lang: String, setCode: String, number: String): String? {
        val targetLang = if (lang == "it") "en" else if (lang == "en") "it" else return null
        val pairCode = setPairs["$lang:$setCode"] ?: return null
        val key = CardText.normalizeNumber(number)
        return catalogDao.cardsOfSet(targetLang, pairCode).firstOrNull { it.numberKey == key }?.imageUrl?.takeIf { it.isNotBlank() }
    }

    private val setPairs: Map<String, String> by lazy { loadSetPairs() }

    /**
     * La stessa carta nell'altra lingua (con nome, immagine e set di quella lingua), oppure null
     * se nel catalogo non c'è. I set italiani e inglesi si collegano con assets/set_pairs.tsv.
     */
    suspend fun findTwin(card: PokemonCard, targetLang: String): CatalogCard? {
        val key = CardEntryId.catalogKeyOf(card.id)
        val lang = key.substringBefore(':')
        if (lang == targetLang) return null
        val setCode = key.substringAfter(':', "").substringBeforeLast(':', "")
        val number = CardText.normalizeNumber(key.substringAfterLast(':'))

        var pairCode = setPairs["$lang:$setCode"]
        if (pairCode == null) {
            // Carte salvate da TCGdex: il codice del set può essere diverso, si prova con il nome
            val wanted = CardText.normalizeName(card.set)
            val own = catalogDao.sets().firstOrNull { it.lang == lang && CardText.normalizeName(it.setName) == wanted }
            pairCode = own?.let { setPairs["$lang:${it.setCode}"] }
        }
        if (pairCode == null) return null
        return catalogDao.cardsOfSet(targetLang, pairCode).firstOrNull { it.numberKey == number }
    }

    /**
     * Cambia tipologia, quantità, condizione, binder e/o lingua di una carta già salvata.
     * Per spostarla (o cambiarle lingua) si crea la riga nuova e poi si toglie la vecchia: se la carta
     * c'è già nella nuova posizione le copie si sommano e non si crea un doppione.
     * Cambiando lingua la carta diventa quella corrispondente nell'altra lingua, con la sua immagine.
     *
     * @return false se la lingua richiesta non è disponibile nel catalogo (il resto viene salvato)
     */
    suspend fun updateCard(
        card: PokemonCard,
        category: CardCategory,
        binderId: Long,
        quantity: Int,
        condition: CardCondition,
        language: String? = null
    ): Boolean {
        val copies = quantity.coerceAtLeast(1)
        val wantsOtherLanguage = language != null && language != CardLanguage.of(card.id)
        val twin = if (wantsOtherLanguage) findTwin(card, language!!) else null
        val languageMissing = wantsOtherLanguage && twin == null

        if (twin == null && binderId == card.binderId) {
            pokemonDao.updateDetails(card.id, category, copies, condition)
            return !languageMissing
        }
        val base = if (twin == null) card else card.copy(
            name = twin.name,
            supertype = twin.cardType,
            number = twin.number,
            artist = twin.artist,
            rarity = twin.rarity,
            imageUrl = twin.imageUrl,
            set = twin.setName
        )
        val newKey = twin?.catalogKey ?: CardEntryId.catalogKeyOf(card.id)
        val newId = CardEntryId.of(binderId, newKey)
        val existing = pokemonDao.getById(newId)
        pokemonDao.insertCard(
            base.copy(
                id = newId,
                binderId = binderId,
                category = category,
                quantity = copies + (existing?.quantity ?: 0),
                condition = condition
            )
        )
        pokemonDao.deleteById(card.id)
        return !languageMissing
    }

    /** Alle carte già salvate senza immagine mette quella che ora il catalogo conosce. */
    suspend fun repairMissingImages() {
        val blank = pokemonDao.getAllCards().first().filter { it.imageUrl.isBlank() }
        if (blank.isEmpty()) return
        val bySet = blank.groupBy { CardEntryId.catalogKeyOf(it.id).substringBeforeLast(':') }
        for ((setKey, entries) in bySet) {
            val lang = setKey.substringBefore(':')
            val setCode = setKey.substringAfter(':', "")
            val inCatalog = catalogDao.cardsOfSet(lang, setCode).associateBy { it.catalogKey }
            for (entry in entries) {
                val url = inCatalog[CardEntryId.catalogKeyOf(entry.id)]?.imageUrl
                if (!url.isNullOrBlank()) pokemonDao.updateImage(entry.id, url)
            }
        }
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
            category = if (category == CardCategory.NORMAL) CardCategory.detect(wish.name, wish.rarity) else category,
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

    /**
     * Ricerca manuale nel catalogo offline. Per nome si può aggiungere il numero ("Pikachu 25")
     * o numero e carte del set ("Pikachu 25/102"); con [byArtist] il testo è il nome dell'autore.
     */
    suspend fun searchCatalog(text: String, byArtist: Boolean = false): List<CatalogCard> {
        val query = CatalogQueryParser.parse(text, byArtist)
        if (!query.isSearchable) return emptyList()
        val limit = if (byArtist) ARTIST_SEARCH_LIMIT else MANUAL_SEARCH_LIMIT
        val found = catalogDao.search(query.name, query.artist, query.numberKey, query.total, limit)
        if (found.isNotEmpty() || query.numberKey.isEmpty() || query.total == 0) return found
        // Nessun set con quel totale esatto: si mostrano le carte con quel numero, dai set con il totale più vicino
        return catalogDao.search(query.name, query.artist, query.numberKey, 0, 500)
            .sortedBy { if (it.setTotal == 0) Int.MAX_VALUE else abs(it.setTotal - query.total) }
            .take(limit)
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

    /** Dà la tipologia (EX, V, Holo...) alle carte rimaste "Normale" che in realtà lo sono di meno. */
    suspend fun redetectCategories() {
        for (card in pokemonDao.normalCards()) {
            val detected = CardCategory.detect(card.name, card.rarity)
            if (detected != CardCategory.NORMAL) pokemonDao.updateCategory(card.id, detected)
        }
    }

    /** Modifica nome, set, numero, autore e rarità di una carta creata da foto. */
    suspend fun updateCustomCardInfo(card: PokemonCard, name: String, set: String, number: String, artist: String?, rarity: String?) {
        val key = CardEntryId.catalogKeyOf(card.id)
        pokemonDao.updateInfoByKey(key, name, set, number, artist, rarity, CardCategory.detect(name, rarity))
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
