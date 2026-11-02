package com.example.pokemonbynder

import android.app.Application
import androidx.room.Room
import com.example.pokemonbynder.data.local.AppDatabase
import com.example.pokemonbynder.data.remote.PokemonApiService
import com.example.pokemonbynder.data.repository.PokemonRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PokemonApplication : Application() {

    lateinit var database: AppDatabase
    lateinit var repository: PokemonRepository

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "pokemon_binder_db"
        ).build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/data/v2/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(PokemonApiService::class.java)

        repository = PokemonRepository(apiService, database.pokemonDao())
    }
}
