package com.example.imagetotable.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object MobileTessData {
    private const val PARENT_FOLDER = "tesseract"
    private const val TESSDATA_FOLDER = "tessdata"
    private const val TRAINED_DATA_FILE = "eng.traineddata"

    suspend fun getOrExtractTessBasePath(context: Context): String = withContext(Dispatchers.IO) {
        val parentDir = File(context.filesDir, PARENT_FOLDER)
        val tessDataDir = File(parentDir, TESSDATA_FOLDER)

        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        val targetFile = File(tessDataDir, TRAINED_DATA_FILE)

        // Copy eng.traineddata from assets/tessdata/ to internal filesDir if not already present
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open("$TESSDATA_FOLDER/$TRAINED_DATA_FILE").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        parentDir.absolutePath
    }
}
