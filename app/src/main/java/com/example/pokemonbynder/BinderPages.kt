package com.example.pokemonbynder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard

private const val ROWS = 3
private const val COLUMNS = 3
private const val PER_PAGE = ROWS * COLUMNS

/**
 * Le carte in "modalità binder": pagine da 9 carte (3 x 3) come in un raccoglitore fisico,
 * da sfogliare scorrendo a destra e a sinistra. Un tocco ingrandisce la carta, il tocco lungo la seleziona.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BinderPages(
    cards: List<PokemonCard>,
    selectedIds: Set<String>,
    selecting: Boolean,
    onOpenCard: (PokemonCard) -> Unit,
    onToggleSelected: (PokemonCard) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount = maxOf(1, (cards.size + PER_PAGE - 1) / PER_PAGE)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0 until ROWS) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (column in 0 until COLUMNS) {
                            val card = cards.getOrNull(page * PER_PAGE + row * COLUMNS + column)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (card == null) EmptySleeve() else PageSlot(
                                    card = card,
                                    selected = card.id in selectedIds,
                                    onClick = { if (selecting) onToggleSelected(card) else onOpenCard(card) },
                                    onLongClick = { onToggleSelected(card) }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Sotto resta spazio libero: il pulsante «+» non deve coprire le carte dell'ultima riga
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pagina ${pagerState.currentPage + 1} di $pageCount",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptySleeve() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageSlot(card: PokemonCard, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        CardImage(
            imageUrl = card.imageUrl,
            catalogKey = CardEntryId.catalogKeyOf(card.id),
            name = card.name,
            modifier = Modifier.fillMaxSize().padding(2.dp),
            contentScale = ContentScale.Fit
        )
        if (card.quantity > 1) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
            ) {
                Text(
                    "×${card.quantity}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        if (selected) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
            ) {
                Text(
                    "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}
