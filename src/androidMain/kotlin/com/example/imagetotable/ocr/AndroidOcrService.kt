package com.example.imagetotable.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class AndroidOcrService(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit = {}
) {
    companion object {
        // Matches letters + combining marks + numbers, OR any single punctuation symbol
        private val TOKEN_REGEX = Regex("""[\p{L}\p{M}\p{N}]+|[^\s\p{L}\p{M}\p{N}]""")

        /**
         * Splits any string into words and punctuation marks (comma, full stop, colon, etc.)
         * as discrete tokens.
         */
        fun tokenizeWithPunctuation(rawText: String): List<String> {
            if (rawText.isBlank()) return emptyList()
            return TOKEN_REGEX.findAll(rawText).map { it.value }.toList()
        }

        /**
         * Ensures a single space gap between all words and punctuation marks.
         * Example: "John Doe, Manager." -> "John Doe , Manager ."
         */
        fun formatWithSingleSpaceGap(rawText: String): String {
            return tokenizeWithPunctuation(rawText).joinToString(" ")
        }
    }

    /**
     * Extracts individual tokens (words, commas, full stops, punctuation marks)
     * from a scanned image (with or without borders).
     */
    suspend fun extractTokens(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        onStatusUpdate("Scanning image tokens...")
        val visionText = recognizeText(bitmap)
        val tokens = mutableListOf<String>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                tokens.addAll(tokenizeWithPunctuation(line.text))
            }
        }
        tokens
    }

    /**
     * Extracts tabular data from an image (with or without borders) using spatial
     * coordinate clustering. Every cell's contents are formatted with single space gaps
     * around punctuation marks.
     */
    suspend fun extractTable(bitmap: Bitmap): Pair<List<String>, List<List<String>>> = withContext(Dispatchers.Default) {
        onStatusUpdate("Analyzing table structure & boundaries...")
        val visionText = recognizeText(bitmap)

        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) {
            return@withContext Pair(emptyList(), emptyList())
        }

        // 1. Calculate average line height for dynamic spatial thresholds
        val avgHeight = allLines
            .mapNotNull { it.boundingBox?.height() }
            .ifEmpty { listOf(24) }
            .average()
            .toFloat()
            .coerceAtLeast(16f)

        val rowThresholdY = avgHeight * 0.55f

        // 2. Vertical Clustering: Group lines into rows (handles both bordered & borderless rows)
        val sortedByY = allLines.sortedBy { it.boundingBox?.top ?: 0 }
        val rowsOfLines = mutableListOf<MutableList<Text.Line>>()

        for (line in sortedByY) {
            val lineBox = line.boundingBox ?: Rect(0, 0, 0, 0)
            val matchingRow = rowsOfLines.firstOrNull { rowLines ->
                val rowAvgTop = rowLines.map { it.boundingBox?.top ?: 0 }.average().toFloat()
                abs(lineBox.top - rowAvgTop) <= rowThresholdY
            }

            if (matchingRow != null) {
                matchingRow.add(line)
            } else {
                rowsOfLines.add(mutableListOf(line))
            }
        }

        // Sort items in each row horizontally from left to right
        rowsOfLines.forEach { row ->
            row.sortBy { it.boundingBox?.left ?: 0 }
        }

        // 3. Horizontal Clustering: Identify global column anchors across all rows
        val allLeftCoords = rowsOfLines.flatMap { row -> row.mapNotNull { it.boundingBox?.left } }.sorted()
        val columnAnchors = mutableListOf<Int>()
        val colToleranceX = (avgHeight * 2.2f).toInt().coerceAtLeast(40)

        for (x in allLeftCoords) {
            if (columnAnchors.none { abs(it - x) < colToleranceX }) {
                columnAnchors.add(x)
            }
        }
        columnAnchors.sort()
        val totalCols = columnAnchors.size.coerceAtLeast(1)

        // 4. Construct 2D Grid Cells with single space gaps between words and punctuation
        val extractedRows = mutableListOf<List<String>>()

        for (row in rowsOfLines) {
            val cellArray = MutableList(totalCols) { "" }

            for (line in row) {
                val lineX = line.boundingBox?.left ?: 0

                // Match with nearest column anchor
                var bestCol = 0
                var minDiff = Int.MAX_VALUE
                for ((idx, anchorX) in columnAnchors.withIndex()) {
                    val diff = abs(anchorX - lineX)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestCol = idx
                    }
                }

                // Format text with single space gap and punctuation tokens separated
                val formattedCellText = formatWithSingleSpaceGap(line.text)
                if (cellArray[bestCol].isBlank()) {
                    cellArray[bestCol] = formattedCellText
                } else {
                    cellArray[bestCol] = "${cellArray[bestCol]} $formattedCellText"
                }
            }

            extractedRows.add(cellArray)
        }

        if (extractedRows.isEmpty()) {
            return@withContext Pair(emptyList(), emptyList())
        }

        // 5. Partition Headers and Data Rows
        val headers = if (extractedRows.size > 1) {
            extractedRows.first().mapIndexed { idx, h -> h.ifBlank { "Col ${idx + 1}" } }
        } else {
            List(totalCols) { idx -> "Col ${idx + 1}" }
        }

        val dataRows = if (extractedRows.size > 1) {
            extractedRows.drop(1)
        } else {
            extractedRows
        }

        Pair(headers, dataRows)
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) continuation.resume(visionText)
            }
            .addOnFailureListener { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
    }
}
