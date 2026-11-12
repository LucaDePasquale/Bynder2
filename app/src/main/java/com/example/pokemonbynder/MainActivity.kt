package com.example.pokemonbynder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.pokemonbynder.data.backup.ImportResult
import com.example.pokemonbynder.data.backup.ShareText
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.Binder
import com.example.pokemonbynder.data.model.CardCategory
import com.example.pokemonbynder.data.model.CardCondition
import com.example.pokemonbynder.data.model.CardEntryId
import com.example.pokemonbynder.data.model.DEFAULT_BINDER_ID
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.model.WishlistCard
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.scanner.Candidate
import com.example.pokemonbynder.scanner.ScanReading
import com.example.pokemonbynder.scanner.ScanSource
import com.example.pokemonbynder.ui.PokemonViewModel
import com.example.pokemonbynder.ui.SortOrder
import com.example.pokemonbynder.ui.scanner.CardScannerAnalyzer
import com.example.pokemonbynder.ui.scanner.PhotoReader
import com.example.pokemonbynder.ui.scanner.PhotoDraft
import com.example.pokemonbynder.scanner.CardText
import android.net.Uri
import com.example.pokemonbynder.ui.scanner.ScannerGuide
import com.example.pokemonbynder.ui.theme.PokemonBynderTheme
import com.example.pokemonbynder.ui.theme.appBarColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PokemonApplication
        val repository = app.repository
        val catalogReady = app.catalogReady

        // La barra in alto è blu scuro in entrambi i temi: le icone di stato restano chiare.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val settings by app.settings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PokemonBynderTheme(darkTheme = dark) {
              CompositionLocalProvider(LocalImageResolver provides app.imageResolver) {
                val viewModel: PokemonViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PokemonViewModel(repository, catalogReady, app.backupManager) as T
                        }
                    }
                )
                MainScreen(viewModel, app.catalogError, app.settings)
              }
            }
        }
    }
}

/** Le pagine dell'app. Le prime cinque stanno nella barra in basso; "Statistiche" si apre dalla Home. */
enum class AppScreen(val label: String) {
    HOME("Home"),
    BINDERS("Binder"),
    CATALOG("Catalogo"),
    WISHLIST("Wishlist"),
    SETTINGS("Impostazioni"),
    STATS("Statistiche")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PokemonViewModel, catalogError: StateFlow<String?>, settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.state.collectAsState()

    val importError by catalogError.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val allCards by viewModel.allCards.collectAsState()
    val binders by viewModel.binders.collectAsState()
    val selectedBinderId by viewModel.selectedBinderId.collectAsState()
    val cardCounts by viewModel.cardCounts.collectAsState()
    val wishlist by viewModel.wishlist.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val priceProgress by viewModel.priceProgress.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val catalogReady by viewModel.isCatalogReady.collectAsState()
    val scanSource by viewModel.scanSource.collectAsState()
    val scanError by viewModel.scanError.collectAsState()

    // Le impostazioni valgono subito e anche alla prossima apertura dell'app
    LaunchedEffect(settings.scanSource) { viewModel.setScanSource(settings.scanSource) }
    LaunchedEffect(settings.continuousScan) { viewModel.setContinuousScan(settings.continuousScan) }
    LaunchedEffect(settings.sortOrder) { viewModel.setSortOrder(settings.sortOrder) }

    // Navigazione
    var screenIndex by rememberSaveable { mutableStateOf(0) }
    var binderOpen by rememberSaveable { mutableStateOf(false) }
    var catalogSection by rememberSaveable { mutableStateOf(0) }
    val screen = AppScreen.entries[screenIndex.coerceIn(0, AppScreen.entries.size - 1)]
    val inBinderDetail = screen == AppScreen.BINDERS && binderOpen
    val selectedBinder = binders.firstOrNull { it.id == selectedBinderId }

    fun goTo(target: AppScreen) {
        screenIndex = target.ordinal
        binderOpen = false
    }

    // Finestre e dialoghi
    var cardToEdit by remember { mutableStateOf<PokemonCard?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var cardToDelete by remember { mutableStateOf<PokemonCard?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showWishSearch by remember { mutableStateOf(false) }
    var showCatalogAdd by remember { mutableStateOf(false) }
    var pickerSection by remember { mutableStateOf(0) }
    var pendingBulk by remember { mutableStateOf<List<CatalogCard>?>(null) }
    var wishToMove by remember { mutableStateOf<WishlistCard?>(null) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var showNewBinderDialog by remember { mutableStateOf(false) }
    var binderToRename by remember { mutableStateOf<Binder?>(null) }
    var binderToDelete by remember { mutableStateOf<Binder?>(null) }
    var binderMenuOpen by remember { mutableStateOf(false) }
    var showBinderTools by rememberSaveable { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showImportFrom by remember { mutableStateOf(false) }
    val collection by viewModel.collection.collectAsState()

    // Selezione multipla: si entra con un tocco lungo su una carta del binder
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    val selection = cards.filter { it.id in selectedIds }
    val selecting = selection.isNotEmpty() && inBinderDetail
    LaunchedEffect(selectedBinderId, screenIndex, binderOpen) { selectedIds = emptySet(); fabOpen = false }

    fun toggleSelected(card: PokemonCard) {
        selectedIds = if (card.id in selectedIds) selectedIds - card.id else selectedIds + card.id
    }

    val addCardToWishlist: (CatalogCard) -> Unit = { card ->
        viewModel.addToWishlist(card) { added -> toastWishlist(context, added) }
    }
    val addCardsToWishlist: (List<CatalogCard>) -> Unit = { list ->
        viewModel.addToWishlistAll(list) { added ->
            val message = when {
                added == null -> "Impossibile aggiungere alla wishlist"
                added == 0 -> "Già nella wishlist"
                added == 1 -> "1 carta aggiunta alla wishlist"
                else -> "$added carte aggiunte alla wishlist"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val binderNames = binders.associate { it.id to it.name }
    fun priceOf(card: PokemonCard): Double? = prices[CardEntryId.catalogKeyOf(card.id)]
    val defaultBinderId = selectedBinderId ?: binders.firstOrNull()?.id ?: DEFAULT_BINDER_ID

    // Backup su file e condivisione di liste
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.exportBackup { json ->
                if (json == null) {
                    Toast.makeText(context, "Impossibile creare il backup", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        val saved = writeBackupFile(context, uri, json)
                        Toast.makeText(
                            context,
                            if (saved) "Backup salvato" else "Impossibile scrivere il file di backup",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val json = readBackupFile(context, uri)
                if (json == null) {
                    Toast.makeText(context, "Impossibile leggere il file", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.importBackup(json) { result, error ->
                        if (result != null) importResult = result
                        else Toast.makeText(context, error ?: "Ripristino non riuscito", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    fun shareOrExplain(title: String, text: String?, emptyMessage: String) {
        if (text == null) Toast.makeText(context, emptyMessage, Toast.LENGTH_SHORT).show()
        else shareText(context, title, text)
    }
    fun refreshAllPrices() {
        viewModel.refreshBinderPrices(allCards) { found, checked ->
            Toast.makeText(context, priceResultMessage(found, checked), Toast.LENGTH_LONG).show()
        }
    }

    // Errori della ricerca (es. rete assente): si mostrano una volta sola.
    LaunchedEffect(scanError) {
        scanError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearScanError()
        }
    }

    // Carta da una foto della galleria (non serve nessun permesso)
    var photoChoice by remember { mutableStateOf<Uri?>(null) }
    var customDraft by remember { mutableStateOf<Pair<Uri, PhotoDraft>?>(null) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photoChoice = uri
    }
    fun createFromPhoto(uri: Uri) {
        Toast.makeText(context, "Leggo la foto…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val analysis = PhotoReader.analyze(context, uri)
            customDraft = uri to PhotoReader.draftOf(analysis)
        }
    }
    fun recognizePhoto(uri: Uri) {
        Toast.makeText(context, "Leggo la foto…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val analysis = PhotoReader.analyze(context, uri)
            if (analysis.readings.isEmpty()) {
                Toast.makeText(context, "Nella foto non vedo testo: compila tu i dati della carta", Toast.LENGTH_LONG).show()
                customDraft = uri to PhotoReader.draftOf(analysis)
            } else {
                viewModel.identifyPhoto(analysis.readings) { found ->
                    if (!found) {
                        Toast.makeText(context, "Carta non trovata nel catalogo: la creo dalla foto", Toast.LENGTH_LONG).show()
                        customDraft = uri to PhotoReader.draftOf(analysis)
                    }
                }
            }
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.setScanning(true)
    }

    // Tasto indietro: l'ultimo registrato ha la precedenza
    BackHandler(enabled = screen != AppScreen.HOME && !inBinderDetail) { goTo(AppScreen.HOME) }
    BackHandler(enabled = inBinderDetail) { binderOpen = false }
    BackHandler(enabled = selecting) { selectedIds = emptySet() }
    BackHandler(enabled = isScanning) { viewModel.setScanning(false) }

    val barTextColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
        containerColor = appBarColor(),
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White
    )

    Scaffold(
        topBar = {
            if (selecting) {
                CenterAlignedTopAppBar(
                    title = { Text(if (selection.size == 1) "1 selezionata" else "${selection.size} selezionate", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Annulla selezione")
                        }
                    },
                    actions = {
                        if (binders.size > 1) {
                            TextButton(onClick = { showTransfer = true }) { Text("Copia/Sposta") }
                        }
                        TextButton(onClick = { showDeleteSelected = true }) { Text("Elimina") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                )
            } else {
                val title = when {
                    inBinderDetail -> selectedBinder?.name ?: "Tutte le carte"
                    screen == AppScreen.HOME -> "Pokémon Virtual Binder"
                    screen == AppScreen.BINDERS -> "I miei binder"
                    else -> screen.label
                }
                CenterAlignedTopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (inBinderDetail) {
                            TextButton(onClick = { binderOpen = false }) { Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                        } else if (screen == AppScreen.STATS) {
                            TextButton(onClick = { goTo(AppScreen.HOME) }) { Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                        }
                    },
                    actions = {
                        // Rinomina ed elimina riguardano il binder aperto (non "Tutte le carte")
                        if (inBinderDetail) {
                            IconButton(onClick = { binderMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Azioni sul binder")
                            }
                            DropdownMenu(expanded = binderMenuOpen, onDismissRequest = { binderMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (showBinderTools) "Nascondi ricerca e filtri" else "Ricerca e filtri") },
                                    onClick = { binderMenuOpen = false; showBinderTools = !showBinderTools }
                                )
                                if (selectedBinder != null) {
                                    DropdownMenuItem(
                                        text = { Text("Rinomina") },
                                        onClick = { binderMenuOpen = false; binderToRename = selectedBinder }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Elimina binder") },
                                        enabled = binders.size > 1,
                                        onClick = { binderMenuOpen = false; binderToDelete = selectedBinder }
                                    )
                                }
                            }
                        }
                    },
                    colors = barTextColors
                )
            }
        },
        bottomBar = {
            if (!isScanning) {
                NavigationBar {
                    val items = listOf(
                        AppScreen.HOME to Icons.Default.Home,
                        AppScreen.BINDERS to Icons.Default.List,
                        AppScreen.CATALOG to Icons.Default.Search,
                        AppScreen.WISHLIST to Icons.Default.Favorite,
                        AppScreen.SETTINGS to Icons.Default.Settings
                    )
                    items.forEach { (item, icon) ->
                        NavigationBarItem(
                            selected = screen == item || (item == AppScreen.HOME && screen == AppScreen.STATS),
                            onClick = { goTo(item) },
                            icon = { Icon(icon, contentDescription = item.label) },
                            label = { Text(item.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (catalogReady && !isScanning && candidates == null && !selecting) {
                if (screen == AppScreen.WISHLIST) {
                    ExtendedFloatingActionButton(
                        onClick = { showWishSearch = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Aggiungi alla wishlist") }
                    )
                } else if (inBinderDetail) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (fabOpen) {
                            ExtendedFloatingActionButton(
                                onClick = { fabOpen = false; showCatalogAdd = true },
                                icon = { Icon(Icons.Default.Search, null) },
                                text = { Text("Cerca nel catalogo") }
                            )
                            ExtendedFloatingActionButton(
                                onClick = { fabOpen = false; showSourceDialog = true },
                                icon = { Icon(Icons.Default.Add, null) },
                                text = { Text("Scansiona carta") }
                            )
                            ExtendedFloatingActionButton(
                                onClick = { fabOpen = false; photoLauncher.launch("image/*") },
                                icon = { Icon(Icons.Default.Add, null) },
                                text = { Text("Carta da foto") }
                            )
                            if (selectedBinder != null && binders.size > 1) {
                                ExtendedFloatingActionButton(
                                    onClick = { fabOpen = false; showImportFrom = true },
                                    icon = { Icon(Icons.Default.List, null) },
                                    text = { Text("Da un altro binder") }
                                )
                            }
                        }
                        FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                            Icon(if (fabOpen) Icons.Default.Close else Icons.Default.Add, contentDescription = "Aggiungi carte")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (screen) {
                AppScreen.HOME -> HomePage(
                    allCards = collection,
                    bindersCount = binders.size,
                    wishlistCount = wishlist.size,
                    prices = prices,
                    onScan = { showSourceDialog = true },
                    onSearch = { catalogSection = 0; goTo(AppScreen.CATALOG) },
                    onOpenBinders = { goTo(AppScreen.BINDERS) },
                    onOpenWishlist = { goTo(AppScreen.WISHLIST) },
                    onOpenSets = { catalogSection = 1; goTo(AppScreen.CATALOG) },
                    onOpenStats = { goTo(AppScreen.STATS) },
                    onOpenCard = { previewId = it.id }
                )

                AppScreen.BINDERS -> if (binderOpen) {
                    BinderDetail(
                        viewModel = viewModel,
                        listView = settings.listView,
                        onToggleView = { settingsStore.update { it.copy(listView = !it.listView) } },
                        selectedIds = selectedIds,
                        selecting = selecting,
                        binderNames = binderNames,
                        showTools = showBinderTools,
                        onToggleSelected = { toggleSelected(it) },
                        onOpenCard = { previewId = it.id },
                        onEditCard = { cardToEdit = it },
                        onDeleteCard = { cardToDelete = it }
                    )
                } else {
                    BindersOverview(
                        binders = binders,
                        allCards = allCards,
                        collection = collection,
                        prices = prices,
                        onOpen = { id ->
                            viewModel.selectBinder(id)
                            binderOpen = true
                        },
                        onNew = { showNewBinderDialog = true }
                    )
                }

                AppScreen.CATALOG -> CatalogPage(
                    viewModel = viewModel,
                    catalogReady = catalogReady,
                    ownedCards = allCards,
                    onAddToBinder = { viewModel.pickManualCard(it) },
                    onWishlist = addCardToWishlist,
                    onAddToWishlist = addCardsToWishlist,
                    section = catalogSection,
                    onSectionChange = { catalogSection = it }
                )

                AppScreen.WISHLIST -> WishlistTab(
                    wishlist = wishlist,
                    prices = prices,
                    progress = priceProgress,
                    onFound = { wishToMove = it },
                    onRemove = { wish ->
                        viewModel.removeFromWishlist(wish) { success ->
                            if (!success) Toast.makeText(context, "Impossibile rimuovere la carta", Toast.LENGTH_LONG).show()
                        }
                    },
                    onRefresh = {
                        viewModel.refreshWishlistPrices(wishlist) { found, checked ->
                            Toast.makeText(context, priceResultMessage(found, checked), Toast.LENGTH_LONG).show()
                        }
                    }
                )

                AppScreen.SETTINGS -> SettingsPage(
                    settings = settings,
                    onChange = { change -> settingsStore.update(change) },
                    priceProgress = priceProgress,
                    onRefreshPrices = { refreshAllPrices() },
                    onExport = {
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                        exportLauncher.launch("pokemon-binder-$day.json")
                    },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onShareDuplicates = { shareOrExplain("Carte doppie", ShareText.duplicates(collection), "Non hai carte doppie") },
                    onShareWishlist = { shareOrExplain("Wishlist", ShareText.wishlist(wishlist), "La wishlist è vuota") },
                    onShareCollection = { shareOrExplain("La mia collezione", ShareText.collection(allCards, binders), "La collezione è vuota") }
                )

                AppScreen.STATS -> StatsTab(
                    cards = collection,
                    prices = prices,
                    progress = priceProgress,
                    onRefreshPrices = { refreshAllPrices() }
                )
            }

            if (isScanning) {
                ScannerOverlay(
                    sourceLabel = scanSource.label,
                    onReading = { reading -> viewModel.onReading(reading) },
                    onManual = {
                        viewModel.setScanning(false)
                        catalogSection = 0
                        goTo(AppScreen.CATALOG)
                    },
                    onPhoto = { photoLauncher.launch("image/*") },
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

            // Carta ingrandita (un tocco su una carta del binder o della Home)
            allCards.firstOrNull { it.id == previewId }?.let { card ->
                CardPreviewDialog(
                    card = card,
                    binderName = binderNames[card.binderId],
                    price = priceOf(card),
                    onEdit = {
                        previewId = null
                        cardToEdit = card
                    },
                    onDismiss = { previewId = null }
                )
            }

            photoChoice?.let { uri ->
                AlertDialog(
                    onDismissRequest = { photoChoice = null },
                    title = { Text("Cosa vuoi fare con la foto?") },
                    text = { Text("Se la carta non è nel catalogo (es. carta vecchia) puoi crearne la versione digitale usando la tua foto.") },
                    confirmButton = {
                        Button(onClick = { photoChoice = null; createFromPhoto(uri) }) { Text("Crea carta digitale") }
                    },
                    dismissButton = {
                        TextButton(onClick = { photoChoice = null; recognizePhoto(uri) }) { Text("Riconosci nel catalogo") }
                    }
                )
            }

            customDraft?.let { pending ->
                CustomCardDialog(
                    photoUri = pending.first,
                    draft = pending.second,
                    binders = binders,
                    defaultBinderId = defaultBinderId,
                    defaultCondition = settings.defaultCondition,
                    onConfirm = { input ->
                        customDraft = null
                        scope.launch {
                            val image = PhotoReader.saveCopy(context, pending.first)
                            if (image == null) {
                                Toast.makeText(context, "Impossibile salvare la foto", Toast.LENGTH_LONG).show()
                            } else {
                                val setCode = "custom-" + CardText.normalizeName(input.setName).ifEmpty { "senza-set" }
                                val number = input.number.ifBlank { System.currentTimeMillis().toString(36) }
                                val card = CatalogCard(
                                    lang = input.lang,
                                    setCode = setCode,
                                    setName = input.setName.ifBlank { "Carte create da foto" },
                                    number = number,
                                    numberKey = CardText.normalizeNumber(number),
                                    setTotal = input.total,
                                    name = input.name,
                                    nameNorm = CardText.normalizeName(input.name),
                                    rarity = input.rarity.ifBlank { null },
                                    artist = input.artist.ifBlank { null },
                                    cardType = "Pokémon",
                                    imageUrl = image
                                )
                                viewModel.addCustomCard(card, input.category, input.binderId, input.condition) { success, quantity ->
                                    val msg = if (success) {
                                        "Creata e aggiunta: ${card.name}" + if (quantity > 1) " (ora ×$quantity)" else ""
                                    } else "Errore nel salvataggio della carta"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onDismiss = { customDraft = null }
                )
            }

            // Dialog per scegliere la carta giusta, la tipologia e il binder
            candidates?.let { list ->
                CandidateSelectionDialog(
                    candidates = list,
                    binders = binders,
                    defaultBinderId = defaultBinderId,
                    defaultCondition = settings.defaultCondition,
                    onConfirm = { card, category, binderId, condition ->
                        viewModel.confirmAndAddCard(card, category, binderId, condition) { success, quantity ->
                            if (success) {
                                val where = binderNames[binderId]?.let { " in «$it»" }.orEmpty()
                                val copies = if (quantity > 1) " (ora ×$quantity)" else ""
                                Toast.makeText(context, "Aggiunta: ${card.name} (${card.number})$where$copies", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Errore nel salvataggio della carta", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { viewModel.cancelSelection() }
                )
            }

            // Dialog per cambiare tipologia, copie, condizione e/o binder di una carta già salvata
            cardToEdit?.let { card ->
                EditCardDialog(
                    card = card,
                    binders = binders,
                    onConfirm = { category, binderId, quantity, condition, language ->
                        viewModel.updateCard(card, category, binderId, quantity, condition, language) { success, languageMissing ->
                            if (!success) {
                                Toast.makeText(context, "Errore nel salvataggio", Toast.LENGTH_LONG).show()
                            } else if (languageMissing) {
                                Toast.makeText(
                                    context,
                                    "Carta aggiornata, ma la versione in ${CardLanguage.label(language)} non è nel catalogo: lingua non cambiata",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "Carta aggiornata", Toast.LENGTH_SHORT).show()
                            }
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

            // Eliminazione di una carta dal menu dei tre puntini
            cardToDelete?.let { card ->
                DeleteCardsDialog(
                    count = 1,
                    onConfirm = {
                        cardToDelete = null
                        viewModel.deleteCard(card) { success ->
                            if (success) Toast.makeText(context, "Carta eliminata", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Impossibile eliminare la carta", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { cardToDelete = null }
                )
            }

            // Wishlist: catalogo a tutto schermo (ricerca per nome, numero, autore e set)
            if (showWishSearch) {
                CatalogPickerDialog(title = "Aggiungi alla wishlist", onDismiss = { showWishSearch = false }) {
                    CatalogPage(
                        viewModel = viewModel,
                        catalogReady = catalogReady,
                        ownedCards = allCards,
                        onAddToBinder = { viewModel.pickManualCard(it) },
                        onWishlist = addCardToWishlist,
                        onAddToWishlist = addCardsToWishlist,
                        section = pickerSection,
                        onSectionChange = { pickerSection = it }
                    )
                }
            }

            // Binder: aggiunta senza scansione, una carta o tutte le mancanti di un set
            if (showCatalogAdd) {
                CatalogPickerDialog(title = "Aggiungi al binder", onDismiss = { showCatalogAdd = false }) {
                    CatalogPage(
                        viewModel = viewModel,
                        catalogReady = catalogReady,
                        ownedCards = allCards,
                        onAddToBinder = { viewModel.pickManualCard(it) },
                        onWishlist = addCardToWishlist,
                        onAddToWishlist = { list ->
                            if (list.size == 1) {
                                viewModel.pickManualCard(list[0])
                            } else {
                                pendingBulk = list
                            }
                        },
                        section = pickerSection,
                        onSectionChange = { pickerSection = it },
                        forBinder = true
                    )
                }
            }

            // Conferma prima di aggiungere molte carte insieme
            pendingBulk?.let { list ->
                val target = binderNames[defaultBinderId] ?: "il binder"
                AlertDialog(
                    onDismissRequest = { pendingBulk = null },
                    title = { Text("Aggiungere ${list.size} carte?") },
                    text = {
                        Text(
                            "Le ${list.size} carte vanno in «$target», una copia ciascuna, tipologia Normale e condizione " +
                                "${settings.defaultCondition.label}. Potrai modificarle una per una."
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            pendingBulk = null
                            viewModel.addAllToBinder(list, defaultBinderId, settings.defaultCondition) { added ->
                                val message = if (added == null) "Impossibile aggiungere le carte"
                                else "$added carte aggiunte a «$target»"
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Aggiungi") }
                    },
                    dismissButton = { TextButton(onClick = { pendingBulk = null }) { Text("Annulla") } }
                )
            }

            // Carta della wishlist trovata: va nel binder
            wishToMove?.let { wish ->
                WishlistFoundDialog(
                    wish = wish,
                    binders = binders,
                    defaultBinderId = defaultBinderId,
                    defaultCondition = settings.defaultCondition,
                    onConfirm = { category, binderId, condition ->
                        wishToMove = null
                        viewModel.moveWishlistToBinder(wish, category, binderId, condition) { success ->
                            if (success) Toast.makeText(context, "Aggiunta al binder: ${wish.name}", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Errore nel salvataggio della carta", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { wishToMove = null }
                )
            }

            // Riepilogo del ripristino da backup
            importResult?.let { result ->
                ImportResultDialog(result = result, onDismiss = { importResult = null })
            }

            // Copia o sposta le carte selezionate in un altro binder
            if (showTransfer && selecting) {
                TransferDialog(
                    count = selection.size,
                    binders = binders,
                    currentBinderId = selectedBinderId,
                    onConfirm = { target, move ->
                        showTransfer = false
                        val chosen = selection
                        viewModel.transferCards(chosen, target, move) { done, skipped ->
                            val where = binderNames[target]?.let { " in «$it»" }.orEmpty()
                            val verb = if (move) "spostate" else "copiate"
                            val message = when {
                                done < 0 -> "Operazione non riuscita"
                                done == 0 -> "Nessuna carta $verb: erano già lì"
                                skipped > 0 -> "$done carte $verb$where ($skipped già presenti)"
                                else -> "$done carte $verb$where"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                        selectedIds = emptySet()
                    },
                    onDismiss = { showTransfer = false }
                )
            }

            // Aggiunta di carte prese da un altro binder
            val importTarget = selectedBinder
            if (showImportFrom && importTarget != null) {
                ImportFromBinderDialog(
                    binders = binders,
                    targetBinder = importTarget,
                    allCards = allCards,
                    onConfirm = { chosen ->
                        showImportFrom = false
                        viewModel.transferCards(chosen, importTarget.id, false) { done, _ ->
                            val message = if (done < 0) "Operazione non riuscita" else "$done carte aggiunte a «${importTarget.name}»"
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onDismiss = { showImportFrom = false }
                )
            }

            // Eliminazione delle carte selezionate
            if (showDeleteSelected && selecting) {
                DeleteCardsDialog(
                    count = selection.size,
                    onConfirm = {
                        showDeleteSelected = false
                        viewModel.deleteCards(selection) { success ->
                            if (success) Toast.makeText(context, "Carte eliminate", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(context, "Impossibile eliminare le carte", Toast.LENGTH_LONG).show()
                        }
                        selectedIds = emptySet()
                    },
                    onDismiss = { showDeleteSelected = false }
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
                            if (success) binderOpen = true
                            else Toast.makeText(context, "Impossibile creare il binder", Toast.LENGTH_LONG).show()
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
                            if (success) {
                                binderOpen = false
                                Toast.makeText(context, "Binder eliminato", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Impossibile eliminare il binder", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { binderToDelete = null }
                )
            }

            // Scelta della fonte (catalogo offline o TCGdex) prima di aprire la fotocamera
            if (showSourceDialog) {
                ScanSourceDialog(
                    initial = settings.scanSource,
                    initialContinuous = settings.continuousScan,
                    onConfirm = { source, continuous ->
                        showSourceDialog = false
                        settingsStore.update { it.copy(scanSource = source, continuousScan = continuous) }
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
    defaultCondition: CardCondition = CardCondition.NEAR_MINT,
    onConfirm: (CatalogCard, CardCategory, Long, CardCondition) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(candidates) { mutableStateOf(candidates.first().card) }
    var selectedCategory by remember { mutableStateOf(CardCategory.NORMAL) }
    var selectedCondition by remember { mutableStateOf(defaultCondition) }
    var selectedBinderId by remember(candidates) { mutableStateOf(defaultBinderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (candidates.size > 1) "Quale carta è?" else "Carta rilevata") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CardImage(
                    imageUrl = selected.imageUrl,
                    catalogKey = selected.catalogKey,
                    name = selected.name,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    contentScale = ContentScale.Fit
                )
                val extra = listOfNotNull(
                    selected.artist?.takeIf { it.isNotBlank() }?.let { "Autore: $it" },
                    selected.rarity?.takeIf { it.isNotBlank() }?.let { "Rarità: $it" }
                ).joinToString(" · ")
                if (extra.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(extra, style = MaterialTheme.typography.bodySmall)
                }
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
                Spacer(modifier = Modifier.height(8.dp))
                ConditionPicker(selected = selectedCondition, onSelect = { selectedCondition = it })

                if (binders.size > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scegli il binder:")
                    Spacer(modifier = Modifier.height(8.dp))
                    BinderPicker(binders = binders, selectedId = selectedBinderId, onSelect = { selectedBinderId = it })
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected, selectedCategory, selectedBinderId, selectedCondition) }) { Text("Aggiungi al Binder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Cambia tipologia, copie, condizione, binder e/o lingua di una carta già salvata. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardDialog(
    card: PokemonCard,
    binders: List<Binder>,
    onConfirm: (CardCategory, Long, Int, CardCondition, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember(card.id) { mutableStateOf(card.category) }
    var selectedBinderId by remember(card.id) { mutableStateOf(card.binderId) }
    var quantity by remember(card.id) { mutableStateOf(card.quantity) }
    var condition by remember(card.id) { mutableStateOf(card.condition) }
    var language by remember(card.id) { mutableStateOf(CardLanguage.of(card.id)) }
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
                Text(
                    "${card.set} · ${card.number} · ${CardLanguage.label(CardLanguage.of(card.id))}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Tipologia:")
                Spacer(modifier = Modifier.height(4.dp))
                CategoryPicker(selected = selectedCategory, onSelect = { selectedCategory = it })

                Spacer(modifier = Modifier.height(12.dp))
                QuantityStepper(quantity = quantity, onChange = { quantity = it })
                Spacer(modifier = Modifier.height(4.dp))
                ConditionPicker(selected = condition, onSelect = { condition = it })

                Spacer(modifier = Modifier.height(12.dp))
                Text("Lingua della carta:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("it", "en") + CardLanguage.of(card.id)).distinct().forEach { code ->
                        FilterChip(
                            selected = language == code,
                            onClick = { language = code },
                            label = { Text(CardLanguage.label(code)) }
                        )
                    }
                }
                if (language != CardLanguage.of(card.id)) {
                    Text(
                        "La carta diventa la versione ${CardLanguage.label(language)}, con la sua immagine e il suo nome.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

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
        confirmButton = { Button(onClick = { onConfirm(selectedCategory, selectedBinderId, quantity, condition, language) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Riga con il numero di carte mostrate, il menu dell'ordinamento e il cambio griglia/lista. */
@Composable
fun SortAndViewBar(
    count: Int,
    copies: Int,
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
            (if (count == 1) "1 carta" else "$count carte") + if (copies != count) " · $copies copie" else "",
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
fun ScannerOverlay(sourceLabel: String, onReading: (ScanReading) -> Unit, onManual: () -> Unit, onPhoto: () -> Unit, onClose: () -> Unit) {
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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

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
                        camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
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

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                Button(
                    onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text(if (torchOn) "Spegni" else "Torcia") }
            }
            val darkButton = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f), contentColor = Color.White)
            Button(onClick = onPhoto, colors = darkButton, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Da foto") }
            Button(onClick = onManual, colors = darkButton, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Cerca a mano") }
        }

        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** Messaggio al termine dell'aggiornamento dei prezzi. */
fun priceResultMessage(found: Int?, checked: Int): String = when {
    found == null -> "Impossibile contattare TCGdex. Controlla la connessione."
    found == 0 -> "Nessun prezzo trovato per $checked ${if (checked == 1) "carta" else "carte"}."
    else -> "Prezzi aggiornati: $found su $checked."
}

fun toastWishlist(context: Context, added: Boolean?) {
    val message = when (added) {
        true -> "Aggiunta alla wishlist"
        false -> "Già nella wishlist"
        null -> "Impossibile aggiungere alla wishlist"
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}


/** Ricerca per nome (con numero) o per autore nel catalogo offline; il risultato scelto passa al dialog successivo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualSearchDialog(
    title: String = "Cerca una carta",
    onSearch: (String, Boolean, (List<CatalogCard>?) -> Unit) -> Unit,
    onPick: (CatalogCard) -> Unit,
    onWish: ((CatalogCard) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogCard>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var byArtist by remember { mutableStateOf(false) }

    // Si cerca dopo una breve pausa nella digitazione; le risposte vecchie vengono ignorate.
    LaunchedEffect(query, byArtist) {
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            failed = false
            searched = false
            return@LaunchedEffect
        }
        delay(300)
        onSearch(text, byArtist) { found ->
            if (text == query.trim()) {
                failed = found == null
                results = found.orEmpty()
                searched = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(if (byArtist) "Autore (es. Arita)" else "Nome e numero (es. Pikachu 25/102)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byArtist, onClick = { byArtist = false }, label = { Text("Nome e numero") })
                    FilterChip(selected = byArtist, onClick = { byArtist = true }, label = { Text("Autore") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    when {
                        query.trim().length < 2 -> Text(searchHint(byArtist), style = MaterialTheme.typography.bodySmall)
                        failed -> Text("Errore nella ricerca nel catalogo.", style = MaterialTheme.typography.bodySmall)
                        searched && results.isEmpty() -> Text("Nessuna carta trovata.", style = MaterialTheme.typography.bodySmall)
                        else -> results.forEach { card ->
                            val total = if (card.setTotal > 0) "/${card.setTotal}" else ""
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(card) }.padding(vertical = 4.dp)
                            ) {
                                CardImage(
                                    imageUrl = card.imageUrl,
                                    catalogKey = card.catalogKey,
                                    name = card.name,
                                    modifier = Modifier.width(40.dp).height(56.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(card.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${card.setName} · ${card.number}$total · ${card.lang.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (byArtist && !card.artist.isNullOrBlank()) {
                                        Text(card.artist, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (onWish != null) {
                                    TextButton(onClick = { onWish(card) }) { Text("Wishlist") }
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
