package com.example.imagetotable.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidOcrService(private val context: Context) {

    suspend fun extractTable(bitmap: Bitmap): Pair<List<String>, List<List<String>>> =
        withContext(Dispatchers.Default) {
            val basePath = MobileTessData.getOrExtractTessBasePath(context)
            val tess = TessBaseAPI()

            try {
                val initialized = tess.init(basePath, "eng")
                if (!initialized) {
                    throw IllegalStateException("Failed to initialize Tesseract with language 'eng'.")
                }

                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setImage(bitmap)
                val rawText = tess.utF8Text ?: ""
                tess.clear()

                parseRawTextToTable(rawText)
            } finally {
                tess.recycle()
            }
        }

    private fun parseRawTextToTable(rawText: String): Pair<List<String>, List<List<String>>> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("^[\\-_+=|\\s]+$")) } // Filter divider lines

        if (lines.isEmpty()) {
            return Pair(listOf("Col 1", "Col 2"), emptyList())
        }

        // Split columns by tabs, pipes (|), or 2+ consecutive spaces
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
