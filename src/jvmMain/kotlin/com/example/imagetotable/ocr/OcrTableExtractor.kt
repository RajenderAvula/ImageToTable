// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ocr/OcrTableExtractor.kt
package com.example.imagetotable.ocr

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.Word
import java.awt.Rectangle
import java.io.File
import kotlin.math.abs

data class ExtractedTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

object OcrTableExtractor {
    private val tesseract = Tesseract().apply {
        val tessDataEnv = System.getenv("TESSDATA_PREFIX") ?: "./tessdata"
        setDatapath(tessDataEnv)
        setLanguage("eng")
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO)
    }

    fun extractTableFromImage(imageFile: File): ExtractedTable {
        // Preprocess through OpenCV: Otsu thresholding + border subtraction
        val preprocessed = OpenCvPreprocessor.process(imageFile)
        val ocrReadyImage = preprocessed.cleanedTextImage

        // Retrieve words with coordinates from preprocessed image
        val words: List<Word> = tesseract.getWords(ocrReadyImage, ITessAPI.TessPageIteratorLevel.RIL_WORD)
            .filter { it.text.isNotBlank() }

        if (words.isEmpty()) {
            return ExtractedTable(headers = listOf("Column 1"), rows = emptyList())
        }

        // Sort words top-to-bottom, left-to-right
        val sortedWords = words.sortedWith(
            compareBy<Word> { it.boundingBox.y }.thenBy { it.boundingBox.x }
        )

        // Cluster words into rows based on line-height overlap
        val rawRows = mutableListOf<MutableList<Word>>()
        for (word in sortedWords) {
            val matchingRow = rawRows.firstOrNull { rowWords ->
                val avgY = rowWords.map { it.boundingBox.y }.average()
                val avgH = rowWords.map { it.boundingBox.height }.average()
                val threshold = (avgH * 0.6).coerceAtLeast(10.0)
                abs(word.boundingBox.y - avgY) <= threshold
            }

            if (matchingRow != null) {
                matchingRow.add(word)
            } else {
                rawRows.add(mutableListOf(word))
            }
        }

        rawRows.sortBy { row -> row.minOf { it.boundingBox.y } }

        // Tokenize cells by horizontal gap distance
        val structuredRows = rawRows.map { rowWords ->
            rowWords.sortBy { it.boundingBox.x }
            val cells = mutableListOf<String>()
            val currentCell = StringBuilder()
            var prevBox: Rectangle? = null

            for (w in rowWords) {
                val box = w.boundingBox
                if (prevBox == null) {
                    currentCell.append(w.text.trim())
                } else {
                    val gap = box.x - (prevBox.x + prevBox.width)
                    val spaceThreshold = (prevBox.height * 0.9).coerceAtLeast(25.0)

                    if (gap > spaceThreshold) {
                        cells.add(currentCell.toString())
                        currentCell.clear()
                        currentCell.append(w.text.trim())
                    } else {
                        currentCell.append(" ").append(w.text.trim())
                    }
                }
                prevBox = box
            }
            if (currentCell.isNotEmpty()) {
                cells.add(currentCell.toString())
            }
            cells
        }

        val maxCols = structuredRows.maxOfOrNull { it.size } ?: 1
        val normalizedRows = structuredRows.map { row ->
            row + List(maxCols - row.size) { "" }
        }

        val headers = normalizedRows.firstOrNull()?.mapIndexed { idx, value ->
            value.ifBlank { "Column ${idx + 1}" }
        } ?: List(maxCols) { "Column ${it + 1}" }

        val dataRows = if (normalizedRows.size > 1) normalizedRows.drop(1) else emptyList()

        return ExtractedTable(headers = headers, rows = dataRows)
    }
}
