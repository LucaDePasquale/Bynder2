package com.example.pokemonbynder

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** L'ordine scelto a mano per le carte di ogni binder, salvato sul telefono. */
class ManualOrderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("manual_order", Context.MODE_PRIVATE)
    private val _orders = MutableStateFlow(load())
    val orders: StateFlow<Map<Long, List<String>>> = _orders.asStateFlow()

    private fun load(): Map<Long, List<String>> =
        prefs.all.mapNotNull { (key, value) ->
            val binderId = key.removePrefix("order_").toLongOrNull()
            val ids = (value as? String)?.split('\n')?.filter { it.isNotEmpty() }
            if (binderId != null && ids != null) binderId to ids else null
        }.toMap()

    @Synchronized
    fun set(binderId: Long, ids: List<String>) {
        _orders.value = _orders.value + (binderId to ids)
        prefs.edit().putString("order_$binderId", ids.joinToString("\n")).apply()
    }
}

/** Mette le carte nell'ordine salvato; quelle nuove (non nell'elenco) restano in fondo, nell'ordine di arrivo. */
fun sortManual(cards: List<PokemonCard>, order: List<String>?): List<PokemonCard> {
    if (order.isNullOrEmpty()) return cards
    val index = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { position, id -> index[id] = position }
    return cards.sortedBy { index[it.id] ?: Int.MAX_VALUE }
}

/** Valore per ordinare per rarità: più alto = più rara. Riconosce i nomi italiani e inglesi. */
fun rarityRank(rarity: String?): Int {
    val r = (rarity ?: "").lowercase()
    return when {
        r.isBlank() || r.contains("nessuna") || r == "none" -> 0
        r.contains("promo") -> 1
        r.contains("hyper") || r.contains("iper") || r.contains("secret") || r.contains("segreta") ||
            r.contains("gold") || r.contains("oro") || r.contains("rainbow") || r.contains("arcobaleno") -> 11
        r.contains("special illustration") || r.contains("illustrazione speciale") -> 10
        r.contains("illustration") || r.contains("illustrazione") -> 9
        r.contains("ultra") || r.contains("full art") || r.contains("shiny") || r.contains("radiant") ||
            r.contains("splendente") || r.contains("brillante") || r.contains("ace spec") -> 8
        r.contains("double") || r.contains("doppia") -> 7
        r.contains("holo") -> 6
        r.contains("uncommon") || r.contains("non comune") -> 3
        r.contains("common") || r.contains("comune") -> 2
        r.contains("rare") || r.contains("rara") || r.contains("raro") -> 5
        else -> 1
    }
}

/** Schermata per mettere le carte di un binder nell'ordine che si vuole. Ogni spostamento si salva subito. */
@Composable
fun ReorderDialog(
    binderName: String,
    cards: List<PokemonCard>,
    onOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var items by remember { mutableStateOf(cards) }
    var positionFor by remember { mutableStateOf<PokemonCard?>(null) }

    fun move(card: PokemonCard, to: Int) {
        val list = items.toMutableList()
        val from = list.indexOfFirst { it.id == card.id }
        if (from < 0) return
        list.removeAt(from)
        list.add(to.coerceIn(0, list.size), card)
        items = list
        onOrder(list.map { it.id })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Riordina «$binderName»",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("Fatto") }
                }
                Text(
                    "Usa ▲ e ▼ per spostare una carta, oppure ⋮ per portarla in cima, in fondo o in una posizione precisa.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(items, key = { _, card -> card.id }) { index, card ->
                        var menuOpen by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(36.dp)
                            )
                            CardImage(
                                imageUrl = card.imageUrl,
                                catalogKey = CardEntryId.catalogKeyOf(card.id),
                                name = card.name,
                                modifier = Modifier.width(40.dp).height(56.dp)
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text("${card.set} · ${card.number}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            TextButton(enabled = index > 0, onClick = { move(card, index - 1) }) { Text("▲") }
                            TextButton(enabled = index < items.size - 1, onClick = { move(card, index + 1) }) { Text("▼") }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Altro")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("In cima") }, onClick = { menuOpen = false; move(card, 0) })
                                    DropdownMenuItem(text = { Text("In fondo") }, onClick = { menuOpen = false; move(card, items.size) })
                                    DropdownMenuItem(text = { Text("In posizione…") }, onClick = { menuOpen = false; positionFor = card })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    positionFor?.let { card ->
        var text by remember(card.id) { mutableStateOf("") }
        val target = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { positionFor = null },
            title = { Text("Sposta «${card.name}»") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Posizione (1-${items.size})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = target != null && target in 1..items.size,
                    onClick = { move(card, (target ?: 1) - 1); positionFor = null }
                ) { Text("Sposta") }
            },
            dismissButton = { TextButton(onClick = { positionFor = null }) { Text("Annulla") } }
        )
    }
}
