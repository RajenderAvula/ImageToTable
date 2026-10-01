package com.example.imagetotable.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.imagetotable.model.CellAttachmentHelper
import com.example.imagetotable.model.TableData
import com.example.imagetotable.model.TableRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupRestoreManager {

    private const val BACKUP_MANIFEST_FILE = "tables_manifest.json"
    private const val ATTACHMENTS_FOLDER = "attachments/"

    /**
     * Builds a comprehensive ZIP package containing JSON representation of all tables
     * and actual local copies of all attached files, videos, voice memos, and images.
     */
    suspend fun createFullBackupArchive(context: Context): File = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backupFile = File(context.cacheDir, "ImageToTable_Backup_$timeStamp.ittzip")
        if (backupFile.exists()) backupFile.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { zos ->
            // 1. Serialize all tables to JSON Manifest
            val tablesArray = JSONArray()
            val allAttachmentUris = mutableMapOf<String, String>() // Local Path in ZIP -> Android Uri String

            TableRepository.tables.forEach { table ->
                val tableObj = JSONObject().apply {
                    put("id", table.id)
                    put("name", table.tableName)
                    put("dateTime", table.tableDateTime)
                    put("cornerHeader", table.cornerHeader)

                    val headersArr = JSONArray()
                    table.headers.forEach { h ->
                        headersArr.put(JSONObject().apply {
                            put("name", h.name)
                            put("type", h.type.name)
                            put("formula", h.formula)
                        })
                    }
                    put("headers", headersArr)

                    val rowNamesArr = JSONArray()
                    table.rowNames.forEach { rName -> rowNamesArr.put(rName) }
                    put("rowNames", rowNamesArr)

                    val rowsArr = JSONArray()
                    table.rows.forEach { rowCells ->
                        val cellRowArr = JSONArray()
                        rowCells.forEach { cellRaw ->
                            val payload = CellAttachmentHelper.parseCellContent(cellRaw)
                            payload.attachments.forEach { att ->
                                if (att.uriString.isNotBlank() && !att.uriString.startsWith("tel:") && !att.uriString.startsWith("table:")) {
                                    val safeFileName = "${att.id}_${att.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
                                    allAttachmentUris["$ATTACHMENTS_FOLDER$safeFileName"] = att.uriString
                                }
                            }
                            cellRowArr.put(cellRaw)
                        }
                        rowsArr.put(cellRowArr)
                    }
                    put("rows", rowsArr)
                }
                tablesArray.put(tableObj)
            }

            val manifestRoot = JSONObject().apply {
                put("version", 2)
                put("exportedAt", timeStamp)
                put("tables", tablesArray)
            }

            // Write Manifest Entry
            zos.putNextEntry(ZipEntry(BACKUP_MANIFEST_FILE))
            zos.write(manifestRoot.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Stream all physical media/attachment streams directly into ZIP
            allAttachmentUris.forEach { (zipEntryPath, uriString) ->
                try {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        zos.putNextEntry(ZipEntry(zipEntryPath))
                        inputStream.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (_: Exception) {
                    // Continue even if an external ephemeral attachment is unreachable
                }
            }
        }

        backupFile
    }

    /**
     * Backup to Mail: Bundles the full database and all media files into a .ittzip
     * and triggers the device's default mail composer.
     */
    suspend fun backupToMail(context: Context, recipientEmail: String = ""): Boolean = withContext(Dispatchers.Main) {
        try {
            val backupFile = withContext(Dispatchers.IO) { createFullBackupArchive(context) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", backupFile)

            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                if (recipientEmail.isNotBlank()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                }
                putExtra(Intent.EXTRA_SUBJECT, "ImageToTable Backup [${backupFile.name}]")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Attached is the full database backup including tables, attachments, notes, audio memos, videos, and images.\n\nKeep this file safe. You can restore directly in the app."
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(emailIntent, "Send Backup via Email"))
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Email backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Restores tables and all unbundled media files from any provided .ittzip or .zip InputStream.
     */
    suspend fun restoreFromArchiveStream(context: Context, inputStream: InputStream): Int = withContext(Dispatchers.IO) {
        val attachmentsDir = File(context.filesDir, "restored_attachments").apply { if (!exists()) mkdirs() }
        var manifestJsonString: String? = null
        val restoredFilesMap = mutableMapOf<String, File>() // safeFileName -> extracted File

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == BACKUP_MANIFEST_FILE) {
                    val reader = BufferedReader(InputStreamReader(zis, Charsets.UTF_8))
                    manifestJsonString = reader.readText()
                } else if (entry.name.startsWith(ATTACHMENTS_FOLDER) && !entry.isDirectory) {
                    val fileName = entry.name.removePrefix(ATTACHMENTS_FOLDER)
                    val targetFile = File(attachmentsDir, fileName)
                    FileOutputStream(targetFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    restoredFilesMap[fileName] = targetFile
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (manifestJsonString.isNullOrBlank()) {
            throw IllegalArgumentException("Invalid backup file: Manifest not found.")
        }

        val manifestObj = JSONObject(manifestJsonString!!)
        val tablesArr = manifestObj.optJSONArray("tables") ?: JSONArray()
        var restoredCount = 0

        for (i in 0 until tablesArr.length()) {
            val tObj = tablesArr.getJSONObject(i)
            val id = tObj.optString("id", UUID.randomUUID().toString())
            val name = tObj.optString("name", "Restored Table $i")
            val dateTime = tObj.optString("dateTime", "")
            val cornerHeader = tObj.optString("cornerHeader", "ID / #")

            val headers = mutableListOf<com.example.imagetotable.model.ColumnDef>()
            val hArr = tObj.optJSONArray("headers") ?: JSONArray()
            for (hIdx in 0 until hArr.length()) {
                val hObj = hArr.getJSONObject(hIdx)
                headers.add(
                    com.example.imagetotable.model.ColumnDef(
                        name = hObj.optString("name", "Col ${hIdx + 1}"),
                        type = try {
                            com.example.imagetotable.model.ColumnType.valueOf(hObj.optString("type", "TEXT"))
                        } catch (_: Exception) { com.example.imagetotable.model.ColumnType.TEXT },
                        formula = hObj.optString("formula", "")
                    )
                )
            }

            val rowNames = mutableListOf<String>()
            val rnArr = tObj.optJSONArray("rowNames") ?: JSONArray()
            for (rnIdx in 0 until rnArr.length()) {
                rowNames.add(rnArr.getString(rnIdx))
            }

            val rows = mutableListOf<List<String>>()
            val rArr = tObj.optJSONArray("rows") ?: JSONArray()
            for (rIdx in 0 until rArr.length()) {
                val cellRowArr = rArr.getJSONArray(rIdx)
                val rowCells = mutableListOf<String>()
                for (cIdx in 0 until cellRowArr.length()) {
                    var cellRaw = cellRowArr.getString(cIdx)

                    // Remap restored attachment URIs to persistent restored file paths
                    val payload = CellAttachmentHelper.parseCellContent(cellRaw)
                    if (payload.attachments.isNotEmpty()) {
                        val remappedAttachments = payload.attachments.map { att ->
                            val safeFileName = "${att.id}_${att.displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
                            val extractedFile = restoredFilesMap[safeFileName]
                            if (extractedFile != null && extractedFile.exists()) {
                                att.copy(uriString = Uri.fromFile(extractedFile).toString())
                            } else {
                                att
                            }
                        }
                        cellRaw = CellAttachmentHelper.formatCellContent(
                            displayText = payload.displayText,
                            attachments = remappedAttachments,
                            note = payload.note,
                            additionalNote = payload.additionalNote,
                            checklists = payload.checklists
                        )
                    }

                    rowCells.add(cellRaw)
                }
                rows.add(rowCells)
            }

            val newTable = TableData(
                initialId = id,
                initialName = name,
                initialHeaders = headers,
                initialRows = rows,
                initialCorner = cornerHeader,
                initialDateTime = dateTime
            )
            newTable.rowNames.clear()
            newTable.rowNames.addAll(rowNames)
            newTable.recomputeFormulas()

            TableRepository.saveOrUpdate(newTable)
            restoredCount++
        }

        restoredCount
    }
}
