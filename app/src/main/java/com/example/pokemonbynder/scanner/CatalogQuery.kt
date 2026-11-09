package com.example.pokemonbynder.scanner

/**
 * Ricerca nel catalogo già scomposta nelle sue parti.
 * Esempi: "Pikachu 25/102" -> nome pikachu, numero 25, set da 102 carte;
 * "Pikachu 25" -> nome e numero; "25/102" -> solo numero e totale; autore "Arita" -> solo autore.
 */
data class CatalogQuery(
    val name: String = "",       // già normalizzato (vedi CardText.normalizeName)
    val artist: String = "",     // testo cercato nell'autore
    val numberKey: String = "",  // numero normalizzato (vedi CardText.normalizeNumber), vuoto se non indicato
    val total: Int = 0,          // carte del set stampate sulla carta (es. 102), 0 se non indicato
    val rawName: String = ""     // nome come scritto (senza il numero), per le ricerche online
) {
    val isSearchable: Boolean
        get() = name.length >= 2 || artist.length >= 2 || numberKey.isNotEmpty() || total > 0
}

object CatalogQueryParser {
    // "25/102", "TG29/TG30", "105a/129"
    private val slashNumber = Regex("""(?<![A-Za-z0-9])([A-Za-z]{0,3}[0-9]{1,3}[A-Za-z]?)\s*/\s*[A-Za-z]{0,3}([0-9]{1,3})(?![0-9])""")

    // Numero scritto in fondo al nome: "Pikachu 25", "Pikachu TG05"
    private val trailingNumber = Regex("""(?:^|\s)([A-Za-z]{0,3}[0-9]{1,3}[A-Za-z]?)$""")

    private val manySpaces = Regex("""\s+""")

    fun parse(raw: String, byArtist: Boolean): CatalogQuery {
        val text = manySpaces.replace(raw.trim(), " ")
        if (byArtist) return CatalogQuery(artist = text)

        var rest = text
        var key = ""
        var total = 0

        val slash = slashNumber.find(rest)
        if (slash != null) {
            key = CardText.normalizeNumber(slash.groupValues[1])
            total = slash.groupValues[2].toIntOrNull() ?: 0
            rest = rest.removeRange(slash.range).trim()
        } else {
            val trailing = trailingNumber.find(rest)
            if (trailing != null) {
                key = CardText.normalizeNumber(trailing.groupValues[1])
                rest = rest.removeRange(trailing.range).trim()
            }
        }
        return CatalogQuery(name = CardText.normalizeName(rest), numberKey = key, total = total, rawName = rest)
    }
}
