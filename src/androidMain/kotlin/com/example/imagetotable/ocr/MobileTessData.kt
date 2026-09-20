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

    /**
     * Copies eng.traineddata to internal app storage if not present.
     * Returns the absolute path to the parent directory containing the "tessdata" folder.
     */
    suspend fun getOrExtractTessBasePath(context: Context): String = withContext(Dispatchers.IO) {
        val parentDir = File(context.filesDir, PARENT_FOLDER)
        val tessDataDir = File(parentDir, TESSDATA_FOLDER)

        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        val targetFile = File(tessDataDir, TRAINED_DATA_FILE)

        // Only copy from APK assets if the file doesn't already exist or is empty
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open("$TESSDATA_FOLDER/$TRAINED_DATA_FILE").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Tesseract requires the path to the directory CONTAINING the 'tessdata' directory
        parentDir.absolutePath
    }
}
