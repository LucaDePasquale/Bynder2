package com.example.pokemonbynder.ui.scanner

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CardScannerAnalyzer(
    private val onCardDetected: (String, String) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastProcessingTime = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessingTime < 1000) { 
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        extractCardInfo(visionText)
                    }
                }
                .addOnCompleteListener {
                    lastProcessingTime = currentTime
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun extractCardInfo(visionText: Text) {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return

        // Regex per il numero della carta (es: 151/165, 151/165, 151 165)
        val numberRegex = Regex("""(\d{1,3})\s?[/|\\|\s]\s?(\d{1,3})""")
        var detectedNumber = ""
        var detectedName = ""

        // 1. Cerchiamo il numero della carta
        for (line in allLines) {
            val match = numberRegex.find(line.text)
            if (match != null) {
                detectedNumber = match.groupValues[1]
                break
            }
        }

        // 2. Cerchiamo il nome del Pokémon
        val blacklist = listOf("HP", "STAGE", "BASIC", "EVOLVES", "LEVEL", "POCKET", "MONSTERS", "TRAINER", "ITEM", "VMAX", "VSTAR")
        
        // Ordiniamo le righe dall'alto verso il basso
        val sortedLines = allLines.sortedBy { it.boundingBox?.top ?: 0 }
        
        for (line in sortedLines.take(10)) {
            val text = line.text.trim()
            val upper = text.uppercase()
            
            if (text.length in 3..25 && 
                !text.contains("/") && 
                !text.any { it.isDigit() } &&
                blacklist.none { upper.contains(it) }) {
                detectedName = text
                break
            }
        }

        if (detectedName.isNotBlank()) {
            Log.d("Scanner", "Rilevato: $detectedName | Numero: $detectedNumber")
            onCardDetected(detectedName, detectedNumber)
        }
    }
}
