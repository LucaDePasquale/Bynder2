package com.example.pokemonbynder.scanner

/** Da dove cercare la carta dopo aver letto nome e numero con la fotocamera. */
enum class ScanSource(val label: String, val description: String) {
    CATALOG(
        "Catalogo offline",
        "Veloce e funziona senza internet. Contiene le carte incluse nell'app (italiano e inglese)."
    ),
    TCGDEX(
        "TCGdex online",
        "Set sempre aggiornati, anche i più recenti. Serve internet e la ricerca è un po' più lenta."
    );

    companion object {
        fun fromName(name: String?): ScanSource = entries.firstOrNull { it.name == name } ?: CATALOG
    }
}
