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
        // Automatically unpacks and links the correct native OS libraries
        OpenCV.loadLocally()
    }

    data class PreprocessResult(
        val cleanedTextImage: BufferedImage,
        val tableGridMask: BufferedImage
    )

    /**
     * Reads an image file, extracts table structure via morphology,
     * strips grid borders from text, and returns a binarized BufferedImage ready for Tess4J.
     */
    fun process(imageFile: File): PreprocessResult {
        val src = Imgcodecs.imread(imageFile.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!src.empty()) { "Failed to load image at: ${imageFile.absolutePath}" }

        // 1. Convert to Grayscale
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

        // 2. Otsu Thresholding (Inverted: White text & borders on Black background)
        val binary = Mat()
        Imgproc.threshold(
            gray,
            binary,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
        )

        // 3. Define adaptive kernel sizes based on image dimensions
        val scale = 30 // Higher = detects longer lines only; Lower = detects shorter lines
        val horizontalSize = (binary.cols() / scale).coerceAtLeast(10)
        val verticalSize = (binary.rows() / scale).coerceAtLeast(10)

        // 4. Extract Horizontal Table Lines
        val horizontalStructure = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(horizontalSize.toDouble(), 1.0)
        )
        val horizontalLines = Mat()
        Imgproc.erode(binary, horizontalLines, horizontalStructure)
        Imgproc.dilate(horizontalLines, horizontalLines, horizontalStructure)

        // 5. Extract Vertical Table Lines
        val verticalStructure = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, verticalSize.toDouble())
        )
        val verticalLines = Mat()
        Imgproc.erode(binary, verticalLines, verticalStructure)
        Imgproc.dilate(verticalLines, verticalLines, verticalStructure)

        // 6. Combine Horizontal and Vertical lines into a Table Grid Mask
        val tableGrid = Mat()
        Core.add(horizontalLines, verticalLines, tableGrid)

        // 7. Remove Grid Lines from Binary Image: (Binary - Grid) leaves pure text
        val textOnly = Mat()
        Core.subtract(binary, tableGrid, textOnly)

        // 8. Slight Dilation to repair strokes cut during line subtraction
        val repairKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.dilate(textOnly, textOnly, repairKernel)

        // 9. Invert back to Black text on White background for Tess4J
        val finalOcrMat = Mat()
        Core.bitwise_not(textOnly, finalOcrMat)

        // Convert Mats to BufferedImages
        val cleanedImg = matToBufferedImage(finalOcrMat)
        val gridImg = matToBufferedImage(tableGrid)

        // Release native OpenCV memory
        src.release()
        gray.release()
        binary.release()
        horizontalStructure.release()
        horizontalLines.release()
        verticalStructure.release()
        verticalLines.release()
        tableGrid.release()
        textOnly.release()
        repairKernel.release()
        finalOcrMat.release()

        return PreprocessResult(cleanedTextImage = cleanedImg, tableGridMask = gridImg)
    }

    private fun matToBufferedImage(mat: Mat): BufferedImage {
        val mob = MatOfByte()
        Imgcodecs.imencode(".png", mat, mob)
        val byteArray = mob.toArray()
        mob.release()
        return ImageIO.read(ByteArrayInputStream(byteArray))
    }
}
