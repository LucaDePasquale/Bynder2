package com.example.pokemonbynder.ui.scanner

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
import com.google.mlkit.vision.text.TextRecognition
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
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var lastProcessingTime = 0L

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

        recognizer.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        OcrLine(line.text, Box(rect.left, rect.top, rect.right, rect.bottom))
                    }
                CardTextParser.parse(lines, guide)?.let { reading ->
                    Log.d("Scanner", "Nomi: ${reading.nameLines} | Numero: ${reading.number} | Lingua: ${reading.languageHint}")
                    onReading(reading)
                }
            }
            .addOnFailureListener { Log.e("Scanner", "OCR non riuscito", it) }
            .addOnCompleteListener {
                lastProcessingTime = startedAt
                imageProxy.close()
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
    }
}
