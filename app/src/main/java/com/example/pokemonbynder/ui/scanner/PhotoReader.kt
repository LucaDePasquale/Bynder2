package com.example.pokemonbynder.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.pokemonbynder.scanner.Box
import com.example.pokemonbynder.scanner.CardTextParser
import com.example.pokemonbynder.scanner.OcrLine
import com.example.pokemonbynder.scanner.ScanReading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Legge il testo di una carta da una foto della galleria (stesso lettore della fotocamera).
 * Una foto non ha il riquadro guida, quindi si provano più ipotesi su dove sta la carta
 * (tutta la foto, il centro con le proporzioni di una carta, la zona occupata dal testo)
 * e chi sceglie la migliore è il confronto con il catalogo.
 */
/** Righe lette dalla foto e le letture ricavate con le diverse ipotesi sul riquadro della carta. */
class PhotoAnalysis(val readings: List<ScanReading>, val lines: List<OcrLine>)

/** Dati letti dalla foto per precompilare la carta digitale (tutti correggibili dall'utente). */
data class PhotoDraft(
    val name: String = "",
    val number: String = "",
    val total: String = "",
    val artist: String = "",
    val lang: String = "it"
)

object PhotoReader {
    private const val MAX_SIDE = 2400
    private const val CARD_ASPECT = 0.716f

    private val illustrator = Regex("""(?i)\bIllus\.?[:\s]+(.+)""")

    suspend fun read(context: Context, uri: Uri): List<ScanReading> = analyze(context, uri).readings

    suspend fun analyze(context: Context, uri: Uri): PhotoAnalysis = withContext(Dispatchers.Default) {
        val bitmap = loadBitmap(context, uri) ?: return@withContext PhotoAnalysis(emptyList(), emptyList())
        val lines = try {
            recognize(bitmap)
        } catch (e: Exception) {
            Log.e("PhotoReader", "OCR della foto non riuscito", e)
            emptyList()
        }
        if (lines.isEmpty()) return@withContext PhotoAnalysis(emptyList(), emptyList())
        val readings = guides(bitmap.width, bitmap.height, lines)
            .mapNotNull { CardTextParser.parse(lines, it) }
            .distinct()
        PhotoAnalysis(readings, lines)
    }

    /** Nome, numero, lingua e autore che si riescono a leggere; ciò che manca resta vuoto. */
    fun draftOf(analysis: PhotoAnalysis): PhotoDraft {
        val readings = analysis.readings
        val reading = readings.firstOrNull { it.nameLines.isNotEmpty() && it.number != null }
            ?: readings.firstOrNull { it.nameLines.isNotEmpty() }
            ?: readings.firstOrNull()
        val artist = analysis.lines.firstNotNullOfOrNull { line ->
            illustrator.find(line.text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
        }.orEmpty()
        return PhotoDraft(
            name = reading?.nameLines?.firstOrNull().orEmpty(),
            number = reading?.number?.key?.uppercase().orEmpty(),
            total = reading?.number?.total?.takeIf { it > 0 }?.toString().orEmpty(),
            artist = artist,
            lang = reading?.languageHint ?: "it"
        )
    }

    /** Copia la foto (ridotta) nella memoria dell'app e restituisce l'indirizzo da usare come immagine della carta. */
    suspend fun saveCopy(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(context, uri, 1400) ?: return@withContext null
        try {
            val dir = File(context.filesDir, "custom_cards").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            Log.e("PhotoReader", "Impossibile salvare la foto", e)
            null
        }
    }

    private fun guides(width: Int, height: Int, lines: List<OcrLine>): List<Box> {
        val full = Box(0, 0, width, height)

        val cardWidth: Int
        val cardHeight: Int
        if (width.toFloat() / height > CARD_ASPECT) {
            cardHeight = height
            cardWidth = (height * CARD_ASPECT).toInt()
        } else {
            cardWidth = width
            cardHeight = (width / CARD_ASPECT).toInt()
        }
        val centered = Box(
            (width - cardWidth) / 2, (height - cardHeight) / 2,
            (width + cardWidth) / 2, (height + cardHeight) / 2
        )

        val padX = (width * 0.04f).toInt()
        val padY = (height * 0.04f).toInt()
        val textArea = Box(
            maxOf(0, lines.minOf { it.box.left } - padX),
            maxOf(0, lines.minOf { it.box.top } - padY),
            minOf(width, lines.maxOf { it.box.right } + padX),
            minOf(height, lines.maxOf { it.box.bottom } + padY)
        )
        return listOf(full, centered, textArea).distinct()
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine<Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            return text.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val rect = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, Box(rect.left, rect.top, rect.right, rect.bottom))
                }
        } finally {
            recognizer.close()
        }
    }

    /** Carica la foto con una risoluzione sufficiente per l'OCR e la raddrizza secondo i dati EXIF. */
    private fun loadBitmap(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) raw
            else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
        } catch (e: Exception) {
            Log.e("PhotoReader", "Impossibile aprire la foto", e)
            null
        }
    }
}
