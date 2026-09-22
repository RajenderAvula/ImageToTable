package com.example.imagetotable.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AndroidOcrService(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit = {}
) {

    data class WordItem(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val width: Int get() = (right - left).coerceAtLeast(1)
    }

    suspend fun extractTable(bitmap: Bitmap): Pair<List<String>, List<List<String>>> =
        withContext(Dispatchers.Default) {
            val basePath = MobileTessData.getOrExtractTessBasePath(context, onStatusUpdate)

            withContext(Dispatchers.Main) {
                onStatusUpdate("Enhancing image contrast & binarizing...")
            }

            // Clean image of shadows and background noise
            val preprocessedBitmap = ImagePreProcessor.prepareForOcr(bitmap)

            withContext(Dispatchers.Main) {
                onStatusUpdate("Running OCR recognition...")
            }

            val tess = TessBaseAPI()
            try {
                val initialized = tess.init(basePath, "eng")
                if (!initialized) {
                    throw IllegalStateException("Failed to initialize Tesseract with language 'eng'.")
                }

                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setVariable("preserve_interword_spaces", "1")
                tess.setImage(preprocessedBitmap)
                val rawText = tess.utF8Text ?: ""

                val words = mutableListOf<WordItem>()
                val iterator = tess.resultIterator
                if (iterator != null) {
                    iterator.begin()
                    do {
                        val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!word.isNullOrBlank() && box != null && box.size >= 4) {
                            val cleanWord = word.trim()
                            if (cleanWord.isNotEmpty()) {
                                words.add(WordItem(cleanWord, box[0], box[1], box[2], box[3]))
                            }
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                }
                tess.clear()

                if (words.isNotEmpty()) {
                    // 1. Group words vertically into Rows based on vertical line overlap
                    words.sortBy { it.top }
                    val rowClusters = mutableListOf<MutableList<WordItem>>()
                    for (w in words) {
                        val matchingRow = rowClusters.firstOrNull { cluster ->
                            val avgY = cluster.map { it.centerY }.average()
                            val avgH = cluster.map { it.height }.average().coerceAtLeast(14.0)
                            abs(w.centerY - avgY) <= (avgH * 0.65)
                        }
                        if (matchingRow != null) {
                            matchingRow.add(w)
                        } else {
                            rowClusters.add(mutableListOf(w))
                        }
                    }

                    // Sort rows top-to-bottom
                    rowClusters.sortBy { cluster -> cluster.map { it.top }.minOrNull() ?: 0 }

                    // 2. Group horizontally adjacent words into Cells (Prevents splitting names into 2 columns)
                    val tableRows = rowClusters.map { cluster ->
                        cluster.sortBy { it.left }

                        val cells = mutableListOf<String>()
                        var currentCell = StringBuilder()
                        var prevWord: WordItem? = null

                        for (w in cluster) {
                            if (prevWord == null) {
                                currentCell.append(w.text)
                            } else {
                                val gap = w.left - prevWord.right
                                val avgCharWidth = (prevWord.width.toFloat() / prevWord.text.length.coerceAtLeast(1))
                                    .coerceAtLeast(8f)

                                // If the horizontal gap is larger than ~2.8 characters, treat it as a new column.
                                // Otherwise, it is an intra-cell space (e.g. "Ballpoint" + "Pens").
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

                    Pair(headers, dataRows)
                } else {
                    parseRawTextFallback(rawText)
                }
            } finally {
                tess.recycle()
            }
        }

    private fun parseRawTextFallback(rawText: String): Pair<List<String>, List<List<String>>> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("^[\\-_+=|\\s]+$")) }

        if (lines.isEmpty()) {
            return Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        // Use tabs or pipes as strict column delimiters, and only wide 4+ spaces for gaps
        val parsedRows = lines.map { line ->
            line.trim('|')
                .split(Regex("\\s*\\|\\s*|\\t+|\\s{4,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }

        if (parsedRows.isEmpty()) {
            return Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        val maxCols = parsedRows.maxOf { it.size }.coerceAtLeast(1)
        val normalizedRows = parsedRows.map { row ->
            row + List(maxCols - row.size) { "" }
        }

        val headers = normalizedRows.first().mapIndexed { idx, col ->
            col.ifBlank { "Column ${idx + 1}" }
        }
        val dataRows = if (normalizedRows.size > 1) normalizedRows.drop(1) else emptyList()

        return Pair(headers, dataRows)
    }
}
