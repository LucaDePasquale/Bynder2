package com.example.pokemonbynder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard

/** Pagina iniziale: riepilogo della collezione, azioni rapide e ultime carte aggiunte. */
@Composable
fun HomePage(
    allCards: List<PokemonCard>,
    bindersCount: Int,
    wishlistCount: Int,
    prices: Map<String, Double>,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onOpenBinders: () -> Unit,
    onOpenWishlist: () -> Unit,
    onOpenSets: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenCard: (PokemonCard) -> Unit
) {
    val distinct = remember(allCards) { allCards.map { CardEntryId.catalogKeyOf(it.id) }.distinct().size }
    val copies = remember(allCards) { allCards.sumOf { it.quantity } }
    val value = remember(allCards, prices) {
        allCards.sumOf { (prices[CardEntryId.catalogKeyOf(it.id)] ?: 0.0) * it.quantity }
    }
    // Il database le restituisce dalla più vecchia alla più nuova
    val recent = remember(allCards) { allCards.takeLast(10).asReversed() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Riepilogo
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("La tua collezione", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("$copies", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (copies == 1) "carta" else "carte",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    buildString {
                        append("$distinct diverse · $bindersCount ")
                        append(if (bindersCount == 1) "binder" else "binder")
                        if (value > 0.0) append(" · ${formatPrice(value)}")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Scansiona", maxLines = 1) }
                    OutlinedButton(
                        onClick = onSearch,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.weight(1f)
                    ) { Text("Cerca nel catalogo", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        // Azioni rapide
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile("📚", "I miei binder", "$bindersCount in tutto", onOpenBinders, Modifier.weight(1f))
                HomeTile("🎯", "Wishlist", if (wishlistCount == 1) "1 carta cercata" else "$wishlistCount carte cercate", onOpenWishlist, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile("🧩", "Completa i set", "Cosa ti manca", onOpenSets, Modifier.weight(1f))
                HomeTile("📊", "Statistiche", "Per tipologia e set", onOpenStats, Modifier.weight(1f))
            }
        }

        // Ultime carte aggiunte
        Text("Ultime carte aggiunte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (recent.isEmpty()) {
            Text(
                "Non hai ancora carte. Premi «Scansiona» per aggiungere la prima!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                recent.forEach { card ->
                    Card(
                        modifier = Modifier.width(110.dp).clickable { onOpenCard(card) },
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CardImage(
                                imageUrl = card.imageUrl,
                                catalogKey = CardEntryId.catalogKeyOf(card.id),
                                name = card.name,
                                modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp, start = 6.dp, end = 6.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                card.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTile(emoji: String, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
