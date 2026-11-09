package com.example.pokemonbynder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
 * (TCGdex), anche in un'altra lingua.
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

    LaunchedEffect(imageUrl, catalogKey) {
        if (imageUrl.isBlank() && catalogKey.isNotBlank() && resolver != null) {
            resolver.resolve(catalogKey, null)?.let { url = it }
        }
    }

    AsyncImage(
        model = url,
        contentDescription = name,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            val failed = url
            if (failed != null && resolver != null && catalogKey.isNotBlank()) {
                if (stage == 0 && failed.endsWith(".webp")) {
                    stage = 1
                    url = failed.removeSuffix(".webp") + ".png"
                } else if (stage < 2) {
                    stage = 2
                    scope.launch { resolver.resolve(catalogKey, failed)?.let { url = it } }
                }
            }
        }
    )
}
