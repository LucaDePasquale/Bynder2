package com.example.pokemonbynder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardPrice
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard

@Database(
    entities = [PokemonCard::class, Binder::class, WishlistCard::class, CardPrice::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pokemonDao(): PokemonDao
    abstract fun binderDao(): BinderDao
    abstract fun wishlistDao(): WishlistDao
    abstract fun priceDao(): PriceDao
}
