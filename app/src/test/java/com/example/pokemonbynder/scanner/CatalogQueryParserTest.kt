package com.example.pokemonbynder.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryParserTest {

    @Test
    fun nameAndNumberWithSetTotal() {
        val q = CatalogQueryParser.parse("Pikachu 25/102", byArtist = false)
        assertEquals("pikachu", q.name)
        assertEquals("25", q.numberKey)
        assertEquals(102, q.total)
    }

    @Test
    fun nameWithTrailingNumberOnly() {
        val q = CatalogQueryParser.parse("Pikachu 025", byArtist = false)
        assertEquals("pikachu", q.name)
        assertEquals("25", q.numberKey)
        assertEquals(0, q.total)
    }

    @Test
    fun plainNameHasNoNumber() {
        val q = CatalogQueryParser.parse("Charizard ex", byArtist = false)
        assertEquals("charizardex", q.name)
        assertEquals("", q.numberKey)
        assertEquals(0, q.total)
    }

    @Test
    fun numberAndTotalWithoutName() {
        val q = CatalogQueryParser.parse("25/102", byArtist = false)
        assertEquals("", q.name)
        assertEquals("25", q.numberKey)
        assertEquals(102, q.total)
        assertTrue(q.isSearchable)
    }

    @Test
    fun artistSearchKeepsTheText() {
        val q = CatalogQueryParser.parse("  Mitsuhiro   Arita ", byArtist = true)
        assertEquals("Mitsuhiro Arita", q.artist)
        assertEquals("", q.name)
        assertEquals("", q.numberKey)
    }

    @Test
    fun tooShortTextIsNotSearchable() {
        assertFalse(CatalogQueryParser.parse("p", byArtist = false).isSearchable)
        assertFalse(CatalogQueryParser.parse("a", byArtist = true).isSearchable)
    }
}
