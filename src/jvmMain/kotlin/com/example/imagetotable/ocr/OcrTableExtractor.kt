// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ocr/OcrTableExtractor.kt
package com.example.imagetotable.ocr

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import java.io.File

data class ExtractedTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

object OcrTableExtractor {
    private val tesseract = Tesseract().apply {
        val tessDataEnv = System.getenv("TESSDATA_PREFIX") ?: "./tessdata"
        setDatapath(tessDataEnv)
        setLanguage("eng")
        // PSM_SINGLE_BLOCK or PSM_SINGLE_LINE works best inside individual cropped cells
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK)
    }

    fun extractTableFromImage(imageFile: File): ExtractedTable {
        val context = OpenCvPreprocessor.process(imageFile)
        val ocrImage = context.cleanedTextImage
        val cellMatrix = context.cellMatrix

        if (cellMatrix.isEmpty()) {
            return ExtractedTable(headers = listOf("Column 1"), rows = emptyList())
        }

        // Determine the maximum number of columns across all detected rows
        val maxCols = cellMatrix.maxOfOrNull { it.size } ?: 1

        // Execute OCR per cell coordinate box
        val parsedRows = cellMatrix.map { rowCells ->
            val rowValues = rowCells.map { cell ->
                try {
                    // Target OCR to the exact bounding box of this cell
                    val cellText = tesseract.doOCR(ocrImage, cell.awtRectangle)
                    cellText.replace("\n", " ").trim()
                } catch (_: Exception) {
                    ""
                }
            }.toMutableList()

            // Pad rows with fewer columns so the table forms an even matrix
            while (rowValues.size < maxCols) {
                rowValues.add("")
            }
            rowValues
        }

        // Designate the first physical row as header, remaining rows as body
        val rawHeaders = parsedRows.firstOrNull() ?: emptyList()
        val headers = rawHeaders.mapIndexed { idx, text ->
            text.ifBlank { "Column ${idx + 1}" }
        }

        val dataRows = if (parsedRows.size > 1) parsedRows.drop(1) else emptyList()

        return ExtractedTable(headers = headers, rows = dataRows)
    }
}
