package com.example.pokemonbynder.ui

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemonbynder.data.backup.BackupException
import com.example.pokemonbynder.data.backup.BackupManager
import com.example.pokemonbynder.data.backup.ImportResult
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import com.example.pokemonbynder.data.repository.PokemonRepository
import com.example.pokemonbynder.data.repository.toPriceRequest
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
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

    /**
     * Carte da mostrare: nel binder scelto (o in tutti), con la tipologia scelta (o tutte),
     * che contengono il testo cercato, nell'ordine scelto.
     */
    val cards: StateFlow<List<PokemonCard>> = combine(
        repository.allCards,
        _selectedBinderId,
        _filterCategory,
        _searchQuery,
        _sortOrder
    ) { allCards, binderId, category, query, order ->
        val text = query.trim()
        val filtered = allCards.filter {
            (binderId == null || it.binderId == binderId) &&
                (category == null || it.category == category) &&
                (text.isEmpty() || it.matches(text))
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
            lastOnlineRequestAt = 0L
            scanStartedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Chiamato dall'analizzatore della fotocamera a ogni lettura riuscita. */
    fun onReading(reading: ScanReading) {
        if (!_isScanning.value || _candidates.value != null) return
        val source = _scanSource.value
        val now = SystemClock.elapsedRealtime()
        // Online le richieste sono limitate: al massimo una ogni 1,5 secondi.
        if (source == ScanSource.TCGDEX && now - lastOnlineRequestAt < ONLINE_MIN_INTERVAL_MS) return
        if (!isProcessing.compareAndSet(false, true)) return // una ricerca alla volta
        if (source == ScanSource.TCGDEX) lastOnlineRequestAt = now

        viewModelScope.launch {
            try {
                val match = repository.findCandidates(reading, source)
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
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                repository.updateCard(card, category, binderId, quantity, condition)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Modifica della carta non riuscita", e)
                onResult(false)
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
    fun searchCatalog(text: String, onResult: (List<CatalogCard>?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(repository.searchCatalog(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PokemonViewModel", "Ricerca manuale non riuscita", e)
                onResult(null)
            }
        }
    }

    /** Carta scelta a mano dai risultati: si apre lo stesso dialog di tipologia e binder della scansione. */
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

    fun setFilterCategory(category: CardCategory?) {
        _filterCategory.value = category
    }
}
