package com.example.imagetotable.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object ImagePreProcessor {

    /**
     * Preprocesses bitmap for OCR:
     * 1. Scales up small crops so character height is at least ~30-35px.
     * 2. Converts to Grayscale.
     * 3. Applies Otsu's thresholding (binarization) to strip background noise and shadows.
     */
    fun prepareForOcr(source: Bitmap): Bitmap {
        // 1. Ensure minimum resolution for OCR accuracy
        val minDimension = 1000
        val scale = if (source.width < minDimension && source.height < minDimension) {
            val factor = minDimension.toFloat() / maxOf(source.width, source.height)
            factor.coerceAtMost(3.0f)
        } else {
            1.0f
        }

        val scaledBitmap = if (scale > 1.0f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true
            )
        } else {
            source
        }

        // 2. Grayscale conversion
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayBitmap)
        val paint = Paint().apply {
            val cm = ColorMatrix().apply { setSaturation(0f) }
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        // 3. Fast Otsu Binarization (Black/White thresholding)
        val pixels = IntArray(width * height)
        grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val red = (pixels[i] shr 16) and 0xFF
            histogram[red]++
        }

        val total = width * height
        var sum = 0f
        for (t in 0..255) sum += t * histogram[t]

        var sumB = 0f
        var wB = 0
        var varMax = 0f
        var threshold = 128

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += (t * histogram[t]).toFloat()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val varBetween = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        for (i in pixels.indices) {
            val red = (pixels[i] shr 16) and 0xFF
            val binVal = if (red > threshold) 0xFF else 0x00
            pixels[i] = (0xFF shl 24) or (binVal shl 16) or (binVal shl 8) or binVal
        }

        val binarized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binarized.setPixels(pixels, 0, width, 0, 0, width, height)
        return binarized
    }
}
