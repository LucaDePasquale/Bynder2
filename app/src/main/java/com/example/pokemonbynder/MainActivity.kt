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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.ui.PokemonViewModel
import com.example.pokemonbynder.ui.scanner.CardScannerAnalyzer
import com.example.pokemonbynder.ui.scanner.ScannerGuide
import com.example.pokemonbynder.ui.theme.PokemonBynderTheme
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
                ExtendedFloatingActionButton(
                    onClick = { showSourceDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Scansiona Carta") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (cards.isEmpty() && !isScanning) {
                Text("Binder vuoto. Scansiona una carta!", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cards, key = { it.id }) { card -> CardItem(card, onClick = { cardToEdit = card }) }
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

            // Dialog per scegliere la carta giusta e la tipologia
            candidates?.let { list ->
                CandidateSelectionDialog(
                    candidates = list,
                    onConfirm = { card, category ->
                        viewModel.confirmAndAddCard(card, category) { success ->
                            if (success) Toast.makeText(context, "Aggiunta: ${card.name} (${card.number})", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Errore nel salvataggio della carta", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { viewModel.cancelSelection() }
                )
            }

            // Dialog per cambiare la tipologia di una carta già nel binder
            cardToEdit?.let { card ->
                EditCategoryDialog(
                    card = card,
                    onConfirm = { category ->
                        viewModel.updateCardCategory(card, category) { success ->
                            if (success) Toast.makeText(context, "Tipologia aggiornata: ${category.label}", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Errore nel salvataggio", Toast.LENGTH_LONG).show()
                        }
                        cardToEdit = null
                    },
                    onDismiss = { cardToEdit = null }
                )
            }

            // Scelta della fonte (catalogo offline o TCGdex) prima di aprire la fotocamera
            if (showSourceDialog) {
                ScanSourceDialog(
                    initial = lastSource,
                    onConfirm = { source ->
                        showSourceDialog = false
                        lastSource = source
                        prefs.edit().putString("scan_source", source.name).apply()
                        viewModel.setScanSource(source)
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
    onConfirm: (CatalogCard, CardCategory) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(candidates) { mutableStateOf(candidates.first().card) }
    var selectedCategory by remember { mutableStateOf(CardCategory.NORMAL) }

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
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected, selectedCategory) }) { Text("Aggiungi al Binder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Cambia la tipologia di una carta già presente nel binder. */
@Composable
fun EditCategoryDialog(
    card: PokemonCard,
    onConfirm: (CardCategory) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember(card.id) { mutableStateOf(card.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica tipologia") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text("${card.set} · ${card.number}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                CategoryPicker(selected = selectedCategory, onSelect = { selectedCategory = it })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedCategory) }) { Text("Salva") } },
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
    onConfirm: (ScanSource) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }

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
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }) { Text("Avvia scansione") } },
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
fun CardItem(card: PokemonCard, onClick: () -> Unit) {
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
                SuggestionChip(onClick = onClick, label = { Text(card.category.label) })
            }
        }
    }
}
