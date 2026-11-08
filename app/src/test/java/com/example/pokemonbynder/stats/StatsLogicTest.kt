package com.example.pokemonbynder.stats

import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.data.stats.CollectionStatsCalculator
import com.example.pokemonbynder.data.stats.OwnedIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsLogicTest {

    private fun card(
        id: String,
        name: String = "Pikachu",
        set: String = "Base Set",
        number: String = "58",
        quantity: Int = 1,
        rarity: String? = "Common",
        category: CardCategory = CardCategory.NORMAL,
        binderId: Long = 1
    ) = PokemonCard(
        id = id, name = name, supertype = "Pokémon", subtypes = emptyList(), number = number, artist = null,
        rarity = rarity, imageUrl = "", set = set, category = category, binderId = binderId, quantity = quantity
    )

    @Test
    fun languageComesFromTheCatalogKey() {
        assertEquals("it", CardLanguage.of("it:base1:58"))
        assertEquals("en", CardLanguage.of("3|en:base1:58"))
        assertEquals("Italiano", CardLanguage.label("it"))
        assertEquals("IT", CardLanguage.short("it"))
    }

    @Test
    fun ownedIndexMatchesByCodeAndIgnoresLeadingZeros() {
        val index = OwnedIndex(listOf(card("it:base1:058", number = "058")))
        val numbers = index.numbersFor("it", "base1", "Base Set")
        assertTrue(index.isOwned(numbers, "58"))
        assertFalse(index.isOwned(numbers, "59"))
        // un'altra lingua dello stesso set non conta
        assertTrue(index.numbersFor("en", "base1", "Base Set").isEmpty())
    }

    @Test
    fun ownedIndexMatchesBySetNameWhenCodesDiffer() {
        // Carta salvata da TCGdex con un codice di set diverso da quello del catalogo
        val index = OwnedIndex(listOf(card("it:sv07:12", set = "Corona Astrale", number = "12")))
        val numbers = index.numbersFor("it", "SCR", "Corona Astrale")
        assertTrue(index.isOwned(numbers, "12"))
    }

    @Test
    fun statsCountCopiesAndDistinctCards() {
        val cards = listOf(
            card("it:a:1", name = "A", quantity = 2, rarity = "Rare", category = CardCategory.EX),
            card("2|it:a:1", name = "A", quantity = 1, rarity = "Rare", category = CardCategory.EX, binderId = 2),
            card("en:b:5", name = "B", set = "Altro set", number = "5", rarity = null)
        )
        val stats = CollectionStatsCalculator.compute(cards, mapOf("it:a:1" to 3.0))

        assertEquals(2, stats.distinctCards)
        assertEquals(4, stats.copies)
        assertEquals(9.0, stats.totalValue, 0.0001) // 3 copie x 3 euro
        assertEquals(1, stats.cardsWithoutPrice)
        assertEquals("EX", stats.byCategory.first().label)
        assertEquals(3, stats.byCategory.first().copies)
        assertEquals(1, stats.byCategory.first().distinct)
        assertTrue(stats.byRarity.any { it.label == "Rarità sconosciuta" })
        assertEquals(listOf("Italiano", "Inglese"), stats.byLanguage.map { it.label })
        assertEquals(listOf("A"), stats.mostValuable.map { it.card.name })
    }

    @Test
    fun mostValuableAreSortedAndLimited() {
        val cards = (1..15).map { card("it:s:$it", name = "C$it", number = "$it") }
        val prices = (1..15).associate { "it:s:$it" to it.toDouble() }
        val stats = CollectionStatsCalculator.compute(cards, prices, maxValuable = 3)
        assertEquals(listOf("C15", "C14", "C13"), stats.mostValuable.map { it.card.name })
    }
}
