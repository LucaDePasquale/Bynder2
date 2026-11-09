package com.example.pokemonbynder

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.ui.PokemonViewModel

/** Elenco dei binder: "Tutte le carte" più un riquadro per ogni binder, con numero di carte e valore. */
@Composable
fun BindersOverview(
    binders: List<Binder>,
    allCards: List<PokemonCard>,
    prices: Map<String, Double>,
    onOpen: (Long?) -> Unit,
    onNew: () -> Unit
) {
    val perBinder = remember(allCards) { allCards.groupBy { it.binderId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            BinderCard(
                title = "Tutte le carte",
                cards = allCards,
                prices = prices,
                onClick = { onOpen(null) }
            )
        }
        items(binders, key = { it.id }) { binder ->
            BinderCard(
                title = binder.name,
                cards = perBinder[binder.id].orEmpty(),
                prices = prices,
                onClick = { onOpen(binder.id) }
            )
        }
        item {
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("+ Nuovo binder") }
        }
    }
}

@Composable
private fun BinderCard(title: String, cards: List<PokemonCard>, prices: Map<String, Double>, onClick: () -> Unit) {
    val copies = cards.sumOf { it.quantity }
    val value = cards.sumOf { (prices[CardEntryId.catalogKeyOf(it.id)] ?: 0.0) * it.quantity }
    val cover = cards.lastOrNull()?.imageUrl.orEmpty()
    val coverKey = cards.lastOrNull()?.let { CardEntryId.catalogKeyOf(it.id) }.orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (coverKey.isNotBlank()) {
                    CardImage(
                        imageUrl = cover,
                        catalogKey = coverKey,
                        name = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(title.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        copies == 0 -> "Vuoto"
                        copies == cards.size -> if (copies == 1) "1 carta" else "$copies carte"
                        else -> "${cards.size} carte diverse · $copies copie"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (value > 0.0) {
                    Text("Valore stimato ${formatPrice(value)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Le carte di un binder (o di tutti): filtri, ricerca, ordinamento e vista a griglia o a lista.
 * Un tocco su una carta la ingrandisce; i tre puntini aprono le azioni; il tocco lungo seleziona.
 */
@Composable
fun BinderDetail(
    viewModel: PokemonViewModel,
    listView: Boolean,
    onToggleView: () -> Unit,
    selectedIds: Set<String>,
    selecting: Boolean,
    binderNames: Map<Long, String>,
    onToggleSelected: (PokemonCard) -> Unit,
    onOpenCard: (PokemonCard) -> Unit,
    onEditCard: (PokemonCard) -> Unit,
    onDeleteCard: (PokemonCard) -> Unit
) {
    val context = LocalContext.current
    val cards by viewModel.cards.collectAsState()
    val allCards by viewModel.allCards.collectAsState()
    val selectedBinderId by viewModel.selectedBinderId.collectAsState()
    val filterCategory by viewModel.filterCategory.collectAsState()
    val filterLanguage by viewModel.filterLanguage.collectAsState()
    val onlyDuplicates by viewModel.onlyDuplicates.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val cardCounts by viewModel.cardCounts.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val priceProgress by viewModel.priceProgress.collectAsState()
    val languages = remember(allCards) { allCards.map { CardLanguage.of(it.id) }.distinct().sorted() }

    fun priceOf(card: PokemonCard): Double? = prices[CardEntryId.catalogKeyOf(card.id)]

    Column(modifier = Modifier.fillMaxSize()) {
        CategoryFilterBar(selected = filterCategory, onSelect = { viewModel.setFilterCategory(it) })

        ExtraFilterBar(
            languages = languages,
            selectedLanguage = filterLanguage,
            onLanguage = { viewModel.setFilterLanguage(it) },
            onlyDuplicates = onlyDuplicates,
            onOnlyDuplicates = { viewModel.setOnlyDuplicates(it) }
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Cerca per nome, set o numero") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancella ricerca")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )

        SortAndViewBar(
            count = cards.size,
            copies = cards.sumOf { it.quantity },
            sortOrder = sortOrder,
            onSortChange = { viewModel.setSortOrder(it) },
            listView = listView,
            onToggleView = onToggleView
        )

        ValueBar(
            text = valueText(
                label = "Valore",
                total = cards.sumOf { (priceOf(it) ?: 0.0) * it.quantity },
                withoutPrice = cards.count { priceOf(it) == null },
                cardCount = cards.size
            ),
            progress = priceProgress,
            enabled = cards.isNotEmpty(),
            onRefresh = {
                viewModel.refreshBinderPrices(cards) { found, checked ->
                    Toast.makeText(context, priceResultMessage(found, checked), Toast.LENGTH_LONG).show()
                }
            }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (cards.isEmpty()) {
                val inScope = selectedBinderId?.let { cardCounts[it] ?: 0 } ?: cardCounts.values.sum()
                Text(
                    if (inScope == 0) "Binder vuoto. Scansiona una carta!" else "Nessuna carta corrisponde a ricerca e filtri.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            } else if (listView) {
                LazyColumn(
                    // Spazio in fondo: il pulsante non deve coprire l'ultima carta
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cards, key = { it.id }) { card ->
                        CardRow(
                            card = card,
                            binderName = if (selectedBinderId == null) binderNames[card.binderId] else null,
                            price = priceOf(card),
                            selected = card.id in selectedIds,
                            showMenu = !selecting,
                            onClick = { if (selecting) onToggleSelected(card) else onOpenCard(card) },
                            onLongClick = { onToggleSelected(card) },
                            onEdit = { onEditCard(card) },
                            onDelete = { onDeleteCard(card) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cards, key = { it.id }) { card ->
                        CardItem(
                            card = card,
                            // Guardando tutti i binder insieme si vede in quale sta ogni carta
                            binderName = if (selectedBinderId == null) binderNames[card.binderId] else null,
                            price = priceOf(card),
                            selected = card.id in selectedIds,
                            showMenu = !selecting,
                            onClick = { if (selecting) onToggleSelected(card) else onOpenCard(card) },
                            onLongClick = { onToggleSelected(card) },
                            onEdit = { onEditCard(card) },
                            onDelete = { onDeleteCard(card) }
                        )
                    }
                }
            }
        }
    }
}
