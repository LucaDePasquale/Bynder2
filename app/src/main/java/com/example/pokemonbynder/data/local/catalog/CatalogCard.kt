package com.example.pokemonbynder.data.local.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una carta del catalogo offline (importato da assets/catalog.tsv.gz, generato dal database PTCG).
 * Non è il binder dell'utente: quello resta in [com.example.pokemonbynder.data.model.PokemonCard].
 */
@Entity(
    tableName = "catalog_cards",
    indices = [Index("numberKey"), Index("nameNorm")]
)
data class CatalogCard(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val lang: String,          // "it" / "en"
    val setCode: String,
    val setName: String,
    val number: String,        // come nel database, es. "25", "TG01", "105a"
    val numberKey: String,     // numero normalizzato per il confronto, es. "25", "tg1"
    val setTotal: Int,         // totale stampato sulla carta (es. 165), 0 se sconosciuto
    val name: String,
    val nameNorm: String,      // nome normalizzato per il confronto
    val rarity: String?,
    val artist: String?,
    val cardType: String,
    val imageUrl: String       // può essere vuoto: alcune carte non hanno immagine nel database
) {
    /** Identificativo stabile della carta nel catalogo (non dipende dal binder). */
    val catalogKey: String get() = "$lang:$setCode:$number"
}
