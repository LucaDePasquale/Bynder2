package com.example.pokemonbynder.data.backup

import androidx.room.withTransaction
import com.example.pokemonbynder.data.local.AppDatabase
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Esito di un'importazione. */
data class ImportResult(
    val cardsAdded: Int,
    val cardsAlreadyThere: Int,
    val bindersAdded: Int,
    val wishlistAdded: Int
)

/**
 * Esporta la collezione in un file e la reimporta.
 *
 * L'importazione UNISCE il backup a ciò che c'è già e non cancella né modifica nulla:
 * - i binder con lo stesso nome (senza distinguere maiuscole) sono considerati lo stesso binder, gli altri vengono creati;
 * - le carte già presenti nello stesso binder restano come sono (importare due volte lo stesso file non crea doppioni);
 * - la wishlist si completa con le carte che mancano; i prezzi si aggiornano solo se il backup è più recente.
 * Tutto avviene in un'unica transazione: se qualcosa va storto, non cambia niente.
 */
class BackupManager(private val database: AppDatabase) {

    suspend fun export(): String {
        val data = BackupData(
            binders = database.binderDao().getAll().first(),
            cards = database.pokemonDao().getAllCards().first(),
            wishlist = database.wishlistDao().getAll().first(),
            prices = database.priceDao().getAll().first()
        )
        return withContext(Dispatchers.Default) { BackupCodec.encode(data, System.currentTimeMillis()) }
    }

    /** Lancia [BackupException] se il file non è un backup valido. */
    suspend fun import(json: String): ImportResult {
        val data = withContext(Dispatchers.Default) { BackupCodec.decode(json) }
        val binderDao = database.binderDao()
        val pokemonDao = database.pokemonDao()
        val wishlistDao = database.wishlistDao()
        val priceDao = database.priceDao()

        return database.withTransaction {
            val binders = binderDao.getAll().first().toMutableList()
            val idMap = HashMap<Long, Long>()
            var bindersAdded = 0
            for (backupBinder in data.binders) {
                val match = binders.firstOrNull { it.name.equals(backupBinder.name, ignoreCase = true) }
                if (match != null) {
                    idMap[backupBinder.id] = match.id
                } else {
                    val newId = binderDao.insert(Binder(name = backupBinder.name, createdAt = backupBinder.createdAt))
                    binders.add(Binder(id = newId, name = backupBinder.name, createdAt = backupBinder.createdAt))
                    idMap[backupBinder.id] = newId
                    bindersAdded++
                }
            }
            val fallbackBinderId = binders.firstOrNull()?.id ?: DEFAULT_BINDER_ID

            var added = 0
            var alreadyThere = 0
            for (card in data.cards) {
                val binderId = idMap[card.binderId] ?: fallbackBinderId
                val id = CardEntryId.of(binderId, CardEntryId.catalogKeyOf(card.id))
                if (pokemonDao.getById(id) != null) {
                    alreadyThere++
                } else {
                    pokemonDao.insertCard(card.copy(id = id, binderId = binderId))
                    added++
                }
            }

            var wishlistAdded = 0
            for (wish in data.wishlist) {
                if (wishlistDao.insert(wish) != -1L) wishlistAdded++
            }

            val knownPrices = priceDao.getAll().first().associateBy { it.catalogKey }
            for (price in data.prices) {
                val known = knownPrices[price.catalogKey]
                if (known == null || price.updatedAt > known.updatedAt) priceDao.upsert(price)
            }

            ImportResult(added, alreadyThere, bindersAdded, wishlistAdded)
        }
    }
}
