package com.example.pokemonbynder.data.local.catalog

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Database separato da quello del binder: il catalogo si può ricreare in qualsiasi momento
 * dal file negli assets, senza toccare le carte salvate dall'utente.
 */
@Database(entities = [CatalogCard::class], version = 1, exportSchema = false)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
