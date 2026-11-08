package com.example.pokemonbynder.backup

import com.example.pokemonbynder.data.backup.ShareText
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {

    private fun card(id: String, name: String, quantity: Int, binderId: Long = 1, set: String = "Base Set") = PokemonCard(
        id = id, name = name, supertype = "Pokémon", subtypes = emptyList(), number = "58", artist = null,
        rarity = null, imageUrl = "", set = set, binderId = binderId, quantity = quantity,
        condition = CardCondition.NEAR_MINT
    )

    @Test
    fun nothingToShareGivesNull() {
        assertNull(ShareText.duplicates(listOf(card("a", "Pikachu", 1))))
        assertNull(ShareText.wishlist(emptyList()))
        assertNull(ShareText.collection(emptyList(), emptyList()))
    }

    @Test
    fun duplicatesAddUpCopiesAcrossBinders() {
        // Una copia in due binder diversi vale come una carta doppia
        val text = ShareText.duplicates(
            listOf(card("it:b:58", "Pikachu", 1), card("2|it:b:58", "Pikachu", 1, binderId = 2), card("it:b:4", "Charizard", 1))
        )!!
        assertTrue(text.startsWith("Carte doppie (1 in più)"))
        assertTrue(text.contains("• Pikachu — Base Set 58 — ×2 (1 in più)"))
        assertTrue(!text.contains("Charizard"))
    }

    @Test
    fun wishlistIsListedWithCount() {
        val text = ShareText.wishlist(listOf(WishlistCard("k", "Mew", "Pokémon", "151", null, null, "", "151")))!!
        assertEquals("Carte che cerco (1)\n• Mew — 151 151", text)
    }

    @Test
    fun collectionIsGroupedByBinder() {
        val text = ShareText.collection(
            listOf(card("a", "Pikachu", 2), card("2|b", "Mew", 1, binderId = 2)),
            listOf(Binder(1, "Il mio binder"), Binder(2, "Fuoco"))
        )!!
        assertTrue(text.contains("Il mio binder (2 carte)"))
        assertTrue(text.contains("• Pikachu — Base Set 58 ×2 (NM)"))
        assertTrue(text.contains("Fuoco (1 carta)"))
    }
}
