package com.example.pokemonbynder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.PokemonCard

/** Copia o sposta le carte selezionate in un altro binder. */
@Composable
fun TransferDialog(
    count: Int,
    binders: List<Binder>,
    currentBinderId: Long?,
    onConfirm: (targetId: Long, move: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = binders.filter { it.id != currentBinderId }
    var move by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(targets.firstOrNull()?.id ?: -1L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "1 carta selezionata" else "$count carte selezionate") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !move, onClick = { move = false }, label = { Text("Copia") })
                    FilterChip(selected = move, onClick = { move = true }, label = { Text("Sposta") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (move) "Le carte passano nel binder scelto e spariscono da quello attuale."
                    else "Le carte restano anche qui. Nelle «Tutte le carte» una carta presente in più binder è contata una volta sola.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (targets.isEmpty()) {
                    Text("Serve almeno un altro binder: creane uno dalla pagina «I miei binder».")
                } else {
                    Text("Nel binder:")
                    Spacer(modifier = Modifier.height(4.dp))
                    BinderPicker(binders = targets, selectedId = target, onSelect = { target = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = targets.isNotEmpty(), onClick = { onConfirm(target, move) }) {
                Text(if (move) "Sposta" else "Copia")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Sceglie delle carte di un altro binder e le copia in [targetBinder]. */
@Composable
fun ImportFromBinderDialog(
    binders: List<Binder>,
    targetBinder: Binder,
    allCards: List<PokemonCard>,
    onConfirm: (List<PokemonCard>) -> Unit,
    onDismiss: () -> Unit
) {
    val sources = binders.filter { it.id != targetBinder.id }
    var sourceId by remember { mutableStateOf(sources.firstOrNull()?.id ?: -1L) }
    val already = remember(allCards, targetBinder.id) {
        allCards.filter { it.binderId == targetBinder.id }.map { CardEntryId.catalogKeyOf(it.id) }.toSet()
    }
    val available = remember(allCards, sourceId) {
        allCards.filter { it.binderId == sourceId }.sortedBy { it.name.lowercase() }
    }
    val selectable = available.filter { CardEntryId.catalogKeyOf(it.id) !in already }
    var picked by remember(sourceId) { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi da un altro binder") },
        text = {
            Column {
                Text("Da quale binder?")
                Spacer(modifier = Modifier.height(4.dp))
                BinderPicker(binders = sources, selectedId = sourceId, onSelect = { sourceId = it })
                Spacer(modifier = Modifier.height(8.dp))
                if (selectable.isEmpty()) {
                    Text(if (available.isEmpty()) "Questo binder è vuoto." else "Hai già tutte le sue carte in «${targetBinder.name}».")
                } else {
                    TextButton(onClick = {
                        picked = if (picked.size == selectable.size) emptySet() else selectable.map { it.id }.toSet()
                    }) { Text(if (picked.size == selectable.size) "Deseleziona tutte" else "Seleziona tutte") }
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(available, key = { it.id }) { card ->
                            val here = CardEntryId.catalogKeyOf(card.id) in already
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !here) {
                                        picked = if (card.id in picked) picked - card.id else picked + card.id
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = card.id in picked, onCheckedChange = null, enabled = !here)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "${card.name} · ${card.set} ${card.number}" + if (here) " (già qui)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = picked.isNotEmpty(),
                onClick = { onConfirm(available.filter { it.id in picked }) }
            ) { Text("Aggiungi (${picked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
