package com.example.pokemonbynder.ui.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.pokemonbynder.scanner.Box
import com.example.pokemonbynder.scanner.CardTextParser
import com.example.pokemonbynder.scanner.OcrLine
import com.example.pokemonbynder.scanner.ScanReading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Dimensioni del riquadro giallo mostrato sopra la fotocamera (le usa anche l'analizzatore). */
object ScannerGuide {
    const val WIDTH_DP = 280
    const val HEIGHT_DP = 390
}

/**
 * Legge il testo della carta dalla fotocamera e lo trasforma in una [ScanReading].
 *
 * Considera solo il testo dentro il riquadro guida. Il riquadro è al centro dello schermo e l'anteprima
 * usa FILL_CENTER, quindi si calcola dove cade nell'immagine analizzata e si cercano
 * il nome nella fascia alta e il numero (es. 025/165) in quella bassa.
 *
 * @param previewSize larghezza e altezza in pixel di PreviewView (0 se non ancora disposta)
 * @param guideSizePx larghezza e altezza in pixel del riquadro guida
 */
class CardScannerAnalyzer(
    private val previewSize: () -> Pair<Int, Int>,
    private val guideSizePx: Pair<Int, Int>,
    private val onReading: (ScanReading) -> Unit
) : ImageAnalysis.Analyzer {

    private companion object {
        const val MIN_INTERVAL_MS = 400L
        const val STRIP_SCALE = 2
        const val STRIP_FRACTION = 0.30
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    @Volatile
    private var lastProcessingTime = 0L

    /**
     * Tre passaggi, ognuno solo se il precedente non basta (così le carte normali restano veloci):
     * 1) lettura normale; 2) lettura con il riconoscitore giapponese (carte giapponesi);
     * 3) striscia in basso ingrandita e con più contrasto, per il numero sulle alt art e le SAR.
     */
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startedAt = System.currentTimeMillis()
        val mediaImage = imageProxy.image
        val (viewWidth, viewHeight) = previewSize()
        if (startedAt - lastProcessingTime < MIN_INTERVAL_MS || mediaImage == null || viewWidth <= 0 || viewHeight <= 0) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        val guide = guideInImage(uprightWidth, uprightHeight, viewWidth, viewHeight)
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        var finished = false
        fun finish(reading: ScanReading?) {
            if (finished) return
            finished = true
            reading?.let {
                Log.d("Scanner", "Nomi: ${it.nameLines} | Numero: ${it.number} | Lingua: ${it.languageHint}")
                onReading(it)
            }
            lastProcessingTime = startedAt
            imageProxy.close()
        }

        fun complete(reading: ScanReading?) = reading != null && reading.number != null && reading.nameLines.isNotEmpty()

        // 3) striscia in basso
        fun thirdPass(lines: List<OcrLine>, best: ScanReading?) {
            val strip = stripBitmap(imageProxy, rotation, guide)
            if (strip == null) {
                finish(best)
                return
            }
            val (bitmap, origin) = strip
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val extra = toLines(text).map {
                        OcrLine(
                            it.text,
                            Box(
                                origin.left + it.box.left / STRIP_SCALE,
                                origin.top + it.box.top / STRIP_SCALE,
                                origin.left + it.box.right / STRIP_SCALE,
                                origin.top + it.box.bottom / STRIP_SCALE
                            )
                        )
                    }
                    val merged = CardTextParser.parse(lines + extra, guide)
                    finish(merged ?: best)
                }
                .addOnFailureListener { finish(best) }
        }

        // 2) riconoscitore giapponese
        fun secondPass(lines: List<OcrLine>, best: ScanReading?) {
            japaneseRecognizer.process(image)
                .addOnSuccessListener { text ->
                    val combined = lines + toLines(text)
                    val reading = CardTextParser.parse(combined, guide) ?: best
                    if (complete(reading)) finish(reading) else thirdPass(combined, reading)
                }
                .addOnFailureListener { thirdPass(lines, best) }
        }

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = toLines(text)
                val reading = CardTextParser.parse(lines, guide)
                if (complete(reading)) finish(reading) else secondPass(lines, reading)
            }
            .addOnFailureListener {
                Log.e("Scanner", "OCR non riuscito", it)
                finish(null)
            }
    }

    private fun toLines(text: Text): List<OcrLine> = text.textBlocks
        .flatMap { it.lines }
        .mapNotNull { line ->
            val rect = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, Box(rect.left, rect.top, rect.right, rect.bottom))
        }

    /** Striscia in basso della carta, ingrandita e con più contrasto; restituisce anche dove si trova nell'immagine. */
    private fun stripBitmap(imageProxy: ImageProxy, rotation: Int, guide: Box): Pair<Bitmap, Box>? {
        return try {
            val raw = imageProxy.toBitmap()
            val upright = if (rotation == 0) raw else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            }
            val left = guide.left.coerceIn(0, upright.width - 2)
            val right = guide.right.coerceIn(left + 1, upright.width)
            val bottom = guide.bottom.coerceIn(2, upright.height)
            val top = (guide.bottom - (guide.height * STRIP_FRACTION).toInt()).coerceIn(0, bottom - 1)
            val crop = Bitmap.createBitmap(upright, left, top, right - left, bottom - top)
            val scaled = Bitmap.createScaledBitmap(crop, crop.width * STRIP_SCALE, crop.height * STRIP_SCALE, true)
            val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val contrast = ColorMatrix().apply {
                setSaturation(0f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            2f, 0f, 0f, 0f, -128f,
                            0f, 2f, 0f, 0f, -128f,
                            0f, 0f, 2f, 0f, -128f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(contrast) }
            Canvas(out).drawBitmap(scaled, 0f, 0f, paint)
            out to Box(left, top, right, bottom)
        } catch (e: Exception) {
            Log.e("Scanner", "Striscia non ricavabile", e)
            null
        }
    }

    /** Dove cade il riquadro guida (al centro dell'anteprima, FILL_CENTER) nell'immagine analizzata. */
    private fun guideInImage(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Box {
        val scale = maxOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val width = (guideSizePx.first / scale).toInt()
        val height = (guideSizePx.second / scale).toInt()
        val left = imageWidth / 2 - width / 2
        val top = imageHeight / 2 - height / 2
        return Box(left, top, left + width, top + height)
    }

    fun close() {
        recognizer.close()
        japaneseRecognizer.close()
    }
}
