package com.example.imagetotable.util

import android.content.Context
import java.io.File

object CacheManager {
    fun getCacheSizeBytes(context: Context): Long {
        var size = 0L
        context.cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    fun getFormattedCacheSize(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        return when {
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.2f KB", bytes.toDouble() / 1024)
            else -> "$bytes Bytes"
        }
    }

    fun clearCache(context: Context): Boolean {
        return try {
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
            true
        } catch (_: Exception) {
            false
        }
    }
}
