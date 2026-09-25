package com.example.imagetotable.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

data class PdfPageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    var bitmap: Bitmap,
    var rotationDegrees: Float = 0f
)

object PdfCompressorExporter {

    /**
     * Compiles pages into a PDF. If targetSizeKb is provided and > 0,
     * it iteratively searches for the best (scale, quality) to fit within targetSizeKb.
     */
    suspend fun exportPagesToPdf(
        pages: List<PdfPageItem>,
        targetSizeKb: Int?,
        outputStream: OutputStream,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext

        // Apply page-level rotations first
        val orientedBitmaps = pages.map { page ->
            if (page.rotationDegrees % 360f != 0f) {
                val matrix = Matrix().apply { postRotate(page.rotationDegrees) }
                Bitmap.createBitmap(page.bitmap, 0, 0, page.bitmap.width, page.bitmap.height, matrix, true)
            } else {
                page.bitmap
            }
        }

        if (targetSizeKb == null || targetSizeKb <= 0) {
            withContext(Dispatchers.Main) { onProgress("Compiling high-quality PDF...") }
            val rawPdfBytes = buildPdfByteArray(orientedBitmaps, scale = 1.0f, jpegQuality = 92)
            outputStream.write(rawPdfBytes)
            outputStream.flush()
            return@withContext
        }

        val targetSizeBytes = targetSizeKb * 1024L
        withContext(Dispatchers.Main) { onProgress("Optimizing for target size: ${targetSizeKb} KB...") }

        // Binary search bounds on quality and scale
        var minQuality = 15
        var maxQuality = 90
        var bestPdfBytes: ByteArray? = null

        // Pass 1: Try full scale with quality adjustments
        for (step in 0..4) {
            val candidateQuality = (minQuality + maxQuality) / 2
            val candidatePdf = buildPdfByteArray(orientedBitmaps, scale = 1.0f, jpegQuality = candidateQuality)

            if (candidatePdf.size <= targetSizeBytes) {
                bestPdfBytes = candidatePdf
                minQuality = candidateQuality + 1 // Try higher quality
            } else {
                maxQuality = candidateQuality - 1 // Reduce quality
            }
        }

        // Pass 2: If still too large, downscale image dimensions
        if (bestPdfBytes == null || bestPdfBytes.size > targetSizeBytes) {
            var scale = 0.8f
            while (scale >= 0.3f && (bestPdfBytes == null || bestPdfBytes.size > targetSizeBytes)) {
                val candidatePdf = buildPdfByteArray(orientedBitmaps, scale = scale, jpegQuality = 60)
                bestPdfBytes = candidatePdf
                if (candidatePdf.size <= targetSizeBytes) break
                scale -= 0.15f
            }
        }

        val finalBytes = bestPdfBytes ?: buildPdfByteArray(orientedBitmaps, scale = 0.4f, jpegQuality = 35)
        outputStream.write(finalBytes)
        outputStream.flush()
    }

    private fun buildPdfByteArray(
        bitmaps: List<Bitmap>,
        scale: Float,
        jpegQuality: Int
    ): ByteArray {
        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        try {
            bitmaps.forEachIndexed { index, original ->
                // Compress & downsample bitmap
                val scaledW = (original.width * scale).roundToInt().coerceAtLeast(100)
                val scaledH = (original.height * scale).roundToInt().coerceAtLeast(100)
                val scaled = Bitmap.createScaledBitmap(original, scaledW, scaledH, true)

                val compressedStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, compressedStream)
                val compressedBytes = compressedStream.toByteArray()
                val decodedPageBmp = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                val pageInfo = PdfDocument.PageInfo.Builder(decodedPageBmp.width, decodedPageBmp.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(decodedPageBmp, 0f, 0f, paint)
                pdfDocument.finishPage(page)
            }

            val pdfByteStream = ByteArrayOutputStream()
            pdfDocument.writeTo(pdfByteStream)
            return pdfByteStream.toByteArray()
        } finally {
            pdfDocument.close()
        }
    }
}
