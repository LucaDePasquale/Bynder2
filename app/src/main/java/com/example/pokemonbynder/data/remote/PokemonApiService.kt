package com.example.pokemonbynder.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface PokemonApiService {
    @GET("sets/en.json")
    suspend fun getSets(): List<SetDto>

    @GET("cards/en/{setId}.json")
    suspend fun getCardsBySet(@Path("setId") setId: String): List<CardDto>
}

data class SetDto(
    val id: String,
    val name: String,
    val series: String,
    val total: Int,
    val releaseDate: String?
)

data class CardDto(
    val id: String,
    val name: String,
    val supertype: String,
    val subtypes: List<String>?,
    val number: String,
    val artist: String?,
    val rarity: String?,
    val images: CardImages,
    val set: CardSet
)

data class CardImages(
    val small: String,
    val large: String
)

data class CardSet(
    val id: String,
    val name: String,
    val series: String
)
