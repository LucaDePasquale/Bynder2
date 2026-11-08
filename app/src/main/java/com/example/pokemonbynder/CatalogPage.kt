package com.example.pokemonbynder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.local.catalog.CatalogCard
import com.example.pokemonbynder.data.model.PokemonCard
import com.example.pokemonbynder.data.stats.CardLanguage
import com.example.pokemonbynder.data.stats.OwnedIndex
import com.example.pokemonbynder.ui.PokemonViewModel
import com.example.pokemonbynder.scanner.CardText
import kotlinx.coroutines.delay

/** Pagina "Catalogo": ricerca per nome tra tutte le carte (italiano e inglese) e sfoglia dei set. */
@Composable
fun CatalogPage(
    viewModel: PokemonViewModel,
    catalogReady: Boolean,
    ownedCards: List<PokemonCard>,
    onAddToBinder: (CatalogCard) -> Unit,
    onWishlist: (CatalogCard) -> Unit,
    onAddToWishlist: (List<CatalogCard>) -> Unit,
    section: Int,
    onSectionChange: (Int) -> Unit,
    forBinder: Boolean = false
) {

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) {
            Tab(selected = section == 0, onClick = { onSectionChange(0) }, text = { Text("Cerca carte") })
            Tab(selected = section == 1, onClick = { onSectionChange(1) }, text = { Text("Set") })
        }
        if (section == 0) {
            CatalogSearch(
                viewModel = viewModel,
                catalogReady = catalogReady,
                ownedCards = ownedCards,
                onAddToBinder = onAddToBinder,
                onWishlist = onWishlist
            )
        } else {
            SetsTab(
                catalogReady = catalogReady,
                ownedCards = ownedCards,
                loadSets = { viewModel.loadCatalogSets() },
                loadSetCards = { lang, code -> viewModel.loadSetCards(lang, code) },
                onAddToWishlist = onAddToWishlist,
                action = if (forBinder) BINDER_SET_ACTION else WISHLIST_SET_ACTION
            )
        }
    }
}

/**
 * Catalogo a tutto schermo con ricerca e set, aperto da un binder o dalla wishlist per aggiungere carte
 * senza scansionarle. Resta aperto: si possono aggiungere più carte di seguito.
 */
@Composable
fun CatalogPickerDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Chiudi") }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** Scelta di cosa si cerca: il nome della carta (con numero opzionale) oppure l'autore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchModeChips(byArtist: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(selected = !byArtist, onClick = { onChange(false) }, label = { Text("Nome e numero") })
        FilterChip(selected = byArtist, onClick = { onChange(true) }, label = { Text("Autore") })
    }
}

/** Suggerimento sotto il campo di ricerca, diverso a seconda di cosa si cerca. */
fun searchHint(byArtist: Boolean): String =
    if (byArtist) "Scrivi il nome dell'illustratore (es. Mitsuhiro Arita) per vedere le sue carte."
    else "Scrivi il nome della carta. Puoi aggiungere il numero (Pikachu 25) o anche le carte del set (Pikachu 25/102)."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogSearch(
    viewModel: PokemonViewModel,
    catalogReady: Boolean,
    ownedCards: List<PokemonCard>,
    onAddToBinder: (CatalogCard) -> Unit,
    onWishlist: (CatalogCard) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogCard>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf<String?>(null) }
    var byArtist by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<CatalogCard?>(null) }
    val owned = remember(ownedCards) { OwnedIndex(ownedCards) }

    // Si cerca dopo una breve pausa nella digitazione; le risposte vecchie vengono ignorate.
    LaunchedEffect(query, catalogReady, byArtist) {
        val text = query.trim()
        if (text.length < 2 || !catalogReady) {
            results = emptyList()
            failed = false
            searched = false
            return@LaunchedEffect
        }
        delay(300)
        viewModel.searchCatalog(text, byArtist) { found ->
            if (text == query.trim()) {
                failed = found == null
                results = found.orEmpty()
                searched = true
            }
        }
    }

    val languages = remember(results) { results.map { it.lang }.distinct().sorted() }
    val shown = if (language == null) results else results.filter { it.lang == language }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (byArtist) "Autore (es. Arita)" else "Nome e numero (es. Pikachu 25/102)") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancella ricerca")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )

        SearchModeChips(byArtist = byArtist, onChange = { byArtist = it })
        Spacer(modifier = Modifier.height(4.dp))

        if (languages.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = language == null, onClick = { language = null }, label = { Text("Tutte le lingue") })
                languages.forEach { code ->
                    FilterChip(
                        selected = language == code,
                        onClick = { language = code },
                        label = { Text(CardLanguage.label(code)) }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val message = when {
                !catalogReady -> "Il catalogo si sta preparando…"
                query.trim().length < 2 -> searchHint(byArtist) + "\nLa ricerca copre il catalogo in italiano e in inglese."
                failed -> "Errore nella ricerca nel catalogo."
                searched && shown.isEmpty() -> "Nessuna carta trovata."
                else -> null
            }
            if (message != null) {
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.catalogKey }) { card ->
                        val numbers = owned.numbersFor(card.lang, card.setCode, card.setName)
                        CatalogResult(card = card, isOwned = owned.isOwned(numbers, card.number), showArtist = byArtist, onClick = { preview = card })
                    }
                }
            }
        }
    }

    preview?.let { card ->
        val numbers = owned.numbersFor(card.lang, card.setCode, card.setName)
        CatalogCardDialog(
            card = card,
            isOwned = owned.isOwned(numbers, card.number),
            onAddToBinder = {
                preview = null
                onAddToBinder(card)
            },
            onWishlist = { onWishlist(card) },
            onDismiss = { preview = null }
        )
    }
}

@Composable
private fun CatalogResult(card: CatalogCard, isOwned: Boolean, showArtist: Boolean, onClick: () -> Unit) {
    val total = if (card.setTotal > 0) "/${card.setTotal}" else ""
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            if (card.imageUrl.isBlank()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("Nessuna immagine", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(card.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${card.setName} · ${card.number}$total · ${CardLanguage.short(card.lang)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (showArtist && !card.artist.isNullOrBlank()) {
                    Text(card.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isOwned) {
                    Text("✓ Ce l'hai", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun CatalogDetail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Carta del catalogo ingrandita, con i pulsanti per metterla in un binder o nella wishlist. */
@Composable
fun CatalogCardDialog(
    card: CatalogCard,
    isOwned: Boolean,
    onAddToBinder: () -> Unit,
    onWishlist: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 760.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LargeCardImage(imageUrl = card.imageUrl, name = card.name, modifier = Modifier.fillMaxWidth(0.85f))
                Spacer(modifier = Modifier.height(12.dp))
                Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (isOwned) {
                    Text("✓ Ce l'hai già", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    CatalogDetail("Set", card.setName)
                    CatalogDetail("Numero", if (card.setTotal > 0) "${card.number}/${card.setTotal}" else card.number)
                    CatalogDetail("Lingua", CardLanguage.label(card.lang))
                    card.rarity?.takeIf { it.isNotBlank() }?.let { CatalogDetail("Rarità", it) }
                    card.artist?.takeIf { it.isNotBlank() }?.let { CatalogDetail("Artista", it) }
                    CatalogDetail("Tipo", card.cardType)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Chiudi") }
                    OutlinedButton(onClick = onWishlist) { Text("Wishlist") }
                    Button(onClick = onAddToBinder) { Text("Aggiungi") }
                }
            }
        }
    }
}
