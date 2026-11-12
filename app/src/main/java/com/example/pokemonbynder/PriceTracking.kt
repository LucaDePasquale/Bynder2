package com.example.pokemonbynder

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

/** Un punto dello storico: quanto valeva la collezione in quel momento. */
data class PricePoint(val time: Long, val value: Double)

/** Storico del valore della collezione (al massimo un punto al giorno), salvato sul telefono. */
class PriceHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("price_history", Context.MODE_PRIVATE)
    private val _points = MutableStateFlow(load())
    val points: StateFlow<List<PricePoint>> = _points.asStateFlow()

    private fun load(): List<PricePoint> =
        (prefs.getString(KEY, "") ?: "").split(';').mapNotNull { item ->
            val parts = item.split(':')
            val time = parts.getOrNull(0)?.toLongOrNull()
            val value = parts.getOrNull(1)?.toDoubleOrNull()
            if (time != null && value != null) PricePoint(time, value) else null
        }

    /** Registra il valore di oggi: se oggi c'è già un punto lo sostituisce. */
    @Synchronized
    fun record(value: Double, now: Long = System.currentTimeMillis()) {
        if (value <= 0.0) return
        val day = now / DAY_MS
        val kept = _points.value.filterNot { it.time / DAY_MS == day }
        val updated = (kept + PricePoint(now, value)).takeLast(MAX_POINTS)
        _points.value = updated
        prefs.edit().putString(KEY, updated.joinToString(";") { "${it.time}:${it.value}" }).apply()
    }

    private companion object {
        const val KEY = "points"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_POINTS = 400
    }
}

/** Il prezzo che si vuole pagare al massimo per ogni carta della wishlist. */
class WishTargetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wish_targets", Context.MODE_PRIVATE)
    private val _targets = MutableStateFlow(load())
    val targets: StateFlow<Map<String, Double>> = _targets.asStateFlow()

    private fun load(): Map<String, Double> =
        prefs.all.mapNotNull { (key, value) ->
            val price = (value as? String)?.toDoubleOrNull()
            if (price != null) key to price else null
        }.toMap()

    /** [price] null toglie l'obiettivo. */
    @Synchronized
    fun set(wishId: String, price: Double?) {
        val updated = _targets.value.toMutableMap()
        if (price == null) updated.remove(wishId) else updated[wishId] = price
        _targets.value = updated
        prefs.edit().apply { if (price == null) remove(wishId) else putString(wishId, price.toString()) }.apply()
    }
}

/** Grafico semplice del valore della collezione nel tempo. */
@Composable
fun PriceHistoryChart(points: List<PricePoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Valore nel tempo", style = MaterialTheme.typography.titleSmall)
        if (points.size < 2) {
            Text(
                "Il grafico comparirà dopo almeno due giorni con i prezzi caricati. Aggiorna i prezzi ogni tanto.",
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }
        val min = points.minOf { it.value }
        val max = points.maxOf { it.value }
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        Spacer(modifier = Modifier.height(8.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val pad = 8.dp.toPx()
            val width = size.width - pad * 2
            val height = size.height - pad * 2
            for (i in 0..2) {
                val y = pad + height * i / 2f
                drawLine(gridColor, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 1.dp.toPx())
            }
            val first = points.first().time
            val span = (points.last().time - first).coerceAtLeast(1L)
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = pad + width * ((point.time - first).toFloat() / span)
                val y = pad + height * (1f - ((point.value - min) / range).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x, y))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
        Spacer(modifier = Modifier.height(4.dp))
        val format = DateFormat.getDateInstance(DateFormat.SHORT)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(format.format(Date(points.first().time)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(format.format(Date(points.last().time)), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Minimo ${formatPrice(min)} · massimo ${formatPrice(max)} · ora ${formatPrice(points.last().value)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Sceglie il prezzo massimo che si vuole pagare per una carta della wishlist. */
@Composable
fun WishTargetDialog(
    cardName: String,
    current: Double?,
    onSave: (Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(cardName) { mutableStateOf(current?.let { String.format(java.util.Locale.US, "%.2f", it) }.orEmpty()) }
    val value = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prezzo obiettivo") },
        text = {
            Column {
                Text("Ti segnalo «$cardName» quando il prezzo scende sotto questa cifra (in euro).", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                    label = { Text("Prezzo massimo (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(enabled = value != null && value > 0.0, onClick = { onSave(value) }) { Text("Salva") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Togli") }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        }
    )
}

/** Prezzi scritti a mano (a copia): hanno la precedenza su quelli presi da internet. */
class ManualPriceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("manual_prices", Context.MODE_PRIVATE)
    private val _prices = MutableStateFlow(load())
    val prices: StateFlow<Map<String, Double>> = _prices.asStateFlow()

    private fun load(): Map<String, Double> =
        prefs.all.mapNotNull { (key, value) ->
            val price = (value as? String)?.toDoubleOrNull()
            if (price != null) key to price else null
        }.toMap()

    /** [price] null torna al prezzo di internet. */
    @Synchronized
    fun set(key: String, price: Double?) {
        val updated = _prices.value.toMutableMap()
        if (price == null) updated.remove(key) else updated[key] = price
        _prices.value = updated
        prefs.edit().apply { if (price == null) remove(key) else putString(key, price.toString()) }.apply()
    }
}
