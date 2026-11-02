package com.example.pokemonbynder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pokemon_cards")
data class PokemonCard(
    @PrimaryKey val id: String, // ID unico dall'API (es: "sv4pt5-151")
    val name: String,
    val supertype: String, // Es: "Pokémon"
    val subtypes: List<String>, // Es: ["Basic"]
    val number: String,
    val artist: String?,
    val rarity: String?,
    val imageUrl: String,
    val set: String,
    val category: CardCategory // Normale, Reverse, AR, SAR, Full Art
)

enum class CardCategory {
    NORMAL,
    REVERSE,
    AR,
    SAR,
    FULL_ART,
    OTHER
}
