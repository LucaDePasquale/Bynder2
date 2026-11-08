package com.example.pokemonbynder.data.stats

import com.example.pokemonbynder.data.model.CardEntryId

/** La lingua di una carta fa parte del suo identificativo ("it:set:numero"): IT ed EN sono carte distinte. */
object CardLanguage {
    /** Codice lingua ("it", "en"...) di una carta del binder o della wishlist. */
    fun of(entryId: String): String = CardEntryId.catalogKeyOf(entryId).substringBefore(':')

    fun short(code: String): String = code.uppercase()

    fun label(code: String): String = when (code.lowercase()) {
        "it" -> "Italiano"
        "en" -> "Inglese"
        "ja" -> "Giapponese"
        "fr" -> "Francese"
        "de" -> "Tedesco"
        "es" -> "Spagnolo"
        "pt" -> "Portoghese"
        else -> code.uppercase()
    }
}
