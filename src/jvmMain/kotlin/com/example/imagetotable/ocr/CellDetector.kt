// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ocr/CellDetector.kt
package com.example.imagetotable.ocr

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.awt.Rectangle
import kotlin.math.abs

object CellDetector {

    data class CellBox(
        val rect: Rect,
        val awtRectangle: Rectangle
    )

    /**
     * Finds individual cell bounding boxes from the binary table grid mask.
     * Returns a 2D matrix: List<Row> where each Row is List<CellBox> sorted left-to-right.
     */
    fun detectCells(tableGridMat: Mat): List<List<CellBox>> {
        // 1. Invert the grid mask: Cells become white blobs enclosed by black borders
        val invertedGrid = Mat()
        Core.bitwise_not(tableGridMat, invertedGrid)

        // 2. Find external contours of all cell cavities
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            invertedGrid,
            contours,
            hierarchy,
            Imgproc.RETR_CCOMP,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val totalArea = tableGridMat.cols() * tableGridMat.rows()
        val minCellArea = (totalArea * 0.0005).coerceAtLeast(200.0) // Ignore small specs
        val maxCellArea = totalArea * 0.85                         // Ignore full-canvas contour

        val detectedBoxes = mutableListOf<Rect>()

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.area()

            // Filter out non-cell contours
            if (area in minCellArea..maxCellArea && rect.width > 20 && rect.height > 12) {
                // Inset box by 2px on each edge to trim border artifacts
                val insetX = (rect.x + 2).coerceAtMost(tableGridMat.cols() - 1)
                val insetY = (rect.y + 2).coerceAtMost(tableGridMat.rows() - 1)
                val insetW = (rect.width - 4).coerceAtLeast(1)
                val insetH = (rect.height - 4).coerceAtLeast(1)

                detectedBoxes.add(Rect(insetX, insetY, insetW, insetH))
            }
            contour.release()
        }

        invertedGrid.release()
        hierarchy.release()

        if (detectedBoxes.isEmpty()) return emptyList()

        // 3. Cluster bounding boxes into rows based on vertical coordinate overlap
        // Sort primarily by Y to process from top to bottom
        detectedBoxes.sortBy { it.y }

        val clusteredRows = mutableListOf<MutableList<Rect>>()
        for (box in detectedBoxes) {
            val matchingRow = clusteredRows.firstOrNull { rowBoxes ->
                val avgY = rowBoxes.map { it.y }.average()
                val avgHeight = rowBoxes.map { it.height }.average()
                val tolerance = avgHeight * 0.4
                abs(box.y - avgY) <= tolerance
            }

            if (matchingRow != null) {
                matchingRow.add(box)
            } else {
                clusteredRows.add(mutableListOf(box))
            }
        }

        // 4. Sort rows top-to-bottom, and cells within each row left-to-right
        clusteredRows.sortBy { row -> row.map { it.y }.average() }

        return clusteredRows.map { row ->
            row.sortedBy { it.x }.map { rect ->
                CellBox(
                    rect = rect,
                    awtRectangle = Rectangle(rect.x, rect.y, rect.width, rect.height)
                )
            }
        }
    }
}
