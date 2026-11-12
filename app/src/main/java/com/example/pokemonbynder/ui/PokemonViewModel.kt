package com.example.pokemonbynder.ui

import android.os.SystemClock
import android.util.Log
import com.example.pokemonbynder.data.model.CardEntryId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemonbynder.data.backup.BackupException
import com.example.pokemonbynder.data.backup.BackupManager
import com.example.pokemonbynder.data.backup.ImportResult
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.local.catalog.CatalogSetRow
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import com.example.pokemonbynder.data.repository.PokemonRepository
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.data.repository.toPriceRequest
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.PrintedNumber
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.data.repository.ScanMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Ordine in cui si mostrano le carte. */
enum class SortOrder(val label: String) {
    RECENT("Più recenti"),
    NAME("Nome"),
    SET("Set"),
    NUMBER("Numero")
}

class PokemonViewModel(
    private val repository: PokemonRepository,
    catalogReady: StateFlow<Boolean>,
    private val backupManager: BackupManager
) : ViewModel() {

    companion object {
        /** Quante letture recenti si ricordano per verificare che la carta sia stabile. */
        private const val RECENT_READINGS = 3
        private const val MIN_AGREEING_READINGS = 2

        /** Un numero letto in un fotogramma resta valido per questo tempo, se il fotogramma dopo non lo legge. */
        private const val NUMBER_MEMORY_MS = 1_500L

        /** Quanti fotogrammi recenti contribuiscono alla lista dei nomi letti, e quanti nomi al massimo. */
        private const val NAME_MEMORY_FRAMES = 3
        private const val MAX_MERGED_NAMES = 5

        /** Dopo questo tempo si mostrano comunque i candidati migliori, anche se non sicuri. */
        private const val FALLBACK_AFTER_MS = 4_000L
        private const val FALLBACK_MIN_SCORE = 0.4

        /** Online la ricerca è più lenta: si aspetta di più e si limitano le richieste a TCGdex. */
        private const val ONLINE_FALLBACK_AFTER_MS = 8_000L
        private const val ONLINE_MIN_INTERVAL_MS = 1_500L

        /** Pausa prima di riaprire la fotocamera in scansione continua, per non rileggere la carta appena aggiunta. */
        private const val REOPEN_CAMERA_DELAY_MS = 2_000L
    }

    val isCatalogReady: StateFlow<Boolean> = catalogReady

    private val _filterCategory = MutableStateFlow<CardCategory?>(null)
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Candidati da mostrare all'utente (il primo è il più probabile); null = nessuna scelta in corso. */
    private val _candidates = MutableStateFlow<List<Candidate>?>(null)
    val candidates: StateFlow<List<Candidate>?> = _candidates.asStateFlow()

    /** Fonte scelta dall'utente prima di aprire la fotocamera. */
    private val _scanSource = MutableStateFlow(ScanSource.CATALOG)
    val scanSource: StateFlow<ScanSource> = _scanSource.asStateFlow()

    /** Messaggio d'errore da mostrare una volta (es. rete assente); poi va azzerato con [clearScanError]. */
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    val binders: StateFlow<List<Binder>> = repository.binders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Binder mostrato; null = tutti i binder insieme. */
    private val _selectedBinderId = MutableStateFlow<Long?>(null)
    val selectedBinderId: StateFlow<Long?> = _selectedBinderId.asStateFlow()

    /** Tipologia usata come filtro; null = tutte. */
    val filterCategory: StateFlow<CardCategory?> = _filterCategory.asStateFlow()

    /** Quante copie contiene ogni binder (a prescindere dai filtri). */
    val cardCounts: StateFlow<Map<Long, Int>> = repository.allCards
        .map { list -> list.groupBy { it.binderId }.mapValues { (_, group) -> group.sumOf { it.quantity } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Testo cercato nel binder (nome, set o numero); vuoto = nessuna ricerca. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.RECENT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    /** Se attivo, dopo aver aggiunto una carta scansionata la fotocamera si riapre per la successiva. */
    private val _continuousScan = MutableStateFlow(false)
    val continuousScan: StateFlow<Boolean> = _continuousScan.asStateFlow()

    /** Vero quando la carta in scelta arriva dalla ricerca manuale (e non dalla fotocamera). */
    private var addingManually = false

    /** Lingua usata come filtro ("it", "en"...); null = tutte. */
    private val _filterLanguage = MutableStateFlow<String?>(null)
    val filterLanguage: StateFlow<String?> = _filterLanguage.asStateFlow()

    /** Se attivo mostra solo le carte con più di una copia. */
    private val _onlyDuplicates = MutableStateFlow(false)
    val onlyDuplicates: StateFlow<Boolean> = _onlyDuplicates.asStateFlow()

    private data class Filters(
        val binderId: Long?,
        val category: CardCategory?,
        val query: String,
        val language: String?,
        val onlyDuplicates: Boolean
    )

    private val filters = combine(
        _selectedBinderId,
        _filterCategory,
        _searchQuery,
        _filterLanguage,
        _onlyDuplicates
    ) { binderId, category, query, language, duplicates ->
        Filters(binderId, category, query.trim(), language, duplicates)
    }

    /**
     * Carte da mostrare: nel binder scelto (o in tutti), con la tipologia e la lingua scelte (o tutte),
     * che contengono il testo cercato e, se richiesto, solo le doppie, nell'ordine scelto.
     */
    val cards: StateFlow<List<PokemonCard>> = combine(
        repository.allCards,
        filters,
        _sortOrder
    ) { everyCard, f, order ->
        // Guardando tutti i binder insieme la stessa carta compare una volta sola
        val allCards = if (f.binderId == null) uniqueCards(everyCard) else everyCard
        val filtered = allCards.filter {
            (f.binderId == null || it.binderId == f.binderId) &&
                (f.category == null || it.category == f.category) &&
                (f.language == null || CardLanguage.of(it.id) == f.language) &&
                (!f.onlyDuplicates || it.quantity > 1) &&
                (f.query.isEmpty() || it.matches(f.query))
        }
        when (order) {
            // Il database le restituisce dalla più vecchia alla più nuova
            SortOrder.RECENT -> filtered.asReversed()
            SortOrder.NAME -> filtered.sortedWith(compareBy({ it.name.lowercase() }, { it.set }))
            SortOrder.SET -> filtered.sortedWith(compareBy({ it.set.lowercase() }, { numberOrder(it.number) }, { it.number }))
            SortOrder.NUMBER -> filtered.sortedWith(compareBy({ numberOrder(it.number) }, { it.number }, { it.name.lowercase() }))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Numero come intero per ordinare "2" prima di "10"; le carte senza cifre ("TG") vanno in fondo. */
    private fun numberOrder(number: String): Int =
        number.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    private fun PokemonCard.matches(text: String): Boolean =
        name.contains(text, ignoreCase = true) ||
            set.contains(text, ignoreCase = true) ||
            number.contains(text, ignoreCase = true)

    private val isProcessing = AtomicBoolean(false)
    private val recentTops = ArrayDeque<String>()
    private var scanStartedAt = 0L
    private var lastNumber: PrintedNumber? = null
    private var lastNumberAt = 0L
    private val recentNameLines = ArrayDeque<List<String>>()
    private var lastOnlineRequestAt = 0L

    fun setScanSource(source: ScanSource) {
        _scanSource.value = source
    }

    fun clearScanError() {
        _scanError.value = null
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        if (scanning) {
            _candidates.value = null
            _scanError.value = null
            recentTops.clear()
            lastNumber = null
            recentNameLines.clear()
            lastOnlineRequestAt = 0L
            scanStartedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Unisce la lettura con quelle degli ultimi fotogrammi: l'OCR spesso perde il numero o un nome
     * per un attimo (riflessi, mano che trema), quindi si tiene l'ultimo numero letto da poco
     * e i nomi visti negli ultimi fotogrammi.
     */
    private fun mergeWithRecent(reading: ScanReading, now: Long): ScanReading {
        val readNumber = reading.number
        if (readNumber != null) {
            lastNumber = readNumber
            lastNumberAt = now
        }
        val number = readNumber ?: lastNumber?.takeIf { now - lastNumberAt <= NUMBER_MEMORY_MS }

        recentNameLines.addLast(reading.nameLines)
        while (recentNameLines.size > NAME_MEMORY_FRAMES) recentNameLines.removeFirst()
        val names = (reading.nameLines + recentNameLines.flatten()).distinct().take(MAX_MERGED_NAMES)

        return reading.copy(nameLines = names, number = number)
    }

    /** Chiamato dall'analizzatore della fotocamera a ogni lettura riuscita. */
    fun onReading(reading: ScanReading) {
        if (!_isScanning.value || _candidates.value != null) return
        val source = _scanSource.value
        val now = SystemClock.elapsedRealtime()
        val merged = mergeWithRecent(reading, now)
        // Online le richieste sono limitate: al massimo una ogni 1,5 secondi.
        if (source == ScanSource.TCGDEX && now - lastOnlineRequestAt < ONLINE_MIN_INTERVAL_MS) return
        if (!isProcessing.compareAndSet(false, true)) return // una ricerca alla volta
        if (source == ScanSource.TCGDEX) lastOnlineRequestAt = now

        viewModelScope.launch {
            try {
                val match = repository.findCandidates(merged, source)
                val top = match.candidates.firstOrNull() ?: return@launch
                if (!_isScanning.value) return@launch // chiuso nel frattempo

                recentTops.addLast(top.card.catalogKey)
                while (recentTops.size > RECENT_READINGS) recentTops.removeFirst()
                val stable = recentTops.count { it == top.card.catalogKey } >= MIN_AGREEING_READINGS

                val fallbackAfter = if (source == ScanSource.TCGDEX) ONLINE_FALLBACK_AFTER_MS else FALLBACK_AFTER_MS
                val waitedLong = SystemClock.elapsedRealtime() - scanStartedAt > fallbackAfter &&
                    top.score >= FALLBACK_MIN_SCORE

                if ((match.confident && stable) || waitedLong) {
                    _candidates.value = match.candidates
                    _isScanning.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Ricerca non riuscita (${source.name})", e)
                if (_isScanning.value) {
                    _scanError.value = if (source == ScanSource.TCGDEX) {
                        "Impossibile contattare TCGdex. Controlla la connessione o scegli il catalogo offline."
                    } else {
                        "Errore nella ricerca nel catalogo"
                    }
                    _isScanning.value = false
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /** [onResult] riceve l'esito e quante copie della carta ci sono ora nel binder. */
    fun confirmAndAddCard(
        card: CatalogCard,
        category: CardCategory,
        binderId: Long,
        condition: CardCondition,
        onResult: (Boolean, Int) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val quantity = repository.saveCardToBinder(card, category, binderId, condition)
                _candidates.value = null
                val reopenCamera = _continuousScan.value && !addingManually
                addingManually = false
                onResult(true, quantity)
                // Scansione continua: dopo una breve pausa (il tempo di cambiare carta) si riapre la fotocamera
                if (reopenCamera) {
                    delay(REOPEN_CAMERA_DELAY_MS)
                    if (!_isScanning.value && _candidates.value == null) setScanning(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Salvataggio della carta non riuscito", e)
                onResult(false, 0)
            }
        }
    }

    /** Cambia tipologia, quantità, condizione e/o binder di una carta già salvata. */
    fun updateCard(
        card: PokemonCard,
        category: CardCategory,
        binderId: Long,
        quantity: Int,
        condition: CardCondition,
        language: String? = null,
        onResult: (success: Boolean, languageMissing: Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val languageApplied = repository.updateCard(card, category, binderId, quantity, condition, language)
                onResult(true, !languageApplied)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Modifica della carta non riuscita", e)
                onResult(false, false)
            }
        }
    }

    fun selectBinder(id: Long?) {
        _selectedBinderId.value = id
    }

    /** Crea un binder e lo mostra subito. */
    fun addBinder(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                _selectedBinderId.value = repository.addBinder(name)
                onResult(true)
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Creazione del binder non riuscita", e)
                onResult(false)
            }
        }
    }

    fun renameBinder(id: Long, name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.renameBinder(id, name)
                onResult(true)
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Rinomina del binder non riuscita", e)
                onResult(false)
            }
        }
    }

    /** Elimina il binder e le sue carte. */
    fun deleteBinder(id: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteBinder(id)
                if (_selectedBinderId.value == id) _selectedBinderId.value = null
                onResult(true)
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Eliminazione del binder non riuscita", e)
                onResult(false)
            }
        }
    }

    fun cancelSelection() {
        _candidates.value = null
        addingManually = false
    }

    fun setSearchQuery(text: String) {
        _searchQuery.value = text
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setContinuousScan(enabled: Boolean) {
        _continuousScan.value = enabled
    }

    /** Toglie una singola carta dal binder. */
    fun deleteCard(card: PokemonCard, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteCard(card)
                onResult(true)
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Eliminazione della carta non riuscita", e)
                onResult(false)
            }
        }
    }

    /** Ricerca manuale nel catalogo; [onResult] riceve null se la ricerca è fallita. */
    fun searchCatalog(text: String, onResult: (List<CatalogCard>?) -> Unit) = searchCatalog(text, false, onResult)

    /** Ricerca online su TCGdex (it, en, ja); [onResult] riceve null se la rete non risponde. */
    fun searchOnline(text: String, byArtist: Boolean, onResult: (List<CatalogCard>?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.searchOnline(text, byArtist))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Ricerca online non riuscita", e)
                onResult(null)
            }
        }
    }

    /** Completa una carta trovata online con i dati del set; se non riesce restituisce la carta com'è. */
    fun completeOnlineCard(card: CatalogCard, onResult: (CatalogCard) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.completeOnlineCard(card))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(card)
            }
        }
    }

    /** Come sopra; con [byArtist] il testo è il nome dell'autore della carta. */
    fun searchCatalog(text: String, byArtist: Boolean, onResult: (List<CatalogCard>?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.searchCatalog(text, byArtist))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Ricerca manuale non riuscita", e)
                onResult(null)
            }
        }
    }

    /** Carta scelta a mano dai risultati: si apre lo stesso dialog di tipologia e binder della scansione. */
    /**
     * Riconosce la carta da una foto: tra le letture (ipotesi diverse su dove sta la carta nella foto)
     * sceglie quella che somiglia di più a una carta del catalogo e mostra i candidati.
     * [onResult] riceve false se la carta non è stata riconosciuta.
     */
    fun identifyPhoto(readings: List<ScanReading>, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                var best: ScanMatch? = null
                var bestScore = -1.0
                for (reading in readings) {
                    val match = repository.findCandidates(reading, _scanSource.value)
                    val score = match.candidates.firstOrNull()?.score ?: continue
                    if (score > bestScore) {
                        best = match
                        bestScore = score
                    }
                }
                val chosen = best
                if (chosen == null) {
                    onResult(false)
                } else {
                    addingManually = true
                    _isScanning.value = false
                    _candidates.value = chosen.candidates
                    onResult(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Riconoscimento dalla foto non riuscito", e)
                onResult(false)
            }
        }
    }

    /** Aggiunge a un binder una carta creata dall'utente da una foto (non è nel catalogo). */
    fun addCustomCard(
        card: CatalogCard,
        category: CardCategory,
        binderId: Long,
        condition: CardCondition,
        onResult: (Boolean, Int) -> Unit
    ) {
        addingManually = true
        confirmAndAddCard(card, category, binderId, condition, onResult)
    }

    fun pickManualCard(card: CatalogCard) {
        addingManually = true
        _isScanning.value = false
        _candidates.value = listOf(Candidate(card, 1.0))
    }

    /** Toglie più carte insieme dai binder. */
    fun deleteCards(cards: List<PokemonCard>, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteCards(cards.map { it.id })
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Eliminazione delle carte non riuscita", e)
                onResult(false)
            }
        }
    }

    /** La collezione generale: una stessa carta presente in più binder conta una volta sola. */
    val collection: StateFlow<List<PokemonCard>> = repository.allCards
        .map { uniqueCards(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Copia o sposta le carte in un altro binder; [onResult] riceve (fatte, saltate), fatte = -1 se fallisce. */
    fun transferCards(cards: List<PokemonCard>, targetBinderId: Long, move: Boolean, onResult: (Int, Int) -> Unit) {
        viewModelScope.launch {
            try {
                val (done, skipped) = repository.transferCards(cards, targetBinderId, move)
                onResult(done, skipped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Trasferimento delle carte non riuscito", e)
                onResult(-1, 0)
            }
        }
    }

    /** Tutte le carte di tutti i binder, senza filtri (per backup e condivisione). */
    val allCards: StateFlow<List<PokemonCard>> = repository.allCards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Crea il testo del backup; [onResult] riceve null se qualcosa va storto. */
    fun exportBackup(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(backupManager.export())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Creazione del backup non riuscita", e)
                onResult(null)
            }
        }
    }

    /** Unisce il backup alla collezione; [onResult] riceve l'esito oppure il messaggio d'errore. */
    fun importBackup(json: String, onResult: (ImportResult?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(backupManager.import(json), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                onResult(null, e.message)
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Ripristino del backup non riuscito", e)
                onResult(null, "Ripristino non riuscito. La collezione non è stata modificata.")
            }
        }
    }

    /** Carte desiderate, dalla più recente. */
    val wishlist: StateFlow<List<WishlistCard>> = repository.wishlist
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Ultimo prezzo noto (euro) per id del catalogo. */
    val prices: StateFlow<Map<String, Double>> = repository.prices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Avanzamento dell'aggiornamento prezzi (fatte, totali); null se non è in corso. */
    private val _priceProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val priceProgress: StateFlow<Pair<Int, Int>?> = _priceProgress.asStateFlow()

    private val isRefreshingPrices = AtomicBoolean(false)

    /** [onResult] riceve quanti prezzi sono stati trovati e quante carte diverse sono state controllate; null se rete assente. */
    fun refreshBinderPrices(cards: List<PokemonCard>, onResult: (found: Int?, checked: Int) -> Unit) =
        refreshPrices(cards.map { it.toPriceRequest() }, onResult)

    fun refreshWishlistPrices(cards: List<WishlistCard>, onResult: (found: Int?, checked: Int) -> Unit) =
        refreshPrices(cards.map { it.toPriceRequest() }, onResult)

    private fun refreshPrices(
        requests: List<com.example.pokemonbynder.data.remote.PriceRequest>,
        onResult: (found: Int?, checked: Int) -> Unit
    ) {
        val checked = requests.distinctBy { it.key }.size
        if (checked == 0 || !isRefreshingPrices.compareAndSet(false, true)) return
        _priceProgress.value = 0 to checked
        viewModelScope.launch {
            try {
                val found = repository.refreshPrices(requests) { done, total ->
                    _priceProgress.value = done to total
                }
                onResult(found, checked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Aggiornamento dei prezzi non riuscito", e)
                onResult(null, checked)
            } finally {
                _priceProgress.value = null
                isRefreshingPrices.set(false)
            }
        }
    }

    /** [onResult]: true = aggiunta, false = c'era già, null = errore. */
    fun addToWishlist(card: CatalogCard, onResult: (Boolean?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.addToWishlist(card))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Aggiunta alla wishlist non riuscita", e)
                onResult(null)
            }
        }
    }

    fun removeFromWishlist(card: WishlistCard, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.removeFromWishlist(card)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Rimozione dalla wishlist non riuscita", e)
                onResult(false)
            }
        }
    }

    /** Carta trovata: dalla wishlist al binder. */
    fun moveWishlistToBinder(
        wish: WishlistCard,
        category: CardCategory,
        binderId: Long,
        condition: CardCondition,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                repository.moveWishlistToBinder(wish, category, binderId, condition)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Spostamento dalla wishlist non riuscito", e)
                onResult(false)
            }
        }
    }

    fun setFilterLanguage(language: String?) {
        _filterLanguage.value = language
    }

    fun setOnlyDuplicates(only: Boolean) {
        _onlyDuplicates.value = only
    }

    /** Tutti i set del catalogo offline (per la scheda "Set"). */
    suspend fun loadCatalogSets(): List<CatalogSetRow> = repository.catalogSets()

    /** Le carte di un set del catalogo, in ordine di numero. */
    suspend fun loadSetCards(lang: String, setCode: String): List<CatalogCard> = repository.setCards(lang, setCode)

    /** Mette nella wishlist le carte indicate; [onResult] riceve quante erano nuove, o null se c'è un errore. */
    fun addToWishlistAll(cards: List<CatalogCard>, onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.addAllToWishlist(cards))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Aggiunta alla wishlist non riuscita", e)
                onResult(null)
            }
        }
    }

    /** Aggiunge molte carte del catalogo a un binder in un colpo solo; [onResult] riceve null se fallisce. */
    fun addAllToBinder(cards: List<CatalogCard>, binderId: Long, condition: CardCondition, onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.addAllToBinder(cards, binderId, condition))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Aggiunta di più carte al binder non riuscita", e)
                onResult(null)
            }
        }
    }

    fun setFilterCategory(category: CardCategory?) {
        _filterCategory.value = category
    }
}

/** Toglie i doppioni tra binder: una carta per ogni carta del catalogo, con il maggior numero di copie. */
fun uniqueCards(list: List<PokemonCard>): List<PokemonCard> =
    list.groupBy { CardEntryId.catalogKeyOf(it.id) }.values.map { group ->
        val first = group.first()
        val copies = group.maxOf { it.quantity }
        if (copies == first.quantity) first else first.copy(quantity = copies)
    }
