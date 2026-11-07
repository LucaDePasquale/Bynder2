package com.example.pokemonbynder.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pokemon_cards")
data class PokemonCard(
    @PrimaryKey val id: String, // Unico per carta E binder (vedi CardEntryId), es: "it:xy1:17" o "3|it:xy1:17"
    val name: String,
    val supertype: String, // Es: "Pokémon"
    val subtypes: List<String>, // Es: ["Basic"]
    val number: String,
    val artist: String?,
    val rarity: String?,
    val imageUrl: String,
    val set: String,
    val category: CardCategory, // Tipologia della carta fisica, modificabile dopo l'aggiunta
    @ColumnInfo(defaultValue = "1") val binderId: Long = DEFAULT_BINDER_ID // Binder che contiene la carta
)

/**
 * Tipologia della carta fisica. Nel database si salva il NOME di ogni voce (es. "V_ASTRO"):
 * non rinominare né eliminare voci già esistenti, altrimenti le carte salvate perdono la tipologia.
 * Per aggiungerne una nuova basta inserire una riga qui: compare da sola in aggiunta e in modifica.
 */
enum class CardCategory(val label: String) {
    NORMAL("Normale"),
    EX("EX"),
    TAG_TEAM("Tag Team"),
    V("V"),
    V_ASTRO("V ASTRO"),
    VMAX("VMAX"),
    HOLO("Holo"),
    FULL_ART("Full Art"),
    LEGGENDE("Leggende"),
    REVERSE("Reverse"),
    AR("AR"),
    SAR("SAR"),
    OTHER("Altro")
}
