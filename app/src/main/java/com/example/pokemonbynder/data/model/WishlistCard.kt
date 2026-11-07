package com.example.pokemonbynder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una carta desiderata, separata dai binder. L'id è l'identificativo della carta nel catalogo
 * (lingua:set:numero), così la stessa carta non compare due volte.
 */
@Entity(tableName = "wishlist_cards")
data class WishlistCard(
    @PrimaryKey val id: String,
    val name: String,
    val supertype: String,
    val number: String,
    val artist: String?,
    val rarity: String?,
    val imageUrl: String,
    val set: String,
    val addedAt: Long = System.currentTimeMillis()
)
