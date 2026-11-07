package com.example.pokemonbynder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.ui.PokemonViewModel
import com.example.pokemonbynder.ui.SortOrder
import com.example.pokemonbynder.ui.scanner.CardScannerAnalyzer
import com.example.pokemonbynder.ui.scanner.ScannerGuide
import com.example.pokemonbynder.ui.theme.PokemonBynderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PokemonApplication
        val repository = app.repository
        val catalogReady = app.catalogReady

        enableEdgeToEdge()
        setContent {
            PokemonBynderTheme {
                val viewModel: PokemonViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PokemonViewModel(repository, catalogReady) as T
                        }
                    }
                )
                MainScreen(viewModel, app.catalogError)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PokemonViewModel, catalogError: StateFlow<String?>) {
    val importError by catalogError.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val catalogReady by viewModel.isCatalogReady.collectAsState()
    val context = LocalContext.current
    var cardToEdit by remember { mutableStateOf<PokemonCard?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    val scanSource by viewModel.scanSource.collectAsState()
    val scanError by viewModel.scanError.collectAsState()
    val prefs = remember { context.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE) }
    var lastSource by remember { mutableStateOf(ScanSource.fromName(prefs.getString("scan_source", null))) }

    val binders by viewModel.binders.collectAsState()
    val selectedBinderId by viewModel.selectedBinderId.collectAsState()
    val filterCategory by viewModel.filterCategory.collectAsState()
    val cardCounts by viewModel.cardCounts.collectAsState()
    var showNewBinderDialog by remember { mutableStateOf(false) }
    var binderToRename by remember { mutableStateOf<Binder?>(null) }
    var binderToDelete by remember { mutableStateOf<Binder?>(null) }
    val selectedBinder = binders.firstOrNull { it.id == selectedBinderId }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    var showManualSearch by remember { mutableStateOf(false) }
    var lastContinuous by remember { mutableStateOf(prefs.getBoolean("continuous_scan", false)) }
    var listView by remember { mutableStateOf(prefs.getBoolean("list_view", false)) }
    val binderNames = binders.associate { it.id to it.name }

    // Errori della ricerca (es. rete assente): si mostrano una volta sola.
    LaunchedEffect(scanError) {
        scanError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearScanError()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.setScanning(true)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("POKÉMON VIRTUAL BINDER", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if (catalogReady && !isScanning && candidates == null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { showManualSearch = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        icon = { Icon(Icons.Default.Search, null) },
                        text = { Text("Cerca a mano") }
                    )
                    ExtendedFloatingActionButton(
                        onClick = { showSourceDialog = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Scansiona Carta") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                BinderBar(
                    binders = binders,
                    counts = cardCounts,
                    selectedId = selectedBinderId,
                    onSelect = { viewModel.selectBinder(it) },
                    onAdd = { showNewBinderDialog = true }
                )

                // Con un binder scelto si può rinominare o eliminare (l'ultimo binder non si elimina)
                if (selectedBinder != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selectedBinder.name,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { binderToRename = selectedBinder }) { Text("Rinomina") }
                        TextButton(
                            onClick = { binderToDelete = selectedBinder },
                            enabled = binders.size > 1
                        ) { Text("Elimina") }
                    }
                }

                CategoryFilterBar(selected = filterCategory, onSelect = { viewModel.setFilterCategory(it) })

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Cerca per nome, set o numero") },
                    singleLine = true,
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
                    sortOrder = sortOrder,
                    onSortChange = { viewModel.setSortOrder(it) },
                    listView = listView,
                    onToggleView = {
                        listView = !listView
                        prefs.edit().putBoolean("list_view", listView).apply()
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
                            // Spazio in fondo: i pulsanti non devono coprire l'ultima carta
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(cards, key = { it.id }) { card ->
                                CardRow(
                                    card = card,
                                    binderName = if (selectedBinderId == null) binderNames[card.binderId] else null,
                                    onClick = { cardToEdit = card }
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(cards, key = { it.id }) { card ->
                                CardItem(
                                    card = card,
                                    // Guardando tutti i binder insieme si vede in quale sta ogni carta
                                    binderName = if (selectedBinderId == null) binderNames[card.binderId] else null,
                                    onClick = { cardToEdit = card }
                                )
                            }
                        }
                    }
                }
            }

            if (isScanning) {
                ScannerOverlay(
                    sourceLabel = scanSource.label,
                    onReading = { reading -> viewModel.onReading(reading) },
                    onClose = { viewModel.setScanning(false) }
                )
            }

            if (!catalogReady) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val error = importError
                            if (error == null) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Preparazione del catalogo carte...", fontWeight = FontWeight.Bold)
                                Text("Solo al primo avvio, pochi secondi", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Impossibile caricare il catalogo carte", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Dialog per scegliere la carta giusta, la tipologia e il binder
            candidates?.let { list ->
                CandidateSelectionDialog(
                    candidates = list,
                    binders = binders,
                    defaultBinderId = selectedBinderId ?: binders.firstOrNull()?.id ?: DEFAULT_BINDER_ID,
                    onConfirm = { card, category, binderId ->
                        viewModel.confirmAndAddCard(card, category, binderId) { success ->
                            if (success) {
                                val where = binderNames[binderId]?.let { " in «$it»" }.orEmpty()
                                Toast.makeText(context, "Aggiunta: ${card.name} (${card.number})$where", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Errore nel salvataggio della carta", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { viewModel.cancelSelection() }
                )
            }

            // Dialog per cambiare tipologia e/o binder di una carta già salvata
            cardToEdit?.let { card ->
                EditCardDialog(
                    card = card,
                    binders = binders,
                    onConfirm = { category, binderId ->
                        viewModel.updateCard(card, category, binderId) { success ->
                            if (success) Toast.makeText(context, "Carta aggiornata", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Errore nel salvataggio", Toast.LENGTH_LONG).show()
                        }
                        cardToEdit = null
                    },
                    onDelete = {
                        viewModel.deleteCard(card) { success ->
                            if (success) Toast.makeText(context, "Carta eliminata", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Impossibile eliminare la carta", Toast.LENGTH_LONG).show()
                        }
                        cardToEdit = null
                    },
                    onDismiss = { cardToEdit = null }
                )
            }

            // Ricerca a mano nel catalogo, per le carte che la fotocamera non riconosce
            if (showManualSearch) {
                ManualSearchDialog(
                    onSearch = { text, onResult -> viewModel.searchCatalog(text, onResult) },
                    onPick = { card ->
                        showManualSearch = false
                        viewModel.pickManualCard(card)
                    },
                    onDismiss = { showManualSearch = false }
                )
            }

            // Nuovo binder, rinomina ed eliminazione
            if (showNewBinderDialog) {
                BinderNameDialog(
                    title = "Nuovo binder",
                    initialName = "",
                    confirmLabel = "Crea",
                    takenNames = binders.map { it.name },
                    onConfirm = { name ->
                        showNewBinderDialog = false
                        viewModel.addBinder(name) { success ->
                            if (!success) Toast.makeText(context, "Impossibile creare il binder", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { showNewBinderDialog = false }
                )
            }

            binderToRename?.let { binder ->
                BinderNameDialog(
                    title = "Rinomina binder",
                    initialName = binder.name,
                    confirmLabel = "Salva",
                    takenNames = binders.filter { it.id != binder.id }.map { it.name },
                    onConfirm = { name ->
                        binderToRename = null
                        viewModel.renameBinder(binder.id, name) { success ->
                            if (!success) Toast.makeText(context, "Impossibile rinominare il binder", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { binderToRename = null }
                )
            }

            binderToDelete?.let { binder ->
                DeleteBinderDialog(
                    binder = binder,
                    cardCount = cardCounts[binder.id] ?: 0,
                    onConfirm = {
                        binderToDelete = null
                        viewModel.deleteBinder(binder.id) { success ->
                            if (success) Toast.makeText(context, "Binder eliminato", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Impossibile eliminare il binder", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { binderToDelete = null }
                )
            }

            // Scelta della fonte (catalogo offline o TCGdex) prima di aprire la fotocamera
            if (showSourceDialog) {
                ScanSourceDialog(
                    initial = lastSource,
                    initialContinuous = lastContinuous,
                    onConfirm = { source, continuous ->
                        showSourceDialog = false
                        lastSource = source
                        lastContinuous = continuous
                        prefs.edit()
                            .putString("scan_source", source.name)
                            .putBoolean("continuous_scan", continuous)
                            .apply()
                        viewModel.setScanSource(source)
                        viewModel.setContinuousScan(continuous)
                        if (hasCameraPermission) viewModel.setScanning(true)
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onDismiss = { showSourceDialog = false }
                )
            }
        }
    }
}

@Composable
fun CandidateSelectionDialog(
    candidates: List<Candidate>,
    binders: List<Binder>,
    defaultBinderId: Long,
    onConfirm: (CatalogCard, CardCategory, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(candidates) { mutableStateOf(candidates.first().card) }
    var selectedCategory by remember { mutableStateOf(CardCategory.NORMAL) }
    var selectedBinderId by remember(candidates) { mutableStateOf(defaultBinderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (candidates.size > 1) "Quale carta è?" else "Carta rilevata") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = selected.imageUrl.ifBlank { null },
                    contentDescription = selected.name,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(8.dp))

                candidates.forEach { candidate ->
                    val card = candidate.card
                    val total = if (card.setTotal > 0) "/${card.setTotal}" else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selected = card }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selected == card, onClick = { selected = card })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(card.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${card.setName} · ${card.number}$total · ${card.lang.uppercase()}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Scegli la tipologia della carta fisica:")
                Spacer(modifier = Modifier.height(8.dp))
                CategoryPicker(selected = selectedCategory, onSelect = { selectedCategory = it })

                if (binders.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scegli il binder:")
                    Spacer(modifier = Modifier.height(8.dp))
                    BinderPicker(binders = binders, selectedId = selectedBinderId, onSelect = { selectedBinderId = it })
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected, selectedCategory, selectedBinderId) }) { Text("Aggiungi al Binder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Cambia tipologia e/o binder di una carta già salvata. */
@Composable
fun EditCardDialog(
    card: PokemonCard,
    binders: List<Binder>,
    onConfirm: (CardCategory, Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember(card.id) { mutableStateOf(card.category) }
    var selectedBinderId by remember(card.id) { mutableStateOf(card.binderId) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la carta?") },
            text = { Text("«${card.name}» (${card.set} · ${card.number}) verrà tolta dal binder. L'operazione non si può annullare.") },
            confirmButton = { Button(onClick = onDelete) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica carta") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text("${card.set} · ${card.number}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Tipologia:")
                Spacer(modifier = Modifier.height(4.dp))
                CategoryPicker(selected = selectedCategory, onSelect = { selectedCategory = it })

                if (binders.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Binder:")
                    Spacer(modifier = Modifier.height(4.dp))
                    BinderPicker(binders = binders, selectedId = selectedBinderId, onSelect = { selectedBinderId = it })
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Elimina carta") }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedCategory, selectedBinderId) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Riga con il numero di carte mostrate, il menu dell'ordinamento e il cambio griglia/lista. */
@Composable
fun SortAndViewBar(
    count: Int,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    listView: Boolean,
    onToggleView: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (count == 1) "1 carta" else "$count carte",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("Ordina: ${sortOrder.label}") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label, fontWeight = if (order == sortOrder) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            menuOpen = false
                            onSortChange(order)
                        }
                    )
                }
            }
        }
        TextButton(onClick = onToggleView) { Text(if (listView) "Griglia" else "Lista") }
    }
}

/** Ricerca per nome nel catalogo offline; il risultato scelto passa al dialog di tipologia e binder. */
@Composable
fun ManualSearchDialog(
    onSearch: (String, (List<CatalogCard>?) -> Unit) -> Unit,
    onPick: (CatalogCard) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogCard>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    // Si cerca dopo una breve pausa nella digitazione; le risposte vecchie vengono ignorate.
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            failed = false
            searched = false
            return@LaunchedEffect
        }
        delay(300)
        onSearch(text) { found ->
            if (text == query.trim()) {
                failed = found == null
                results = found.orEmpty()
                searched = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cerca una carta") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Nome della carta") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    when {
                        query.trim().length < 2 -> Text("Scrivi almeno 2 lettere.", style = MaterialTheme.typography.bodySmall)
                        failed -> Text("Errore nella ricerca nel catalogo.", style = MaterialTheme.typography.bodySmall)
                        searched && results.isEmpty() -> Text("Nessuna carta trovata.", style = MaterialTheme.typography.bodySmall)
                        else -> results.forEach { card ->
                            val total = if (card.setTotal > 0) "/${card.setTotal}" else ""
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(card) }.padding(vertical = 4.dp)
                            ) {
                                AsyncImage(
                                    model = card.imageUrl.ifBlank { null },
                                    contentDescription = card.name,
                                    modifier = Modifier.width(40.dp).height(56.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(card.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${card.setName} · ${card.number}$total · ${card.lang.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

/** Elenco dei binder tra cui scegliere (in aggiunta e in modifica di una carta). */
@Composable
fun BinderPicker(binders: List<Binder>, selectedId: Long, onSelect: (Long) -> Unit) {
    binders.forEach { binder ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(binder.id) }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            RadioButton(selected = selectedId == binder.id, onClick = { onSelect(binder.id) })
            Text(binder.name, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Barra scorrevole con "Tutti" e un chip per ogni binder (con il numero di carte), più "Nuovo binder". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinderBar(
    binders: List<Binder>,
    counts: Map<Long, Int>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Binder:", style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Tutti (${counts.values.sum()})") }
        )
        binders.forEach { binder ->
            FilterChip(
                selected = selectedId == binder.id,
                onClick = { onSelect(binder.id) },
                label = { Text("${binder.name} (${counts[binder.id] ?: 0})") }
            )
        }
        AssistChip(onClick = onAdd, label = { Text("+ Nuovo binder") })
    }
}

/** Barra scorrevole per filtrare le carte per tipologia (dentro il binder scelto o in tutti). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilterBar(selected: CardCategory?, onSelect: (CardCategory?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Tipologia:", style = MaterialTheme.typography.labelMedium)
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Tutte") })
        CardCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(category.label) }
            )
        }
    }
}

/** Chiede il nome di un binder (nuovo o da rinominare); non accetta nomi vuoti o già usati. */
@Composable
fun BinderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    takenNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val duplicate = takenNames.any { it.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Nome del binder") },
                    singleLine = true,
                    isError = duplicate
                )
                if (duplicate) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Esiste già un binder con questo nome",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && !duplicate) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Conferma prima di eliminare un binder, dicendo quante carte verranno eliminate con lui. */
@Composable
fun DeleteBinderDialog(
    binder: Binder,
    cardCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eliminare il binder?") },
        text = {
            Text(
                when (cardCount) {
                    0 -> "«${binder.name}» è vuoto e verrà eliminato."
                    1 -> "«${binder.name}» e la sua carta verranno eliminati. L'operazione non si può annullare."
                    else -> "«${binder.name}» e le sue $cardCount carte verranno eliminati. L'operazione non si può annullare."
                }
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Elenco di tutte le tipologie, usato sia in aggiunta sia in modifica. */
@Composable
fun CategoryPicker(selected: CardCategory, onSelect: (CardCategory) -> Unit) {
    CardCategory.entries.forEach { category ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(category) }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            RadioButton(selected = selected == category, onClick = { onSelect(category) })
            Text(category.label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Chiede da dove cercare la carta; la scelta viene ricordata per la volta successiva. */
@Composable
fun ScanSourceDialog(
    initial: ScanSource,
    initialContinuous: Boolean,
    onConfirm: (ScanSource, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    var continuous by remember { mutableStateOf(initialContinuous) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Da dove cercare la carta?") },
        text = {
            Column {
                ScanSource.entries.forEach { source ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = source }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = selected == source, onClick = { selected = source })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(source.label, fontWeight = FontWeight.Bold)
                            Text(source.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { continuous = !continuous }.padding(vertical = 8.dp)
                ) {
                    Checkbox(checked = continuous, onCheckedChange = { continuous = it })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Scansione continua", fontWeight = FontWeight.Bold)
                        Text(
                            "Dopo ogni carta aggiunta la fotocamera si riapre per la successiva",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected, continuous) }) { Text("Avvia scansione") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Tiene il riferimento a PreviewView per leggerne la dimensione dal thread dell'analizzatore. */
private class PreviewHolder {
    @Volatile
    var view: PreviewView? = null
}

@Composable
fun ScannerOverlay(sourceLabel: String, onReading: (ScanReading) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewHolder = remember { PreviewHolder() }
    val currentOnReading by rememberUpdatedState(onReading)
    val guideSizePx = remember(density) {
        with(density) { ScannerGuide.WIDTH_DP.dp.roundToPx() to ScannerGuide.HEIGHT_DP.dp.roundToPx() }
    }
    val analyzer = remember {
        CardScannerAnalyzer(
            previewSize = { previewHolder.view?.let { it.width to it.height } ?: (0 to 0) },
            guideSizePx = guideSizePx,
            onReading = { reading -> currentOnReading(reading) }
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Quando il riquadro si chiude, spegne davvero la fotocamera e libera le risorse.
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewHolder.view = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // Risoluzione più alta del default (640x480): serve per leggere il numero stampato in piccolo.
                    // Stesso rapporto 4:3 dell'anteprima, così il riquadro guida cade dove previsto.
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1440, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also {
                            it.setAnalyzer(cameraExecutor, analyzer)
                        }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                    } catch (e: Exception) {
                        Log.e("Scanner", "Error", e)
                        Toast.makeText(context, "Errore fotocamera", Toast.LENGTH_SHORT).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(width = ScannerGuide.WIDTH_DP.dp, height = ScannerGuide.HEIGHT_DP.dp)
                    .align(Alignment.Center)
                    .border(3.dp, Color.Yellow, RoundedCornerShape(16.dp))
            )
            Text(
                "POSIZIONA LA CARTA NEL RIQUADRO",
                color = Color.Yellow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).padding(top = (ScannerGuide.HEIGHT_DP + 50).dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }

        Text(
            "Fonte: $sourceLabel",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )

        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun CardItem(card: PokemonCard, binderName: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            AsyncImage(
                model = card.imageUrl.ifBlank { null },
                contentDescription = card.name,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentScale = ContentScale.Fit
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text("Art: ${card.artist ?: "N/D"}", style = MaterialTheme.typography.bodySmall)
                if (binderName != null) {
                    Text(
                        "Binder: $binderName",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SuggestionChip(onClick = onClick, label = { Text(card.category.label) })
            }
        }
    }
}

/** Versione compatta di [CardItem] per la vista a lista. */
@Composable
fun CardRow(card: PokemonCard, binderName: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = card.imageUrl.ifBlank { null },
                contentDescription = card.name,
                modifier = Modifier.width(56.dp).height(78.dp),
                contentScale = ContentScale.Fit
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(card.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${card.set} · ${card.number}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (binderName != null) "${card.category.label} · $binderName" else card.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
