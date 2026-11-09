package com.example.pokemonbynder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.pokemonbynder.data.model.WishlistCard
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {
    @Query("SELECT * FROM wishlist_cards ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WishlistCard>>

    /** Restituisce -1 se la carta era già nella wishlist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: WishlistCard): Long

    @Query("UPDATE wishlist_cards SET imageUrl = :imageUrl WHERE id = :id")
    suspend fun updateImage(id: String, imageUrl: String)

    @Query("DELETE FROM wishlist_cards WHERE id = :id")
    suspend fun deleteById(id: String)
}
