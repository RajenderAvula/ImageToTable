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

    private data class DetectedGrid(
        val hLines: List<Float>,
        val vLines: List<Float>
    ) {
        val rows: Int get() = (hLines.size - 1).coerceAtLeast(0)
        val cols: Int get() = (vLines.size - 1).coerceAtLeast(0)

        fun findRowIndex(y: Float): Int {
            for (i in 0 until rows) {
                if (y >= hLines[i] && y <= hLines[i + 1]) return i
            }
            return -1
        }

        fun findColIndex(x: Float): Int {
            for (j in 0 until cols) {
                if (x >= vLines[j] && x <= vLines[j + 1]) return j
            }
            return -1
        }
    }

    suspend fun extractTable(
        bitmap: Bitmap,
        excludeHeaders: Boolean = false
    ): Pair<List<String>, List<List<String>>> = withContext(Dispatchers.Default) {
        withContext(Dispatchers.Main) {
            onStatusUpdate("Analyzing table layout, columns & multiline cells...")
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = processImageAsync(inputImage)

        if (visionText.textBlocks.isEmpty()) {
            return@withContext Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        // 1. Detect if physical grid border lines exist
        val detectedGrid = detectTableBorders(bitmap)

        if (detectedGrid != null && detectedGrid.rows > 1 && detectedGrid.cols > 0) {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Extracting bordered cells as single tokens...")
            }
            // Within border lines: words separated by space in a cell form a single token
            return@withContext extractBorderedTable(visionText, detectedGrid, excludeHeaders)
        }

        withContext(Dispatchers.Main) {
            onStatusUpdate("Extracting borderless text elements...")
        }

        // 2. Borderless mode: Collect individual space-separated words as discrete elements
        // "Jane Doe is a good person" on a single line produces 6 separate elements
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

        // 3. Global Column Interval Detection (Distinguishes individual words across the line)
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

        // 4. Row Clustering with Multi-Line Single-Cell Detection
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

        // 5. Map Elements into Column Buckets & Concatenate Multi-Line Cells
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

            // Concatenate vertically stacked lines into a single cell, keeping horizontal words distinct
            val rowValues = cellBuckets.map { bucketElements ->
                if (bucketElements.isEmpty()) {
                    ""
                } else {
                    bucketElements.sortWith(compareBy<TextElementBox> { it.rect.top }.thenBy { it.rect.left })

                    val cellBuilder = StringBuilder()
                    for (b in bucketElements) {
                        if (cellBuilder.isEmpty()) {
                            cellBuilder.append(b.text)
                        } else {
                            cellBuilder.append(" ").append(b.text)
                        }
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

        // 6. Exclude Headers vs Parse Headers
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

    /**
     * Extracts tokens across the table.
     * In bordered mode: words inside a border cell are grouped into 1 token.
     * In borderless mode: words in "Jane Doe is a good person" are returned as 6 separate tokens.
     */
    suspend fun extractTokens(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        val (headers, rows) = extractTable(bitmap, excludeHeaders = false)
        val tokens = mutableListOf<String>()
        headers.filter { it.isNotBlank() }.forEach { tokens.add(it) }
        rows.flatten().filter { it.isNotBlank() }.forEach { tokens.add(it) }
        tokens
    }

    // -----------------------------------------------------------------------------------------
    // BORDERED EXTRACTION: All space-separated words within a cell border form a single token
    // -----------------------------------------------------------------------------------------
    private fun extractBorderedTable(
        visionText: Text,
        grid: DetectedGrid,
        excludeHeaders: Boolean
    ): Pair<List<String>, List<List<String>>> {
        val cellMatrix = Array(grid.rows) { Array(grid.cols) { mutableListOf<String>() } }

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (elem in line.elements) {
                    val box = elem.boundingBox ?: continue
                    val centerX = box.centerX().toFloat()
                    val centerY = box.centerY().toFloat()

                    val rIdx = grid.findRowIndex(centerY)
                    val cIdx = grid.findColIndex(centerX)

                    if (rIdx in 0 until grid.rows && cIdx in 0 until grid.cols) {
                        val elemText = elem.text.trim()
                        if (elemText.isNotBlank()) {
                            cellMatrix[rIdx][cIdx].add(elemText)
                        }
                    }
                }
            }
        }

        // Words separated by space within the same bordered cell are joined into a single token
        val rawRows = cellMatrix.map { rowCells ->
            rowCells.map { cellWords ->
                cellWords.joinToString(" ").trim()
            }
        }

        val nonEmptyRows = rawRows.filter { row -> row.any { it.isNotBlank() } }

        if (nonEmptyRows.isEmpty()) {
            return Pair(List(grid.cols) { "Col ${it + 1}" }, emptyList())
        }

        return if (excludeHeaders) {
            val headers = List(grid.cols) { "Col ${it + 1}" }
            Pair(headers, nonEmptyRows)
        } else {
            val headers = nonEmptyRows.first().mapIndexed { idx, cellText ->
                cellText.ifBlank { "Col ${idx + 1}" }
            }
            val dataRows = if (nonEmptyRows.size > 1) nonEmptyRows.drop(1) else emptyList()
            Pair(headers, dataRows)
        }
    }

    // -----------------------------------------------------------------------------------------
    // BORDER DETECTION VIA PROJECTION PROFILES
    // -----------------------------------------------------------------------------------------
    private fun detectTableBorders(bitmap: Bitmap): DetectedGrid? {
        val sampleW = 400
        val sampleH = (bitmap.height * (400f / bitmap.width)).toInt().coerceAtLeast(100)
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val hProfile = IntArray(sampleH)
        val vProfile = IntArray(sampleW)

        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val pixel = scaled.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                if (luminance < 140) {
                    hProfile[y]++
                    vProfile[x]++
                }
            }
        }

        val hThreshold = (sampleW * 0.40).toInt()
        val vThreshold = (sampleH * 0.35).toInt()

        val scaleXBack = bitmap.width.toFloat() / sampleW
        val scaleYBack = bitmap.height.toFloat() / sampleH

        val hLines = extractLinePeaks(hProfile, hThreshold).map { it * scaleYBack }
        val vLines = extractLinePeaks(vProfile, vThreshold).map { it * scaleXBack }

        return if (hLines.size >= 2 && vLines.size >= 2) {
            DetectedGrid(
                hLines = hLines.sorted(),
                vLines = vLines.sorted()
            )
        } else {
            null
        }
    }

    private fun extractLinePeaks(profile: IntArray, threshold: Int): List<Float> {
        val peaks = mutableListOf<Float>()
        var inPeak = false
        var peakStart = 0

        for (i in profile.indices) {
            if (profile[i] >= threshold) {
                if (!inPeak) {
                    inPeak = true
                    peakStart = i
                }
            } else {
                if (inPeak) {
                    peaks.add((peakStart + i - 1) / 2f)
                    inPeak = false
                }
            }
        }
        if (inPeak) {
            peaks.add((peakStart + profile.size - 1) / 2f)
        }
        return peaks
    }

    private suspend fun processImageAsync(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText) }
                .addOnFailureListener { exception -> cont.resumeWithException(exception) }
        }
}
