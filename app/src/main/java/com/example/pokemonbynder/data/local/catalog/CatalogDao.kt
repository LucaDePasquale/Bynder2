package com.example.pokemonbynder.data.local.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CatalogDao {
    @Query("SELECT COUNT(*) FROM catalog_cards")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(cards: List<CatalogCard>)

    @Query("DELETE FROM catalog_cards")
    suspend fun clear()

    @Query("SELECT * FROM catalog_cards WHERE numberKey = :key")
    suspend fun byNumberKey(key: String): List<CatalogCard>

    @Query("SELECT * FROM catalog_cards WHERE nameNorm IN (:names)")
    suspend fun byNameNorm(names: List<String>): List<CatalogCard>

    @Query("SELECT DISTINCT nameNorm FROM catalog_cards")
    suspend fun distinctNames(): List<String>
}
