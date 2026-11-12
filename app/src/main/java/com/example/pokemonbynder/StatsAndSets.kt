package com.example.pokemonbynder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.local.catalog.CatalogSetRow
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.data.stats.CollectionStatsCalculator
import com.example.pokemonbynder.data.stats.CountEntry
import com.example.pokemonbynder.data.stats.OwnedIndex
import com.example.pokemonbynder.scanner.CardText
import kotlinx.coroutines.CancellationException

/** Filtri extra del binder: solo le doppie e, se ci sono carte in più lingue, la lingua. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraFilterBar(
    languages: List<String>,
    selectedLanguage: String?,
    onLanguage: (String?) -> Unit,
    onlyDuplicates: Boolean,
    onOnlyDuplicates: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = onlyDuplicates,
            onClick = { onOnlyDuplicates(!onlyDuplicates) },
            label = { Text("Solo doppie") }
        )
        if (languages.size > 1) {
            Text("Lingua:", style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = selectedLanguage == null, onClick = { onLanguage(null) }, label = { Text("Tutte") })
            languages.forEach { code ->
                FilterChip(
                    selected = selectedLanguage == code,
                    onClick = { onLanguage(code) },
                    label = { Text(CardLanguage.label(code)) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Completamento dei set
// ---------------------------------------------------------------------------------------------

/** Cosa fanno i pulsanti dell'elenco di un set: nella wishlist o in un binder. */
class SetAction(
    val bulkLabel: (Int) -> String,
    val singleLabel: String,
    val allowOwned: Boolean
)

val WISHLIST_SET_ACTION = SetAction({ "Mancanti in wishlist ($it)" }, "+ Wishlist", false)
val BINDER_SET_ACTION = SetAction({ "Aggiungi mancanti ($it)" }, "+ Aggiungi", true)

/**
 * Scheda "Set": elenco dei set del catalogo con quante carte possiedi; toccando un set si vedono
 * le carte che hai e quelle che mancano, che si possono mettere nella wishlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetsTab(
    catalogReady: Boolean,
    ownedCards: List<PokemonCard>,
    loadSets: suspend () -> List<CatalogSetRow>,
    loadSetCards: suspend (String, String) -> List<CatalogCard>,
    onAddToWishlist: (List<CatalogCard>) -> Unit,
    action: SetAction = WISHLIST_SET_ACTION
) {
    var sets by remember { mutableStateOf<List<CatalogSetRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(catalogReady) {
        if (catalogReady) {
            try {
                sets = loadSets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
    }

    val owned = remember(ownedCards) { OwnedIndex(ownedCards) }
    var open by remember { mutableStateOf<CatalogSetRow?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyStarted by remember { mutableStateOf(false) }

    val current = open
    if (current != null) {
        SetDetail(
            set = current,
            owned = owned,
            loadSetCards = loadSetCards,
            onBack = { open = null },
            onAddToWishlist = onAddToWishlist,
            action = action
        )
        return
    }

    val all = sets
    if (all == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (failed) "Impossibile leggere i set del catalogo." else "Caricamento dei set…")
        }
        return
    }

    val text = CardText.normalizeName(query)
    val shown = all.filter { row ->
        (text.isEmpty() || CardText.normalizeName(row.setName).contains(text)) &&
            (!onlyStarted || owned.numbersFor(row.lang, row.setCode, row.setName).isNotEmpty())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Cerca un set") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = onlyStarted, onClick = { onlyStarted = !onlyStarted }, label = { Text("Solo set iniziati") })
            Spacer(modifier = Modifier.width(12.dp))
            Text("${shown.size} set", style = MaterialTheme.typography.labelMedium)
        }
        if (shown.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (onlyStarted && text.isEmpty()) "Non hai ancora carte di nessun set del catalogo." else "Nessun set trovato.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { "${it.lang}:${it.setCode}" }) { row ->
                    val have = minOf(owned.numbersFor(row.lang, row.setCode, row.setName).size, row.total)
                    SetRow(row = row, have = have, onClick = { open = row })
                }
            }
        }
    }
}

@Composable
private fun SetRow(row: CatalogSetRow, have: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.setName,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(CardLanguage.short(row.lang), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("$have/${row.total}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = if (row.total == 0) 0f else have.toFloat() / row.total,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetDetail(
    set: CatalogSetRow,
    owned: OwnedIndex,
    loadSetCards: suspend (String, String) -> List<CatalogCard>,
    onBack: () -> Unit,
    onAddToWishlist: (List<CatalogCard>) -> Unit,
    action: SetAction
) {
    BackHandler(enabled = true) { onBack() }

    var cards by remember(set) { mutableStateOf<List<CatalogCard>?>(null) }
    LaunchedEffect(set) {
        cards = try {
            loadSetCards(set.lang, set.setCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    var onlyMissing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Tutti i set") }

        val list = cards
        if (list == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Caricamento…") }
            return@Column
        }

        val numbers = owned.numbersFor(set.lang, set.setCode, set.setName)
        val missing = list.filter { !owned.isOwned(numbers, it.number) }
        val have = list.size - missing.size

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("${set.setName} (${CardLanguage.short(set.lang)})", fontWeight = FontWeight.Bold)
            Text("$have/${list.size} carte", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = if (list.isEmpty()) 0f else have.toFloat() / list.size,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyMissing, onClick = { onlyMissing = !onlyMissing }, label = { Text("Solo mancanti") })
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onAddToWishlist(missing) }, enabled = missing.isNotEmpty()) {
                    Text(action.bulkLabel(missing.size))
                }
            }
        }

        val shown = if (onlyMissing) missing else list
        if (shown.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Set completo!", fontWeight = FontWeight.Bold)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(shown, key = { it.catalogKey }) { card ->
                    val isOwned = owned.isOwned(numbers, card.number)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(card.number, modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            card.rarity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (isOwned) {
                            Text("✓ Ce l'hai", style = MaterialTheme.typography.labelMedium)
                        }
                        if (!isOwned || action.allowOwned) {
                            TextButton(onClick = { onAddToWishlist(listOf(card)) }) { Text(action.singleLabel) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Statistiche
// ---------------------------------------------------------------------------------------------

/** Scheda "Statistiche": carte per tipologia, set, rarità e lingua, e le carte di maggior valore. */
@Composable
fun StatsTab(
    cards: List<PokemonCard>,
    prices: Map<String, Double>,
    progress: Pair<Int, Int>?,
    onRefreshPrices: () -> Unit,
    history: List<PricePoint> = emptyList()
) {
    val stats = remember(cards, prices) { CollectionStatsCalculator.compute(cards, prices) }

    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nessuna statistica: aggiungi qualche carta ai binder.", modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("${stats.distinctCards} carte diverse · ${stats.copies} copie", fontWeight = FontWeight.Bold)
        }
        ValueBar(
            text = valueText("Valore", stats.totalValue, stats.cardsWithoutPrice, stats.distinctCards),
            progress = progress,
            enabled = true,
            onRefresh = onRefreshPrices
        )

        PriceHistoryChart(points = history)

        StatsSection("Per tipologia", stats.byCategory)
        StatsSection("Per set (primi 10)", stats.bySet)
        StatsSection("Per rarità", stats.byRarity)
        if (stats.byLanguage.size > 1) StatsSection("Per lingua", stats.byLanguage)

        if (stats.mostValuable.isNotEmpty()) {
            SectionTitle("Le più preziose")
            stats.mostValuable.forEachIndexed { index, valued ->
                val card = valued.card
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}.", modifier = Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${card.set} · ${card.number} · ${CardLanguage.short(CardLanguage.of(card.id))}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(formatPrice(valued.unitPrice), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatsSection(title: String, entries: List<CountEntry>) {
    if (entries.isEmpty()) return
    SectionTitle(title)
    val max = entries.maxOf { it.copies }.coerceAtLeast(1)
    entries.forEach { entry ->
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
            Row {
                Text(entry.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.distinct != entry.copies) "${entry.copies} (${entry.distinct} diverse)" else "${entry.copies}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entry.copies.toFloat() / max)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
