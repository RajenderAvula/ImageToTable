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

    data class TextElementBox(
        val text: String,
        val rect: Rect
    ) {
        val centerX: Int get() = rect.centerX()
        val centerY: Int get() = rect.centerY()
        val height: Int get() = rect.height().coerceAtLeast(1)
        val width: Int get() = rect.width().coerceAtLeast(1)
    }

    data class ColumnInterval(
        val left: Int,
        val right: Int,
        val centerX: Int
    )

    suspend fun extractTable(
        bitmap: Bitmap,
        excludeHeaders: Boolean = false
    ): Pair<List<String>, List<List<String>>> = withContext(Dispatchers.Default) {
        withContext(Dispatchers.Main) {
            onStatusUpdate("Analyzing table layout, columns & multiline cells...")
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = processImageAsync(inputImage)

        // 1. Collect all detected text elements
        val elements = mutableListOf<TextElementBox>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (elem in line.elements) {
                    val box = elem.boundingBox
                    if (box != null && elem.text.isNotBlank()) {
                        elements.add(TextElementBox(elem.text.trim(), box))
                    }
                }
            }
        }

        if (elements.isEmpty()) {
            return@withContext Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        // 2. Global Column Interval Detection (Prevents merged or blank cells from shifting columns)
        val sortedByX = elements.sortedBy { it.rect.left }
        val columnClusters = mutableListOf<MutableList<TextElementBox>>()

        for (el in sortedByX) {
            val matchingCol = columnClusters.firstOrNull { colList ->
                val avgLeft = colList.map { it.rect.left }.average()
                val avgRight = colList.map { it.rect.right }.average()
                val avgW = colList.map { it.width }.average().coerceAtLeast(20.0)

                val overlaps = el.rect.left < avgRight && el.rect.right > avgLeft
                val nearCenter = abs(el.centerX - ((avgLeft + avgRight) / 2)) < (avgW * 0.75)
                overlaps || nearCenter
            }

            if (matchingCol != null) {
                matchingCol.add(el)
            } else {
                columnClusters.add(mutableListOf(el))
            }
        }

        columnClusters.sortBy { col -> col.minOf { it.rect.left } }

        val columns = columnClusters.map { colList ->
            ColumnInterval(
                left = colList.minOf { it.rect.left },
                right = colList.maxOf { it.rect.right },
                centerX = colList.map { it.centerX }.average().toInt()
            )
        }
        val totalCols = columns.size.coerceAtLeast(1)

        // 3. Row Clustering with Multi-Line Single-Cell Detection
        val sortedByY = elements.sortedBy { it.rect.top }
        val rowBands = mutableListOf<MutableList<TextElementBox>>()

        for (el in sortedByY) {
            val matchingRow = rowBands.firstOrNull { rowList ->
                val avgY = rowList.map { it.centerY }.average()
                val avgH = rowList.map { it.height }.average().coerceAtLeast(14.0)

                val verticalOverlap = abs(el.centerY - avgY) <= (avgH * 0.70)

                // Detect vertically stacked lines belonging to the same column cell
                val isMultiLineInSameCol = rowList.any { rowElem ->
                    val sameColSpan = abs(rowElem.centerX - el.centerX) <= (rowElem.width * 0.60)
                    val closelyStacked = (el.rect.top - rowElem.rect.bottom) in -5..(avgH * 0.85).toInt()
                    sameColSpan && closelyStacked
                }

                verticalOverlap || isMultiLineInSameCol
            }

            if (matchingRow != null) {
                matchingRow.add(el)
            } else {
                rowBands.add(mutableListOf(el))
            }
        }

        rowBands.sortBy { rowList -> rowList.minOf { it.rect.top } }

        // 4. Map Elements into Column Buckets & Concatenate Multi-Line Cells
        val gridRows = mutableListOf<List<String>>()

        for (rowElements in rowBands) {
            val cellBuckets = MutableList(totalCols) { mutableListOf<TextElementBox>() }

            for (elem in rowElements) {
                var bestColIdx = 0
                var minDistance = Int.MAX_VALUE

                for ((cIdx, colInterval) in columns.withIndex()) {
                    val dist = abs(elem.centerX - colInterval.centerX)
                    if (dist < minDistance) {
                        minDistance = dist
                        bestColIdx = cIdx
                    }
                }
                cellBuckets[bestColIdx].add(elem)
            }

            // Concatenate multi-line text into a single cell
            val rowValues = cellBuckets.map { bucketElements ->
                if (bucketElements.isEmpty()) {
                    ""
                } else {
                    bucketElements.sortWith(compareBy<TextElementBox> { it.rect.top }.thenBy { it.rect.left })

                    val cellBuilder = StringBuilder()
                    var lastBox: Rect? = null

                    for (b in bucketElements) {
                        if (lastBox == null) {
                            cellBuilder.append(b.text)
                        } else {
                            val isNewLine = (b.rect.top - lastBox.bottom) > -4 &&
                                    (b.rect.top - lastBox.top) > (lastBox.height() * 0.6)

                            if (isNewLine) {
                                cellBuilder.append(" ").append(b.text)
                            } else {
                                cellBuilder.append(" ").append(b.text)
                            }
                        }
                        lastBox = b.rect
                    }
                    cellBuilder.toString().trim()
                }
            }

            if (rowValues.any { it.isNotBlank() }) {
                gridRows.add(rowValues)
            }
        }

        if (gridRows.isEmpty()) {
            return@withContext Pair(List(totalCols) { "Col ${it + 1}" }, emptyList())
        }

        // 5. Exclude Headers vs Parse Headers
        if (excludeHeaders) {
            val defaultHeaders = List(totalCols) { "Col ${it + 1}" }
            Pair(defaultHeaders, gridRows)
        } else {
            val headers = gridRows.first().mapIndexed { idx, hText ->
                hText.ifBlank { "Col ${idx + 1}" }
            }
            val dataRows = if (gridRows.size > 1) gridRows.drop(1) else emptyList()
            Pair(headers, dataRows)
        }
    }

    private suspend fun processImageAsync(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText) }
                .addOnFailureListener { exception -> cont.resumeWithException(exception) }
        }
}
