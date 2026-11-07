package com.example.pokemonbynder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.PokemonCard

@Database(entities = [PokemonCard::class, Binder::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pokemonDao(): PokemonDao
    abstract fun binderDao(): BinderDao
}
