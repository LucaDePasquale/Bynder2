package com.example.pokemonbynder

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.ui.scanner.PhotoDraft

/** I dati di una carta creata dall'utente a partire da una foto. */
class CustomCardInput(
    val name: String,
    val setName: String,
    val number: String,
    val total: Int,
    val artist: String,
    val rarity: String,
    val lang: String,
    val category: CardCategory,
    val binderId: Long,
    val condition: CardCondition
)

/**
 * Scheda per creare la carta digitale da una foto: i campi sono precompilati con quello che si è letto
 * nella foto e si possono correggere. La foto diventa l'immagine della carta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomCardDialog(
    photoUri: Uri,
    draft: PhotoDraft,
    binders: List<Binder>,
    defaultBinderId: Long,
    defaultCondition: CardCondition,
    onConfirm: (CustomCardInput) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var setName by remember(draft) { mutableStateOf("") }
    var number by remember(draft) { mutableStateOf(draft.number) }
    var total by remember(draft) { mutableStateOf(draft.total) }
    var artist by remember(draft) { mutableStateOf(draft.artist) }
    var rarity by remember(draft) { mutableStateOf("") }
    var lang by remember(draft) { mutableStateOf(if (draft.lang == "en") "en" else "it") }
    var category by remember(draft) { mutableStateOf(CardCategory.NORMAL) }
    var condition by remember(draft) { mutableStateOf(defaultCondition) }
    var binderId by remember(draft) { mutableStateOf(defaultBinderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crea carta dalla foto") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Foto della carta",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    "Controlla i dati letti dalla foto e correggili: la foto sarà l'immagine della carta.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nome del Pokémon / carta *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = setName, onValueChange = { setName = it },
                    label = { Text("Set (es. Set Base)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = number, onValueChange = { number = it },
                        label = { Text("Numero") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = total, onValueChange = { total = it.filter(Char::isDigit).take(4) },
                        label = { Text("Carte del set") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = artist, onValueChange = { artist = it },
                    label = { Text("Autore (illustratore)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = rarity, onValueChange = { rarity = it },
                    label = { Text("Rarità (facoltativa)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Lingua della carta:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("it", "en").forEach { code ->
                        FilterChip(selected = lang == code, onClick = { lang = code }, label = { Text(CardLanguage.label(code)) })
                    }
                }

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
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        CustomCardInput(
                            name = name.trim(),
                            setName = setName.trim(),
                            number = number.trim(),
                            total = total.toIntOrNull() ?: 0,
                            artist = artist.trim(),
                            rarity = rarity.trim(),
                            lang = lang,
                            category = category,
                            binderId = binderId,
                            condition = condition
                        )
                    )
                }
            ) { Text("Crea e aggiungi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
