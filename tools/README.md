# Catalogo carte offline

L'app non usa più nessuna API: cerca le carte in un catalogo incluso nell'app
(`app/src/main/assets/catalog.tsv.gz`), generato dal database
https://github.com/type-null/PTCG-database (licenza MIT).

Al primo avvio l'app importa il file in un database Room (`catalog_db`, pochi secondi).
Il binder dell'utente resta nel database `pokemon_binder_db`, separato.

## Rigenerare o aggiornare il catalogo

```bash
git clone --depth 1 https://github.com/type-null/PTCG-database.git
python3 tools/build_card_catalog.py --db PTCG-database --langs it en
```

Poi in `CatalogImporter.kt` aumenta `CATALOG_VERSION` di 1: alla prossima apertura
l'app reimporta il catalogo. Per aggiungere altre lingue basta elencarle in `--langs`
(es. `--langs it en fr de`); il nome del Pokémon letto dalla carta viene confrontato con
i nomi di tutte le lingue incluse.

## Note

- Le immagini non sono nel catalogo: l'app le carica solo quando servono, dall'indirizzo
  indicato nel database (serve internet solo per mostrarle). Alcune carte non hanno immagine.
- Test della logica di lettura e confronto: `./gradlew testDebugUnitTest`
