package com.example.pokemonbynder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.pokemonbynder.data.model.CardPrice
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {
    @Query("SELECT * FROM card_prices")
    fun getAll(): Flow<List<CardPrice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(price: CardPrice)
}
