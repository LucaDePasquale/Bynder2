package com.example.pokemonbynder

import android.Manifest
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.ui.PokemonViewModel
import com.example.pokemonbynder.ui.scanner.CardScannerAnalyzer
import com.example.pokemonbynder.ui.theme.PokemonBynderTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as PokemonApplication).repository

        enableEdgeToEdge()
        setContent {
            PokemonBynderTheme {
                val viewModel: PokemonViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PokemonViewModel(repository) as T
                        }
                    }
                )
                MainScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PokemonViewModel) {
    val cards by viewModel.cards.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val pendingInfo by viewModel.pendingCardInfo.collectAsState(initial = null)
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("POKÉMON VIRTUAL BINDER", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if (!isScanning && !isSearching && pendingInfo == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (hasCameraPermission) viewModel.setScanning(true)
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Scansiona Carta") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (cards.isEmpty() && !isScanning && !isSearching) {
                Text("Binder vuoto. Scansiona una carta!", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cards) { card -> CardItem(card) }
                }
            }

            if (isScanning) {
                ScannerOverlay(
                    onDetected = { name, number -> viewModel.onCardDetected(name, number) },
                    onClose = { viewModel.setScanning(false) }
                )
            }

            if (isSearching) {
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
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Ricerca nel catalogo GitHub...", fontWeight = FontWeight.Bold)
                            Text("Analisi dei set in corso", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Dialog per confermare i dati e scegliere la categoria
            pendingInfo?.let { (name, number) ->
                CategorySelectionDialog(
                    cardName = name,
                    cardNumber = number,
                    onConfirm = { category ->
                        viewModel.confirmAndAddCard(category) { success ->
                            if (success) Toast.makeText(context, "Aggiunta: $name ($number)", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Errore nel caricamento online", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { viewModel.cancelSelection() }
                )
            }
        }
    }
}

@Composable
fun CategorySelectionDialog(cardName: String, cardNumber: String, onConfirm: (CardCategory) -> Unit, onDismiss: () -> Unit) {
    var selectedCategory by remember { mutableStateOf(CardCategory.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carta rilevata: $cardName") },
        text = {
            Column {
                if (cardNumber.isNotBlank()) {
                    Text("Numero: $cardNumber", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("Scegli la tipologia della carta fisica:")
                Spacer(modifier = Modifier.height(8.dp))
                CardCategory.entries.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedCategory = category }.padding(8.dp)
                    ) {
                        RadioButton(selected = selectedCategory == category, onClick = { selectedCategory = category })
                        Text(category.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedCategory) }) { Text("Aggiungi al Binder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun ScannerOverlay(onDetected: (String, String) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also {
                            it.setAnalyzer(cameraExecutor, CardScannerAnalyzer { name, number -> onDetected(name, number) })
                        }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
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
                    .size(width = 280.dp, height = 390.dp)
                    .align(Alignment.Center)
                    .border(3.dp, Color.Yellow, RoundedCornerShape(16.dp))
            )
            Text(
                "POSIZIONA LA CARTA NEL RIQUADRO",
                color = Color.Yellow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).padding(top = 440.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }

        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun CardItem(card: PokemonCard) {
    Card(elevation = CardDefaults.cardElevation(4.dp), shape = RoundedCornerShape(12.dp)) {
        Column {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.name,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentScale = ContentScale.Fit
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text("Art: ${card.artist ?: "N/D"}", style = MaterialTheme.typography.bodySmall)
                SuggestionChip(onClick = {}, label = { Text(card.category.name) })
            }
        }
    }
}
