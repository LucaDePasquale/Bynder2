package com.example.pokemonbynder

import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CardEntryIdTest {

    @Test
    fun defaultBinderKeepsTheOldId() {
        // Le carte salvate prima dei binder non devono cambiare id
        assertEquals("it:xy1:17", CardEntryId.of(DEFAULT_BINDER_ID, "it:xy1:17"))
    }

    @Test
    fun otherBindersGetADifferentId() {
        val a = CardEntryId.of(2, "it:xy1:17")
        val b = CardEntryId.of(3, "it:xy1:17")
        assertEquals("2|it:xy1:17", a)
        assertNotEquals(a, b)
        assertNotEquals(a, CardEntryId.of(DEFAULT_BINDER_ID, "it:xy1:17"))
    }

    @Test
    fun catalogKeyIsRecoveredFromAnyId() {
        assertEquals("it:xy1:17", CardEntryId.catalogKeyOf("it:xy1:17"))
        assertEquals("it:xy1:17", CardEntryId.catalogKeyOf("12|it:xy1:17"))
        assertEquals("en:sv03.5:025", CardEntryId.catalogKeyOf(CardEntryId.of(7, "en:sv03.5:025")))
    }
}
