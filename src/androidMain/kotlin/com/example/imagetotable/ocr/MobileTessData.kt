package com.example.imagetotable.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MobileTessData {
    private const val PARENT_FOLDER = "tesseract"
    private const val TESSDATA_FOLDER = "tessdata"
    private const val TRAINED_DATA_FILE = "eng.traineddata"
    private const val DOWNLOAD_URL =
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
    private const val MIN_VALID_SIZE_BYTES = 1_000_000L // Real file is ~4.1 MB

    suspend fun getOrExtractTessBasePath(
        context: Context,
        onStatusUpdate: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val parentDir = File(context.filesDir, PARENT_FOLDER)
        val tessDataDir = File(parentDir, TESSDATA_FOLDER)
        if (!tessDataDir.exists()) tessDataDir.mkdirs()

        val targetFile = File(tessDataDir, TRAINED_DATA_FILE)

        // If a valid binary model (>1MB) is already present, use it immediately
        if (targetFile.exists() && targetFile.length() >= MIN_VALID_SIZE_BYTES) {
            return@withContext parentDir.absolutePath
        }

        // Delete corrupt, 0-byte, or partial files
        if (targetFile.exists()) {
            targetFile.delete()
        }

        // 1. Try extracting from assets first
        var validAssetExtracted = false
        try {
            context.assets.open("$TESSDATA_FOLDER/$TRAINED_DATA_FILE").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.length() >= MIN_VALID_SIZE_BYTES) {
                validAssetExtracted = true
            } else {
                targetFile.delete() // Asset was an empty/text dummy
            }
        } catch (_: Exception) {
            // Asset missing or not accessible
        }

        // 2. Fallback: Download official binary model if asset was corrupt/missing
        if (!validAssetExtracted) {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Downloading OCR model (~4 MB)...")
            }
            downloadModel(targetFile)
        }

        parentDir.absolutePath
    }

    private fun downloadModel(targetFile: File) {
        val url = URL(DOWNLOAD_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }

        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Failed to download OCR model: HTTP ${connection.responseCode}")
        }

        connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }

        if (targetFile.length() < MIN_VALID_SIZE_BYTES) {
            targetFile.delete()
            throw IllegalStateException("Downloaded model is incomplete or corrupted.")
        }
    }
}
