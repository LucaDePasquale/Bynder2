package com.example.pokemonbynder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Un raccoglitore con un nome scelto dall'utente. Le carte indicano a quale binder appartengono con `binderId`. */
@Entity(tableName = "binders")
data class Binder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Il binder di partenza: ci finiscono le carte salvate prima che esistessero più binder. */
const val DEFAULT_BINDER_ID = 1L
const val DEFAULT_BINDER_NAME = "Il mio binder"

/**
 * Identificativo di una carta dentro un binder.
 * La stessa carta può stare in più binder, quindi l'id di ogni riga dipende anche dal binder.
 * Per il binder di partenza resta quello di sempre (così le carte già salvate non cambiano).
 */
object CardEntryId {
    private const val SEPARATOR = '|'

    fun of(binderId: Long, catalogKey: String): String =
        if (binderId == DEFAULT_BINDER_ID) catalogKey else "$binderId$SEPARATOR$catalogKey"

    /** Operazione inversa: da "3|it:xy1:17" (o da "it:xy1:17") torna "it:xy1:17". */
    fun catalogKeyOf(entryId: String): String = entryId.substringAfter(SEPARATOR)
}
