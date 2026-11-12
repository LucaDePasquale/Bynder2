package com.example.pokemonbynder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/** Indirizzo della ricerca su Cardmarket per una carta (nome e numero); italiano per le carte italiane, altrimenti inglese. */
fun cardmarketUrl(name: String, number: String, lang: String): String {
    val site = if (lang == "it") "it" else "en"
    val text = URLEncoder.encode("$name $number".trim(), "UTF-8")
    return "https://www.cardmarket.com/$site/Pokemon/Products/Search?searchString=$text"
}

/** Apre la carta su Cardmarket nel browser del telefono. */
fun openCardmarket(context: Context, name: String, number: String, lang: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cardmarketUrl(name, number, lang)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Impossibile aprire Cardmarket", Toast.LENGTH_LONG).show()
    }
}
