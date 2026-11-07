package com.example.pokemonbynder.data.local

import androidx.room.TypeConverter
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Gson().toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromCategory(value: CardCategory): String {
        return value.name
    }

    @TypeConverter
    fun toCategory(value: String): CardCategory {
        // Un nome sconosciuto non deve far chiudere l'app: la carta resta nel binder come "Altro".
        return CardCategory.entries.firstOrNull { it.name == value } ?: CardCategory.OTHER
    }

    @TypeConverter
    fun fromCondition(value: CardCondition): String {
        return value.name
    }

    @TypeConverter
    fun toCondition(value: String): CardCondition {
        return CardCondition.entries.firstOrNull { it.name == value } ?: CardCondition.NEAR_MINT
    }
}
