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
    @ColumnInfo(defaultValue = "1") val binderId: Long = DEFAULT_BINDER_ID, // Binder che contiene la carta
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1, // Quante copie si hanno
    val condition: CardCondition = CardCondition.NEAR_MINT // Stato di conservazione
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
    OTHER("Altro");

    companion object {
        /**
         * Ricava la tipologia dal nome e dalla rarità della carta (non si sceglie più a mano).
         * Non può riconoscere le Reverse: nel catalogo sono la stessa carta della versione normale.
         */
        fun detect(name: String, rarity: String?): CardCategory {
            val upper = name.trim().uppercase()
            val r = (rarity ?: "").lowercase()
            return when {
                upper.contains("V-ASTRO") || upper.contains("VASTRO") || upper.endsWith(" V ASTRO") -> V_ASTRO
                upper.endsWith(" VMAX") || upper.contains(" VMAX ") -> VMAX
                upper.contains("&") && upper.contains("GX") -> TAG_TEAM
                upper.endsWith(" EX") || upper.endsWith("-EX") || upper.contains(" EX ") -> EX
                upper.endsWith(" V") || upper.contains("V-UNION") -> V
                upper.contains("LEGEND") || upper.contains("LEGGEND") -> LEGGENDE
                r.contains("special illustration") || r.contains("illustrazione speciale") || r.contains("sar") -> SAR
                r.contains("illustration") || r.contains("illustrazione") -> AR
                r.contains("full art") || r.contains("ultra") -> FULL_ART
                r.contains("holo") -> HOLO
                else -> NORMAL
            }
        }
    }
}

/**
 * Stato di conservazione della carta fisica. Nel database si salva il NOME di ogni voce:
 * non rinominare né eliminare voci già esistenti.
 */
enum class CardCondition(val label: String, val short: String) {
    MINT("Mint", "M"),
    NEAR_MINT("Near Mint", "NM"),
    EXCELLENT("Excellent", "EX"),
    GOOD("Good", "GD"),
    LIGHT_PLAYED("Light Played", "LP"),
    PLAYED("Played", "PL"),
    POOR("Poor", "P")
}
