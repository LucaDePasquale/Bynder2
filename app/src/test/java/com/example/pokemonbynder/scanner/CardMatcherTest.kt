package com.example.pokemonbynder.scanner

import com.example.pokemonbynder.data.local.catalog.CatalogCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMatcherTest {

    private fun card(lang: String, name: String, number: String, total: Int, set: String = "set") = CatalogCard(
        lang = lang,
        setCode = set,
        setName = set,
        number = number,
        numberKey = CardText.normalizeNumber(number),
        setTotal = total,
        name = name,
        nameNorm = CardText.normalizeName(name),
        rarity = null,
        artist = null,
        cardType = "Pokémon",
        imageUrl = ""
    )

    private fun reading(names: List<String>, key: String?, total: Int = 0, lang: String? = null) =
        ScanReading(names, key?.let { PrintedNumber(it, total, it) }, lang)

    @Test
    fun picksTheRightCardDespiteAnOcrTypo() {
        val pool = listOf(
            card("it", "Vivillon", "17", 146, "xy1"),
            card("it", "Eevee", "17", 146, "xy1b"),
            card("it", "Pikachu", "17", 100, "other")
        )

        val ranked = CardMatcher.rank(pool, reading(listOf("Vivilon"), "17", 146))

        assertEquals("Vivillon", ranked.first().card.name)
        assertTrue(CardMatcher.isConfident(ranked))
    }

    @Test
    fun isNotConfidentWhenTheNameWasNotRead() {
        val pool = listOf(
            card("it", "Vivillon", "17", 146, "xy1"),
            card("it", "Eevee", "17", 146, "xy1b")
        )

        val ranked = CardMatcher.rank(pool, reading(emptyList(), "17", 146))

        assertFalse(CardMatcher.isConfident(ranked))
    }

    @Test
    fun sameCardInItalianAndEnglishCountsAsOne() {
        val pool = listOf(
            card("it", "Pikachu", "25", 165, "sv3pt5"),
            card("en", "Pikachu", "25", 165, "MEW")
        )

        val ranked = CardMatcher.rank(pool, reading(listOf("Pikachu"), "25", 165))

        assertEquals("it", ranked.first().card.lang) // a parità vince la lingua preferita
        assertTrue(CardMatcher.isConfident(ranked))
    }

    @Test
    fun languageHintBreaksTies() {
        val pool = listOf(
            card("it", "Pikachu", "25", 165, "sv3pt5"),
            card("en", "Pikachu", "25", 165, "MEW")
        )

        val ranked = CardMatcher.rank(pool, reading(listOf("Pikachu"), "25", 165, lang = "en"))

        assertEquals("en", ranked.first().card.lang)
    }

    @Test
    fun nameOnlyMatchesAreNeverConfident() {
        val pool = listOf(card("it", "Pikachu", "25", 165))

        val ranked = CardMatcher.rank(pool, reading(listOf("Pikachu"), key = null))

        assertFalse(CardMatcher.isConfident(ranked))
    }

    @Test
    fun normalizesNamesAndNumbers() {
        assertEquals("pokemonex", CardText.normalizeName("Pokémon Ex"))
        assertEquals("25", CardText.normalizeNumber("025"))
        assertEquals("tg1", CardText.normalizeNumber("TG01"))
        assertEquals("105a", CardText.normalizeNumber("105a"))
        assertEquals("0", CardText.normalizeNumber("000"))
        assertEquals(1.0, CardText.similarity("abc", "abc"), 0.0)
        assertEquals(0.875, CardText.similarity("vivilon", "vivillon"), 0.0001)
    }
}
