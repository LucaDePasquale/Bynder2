package com.example.pokemonbynder.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.repository.PokemonRepository
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
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
    }

    val isCatalogReady: StateFlow<Boolean> = catalogReady

    private val _filterCategory = MutableStateFlow<CardCategory?>(null)
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Candidati da mostrare all'utente (il primo è il più probabile); null = nessuna scelta in corso. */
    private val _candidates = MutableStateFlow<List<Candidate>?>(null)
    val candidates: StateFlow<List<Candidate>?> = _candidates.asStateFlow()

    val cards: StateFlow<List<PokemonCard>> = combine(
        repository.allCards,
        _filterCategory
    ) { allCards, category ->
        if (category == null) allCards else allCards.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val isProcessing = AtomicBoolean(false)
    private val recentTops = ArrayDeque<String>()
    private var scanStartedAt = 0L

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        if (scanning) {
            _candidates.value = null
            recentTops.clear()
            scanStartedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Chiamato dall'analizzatore della fotocamera a ogni lettura riuscita. */
    fun onReading(reading: ScanReading) {
        if (!_isScanning.value || _candidates.value != null) return
        if (!isProcessing.compareAndSet(false, true)) return // una ricerca alla volta

        viewModelScope.launch {
            try {
                val match = repository.findCandidates(reading)
                val top = match.candidates.firstOrNull() ?: return@launch
                if (!_isScanning.value) return@launch // chiuso nel frattempo

                recentTops.addLast(top.card.binderId)
                while (recentTops.size > RECENT_READINGS) recentTops.removeFirst()
                val stable = recentTops.count { it == top.card.binderId } >= MIN_AGREEING_READINGS

                val waitedLong = SystemClock.elapsedRealtime() - scanStartedAt > FALLBACK_AFTER_MS &&
                    top.score >= FALLBACK_MIN_SCORE

                if ((match.confident && stable) || waitedLong) {
                    _candidates.value = match.candidates
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
