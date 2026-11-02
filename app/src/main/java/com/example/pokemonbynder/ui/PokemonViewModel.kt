package com.example.pokemonbynder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.remote.CardDto
import com.example.pokemonbynder.data.repository.PokemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PokemonViewModel(private val repository: PokemonRepository) : ViewModel() {

    private val _filterCategory = MutableStateFlow<CardCategory?>(null)
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _pendingCard = MutableStateFlow<CardDto?>(null)
    
    // Espone nome e numero per la UI (destrutturabile in Pair)
    val pendingCardInfo: StateFlow<Pair<String, String>?> = _pendingCard
        .map { card -> card?.let { it.name to it.number } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val cards: StateFlow<List<PokemonCard>> = combine(
        repository.allCards,
        _filterCategory
    ) { allCards, category ->
        if (category == null) allCards else allCards.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        if (scanning) _pendingCard.value = null
    }

    fun onCardDetected(name: String, number: String) {
        if (_isSearching.value || _pendingCard.value != null) return

        viewModelScope.launch {
            _isSearching.value = true
            val card = repository.searchCardOnline(name, number)
            if (card != null) {
                _pendingCard.value = card
                _isScanning.value = false
            }
            _isSearching.value = false
        }
    }

    fun confirmAndAddCard(category: CardCategory, onResult: (Boolean) -> Unit) {
        val cardDto = _pendingCard.value
        if (cardDto == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                repository.saveCardToBinder(cardDto, category)
                _pendingCard.value = null
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }

    fun cancelSelection() {
        _pendingCard.value = null
    }

    fun setFilterCategory(category: CardCategory?) {
        _filterCategory.value = category
    }
}
