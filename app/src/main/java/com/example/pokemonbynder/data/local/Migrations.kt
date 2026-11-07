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

/**
 * Versione 2 -> 3: quantità e condizione delle carte, wishlist e prezzi.
 * Le carte già salvate restano come sono: una copia ciascuna, in condizione Near Mint.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pokemon_cards` ADD COLUMN `quantity` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `pokemon_cards` ADD COLUMN `condition` TEXT NOT NULL DEFAULT 'NEAR_MINT'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wishlist_cards` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`supertype` TEXT NOT NULL, `number` TEXT NOT NULL, `artist` TEXT, `rarity` TEXT, " +
                "`imageUrl` TEXT NOT NULL, `set` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `card_prices` (`catalogKey` TEXT NOT NULL, `priceEur` REAL NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`catalogKey`))"
        )
    }
}
