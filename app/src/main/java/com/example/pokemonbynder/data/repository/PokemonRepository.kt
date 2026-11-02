package com.example.pokemonbynder.data.repository

import com.example.pokemonbynder.data.local.PokemonDao
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.remote.CardDto
import com.example.pokemonbynder.data.remote.PokemonApiService
import com.example.pokemonbynder.data.remote.SetDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

class PokemonRepository(
    private val apiService: PokemonApiService,
    private val pokemonDao: PokemonDao
) {
    val allCards: Flow<List<PokemonCard>> = pokemonDao.getAllCards()
    private var cachedSets: List<SetDto>? = null

    /**
     * Cerca una carta nel catalogo GitHub scaricando i file dei set.
     * Utilizza coroutine parallele (lotti da 10) per velocizzare la ricerca partendo dai set più recenti.
     */
    suspend fun searchCardOnline(name: String, number: String): CardDto? = coroutineScope {
        try {
            // Carica la lista dei set e ordina dal più recente
            val sets = cachedSets ?: apiService.getSets()
                .sortedByDescending { it.releaseDate }
                .also { cachedSets = it }
            
            // Normalizziamo il numero cercato (es. "025" -> "25")
            val normalizedSearchNumber = number.trimStart('0').ifBlank { "0" }
            
            // Cerchiamo nei set in parallelo a lotti di 10 per massimizzare la velocità
            for (batch in sets.chunked(10)) {
                val deferredResults = batch.map { set ->
                    async {
                        try {
                            val cards = apiService.getCardsBySet(set.id)
                            // Cerchiamo prima una corrispondenza esatta del nome, poi contenuta
                            cards.find { card ->
                                val cardNum = card.number.trimStart('0').ifBlank { "0" }
                                card.name.equals(name, ignoreCase = true) && cardNum == normalizedSearchNumber
                            } ?: cards.find { card ->
                                val cardNum = card.number.trimStart('0').ifBlank { "0" }
                                card.name.contains(name, ignoreCase = true) && cardNum == normalizedSearchNumber
                            }
                        } catch (e: Exception) { null }
                    }
                }
                
                val match = deferredResults.awaitAll().filterNotNull().firstOrNull()
                if (match != null) return@coroutineScope match
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveCardToBinder(cardDto: CardDto, category: CardCategory) {
        val pokemonCard = PokemonCard(
            id = cardDto.id,
            name = cardDto.name,
            supertype = cardDto.supertype,
            subtypes = cardDto.subtypes ?: emptyList(),
            number = cardDto.number,
            artist = cardDto.artist,
            rarity = cardDto.rarity,
            imageUrl = cardDto.images.large,
            set = cardDto.set.name,
            category = category
        )
        pokemonDao.insertCard(pokemonCard)
    }

    fun getCardsByArtist(artist: String) = pokemonDao.getCardsByArtist(artist)
}
