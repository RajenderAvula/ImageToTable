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
    }

    suspend fun extractTable(bitmap: Bitmap): Pair<List<String>, List<List<String>>> =
        withContext(Dispatchers.Default) {
            val basePath = MobileTessData.getOrExtractTessBasePath(context, onStatusUpdate)

            withContext(Dispatchers.Main) {
                onStatusUpdate("Running OCR recognition...")
            }

            val tess = TessBaseAPI()
            try {
                val initialized = tess.init(basePath, "eng")
                if (!initialized) {
                    throw IllegalStateException("Failed to initialize Tesseract with language 'eng'.")
                }

                // PSM_AUTO preserves tabular row layout and side-by-side positioning
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setImage(bitmap)
                val rawText = tess.utF8Text ?: ""

                // Extract individual words with 2D coordinates for spatial row clustering
                val words = mutableListOf<WordItem>()
                val iterator = tess.resultIterator
                if (iterator != null) {
                    iterator.begin()
                    do {
                        val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!word.isNullOrBlank() && box != null && box.size >= 4) {
                            words.add(WordItem(word.trim(), box[0], box[1], box[2], box[3]))
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                }
                tess.clear()

                // If bounding boxes were extracted, cluster horizontally adjacent words into the same row
                if (words.isNotEmpty()) {
                    words.sortBy { it.top }

                    val rowClusters = mutableListOf<MutableList<WordItem>>()
                    for (w in words) {
                        val match = rowClusters.firstOrNull { cluster ->
                            val avgY = cluster.map { it.centerY }.average()
                            val avgH = cluster.map { it.height }.average().coerceAtLeast(14.0)
                            abs(w.centerY - avgY) <= (avgH * 0.70)
                        }
                        if (match != null) {
                            match.add(w)
                        } else {
                            rowClusters.add(mutableListOf(w))
                        }
                    }

                    // Sort rows from top to bottom, then words within each row left to right
                    rowClusters.sortBy { cluster -> cluster.map { it.top }.minOrNull() ?: 0 }
                    val clusteredRows = rowClusters.map { cluster ->
                        cluster.sortBy { it.left }
                        cluster.map { it.text }
                    }

                    val maxCols = clusteredRows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                    val firstRow = clusteredRows.firstOrNull() ?: emptyList()
                    val headers = (0 until maxCols).map { c ->
                        firstRow.getOrElse(c) { "Column ${c + 1}" }.ifBlank { "Column ${c + 1}" }
                    }
                    val dataRows = if (clusteredRows.size > 1) {
                        clusteredRows.drop(1).map { r ->
                            val padded = r + List((maxCols - r.size).coerceAtLeast(0)) { "" }
                            padded.take(maxCols)
                        }
                    } else emptyList()

                    Pair(headers, dataRows)
                } else {
                    // Fallback to text parsing if iterator was empty
                    parseRawTextToTable(rawText)
                }
            } finally {
                tess.recycle()
            }
        }

    private fun parseRawTextToTable(rawText: String): Pair<List<String>, List<List<String>>> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("^[\\-_+=|\\s]+$")) }

        if (lines.isEmpty()) {
            return Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        val parsedRows = lines.map { line ->
            line.trim('|')
                .split(Regex("\\s*\\|\\s*|\\t+|\\s{2,}"))
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
