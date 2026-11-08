package com.example.pokemonbynder.data.local.catalog

/** Un set del catalogo con il numero di carte che contiene (risultato di una query, non una tabella). */
data class CatalogSetRow(
    val lang: String,
    val setCode: String,
    val setName: String,
    val total: Int
)
