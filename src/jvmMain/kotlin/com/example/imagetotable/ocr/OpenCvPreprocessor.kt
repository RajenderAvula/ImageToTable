// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ocr/OpenCvPreprocessor.kt
package com.example.imagetotable.ocr

import nu.pattern.OpenCV
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

object OpenCvPreprocessor {

    init {
        OpenCV.loadLocally()
    }

    data class TableProcessingContext(
        val cleanedTextImage: BufferedImage,
        val cellMatrix: List<List<CellDetector.CellBox>>
    )

    fun process(imageFile: File): TableProcessingContext {
        val src = Imgcodecs.imread(imageFile.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!src.empty()) { "Failed to load image at: ${imageFile.absolutePath}" }

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

        // Otsu Binarization (Inverted: White text & borders on Black)
        val binary = Mat()
        Imgproc.threshold(
            gray,
            binary,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
        )

        val scale = 30
        val horizontalSize = (binary.cols() / scale).coerceAtLeast(10)
        val verticalSize = (binary.rows() / scale).coerceAtLeast(10)

        // Morphological filtering to isolate grid lines
        val hStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(horizontalSize.toDouble(), 1.0))
        val horizontalLines = Mat()
        Imgproc.erode(binary, horizontalLines, hStructure)
        Imgproc.dilate(horizontalLines, horizontalLines, hStructure)

        val vStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, verticalSize.toDouble()))
        val verticalLines = Mat()
        Imgproc.erode(binary, verticalLines, vStructure)
        Imgproc.dilate(verticalLines, verticalLines, vStructure)

        // Combined grid mask
        val tableGrid = Mat()
        Core.add(horizontalLines, verticalLines, tableGrid)

        // Detect exact cell boundaries using contours on the grid mask
        val cellMatrix = CellDetector.detectCells(tableGrid)

        // Subtract table grid from binary image to leave text only
        val textOnly = Mat()
        Core.subtract(binary, tableGrid, textOnly)

        // Dilation to heal strokes that intersected grid lines
        val repairKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.dilate(textOnly, textOnly, repairKernel)

        // Bitwise NOT to output standard black text on white background for Tesseract
        val finalOcrMat = Mat()
        Core.bitwise_not(textOnly, finalOcrMat)

        val cleanedImage = matToBufferedImage(finalOcrMat)

        // Cleanup native OpenCV matrices
        src.release()
        gray.release()
        binary.release()
        hStructure.release()
        horizontalLines.release()
        vStructure.release()
        verticalLines.release()
        tableGrid.release()
        textOnly.release()
        repairKernel.release()
        finalOcrMat.release()

        return TableProcessingContext(
            cleanedTextImage = cleanedImage,
            cellMatrix = cellMatrix
        )
    }

    private fun matToBufferedImage(mat: Mat): BufferedImage {
        val mob = MatOfByte()
        Imgcodecs.imencode(".png", mat, mob)
        val byteArray = mob.toArray()
        mob.release()
        return ImageIO.read(ByteArrayInputStream(byteArray))
    }
}
