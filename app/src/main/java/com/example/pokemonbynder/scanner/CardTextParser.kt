package com.example.pokemonbynder.scanner

/** Rettangolo in pixel, indipendente da android.graphics così il parser è testabile con JUnit puro. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Una riga di testo letta dall'OCR, con la sua posizione nell'immagine (già raddrizzata). */
data class OcrLine(val text: String, val box: Box)

/** Numero stampato sulla carta: `key` è già normalizzato, `total` è 0 se non letto. */
data class PrintedNumber(val key: String, val total: Int, val raw: String)

/**
 * Risultato della lettura di un fotogramma.
 * @param nameLines possibili nomi (testo pulito, in ordine di grandezza del carattere)
 * @param languageHint "it", "en" oppure null se non riconoscibile
 */
data class ScanReading(
    val nameLines: List<String>,
    val number: PrintedNumber?,
    val languageHint: String?
)

/**
 * Trasforma le righe dell'OCR in una [ScanReading], usando la posizione dentro il riquadro guida:
 * il nome sta nella fascia alta della carta, il numero (es. 025/165) in quella bassa.
 * Il testo fuori dal riquadro (tavolo, altre carte, sfondo) viene ignorato.
 */
object CardTextParser {
    private const val NAME_ZONE = 0.22       // frazione alta del riquadro dove cercare il nome
    private const val NUMBER_ZONE = 0.20     // frazione bassa del riquadro dove cercare il numero
    private const val GUIDE_MARGIN = 0.08    // tolleranza attorno al riquadro
    private const val MAX_NAME_LINES = 3

    // "025/165", "TG01/TG30", "105a/129". O, I, l vengono corretti in 0, 1, 1 (confusioni tipiche dell'OCR).
    private val slashNumber = Regex(
        """(?<![A-Za-z0-9])([A-Za-z]{2,4})?([0-9OoIl]{1,3})([a-z])?\s*[/|\\]\s*(?:[A-Za-z]{2,4})?([0-9OoIl]{1,3})(?![0-9OoIl])"""
    )

    // Promo senza totale: "SWSH193", "SVP 045"
    private val promoNumber = Regex("""(?<![A-Za-z0-9])(SWSH|SVP|SM|XY|BW|HGSS|DP)\s?-?\s?([0-9]{2,3})(?![0-9])""")

    private val hitPoints = Regex("""(?:HP|PS)\s?\d{2,3}\b""", RegexOption.IGNORE_CASE)
    private val keepLettersDigitsAndNameSymbols = Regex("""[^\p{L}\p{N}'’&.\- ]""")
    private val manySpaces = Regex("""\s+""")

    private val italianMarkers = listOf("debolezza", "resistenza", "ritirata", "evolve da", "fase")
    private val englishMarkers = listOf("weakness", "resistance", "retreat", "evolves from", "stage")

    fun parse(lines: List<OcrLine>, guide: Box): ScanReading? {
        val marginX = (guide.width * GUIDE_MARGIN).toInt()
        val marginY = (guide.height * GUIDE_MARGIN).toInt()
        val inside = lines.filter {
            it.box.centerX in (guide.left - marginX)..(guide.right + marginX) &&
                it.box.centerY in (guide.top - marginY)..(guide.bottom + marginY)
        }
        if (inside.isEmpty()) return null

        val nameLimit = guide.top + (guide.height * NAME_ZONE).toInt()
        val numberStart = guide.bottom - (guide.height * NUMBER_ZONE).toInt()

        val nameLines = inside
            .filter { it.box.centerY <= nameLimit }
            .sortedByDescending { it.box.height }
            .mapNotNull { cleanName(it.text) }
            .distinct()
            .take(MAX_NAME_LINES)

        val bottom = inside.filter { it.box.centerY >= numberStart }
        val number = findNumber(bottom) ?: findNumber(inside)

        if (nameLines.isEmpty() && number == null) return null
        return ScanReading(nameLines, number, detectLanguage(inside))
    }

    internal fun cleanName(text: String): String? {
        val withoutHp = hitPoints.replace(text, " ")
        val cleaned = manySpaces.replace(keepLettersDigitsAndNameSymbols.replace(withoutHp, " "), " ").trim()
        return if (cleaned.count { it.isLetter() } >= 3) cleaned else null
    }

    internal fun findNumber(lines: List<OcrLine>): PrintedNumber? {
        for (line in lines) {
            val match = slashNumber.find(line.text) ?: continue
            val prefix = match.groupValues[1]
            val digits = fixDigits(match.groupValues[2])
            val suffix = match.groupValues[3]
            val total = fixDigits(match.groupValues[4]).toIntOrNull() ?: continue
            if (digits.toIntOrNull() == null || total == 0) continue
            return PrintedNumber(
                key = CardText.normalizeNumber(prefix + digits + suffix),
                total = total,
                raw = match.value
            )
        }
        for (line in lines) {
            val match = promoNumber.find(line.text) ?: continue
            val digits = match.groupValues[2]
            return PrintedNumber(
                key = CardText.normalizeNumber(match.groupValues[1] + digits),
                total = 0,
                raw = match.value
            )
        }
        return null
    }

    internal fun detectLanguage(lines: List<OcrLine>): String? {
        val text = lines.joinToString(" ") { it.text }.lowercase()
        val italian = italianMarkers.count { text.contains(it) }
        val english = englishMarkers.count { text.contains(it) }
        return when {
            italian > english -> "it"
            english > italian -> "en"
            else -> null
        }
    }

    private fun fixDigits(text: String): String =
        text.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
}
