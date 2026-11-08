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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.ui.SortOrder

/** Impostazioni: ogni voce si applica subito e viene ricordata alla prossima apertura. */
@Composable
fun SettingsPage(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    priceProgress: Pair<Int, Int>?,
    onRefreshPrices: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onShareDuplicates: () -> Unit,
    onShareWishlist: () -> Unit,
    onShareCollection: () -> Unit
) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection("Aspetto") {
            Text("Tema", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            ChoiceChips(
                options = ThemeMode.entries.toList(),
                selected = settings.themeMode,
                label = { it.label },
                onSelect = { mode -> onChange { it.copy(themeMode = mode) } }
            )
        }

        SettingsSection("Scansione") {
            Text("Dove cercare le carte scansionate", style = MaterialTheme.typography.bodyMedium)
            ScanSource.entries.forEach { source ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableRow { onChange { it.copy(scanSource = source) } }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(selected = settings.scanSource == source, onClick = { onChange { it.copy(scanSource = source) } })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(source.label, fontWeight = FontWeight.Bold)
                        Text(source.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SettingSwitch(
                title = "Scansione continua",
                subtitle = "Dopo ogni carta aggiunta la fotocamera si riapre per la successiva",
                checked = settings.continuousScan,
                onChecked = { value -> onChange { it.copy(continuousScan = value) } }
            )
        }

        SettingsSection("Binder") {
            Text("Vista delle carte", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            ChoiceChips(
                options = listOf(false, true),
                selected = settings.listView,
                label = { if (it) "Lista" else "Griglia" },
                onSelect = { value -> onChange { it.copy(listView = value) } }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("Ordinamento", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            ChoiceChips(
                options = SortOrder.entries.toList(),
                selected = settings.sortOrder,
                label = { it.label },
                onSelect = { order -> onChange { it.copy(sortOrder = order) } }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("Condizione proposta per le nuove carte", style = MaterialTheme.typography.bodyMedium)
            ConditionPicker(selected = settings.defaultCondition, onSelect = { c -> onChange { it.copy(defaultCondition = c) } })
        }

        SettingsSection("Dati e backup") {
            SettingsButton(
                if (priceProgress != null) "Prezzi ${priceProgress.first}/${priceProgress.second}" else "Aggiorna i prezzi di tutte le carte",
                enabled = priceProgress == null,
                onClick = onRefreshPrices
            )
            SettingsButton("Salva backup su file", onClick = onExport)
            SettingsButton("Ripristina da backup", onClick = onImport)
            SettingsButton("Condividi carte doppie", onClick = onShareDuplicates)
            SettingsButton("Condividi wishlist", onClick = onShareWishlist)
            SettingsButton("Condividi collezione", onClick = onShareCollection)
        }

        SettingsSection("Informazioni") {
            Text("Pokémon Virtual Binder", fontWeight = FontWeight.Bold)
            if (version.isNotBlank()) Text("Versione $version", style = MaterialTheme.typography.bodySmall)
            Text(
                "I prezzi arrivano da TCGdex (Cardmarket) e sono indicativi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** Rende tutta la riga cliccabile. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
