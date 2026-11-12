package com.example.pokemonbynder

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.example.pokemonbynder.data.backup.BackupManager
import com.example.pokemonbynder.data.local.AppDatabase
import com.example.pokemonbynder.data.local.MIGRATION_1_2
import com.example.pokemonbynder.data.local.MIGRATION_2_3
import com.example.pokemonbynder.data.local.catalog.CatalogDatabase
import com.example.pokemonbynder.data.local.catalog.CatalogImporter
import com.example.pokemonbynder.data.repository.PokemonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PokemonApplication : Application() {

    lateinit var database: AppDatabase
    lateinit var catalogDatabase: CatalogDatabase
    lateinit var repository: PokemonRepository
    lateinit var backupManager: BackupManager
    lateinit var settings: SettingsStore
    lateinit var priceHistory: PriceHistoryStore
    lateinit var wishTargets: WishTargetStore
    lateinit var imageResolver: ImageResolver

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _catalogReady = MutableStateFlow(false)

    /** Diventa true quando il catalogo offline è pronto (al primo avvio richiede pochi secondi). */
    val catalogReady: StateFlow<Boolean> = _catalogReady.asStateFlow()

    private val _catalogError = MutableStateFlow<String?>(null)

    /** Messaggio d'errore se l'importazione del catalogo fallisce (null se tutto bene). */
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "pokemon_binder_db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        backupManager = BackupManager(database)
        settings = SettingsStore(this)
        priceHistory = PriceHistoryStore(this)
        wishTargets = WishTargetStore(this)

        catalogDatabase = Room.databaseBuilder(
            this,
            CatalogDatabase::class.java,
            "catalog_db"
        ).build()

        repository = PokemonRepository(
            database.pokemonDao(),
            catalogDatabase.catalogDao(),
            database.binderDao(),
            database.wishlistDao(),
            database.priceDao(),
            loadSetPairs = { CatalogImporter.readSetPairs(this) }
        )

        imageResolver = ImageResolver(repository)

        // Con un'installazione nuova non c'è ancora nessun binder: si crea quello di partenza.
        appScope.launch {
            try {
                repository.ensureDefaultBinder()
            } catch (e: Exception) {
                Log.e("PokemonApplication", "Creazione del binder di partenza non riuscita", e)
            }
            try {
                repository.redetectCategories()
            } catch (e: Exception) {
                Log.e("PokemonApplication", "Aggiornamento delle tipologie non riuscito", e)
            }
        }

        appScope.launch {
            try {
                CatalogImporter(this@PokemonApplication, catalogDatabase).importIfNeeded()
                _catalogReady.value = true
                repository.repairMissingImages()
            } catch (e: Exception) {
                Log.e("PokemonApplication", "Importazione del catalogo non riuscita", e)
                _catalogError.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
}
