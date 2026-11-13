package com.example.pokemonbynder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.pokemonbynder.data.repository.PokemonRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Cerca online l'immagine di una carta quando nel catalogo manca o non si carica.
 * Quella trovata viene salvata nel catalogo, nei binder e nella wishlist: la ricerca si fa una volta sola.
 */
class ImageResolver(private val repository: PokemonRepository) {
    private val found = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(3)

    /** [failedUrl] è l'indirizzo che non si è caricato: non lo si restituisce di nuovo. */
    suspend fun resolve(catalogKey: String, failedUrl: String?): String? {
        if (catalogKey.substringAfter(':', "").startsWith("custom-")) return null // carta creata dall'utente
        found[catalogKey]?.let { if (!sameImage(it, failedUrl)) return it }
        if (catalogKey in missing) return null
        return gate.withPermit {
            try {
                val url = repository.findImageOnline(catalogKey)
                if (url == null || sameImage(url, failedUrl)) {
                    missing.add(catalogKey)
                    null
                } else {
                    found[catalogKey] = url
                    url
                }
            } catch (e: IOException) {
                null // rete assente: si riproverà più tardi
            }
        }
    }

    private fun sameImage(a: String, b: String?): Boolean =
        b != null && a.substringBeforeLast('.') == b.substringBeforeLast('.')
}

val LocalImageResolver = staticCompositionLocalOf<ImageResolver?> { null }

/**
 * Immagine di una carta. Se manca o non si carica, prova la variante .png e poi la cerca online
 * (TCGdex), anche in un'altra lingua. Se non si trova da nessuna parte mostra un riquadro con il nome.
 */
@Composable
fun CardImage(
    imageUrl: String,
    catalogKey: String,
    name: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val resolver = LocalImageResolver.current
    val scope = rememberCoroutineScope()
    var url by remember(imageUrl, catalogKey) { mutableStateOf(imageUrl.ifBlank { null }) }
    var stage by remember(imageUrl, catalogKey) { mutableStateOf(0) }
    var failed by remember(imageUrl, catalogKey) { mutableStateOf(false) }

    LaunchedEffect(imageUrl, catalogKey) {
        if (imageUrl.isBlank()) {
            val resolved = if (catalogKey.isNotBlank() && resolver != null) resolver.resolve(catalogKey, null) else null
            if (resolved != null) url = resolved else failed = true
        }
    }

    Box(modifier = modifier) {
        AsyncImage(
            model = url,
            contentDescription = name,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onSuccess = { failed = false },
            onError = {
                val failedUrl = url
                if (failedUrl != null && resolver != null && catalogKey.isNotBlank()) {
                    if (stage == 0 && failedUrl.endsWith(".webp")) {
                        stage = 1
                        url = failedUrl.removeSuffix(".webp") + ".png"
                    } else if (stage < 2) {
                        stage = 2
                        scope.launch {
                            val resolved = resolver.resolve(catalogKey, failedUrl)
                            if (resolved != null) url = resolved else failed = true
                        }
                    } else {
                        failed = true
                    }
                } else {
                    failed = true
                }
            }
        )
        if (failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}
