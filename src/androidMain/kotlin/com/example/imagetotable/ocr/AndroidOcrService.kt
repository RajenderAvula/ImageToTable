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
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class WordItem(
        val text: String,
        val rect: Rect
    ) {
        val centerY: Int get() = rect.centerY()
        val height: Int get() = rect.height().coerceAtLeast(1)
        val width: Int get() = rect.width().coerceAtLeast(1)
    }

    suspend fun extractTable(bitmap: Bitmap): Pair<List<String>, List<List<String>>> =
        withContext(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Running ML Kit Neural OCR...")
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = processImageAsync(image)

            // Collect all detected words with their bounding boxes
            val words = mutableListOf<WordItem>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox
                        if (box != null && element.text.isNotBlank()) {
                            words.add(WordItem(element.text.trim(), box))
                        }
                    }
                }
            }

            if (words.isEmpty()) {
                return@withContext Pair(listOf("Col 1", "Col 2"), emptyList())
            }

            // 1. Cluster words into rows based on vertical (Y) overlap
            words.sortBy { it.rect.top }
            val rowClusters = mutableListOf<MutableList<WordItem>>()

            for (w in words) {
                val matchingRow = rowClusters.firstOrNull { cluster ->
                    val avgY = cluster.map { it.centerY }.average()
                    val avgH = cluster.map { it.height }.average().coerceAtLeast(12.0)
                    abs(w.centerY - avgY) <= (avgH * 0.60)
                }
                if (matchingRow != null) {
                    matchingRow.add(w)
                } else {
                    rowClusters.add(mutableListOf(w))
                }
            }

            // 2. Sort rows top-to-bottom
            rowClusters.sortBy { cluster -> cluster.map { it.rect.top }.minOrNull() ?: 0 }

            // 3. Cluster words into cells horizontally (keeps multi-word phrases in one column)
            val tableRows = rowClusters.map { cluster ->
                cluster.sortBy { it.rect.left }

                val cells = mutableListOf<String>()
                var currentCell = StringBuilder()
                var prevWord: WordItem? = null

                for (w in cluster) {
                    if (prevWord == null) {
                        currentCell.append(w.text)
                    } else {
                        val gap = w.rect.left - prevWord.rect.right
                        val avgCharWidth = (prevWord.width.toFloat() / prevWord.text.length.coerceAtLeast(1))
                            .coerceAtLeast(6f)

                        // Gaps wider than 2.8 average characters trigger a new table column
                        val isNewColumn = gap > (avgCharWidth * 2.8f)

                        if (isNewColumn) {
                            cells.add(currentCell.toString().trim())
                            currentCell = StringBuilder(w.text)
                        } else {
                            currentCell.append(" ").append(w.text)
                        }
                    }
                    prevWord = w
                }
                if (currentCell.isNotEmpty()) {
                    cells.add(currentCell.toString().trim())
                }
                cells
            }

            val maxCols = tableRows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
            val firstRow = tableRows.firstOrNull() ?: emptyList()

            val headers = (0 until maxCols).map { c ->
                firstRow.getOrElse(c) { "Column ${c + 1}" }.ifBlank { "Column ${c + 1}" }
            }

            val dataRows = if (tableRows.size > 1) {
                tableRows.drop(1).map { r ->
                    val padded = r + List((maxCols - r.size).coerceAtLeast(0)) { "" }
                    padded.take(maxCols)
                }
            } else emptyList()

            withContext(Dispatchers.Main) {
                onStatusUpdate("Recognized ${words.size} words across ${tableRows.size} rows.")
            }

            Pair(headers, dataRows)
        }

    private suspend fun processImageAsync(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText)
                }
                .addOnFailureListener { exception ->
                    cont.resumeWithException(exception)
                }
        }
}
