package com.example.pokemonbynder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Ultimo prezzo noto di una carta (in euro, tendenza Cardmarket tramite TCGdex). La chiave è l'id del catalogo. */
@Entity(tableName = "card_prices")
data class CardPrice(
    @PrimaryKey val catalogKey: String,
    val priceEur: Double,
    val updatedAt: Long
)
