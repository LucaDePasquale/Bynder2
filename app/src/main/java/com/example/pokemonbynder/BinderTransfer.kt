package com.example.pokemonbynder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * Aggiunge a [targetBinder] delle carte di un altro binder: prima si sceglie il binder,
 * poi si vedono le sue carte in una lista con la foto e si spuntano quelle volute.
 */
@Composable
fun ImportFromBinderDialog(
    binders: List<Binder>,
    targetBinder: Binder,
    allCards: List<PokemonCard>,
    onConfirm: (List<PokemonCard>) -> Unit,
    onDismiss: () -> Unit
) {
    val sources = binders.filter { it.id != targetBinder.id }
    var sourceId by remember { mutableStateOf<Long?>(null) }
    val source = sources.firstOrNull { it.id == sourceId }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
                if (source == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Aggiungi a «${targetBinder.name}»: scegli il binder",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss) { Text("Annulla") }
                    }
                    if (sources.isEmpty()) {
                        Text("Serve almeno un altro binder.", modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(sources, key = { it.id }) { binder ->
                            val count = allCards.count { it.binderId == binder.id }
                            ListItem(
                                headlineContent = { Text(binder.name) },
                                supportingContent = { Text(if (count == 1) "1 carta" else "$count carte") },
                                modifier = Modifier.clickable { sourceId = binder.id }
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    SourceCards(
                        source = source,
                        targetBinder = targetBinder,
                        allCards = allCards,
                        onBack = { sourceId = null },
                        onConfirm = onConfirm
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SourceCards(
    source: Binder,
    targetBinder: Binder,
    allCards: List<PokemonCard>,
    onBack: () -> Unit,
    onConfirm: (List<PokemonCard>) -> Unit
) {
    val already = remember(allCards, targetBinder.id) {
        allCards.filter { it.binderId == targetBinder.id }.map { CardEntryId.catalogKeyOf(it.id) }.toSet()
    }
    val available = remember(allCards, source.id) {
        allCards.filter { it.binderId == source.id }.sortedBy { it.name.lowercase() }
    }
    val selectable = available.filter { CardEntryId.catalogKeyOf(it.id) !in already }
    var picked by remember(source.id) { mutableStateOf(setOf<String>()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("‹ Binder") }
        Text(
            "«${source.name}»",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
    if (selectable.isEmpty()) {
        Text(
            if (available.isEmpty()) "Questo binder è vuoto." else "Hai già tutte le sue carte in «${targetBinder.name}».",
            modifier = Modifier.padding(16.dp)
        )
    } else {
        TextButton(
            onClick = { picked = if (picked.size == selectable.size) emptySet() else selectable.map { it.id }.toSet() },
            modifier = Modifier.padding(start = 8.dp)
        ) { Text(if (picked.size == selectable.size) "Deseleziona tutte" else "Seleziona tutte") }
    }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(available, key = { it.id }) { card ->
            val here = CardEntryId.catalogKeyOf(card.id) in already
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !here) {
                        picked = if (card.id in picked) picked - card.id else picked + card.id
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = card.id in picked, onCheckedChange = null, enabled = !here)
                Spacer(modifier = Modifier.width(8.dp))
                CardImage(
                    imageUrl = card.imageUrl,
                    catalogKey = CardEntryId.catalogKeyOf(card.id),
                    name = card.name,
                    modifier = Modifier.width(48.dp).height(67.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(
                        "${card.set} · ${card.number}" + if (here) " · già qui" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    Button(
        enabled = picked.isNotEmpty(),
        onClick = { onConfirm(available.filter { it.id in picked }) },
        modifier = Modifier.fillMaxWidth().padding(12.dp)
    ) { Text("Aggiungi (${picked.size})") }
}
