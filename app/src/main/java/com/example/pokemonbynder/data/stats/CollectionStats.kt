package com.example.pokemonbynder.data.stats

import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard

/** Una voce delle statistiche: [copies] = copie possedute, [distinct] = carte diverse. */
data class CountEntry(val label: String, val copies: Int, val distinct: Int)

data class ValuedCard(val card: PokemonCard, val unitPrice: Double)

data class CollectionStats(
    val distinctCards: Int,
    val copies: Int,
    /** Valore stimato di tutte le copie con prezzo noto, in euro. */
    val totalValue: Double,
    /** Carte diverse di cui non si conosce il prezzo. */
    val cardsWithoutPrice: Int,
    val byCategory: List<CountEntry>,
    val bySet: List<CountEntry>,
    val byRarity: List<CountEntry>,
    val byLanguage: List<CountEntry>,
    val mostValuable: List<ValuedCard>
)

object CollectionStatsCalculator {

    fun compute(
        cards: List<PokemonCard>,
        prices: Map<String, Double>,
        maxSets: Int = 10,
        maxValuable: Int = 10
    ): CollectionStats {
        fun keyOf(card: PokemonCard) = CardEntryId.catalogKeyOf(card.id)

        fun group(labelOf: (PokemonCard) -> String): List<CountEntry> =
            cards.groupBy(labelOf).map { (label, rows) ->
                CountEntry(label, rows.sumOf { it.quantity }, rows.distinctBy(::keyOf).size)
            }.sortedWith(compareByDescending<CountEntry> { it.copies }.thenBy { it.label.lowercase() })

        val distinctKeys = cards.map(::keyOf).toSet()
        val valued = cards
            .distinctBy(::keyOf)
            .mapNotNull { card -> prices[keyOf(card)]?.let { ValuedCard(card, it) } }
            .sortedByDescending { it.unitPrice }

        return CollectionStats(
            distinctCards = distinctKeys.size,
            copies = cards.sumOf { it.quantity },
            totalValue = cards.sumOf { (prices[keyOf(it)] ?: 0.0) * it.quantity },
            cardsWithoutPrice = distinctKeys.count { prices[it] == null },
            byCategory = group { it.category.label },
            bySet = group { it.set.ifBlank { "Set sconosciuto" } }.take(maxSets),
            byRarity = group { it.rarity?.takeIf { r -> r.isNotBlank() } ?: "Rarità sconosciuta" },
            byLanguage = group { CardLanguage.label(CardLanguage.of(it.id)) },
            mostValuable = valued.take(maxValuable)
        )
    }
}
