// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ocr/OcrTableExtractor.kt
package com.example.imagetotable.ocr

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

data class ExtractedTableResult(
    val originalImage: BufferedImage,
    val cellMatrix: List<List<CellDetector.CellBox>>,
    val headers: List<String>,
    val rows: List<List<String>>
)

object OcrTableExtractor {
    private val tesseract = Tesseract().apply {
        val tessDataEnv = System.getenv("TESSDATA_PREFIX") ?: "./tessdata"
        setDatapath(tessDataEnv)
        setLanguage("eng")
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK)
    }
// In OcrTableExtractor.kt
fun extractTextForCell(image: java.awt.image.BufferedImage, rect: java.awt.Rectangle): String {
    return try {
        tesseract.doOCR(image, rect).replace("\n", " ").trim()
    } catch (_: Exception) {
        ""
    }
}

    fun extractTableFromImage(imageFile: File): ExtractedTableResult {
        val originalImage: BufferedImage = ImageIO.read(imageFile)
            ?: throw IllegalArgumentException("Cannot decode image: ${imageFile.absolutePath}")

        val context = OpenCvPreprocessor.process(imageFile)
        val ocrImage = context.cleanedTextImage
        val cellMatrix = context.cellMatrix

        if (cellMatrix.isEmpty()) {
            return ExtractedTableResult(
                originalImage = originalImage,
                cellMatrix = emptyList(),
                headers = listOf("Column 1"),
                rows = emptyList()
            )
        }

        val maxCols = cellMatrix.maxOfOrNull { it.size } ?: 1

        val parsedRows = cellMatrix.map { rowCells ->
            val rowValues = rowCells.map { cell ->
                try {
                    val cellText = tesseract.doOCR(ocrImage, cell.awtRectangle)
                    cellText.replace("\n", " ").trim()
                } catch (_: Exception) {
                    ""
                }
            }.toMutableList()

            while (rowValues.size < maxCols) {
                rowValues.add("")
            }
            rowValues
        }

        val rawHeaders = parsedRows.firstOrNull() ?: emptyList()
        val headers = rawHeaders.mapIndexed { idx, text ->
            text.ifBlank { "Column ${idx + 1}" }
        }
        val dataRows = if (parsedRows.size > 1) parsedRows.drop(1) else emptyList()

        return ExtractedTableResult(
            originalImage = originalImage,
            cellMatrix = cellMatrix,
            headers = headers,
            rows = dataRows
        )
    }
}
