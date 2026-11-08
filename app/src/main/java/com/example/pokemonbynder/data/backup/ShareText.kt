package com.example.pokemonbynder.data.backup

import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard

/** Testi pronti da condividere (messaggi, e-mail, note). Restituiscono null se non c'è niente da condividere. */
object ShareText {

    private fun place(set: String, number: String): String =
        listOf(set, number).filter { it.isNotBlank() }.joinToString(" ")

    /** Carte di cui si hanno più copie (somma di tutti i binder), con quante ne avanzano. */
    fun duplicates(cards: List<PokemonCard>): String? {
        val grouped = cards.groupBy { CardEntryId.catalogKeyOf(it.id) }.values
            .map { group -> group.first() to group.sumOf { it.quantity } }
            .filter { it.second > 1 }
            .sortedWith(compareBy({ it.first.set.lowercase() }, { it.first.name.lowercase() }))
        if (grouped.isEmpty()) return null

        val extra = grouped.sumOf { it.second - 1 }
        return buildString {
            appendLine("Carte doppie ($extra in più)")
            grouped.forEach { (card, total) ->
                appendLine("• ${card.name} — ${place(card.set, card.number)} — ×$total (${total - 1} in più)")
            }
        }.trimEnd()
    }

    fun wishlist(list: List<WishlistCard>): String? {
        if (list.isEmpty()) return null
        return buildString {
            appendLine("Carte che cerco (${list.size})")
            list.sortedWith(compareBy({ it.set.lowercase() }, { it.name.lowercase() })).forEach {
                appendLine("• ${it.name} — ${place(it.set, it.number)}")
            }
        }.trimEnd()
    }

    /** Tutta la collezione, binder per binder. */
    fun collection(cards: List<PokemonCard>, binders: List<Binder>): String? {
        if (cards.isEmpty()) return null
        val byBinder = cards.groupBy { it.binderId }
        return buildString {
            binders.filter { byBinder.containsKey(it.id) }.forEach { binder ->
                val inBinder = byBinder.getValue(binder.id)
                val copies = inBinder.sumOf { it.quantity }
                appendLine("${binder.name} ($copies ${if (copies == 1) "carta" else "carte"})")
                inBinder.sortedWith(compareBy({ it.set.lowercase() }, { it.name.lowercase() })).forEach {
                    val times = if (it.quantity > 1) " ×${it.quantity}" else ""
                    appendLine("• ${it.name} — ${place(it.set, it.number)}$times (${it.condition.short})")
                }
                appendLine()
            }
        }.trimEnd()
    }
}
