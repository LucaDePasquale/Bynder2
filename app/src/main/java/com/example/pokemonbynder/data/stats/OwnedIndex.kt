package com.example.pokemonbynder.data.stats

import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.scanner.CardText

/**
 * Sa quali carte di ogni set possiedi, in qualsiasi binder.
 *
 * Una carta si riconosce in due modi: dall'identificativo del catalogo (lingua:set:numero) oppure,
 * per le carte salvate da TCGdex che usano codici di set diversi, da lingua + nome del set + numero.
 */
class OwnedIndex(cards: List<PokemonCard>) {
    private val byCode = HashMap<String, MutableSet<String>>()
    private val byName = HashMap<String, MutableSet<String>>()

    init {
        for (card in cards) {
            val key = CardEntryId.catalogKeyOf(card.id)
            val lang = key.substringBefore(':')
            val setCode = key.substringAfter(':', "").substringBeforeLast(':', "")
            val number = CardText.normalizeNumber(key.substringAfterLast(':'))
            byCode.getOrPut(codeKey(lang, setCode)) { HashSet() }.add(number)
            byName.getOrPut(nameKey(lang, card.set)) { HashSet() }.add(CardText.normalizeNumber(card.number))
        }
    }

    private fun codeKey(lang: String, setCode: String) = "$lang:$setCode"
    private fun nameKey(lang: String, setName: String) = "$lang|${CardText.normalizeName(setName)}"

    /** Numeri normalizzati delle carte possedute di questo set. */
    fun numbersFor(lang: String, setCode: String, setName: String): Set<String> {
        val a = byCode[codeKey(lang, setCode)].orEmpty()
        val b = byName[nameKey(lang, setName)].orEmpty()
        return if (b.isEmpty()) a else if (a.isEmpty()) b else a + b
    }

    fun isOwned(numbers: Set<String>, cardNumber: String): Boolean = CardText.normalizeNumber(cardNumber) in numbers
}
