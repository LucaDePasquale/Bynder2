package com.example.pokemonbynder

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import java.util.Locale

/** Prezzo in euro, ad esempio "€ 1,20" (il separatore dipende dalla lingua del telefono). */
fun formatPrice(value: Double): String = String.format(Locale.getDefault(), "€ %.2f", value)

/** Riga di dettagli sotto il nome: copie, condizione e prezzo (es. "×3 · NM · € 1,20 cad."). */
fun cardDetailsLine(card: PokemonCard, price: Double?): String {
    val parts = ArrayList<String>()
    if (card.quantity > 1) parts.add("×${card.quantity}")
    parts.add(card.condition.short)
    if (price != null) parts.add(formatPrice(price) + if (card.quantity > 1) " cad." else "")
    return parts.joinToString(" · ")
}

/** Scelta della condizione di una carta con un menu a tendina. */
@Composable
fun ConditionPicker(selected: CardCondition, onSelect: (CardCondition) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Condizione: ${selected.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CardCondition.entries.forEach { condition ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${condition.label} (${condition.short})",
                            fontWeight = if (condition == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(condition)
                    }
                )
            }
        }
    }
}

/** Contatore delle copie: − e + (da 1 a 999). */
@Composable
fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Copie:")
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(quantity - 1) }, enabled = quantity > 1) { Text("−") }
        Text(
            "$quantity",
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp)
        )
        OutlinedButton(onClick = { onChange(quantity + 1) }, enabled = quantity < 999) { Text("+") }
    }
}

/** Riga con il valore stimato e il pulsante che scarica i prezzi aggiornati (serve la rete). */
@Composable
fun ValueBar(text: String, progress: Pair<Int, Int>?, enabled: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRefresh, enabled = enabled && progress == null) {
            Text(if (progress != null) "Prezzi ${progress.first}/${progress.second}" else "Aggiorna prezzi")
        }
    }
}

/** Testo del valore: totale delle copie con prezzo noto e quante carte ne sono prive. */
fun valueText(label: String, total: Double, withoutPrice: Int, cardCount: Int): String = when {
    cardCount == 0 -> "$label: —"
    withoutPrice == cardCount -> "$label: prezzi non ancora caricati"
    withoutPrice == 0 -> "$label: ${formatPrice(total)}"
    else -> "$label: ${formatPrice(total)} · $withoutPrice senza prezzo"
}

/** Scheda della wishlist: le carte che si cercano, con prezzo e pulsanti "Trovata" e "Rimuovi". */
@Composable
fun WishlistTab(
    wishlist: List<WishlistCard>,
    prices: Map<String, Double>,
    progress: Pair<Int, Int>?,
    onFound: (WishlistCard) -> Unit,
    onRemove: (WishlistCard) -> Unit,
    onRefresh: () -> Unit
) {
    val total = wishlist.sumOf { prices[it.id] ?: 0.0 }
    val withoutPrice = wishlist.count { prices[it.id] == null }

    Column(modifier = Modifier.fillMaxSize()) {
        ValueBar(
            text = valueText("Costo stimato", total, withoutPrice, wishlist.size),
            progress = progress,
            enabled = wishlist.isNotEmpty(),
            onRefresh = onRefresh
        )
        if (wishlist.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "La wishlist è vuota.\nUsa «Aggiungi alla wishlist» per cercare le carte che ti mancano.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Spazio in fondo: il pulsante non deve coprire l'ultima carta
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(wishlist, key = { it.id }) { wish ->
                    WishRow(wish = wish, price = prices[wish.id], onFound = { onFound(wish) }, onRemove = { onRemove(wish) })
                }
            }
        }
    }
}

@Composable
private fun WishRow(wish: WishlistCard, price: Double?, onFound: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = wish.imageUrl.ifBlank { null },
                contentDescription = wish.name,
                modifier = Modifier.width(56.dp).height(78.dp),
                contentScale = ContentScale.Fit
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(wish.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${wish.set} · ${wish.number}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (price != null) Text(formatPrice(price), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onFound) { Text("Trovata!") }
                    TextButton(onClick = onRemove) { Text("Rimuovi") }
                }
            }
        }
    }
}

/** Carta trovata: si scelgono tipologia, condizione e binder, poi passa dalla wishlist al binder. */
@Composable
fun WishlistFoundDialog(
    wish: WishlistCard,
    binders: List<Binder>,
    defaultBinderId: Long,
    onConfirm: (CardCategory, Long, CardCondition) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(wish.id) { mutableStateOf(CardCategory.NORMAL) }
    var condition by remember(wish.id) { mutableStateOf(CardCondition.NEAR_MINT) }
    var binderId by remember(wish.id) { mutableStateOf(defaultBinderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carta trovata!") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = wish.imageUrl.ifBlank { null },
                    contentDescription = wish.name,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentScale = ContentScale.Fit
                )
                Text(wish.name, fontWeight = FontWeight.Bold)
                Text("${wish.set} · ${wish.number}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Tipologia:")
                Spacer(modifier = Modifier.height(4.dp))
                CategoryPicker(selected = category, onSelect = { category = it })
                Spacer(modifier = Modifier.height(8.dp))
                ConditionPicker(selected = condition, onSelect = { condition = it })
                if (binders.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Binder:")
                    Spacer(modifier = Modifier.height(4.dp))
                    BinderPicker(binders = binders, selectedId = binderId, onSelect = { binderId = it })
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(category, binderId, condition) }) { Text("Aggiungi al Binder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Conferma prima di eliminare le carte selezionate. */
@Composable
fun DeleteCardsDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eliminare le carte selezionate?") },
        text = {
            Text(
                if (count == 1) "Una carta verrà tolta dal binder. L'operazione non si può annullare."
                else "$count carte verranno tolte dal binder. L'operazione non si può annullare."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
