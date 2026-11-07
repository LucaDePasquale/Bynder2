package com.example.pokemonbynder.ui

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.repository.PokemonRepository
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PokemonViewModel(
    private val repository: PokemonRepository,
    catalogReady: StateFlow<Boolean>
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

    val cards: StateFlow<List<PokemonCard>> = combine(
        repository.allCards,
        _filterCategory
    ) { allCards, category ->
        if (category == null) allCards else allCards.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

                recentTops.addLast(top.card.binderId)
                while (recentTops.size > RECENT_READINGS) recentTops.removeFirst()
                val stable = recentTops.count { it == top.card.binderId } >= MIN_AGREEING_READINGS

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

    fun confirmAndAddCard(card: CatalogCard, category: CardCategory, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.saveCardToBinder(card, category)
                _candidates.value = null
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    /** Cambia la tipologia di una carta già nel binder. */
    fun updateCardCategory(card: PokemonCard, category: CardCategory, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.updateCategory(card.id, category)
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    fun cancelSelection() {
        _candidates.value = null
    }

    fun setFilterCategory(category: CardCategory?) {
        _filterCategory.value = category
    }
}
