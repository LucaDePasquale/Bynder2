package com.example.pokemonbynder.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_NAME

/**
 * Versione 1 -> 2: arrivano i binder con un nome.
 * Crea la tabella dei binder, aggiunge il binder di partenza e assegna a esso tutte le carte già salvate:
 * nessuna carta viene persa o modificata.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `binders` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `binders` (`id`, `name`, `createdAt`) " +
                "VALUES ($DEFAULT_BINDER_ID, '$DEFAULT_BINDER_NAME', ${System.currentTimeMillis()})"
        )
        db.execSQL(
            "ALTER TABLE `pokemon_cards` ADD COLUMN `binderId` INTEGER NOT NULL DEFAULT $DEFAULT_BINDER_ID"
        )
    }
}
