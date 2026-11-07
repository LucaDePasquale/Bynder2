package com.example.pokemonbynder.data.local

import androidx.room.*
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import kotlinx.coroutines.flow.Flow

@Dao
interface PokemonDao {
    @Query("SELECT * FROM pokemon_cards")
    fun getAllCards(): Flow<List<PokemonCard>>

    @Query("SELECT * FROM pokemon_cards WHERE artist = :artist")
    fun getCardsByArtist(artist: String): Flow<List<PokemonCard>>

    @Query("SELECT * FROM pokemon_cards WHERE category = :category")
    fun getCardsByCategory(category: CardCategory): Flow<List<PokemonCard>>

    @Query("UPDATE pokemon_cards SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: CardCategory)

    @Query("DELETE FROM pokemon_cards WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pokemon_cards WHERE binderId = :binderId")
    suspend fun deleteCardsInBinder(binderId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: PokemonCard)

    @Delete
    suspend fun deleteCard(card: PokemonCard)
}
