package com.example.pokemonbynder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.stats.CardLanguage

/** Proporzioni di una carta Pokémon (63 x 88 mm). */
const val CARD_ASPECT_RATIO = 0.716f

/** I tre puntini con il menu delle azioni su una carta. */
@Composable
fun CardMenuButton(onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Azioni sulla carta")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Modifica") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(
                text = { Text("Elimina", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

/** Immagine grande di una carta, con un riquadro al suo posto se la carta non ha immagine. */
@Composable
fun LargeCardImage(imageUrl: String, name: String, modifier: Modifier = Modifier, catalogKey: String = "") {
    if (imageUrl.isBlank() && catalogKey.isBlank()) {
        Box(
            modifier = modifier
                .aspectRatio(CARD_ASPECT_RATIO)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Immagine non disponibile", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    } else {
        CardImage(
            imageUrl = imageUrl,
            catalogKey = catalogKey,
            name = name,
            modifier = modifier.aspectRatio(CARD_ASPECT_RATIO),
            contentScale = ContentScale.Fit
        )
    }
}

/** Carta nella vista a griglia: un tocco la ingrandisce, un tocco lungo la seleziona, i tre puntini la modificano. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardItem(
    card: PokemonCard,
    binderName: String?,
    price: Double?,
    selected: Boolean,
    showMenu: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            Box {
                CardImage(
                    imageUrl = card.imageUrl,
                    catalogKey = CardEntryId.catalogKeyOf(card.id),
                    name = card.name,
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
                    contentScale = ContentScale.Fit
                )
                if (showMenu) {
                    CardMenuButton(
                        onEdit = onEdit,
                        onDelete = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    (if (selected) "✓ " else "") + card.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${card.set} · ${card.number}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(cardDetailsLine(card, price), style = MaterialTheme.typography.bodySmall)
                if (binderName != null) {
                    Text(
                        "Binder: $binderName",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                SuggestionChip(onClick = onClick, label = { Text(card.category.label) })
            }
        }
    }
}

/** Carta nella vista a lista compatta. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardRow(
    card: PokemonCard,
    binderName: String?,
    price: Double?,
    selected: Boolean,
    showMenu: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CardImage(
                imageUrl = card.imageUrl,
                catalogKey = CardEntryId.catalogKeyOf(card.id),
                name = card.name,
                modifier = Modifier.width(56.dp).height(78.dp),
                contentScale = ContentScale.Fit
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    (if (selected) "✓ " else "") + card.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${card.set} · ${card.number}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(cardDetailsLine(card, price), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (binderName != null) "${card.category.label} · $binderName" else card.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showMenu) CardMenuButton(onEdit = onEdit, onDelete = onDelete)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Carta ingrandita con tutti i suoi dati; da qui si può passare alla modifica. */
@Composable
fun CardPreviewDialog(
    card: PokemonCard,
    binderName: String?,
    price: Double?,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 760.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LargeCardImage(
                    imageUrl = card.imageUrl,
                    name = card.name,
                    modifier = Modifier.fillMaxWidth(0.85f),
                    catalogKey = CardEntryId.catalogKeyOf(card.id)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    "${card.set} · ${card.number}",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Lingua", CardLanguage.label(CardLanguage.of(card.id)))
                    DetailRow("Tipologia", card.category.label)
                    DetailRow("Condizione", card.condition.label)
                    DetailRow("Copie", "${card.quantity}")
                    card.rarity?.takeIf { it.isNotBlank() }?.let { DetailRow("Rarità", it) }
                    card.artist?.takeIf { it.isNotBlank() }?.let { DetailRow("Artista", it) }
                    if (binderName != null) DetailRow("Binder", binderName)
                    if (price != null) DetailRow("Prezzo", formatPrice(price) + if (card.quantity > 1) " a copia" else "")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Chiudi") }
                    Button(onClick = onEdit) { Text("Modifica") }
                }
            }
        }
    }
}
