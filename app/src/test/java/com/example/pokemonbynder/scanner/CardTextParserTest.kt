package com.example.pokemonbynder.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CardTextParserTest {

    // Riquadro guida 400x550: fascia nome fino a y=321, fascia numero da y=640.
    private val guide = Box(100, 200, 500, 750)

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, Box(left, top, right, bottom))

    @Test
    fun readsNameNumberAndLanguageFromAModernItalianCard() {
        val reading = CardTextParser.parse(
            listOf(
                line("Charizard ex PS 330", 130, 215, 420, 250),
                line("Evolve da Charmeleon", 130, 255, 300, 275),
                line("Debolezza Acqua x2", 120, 650, 300, 670),
                line("025/165", 130, 700, 200, 720),
                line("rumore fuori dal riquadro", 10, 10, 60, 30)
            ),
            guide
        )

        assertNotNull(reading)
        assertEquals("Charizard ex", reading!!.nameLines.first())
        assertEquals("25", reading.number?.key)
        assertEquals(165, reading.number?.total)
        assertEquals("it", reading.languageHint)
    }

    @Test
    fun readsSubsetNumbersWithLetterPrefix() {
        val reading = CardTextParser.parse(
            listOf(
                line("Pikachu VMAX", 130, 215, 420, 250),
                line("TG29/TG30", 130, 700, 220, 720)
            ),
            guide
        )

        assertEquals("tg29", reading!!.number?.key)
        assertEquals(30, reading.number?.total)
    }

    @Test
    fun fixesTypicalOcrConfusionsInDigits() {
        val reading = CardTextParser.parse(
            listOf(
                line("Vivillon", 130, 215, 300, 250),
                line("O25/I65", 130, 700, 200, 720)
            ),
            guide
        )

        assertEquals("25", reading!!.number?.key)
        assertEquals(165, reading.number?.total)
    }

    @Test
    fun readsPromoNumbersWithoutTotal() {
        val reading = CardTextParser.parse(
            listOf(
                line("Obstagoon di Galar", 130, 215, 400, 250),
                line("SWSH193", 130, 700, 220, 720)
            ),
            guide
        )

        assertEquals("swsh193", reading!!.number?.key)
        assertEquals(0, reading.number?.total)
    }

    @Test
    fun removesHitPointsGluedToTheName() {
        assertEquals("Pikachu", CardTextParser.cleanName("Pikachu HP60"))
        assertEquals("Charizard ex", CardTextParser.cleanName("Charizard ex PS330"))
    }

    @Test
    fun discardsLinesWithoutEnoughLetters() {
        assertNull(CardTextParser.cleanName("130"))
        assertNull(CardTextParser.cleanName("HP"))
    }

    @Test
    fun ignoresEverythingOutsideTheGuide() {
        val reading = CardTextParser.parse(
            listOf(
                line("Altra carta 012/100", 700, 300, 900, 320),
                line("Sfondo", 10, 10, 60, 30)
            ),
            guide
        )

        assertNull(reading)
    }

    @Test
    fun detectsEnglishCards() {
        val reading = CardTextParser.parse(
            listOf(
                line("Pikachu", 130, 215, 300, 250),
                line("weakness Fighting x2", 120, 650, 300, 670),
                line("retreat", 320, 650, 400, 670),
                line("58/102", 130, 700, 200, 720)
            ),
            guide
        )

        assertEquals("en", reading!!.languageHint)
    }
}
