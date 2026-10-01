package com.example.imagetotable.model

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class AttachmentType {
    IMAGE,
    VIDEO,
    VOICE,
    PDF,
    FILE,
    CONTACT,
    LINKED_TABLE
}

data class CellAttachment(
    val id: String = UUID.randomUUID().toString(),
    val type: AttachmentType,
    val uriString: String = "",
    val displayName: String = "",
    val mimeType: String = "",
    val detail: String = "" // Phone for CONTACT; file size for FILE/VIDEO/VOICE; tableId for LINKED_TABLE
)

data class CellChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isChecked: Boolean = false
)

data class CellDataPayload(
    val displayText: String,
    val attachments: List<CellAttachment> = emptyList(),
    val note: String = "",
    val additionalNote: String = "",
    val checklists: List<CellChecklistItem> = emptyList()
)

object CellAttachmentHelper {
    private const val ATTACHMENT_MARKER_START = "<!--ATTACHMENTS:"
    private const val ATTACHMENT_MARKER_END = "-->"

    fun parseCellContent(rawContent: String): CellDataPayload {
        if (!rawContent.contains(ATTACHMENT_MARKER_START)) {
            return CellDataPayload(displayText = rawContent)
        }
        val displayText = rawContent.substringBefore(ATTACHMENT_MARKER_START).trimEnd()
        val jsonStr = rawContent
            .substringAfter(ATTACHMENT_MARKER_START)
            .substringBefore(ATTACHMENT_MARKER_END)
            .trim()

        val attachments = mutableListOf<CellAttachment>()
        val checklists = mutableListOf<CellChecklistItem>()
        var note = ""
        var additionalNote = ""

        try {
            if (jsonStr.startsWith("{")) {
                val root = JSONObject(jsonStr)
                note = root.optString("note", "")
                additionalNote = root.optString("additionalNote", "")

                val attArray = root.optJSONArray("attachments") ?: JSONArray()
                for (i in 0 until attArray.length()) {
                    val obj = attArray.getJSONObject(i)
                    attachments.add(
                        CellAttachment(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            type = try {
                                AttachmentType.valueOf(obj.optString("type", AttachmentType.FILE.name))
                            } catch (_: Exception) {
                                AttachmentType.FILE
                            },
                            uriString = obj.optString("uri", ""),
                            displayName = obj.optString("name", "Attachment"),
                            mimeType = obj.optString("mime", ""),
                            detail = obj.optString("detail", "")
                        )
                    )
                }

                val checkArray = root.optJSONArray("checklists") ?: JSONArray()
                for (i in 0 until checkArray.length()) {
                    val cObj = checkArray.getJSONObject(i)
                    checklists.add(
                        CellChecklistItem(
                            id = cObj.optString("id", UUID.randomUUID().toString()),
                            text = cObj.optString("text", ""),
                            isChecked = cObj.optBoolean("done", false)
                        )
                    )
                }
            } else if (jsonStr.startsWith("[")) {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    attachments.add(
                        CellAttachment(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            type = try {
                                AttachmentType.valueOf(obj.optString("type", AttachmentType.FILE.name))
                            } catch (_: Exception) {
                                AttachmentType.FILE
                            },
                            uriString = obj.optString("uri", ""),
                            displayName = obj.optString("name", "Attachment"),
                            mimeType = obj.optString("mime", ""),
                            detail = obj.optString("detail", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return CellDataPayload(
            displayText = displayText,
            attachments = attachments,
            note = note,
            additionalNote = additionalNote,
            checklists = checklists
        )
    }

    fun formatCellContent(
        displayText: String,
        attachments: List<CellAttachment>,
        note: String = "",
        additionalNote: String = "",
        checklists: List<CellChecklistItem> = emptyList()
    ): String {
        if (attachments.isEmpty() && note.isBlank() && additionalNote.isBlank() && checklists.isEmpty()) {
            return displayText.trim()
        }

        val root = JSONObject()
        root.put("note", note.trim())
        root.put("additionalNote", additionalNote.trim())

        val attArray = JSONArray()
        for (att in attachments) {
            val obj = JSONObject().apply {
                put("id", att.id)
                put("type", att.type.name)
                put("uri", att.uriString)
                put("name", att.displayName)
                put("mime", att.mimeType)
                put("detail", att.detail)
            }
            attArray.put(obj)
        }
        root.put("attachments", attArray)

        val checkArray = JSONArray()
        for (item in checklists) {
            val cObj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("done", item.isChecked)
            }
            checkArray.put(cObj)
        }
        root.put("checklists", checkArray)

        return "${displayText.trim()}\n$ATTACHMENT_MARKER_START$root$ATTACHMENT_MARKER_END"
    }

    fun queryFileNameAndSize(context: Context, uri: Uri): Pair<String, String> {
        var name = "Document"
        var sizeStr = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: "Document"
                    }
                    if (sizeIndex != -1) {
                        val bytes = cursor.getLong(sizeIndex)
                        sizeStr = when {
                            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
                            bytes >= 1024 -> "${bytes / 1024} KB"
                            else -> "$bytes B"
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return Pair(name, sizeStr)
    }

    fun queryContactInfo(context: Context, contactUri: Uri): Pair<String, String> {
        var name = "Contact"
        var phone = ""
        try {
            context.contentResolver.query(
                contactUri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val phoneIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (nameIdx != -1) {
                        name = cursor.getString(nameIdx) ?: "Contact"
                    }
                    if (phoneIdx != -1) {
                        phone = cursor.getString(phoneIdx) ?: ""
                    }
                }
            }
        } catch (_: Exception) {
            try {
                context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        if (nameIdx != -1) name = cursor.getString(nameIdx) ?: "Contact"
                    }
                }
            } catch (_: Exception) {}
        }
        return Pair(name, phone)
    }
}
