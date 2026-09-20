package com.example.imagetotable.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

class AndroidOcrService(private val context: Context) {
    private var tessBaseAPI: TessBaseAPI? = null

    suspend fun initEngine() {
        val basePath = MobileTessData.getOrExtractTessBasePath(context)
        tessBaseAPI = TessBaseAPI().apply {
            // basePath points to /data/user/0/<pkg>/files/tesseract
            // which contains the subdirectory 'tessdata/eng.traineddata'
            init(basePath, "eng")
            pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        }
    }

    fun extractTextFromBitmap(bitmap: Bitmap): String {
        val api = tessBaseAPI ?: throw IllegalStateException("TessBaseAPI is not initialized.")
        api.setImage(bitmap)
        val extractedText = api.utF8Text
        api.clear()
        return extractedText
    }

    fun release() {
        tessBaseAPI?.recycle()
        tessBaseAPI = null
    }
}
