package com.example.pokemonbynder

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.example.pokemonbynder.data.local.AppDatabase
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
        ).build()

        catalogDatabase = Room.databaseBuilder(
            this,
            CatalogDatabase::class.java,
            "catalog_db"
        ).build()

        repository = PokemonRepository(database.pokemonDao(), catalogDatabase.catalogDao())

        appScope.launch {
            try {
                CatalogImporter(this@PokemonApplication, catalogDatabase).importIfNeeded()
                _catalogReady.value = true
            } catch (e: Exception) {
                Log.e("PokemonApplication", "Importazione del catalogo non riuscita", e)
                _catalogError.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
}
