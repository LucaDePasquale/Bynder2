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

    @Query("SELECT * FROM catalog_cards WHERE numberKey = :key AND lang != 'ja'")
    suspend fun byNumberKey(key: String): List<CatalogCard>

    @Query("SELECT * FROM catalog_cards WHERE nameNorm IN (:names) AND lang != 'ja'")
    suspend fun byNameNorm(names: List<String>): List<CatalogCard>

    /** Ricerca manuale: nomi che contengono il testo (già normalizzato), prima quelli che iniziano così. */
    @Query(
        "SELECT * FROM catalog_cards WHERE nameNorm LIKE '%' || :query || '%' " +
            "ORDER BY CASE WHEN nameNorm LIKE :query || '%' THEN 0 ELSE 1 END, nameNorm, setName, lang DESC " +
            "LIMIT :limit"
    )
    suspend fun searchByName(query: String, limit: Int): List<CatalogCard>

    /**
     * Ricerca flessibile: ogni criterio vuoto (testo vuoto, 0) viene ignorato.
     * Il nome è già normalizzato; l'autore si cerca come testo contenuto; numero e totale devono coincidere.
     */
    @Query(
        "SELECT * FROM catalog_cards WHERE " +
            "(:name = '' OR nameNorm LIKE '%' || :name || '%') AND " +
            "(:artist = '' OR artist LIKE '%' || :artist || '%') AND " +
            "(:numberKey = '' OR numberKey = :numberKey) AND " +
            "(:total = 0 OR setTotal = :total) " +
            "ORDER BY CASE WHEN :name != '' AND nameNorm LIKE :name || '%' THEN 0 ELSE 1 END, nameNorm, setName, lang DESC " +
            "LIMIT :limit"
    )
    suspend fun search(name: String, artist: String, numberKey: String, total: Int, limit: Int): List<CatalogCard>

    /** Tutti i set del catalogo con quante carte contengono. */
    @Query(
        "SELECT lang, setCode, setName, COUNT(*) AS total FROM catalog_cards " +
            "GROUP BY lang, setCode, setName ORDER BY setName, lang"
    )
    suspend fun sets(): List<CatalogSetRow>

    @Query("SELECT * FROM catalog_cards WHERE lang = :lang AND setCode = :setCode")
    suspend fun cardsOfSet(lang: String, setCode: String): List<CatalogCard>

    @Query("UPDATE catalog_cards SET imageUrl = :url WHERE lang = :lang AND setCode = :setCode AND number = :number")
    suspend fun updateImage(lang: String, setCode: String, number: String, url: String)

    @Query("SELECT DISTINCT nameNorm FROM catalog_cards WHERE lang != 'ja'")
    suspend fun distinctNames(): List<String>
}
