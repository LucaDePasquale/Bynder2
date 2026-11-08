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

    /** Ricerca manuale: nomi che contengono il testo (già normalizzato), prima quelli che iniziano così. */
    @Query(
        "SELECT * FROM catalog_cards WHERE nameNorm LIKE '%' || :query || '%' " +
            "ORDER BY CASE WHEN nameNorm LIKE :query || '%' THEN 0 ELSE 1 END, nameNorm, setName, lang DESC " +
            "LIMIT :limit"
    )
    suspend fun searchByName(query: String, limit: Int): List<CatalogCard>

    /** Tutti i set del catalogo con quante carte contengono. */
    @Query(
        "SELECT lang, setCode, setName, COUNT(*) AS total FROM catalog_cards " +
            "GROUP BY lang, setCode, setName ORDER BY setName, lang"
    )
    suspend fun sets(): List<CatalogSetRow>

    @Query("SELECT * FROM catalog_cards WHERE lang = :lang AND setCode = :setCode")
    suspend fun cardsOfSet(lang: String, setCode: String): List<CatalogCard>

    @Query("SELECT DISTINCT nameNorm FROM catalog_cards")
    suspend fun distinctNames(): List<String>
}
