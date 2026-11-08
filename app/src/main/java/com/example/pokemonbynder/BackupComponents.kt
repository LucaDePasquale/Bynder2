package com.example.pokemonbynder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import com.example.pokemonbynder.data.backup.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Menu in alto a destra: backup su file e condivisione di liste in formato testo. */
@Composable
fun BackupMenu(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onShareDuplicates: () -> Unit,
    onShareWishlist: () -> Unit,
    onShareCollection: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Backup e condivisione")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Salva backup su file") }, onClick = { open = false; onExport() })
        DropdownMenuItem(text = { Text("Ripristina da backup") }, onClick = { open = false; onImport() })
        DropdownMenuItem(text = { Text("Condividi carte doppie") }, onClick = { open = false; onShareDuplicates() })
        DropdownMenuItem(text = { Text("Condividi wishlist") }, onClick = { open = false; onShareWishlist() })
        DropdownMenuItem(text = { Text("Condividi collezione") }, onClick = { open = false; onShareCollection() })
    }
}

/** Riepilogo mostrato al termine di un ripristino. */
@Composable
fun ImportResultDialog(result: ImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup ripristinato") },
        text = {
            Column {
                Text("Carte aggiunte: ${result.cardsAdded}", fontWeight = FontWeight.Bold)
                if (result.cardsAlreadyThere > 0) Text("Già presenti (non toccate): ${result.cardsAlreadyThere}")
                if (result.bindersAdded > 0) Text("Binder creati: ${result.bindersAdded}")
                if (result.wishlistAdded > 0) Text("Carte aggiunte alla wishlist: ${result.wishlistAdded}")
                Text("Le carte che avevi già non sono state modificate né eliminate.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}

/** Scrive il backup nel file scelto dall'utente. Restituisce false se la scrittura non riesce. */
suspend fun writeBackupFile(context: Context, uri: Uri, json: String): Boolean = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
    } catch (e: Exception) {
        false
    }
}

/** Legge il file di backup scelto dall'utente. Restituisce null se non si riesce a leggerlo. */
suspend fun readBackupFile(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    } catch (e: Exception) {
        null
    }
}

/** Apre il selettore di Android per mandare un testo a un'altra app (messaggi, e-mail, note...). */
fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}
