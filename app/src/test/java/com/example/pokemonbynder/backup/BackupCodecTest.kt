package com.example.pokemonbynder.backup

import com.example.pokemonbynder.data.backup.BackupCodec
import com.example.pokemonbynder.data.backup.BackupData
import com.example.pokemonbynder.data.backup.BackupException
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.CardPrice
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {

    private fun card(id: String, binderId: Long = 1) = PokemonCard(
        id = id, name = "Pikachu", supertype = "Pokémon", subtypes = listOf("Basic"), number = "58",
        artist = "Mitsuhiro Arita", rarity = "Common", imageUrl = "https://example.com/58.png", set = "Base Set",
        category = CardCategory.HOLO, binderId = binderId, quantity = 3, condition = CardCondition.LIGHT_PLAYED
    )

    private val sample = BackupData(
        binders = listOf(Binder(1, "Il mio binder", 100L), Binder(2, "Fuoco", 200L)),
        cards = listOf(card("it:base1:58"), card("2|it:base1:58", binderId = 2)),
        wishlist = listOf(WishlistCard("en:sv1:10", "Sprigatito", "Pokémon", "10", null, "Common", "", "Scarlet & Violet", 5L)),
        prices = listOf(CardPrice("it:base1:58", 1.25, 300L))
    )

    @Test
    fun encodeThenDecodeGivesTheSameData() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample, 1234L))
        assertEquals(sample, decoded)
    }

    @Test
    fun unknownCategoryAndConditionFallBackToDefaults() {
        val json = """{"app":"PokemonBynder","version":1,"cards":[
            {"id":"a","name":"X","category":"NON_ESISTE","condition":"???","quantity":0}]}"""
        val card = BackupCodec.decode(json).cards.single()
        assertEquals(CardCategory.OTHER, card.category)
        assertEquals(CardCondition.NEAR_MINT, card.condition)
        assertEquals(1, card.quantity)
    }

    @Test
    fun rowsWithoutIdOrNameAreIgnored() {
        val json = """{"app":"PokemonBynder","version":1,
            "binders":[{"id":1,"name":"  "},{"id":2,"name":"Acqua"}],
            "cards":[{"name":"Senza id"},{"id":"b","name":""},{"id":"c","name":"Buona"}],
            "prices":[{"catalogKey":"k","priceEur":-1.0},{"catalogKey":"j","priceEur":2.5,"updatedAt":9}]}"""
        val data = BackupCodec.decode(json)
        assertEquals(listOf("Acqua"), data.binders.map { it.name })
        assertEquals(listOf("c"), data.cards.map { it.id })
        assertEquals(listOf("j"), data.prices.map { it.catalogKey })
    }

    @Test
    fun foreignOrBrokenFilesAreRejected() {
        listOf("", "non è json", """{"app":"Altro","version":1}""", """{"app":"PokemonBynder"}""").forEach { text ->
            try {
                BackupCodec.decode(text)
                fail("Doveva essere rifiutato: $text")
            } catch (e: BackupException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun newerVersionIsRejected() {
        try {
            BackupCodec.decode("""{"app":"PokemonBynder","version":${BackupCodec.VERSION + 1}}""")
            fail("Doveva essere rifiutato")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("versione più recente"))
        }
    }
}
