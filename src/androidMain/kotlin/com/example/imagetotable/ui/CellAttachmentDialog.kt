package com.example.imagetotable.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.AttachmentType
import com.example.imagetotable.model.CellAttachment
import com.example.imagetotable.model.CellAttachmentHelper

@Composable
fun CellAttachmentDialog(
    rowIndex: Int,
    columnIndex: Int,
    columnName: String,
    initialText: String,
    initialAttachments: List<CellAttachment>,
    onDismiss: () -> Unit,
    onSave: (updatedText: String, updatedAttachments: List<CellAttachment>) -> Unit
) {
    val context = LocalContext.current
    var cellTextState by remember { mutableStateOf(initialText) }
    val attachmentsState = remember { mutableStateListOf<CellAttachment>().apply { addAll(initialAttachments) } }
    var selectedImagePreviewUri by remember { mutableStateOf<Uri?>(null) }

    // Multi-File Picker (Images, PDFs, Documents)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val (name, size) = CellAttachmentHelper.queryFileNameAndSize(context, uri)
                val type = when {
                    mime.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".png", true) -> AttachmentType.IMAGE
                    mime.contains("pdf") || name.endsWith(".pdf", true) -> AttachmentType.PDF
                    else -> AttachmentType.FILE
                }

                attachmentsState.add(
                    CellAttachment(
                        type = type,
                        uriString = uri.toString(),
                        displayName = name,
                        mimeType = mime,
                        detail = size
                    )
                )
            }
        }
    }

    // System Contact Picker
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            val (name, phone) = CellAttachmentHelper.queryContactInfo(context, contactUri)
            attachmentsState.add(
                CellAttachment(
                    type = AttachmentType.CONTACT,
                    uriString = contactUri.toString(),
                    displayName = name,
                    detail = phone
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF8FAFC)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D47A1))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "📎 Cell Attachments & Contacts",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Row #${rowIndex + 1} • $columnName",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Cell Text Field
                    OutlinedTextField(
                        value = cellTextState,
                        onValueChange = { cellTextState = it },
                        label = { Text("Cell Text Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Action Add Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("📄 + Files / Images", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { contactPickerLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("👤 + Contact", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Divider()

                    Text(
                        text = "Attached Items (${attachmentsState.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF37474F)
                    )

                    if (attachmentsState.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFECEFF1), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No attachments yet.\nTap '+ Files / Images' or '+ Contact' above.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(attachmentsState) { index, item ->
                                AttachmentPreviewCard(
                                    attachment = item,
                                    onImageClick = { selectedImagePreviewUri = Uri.parse(item.uriString) },
                                    onOpenClick = { openAttachmentFile(context, Uri.parse(item.uriString), item.mimeType) },
                                    onDeleteClick = { attachmentsState.removeAt(index) }
                                )
                            }
                        }
                    }
                }

                // Bottom Save Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(0.5.dp, Color(0xFFE0E0E0))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSave(cellTextState, attachmentsState.toList())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save & Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Full-screen Image Preview Popup
    if (selectedImagePreviewUri != null) {
        Dialog(onDismissRequest = { selectedImagePreviewUri = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.75f),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val bmp = remember(selectedImagePreviewUri) { loadBitmapThumbnail(context, selectedImagePreviewUri!!, sampleSize = 1) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Full Image Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    IconButton(
                        onClick = { selectedImagePreviewUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreviewCard(
    attachment: CellAttachment,
    onImageClick: () -> Unit,
    onOpenClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Visual Preview / Avatar
            when (attachment.type) {
                AttachmentType.IMAGE -> {
                    val bmp = remember(attachment.uriString) {
                        loadBitmapThumbnail(context, Uri.parse(attachment.uriString), sampleSize = 4)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = attachment.displayName,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(6.dp))
                                .clickable { onImageClick() },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFFE1F5FE), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🖼", fontSize = 22.sp)
                        }
                    }
                }
                AttachmentType.PDF -> {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0xFFFFEBEE), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("PDF", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                AttachmentType.CONTACT -> {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0xFFE0F2FE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 22.sp)
                    }
                }
                AttachmentType.FILE -> {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0xFFECEFF1), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📄", fontSize = 22.sp)
                    }
                }
            }

            // Info & Communication Actions
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (attachment.detail.isNotBlank()) {
                    Text(
                        text = attachment.detail,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1
                    )
                }

                // Interactive Quick Communication Actions for Contacts (Call, Text, WhatsApp)
                if (attachment.type == AttachmentType.CONTACT && attachment.detail.isNotBlank()) {
                    val rawPhone = attachment.detail.trim()
                    // Strip special chars & plus sign for WhatsApp standard format
                    val cleanPhoneForWa = rawPhone.replace(Regex("[^0-9]"), "")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Phone Call Action
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE3F2FD),
                            modifier = Modifier.clickable {
                                try {
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$rawPhone"))
                                    context.startActivity(dialIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open dialer: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(
                                text = "📞 Call",
                                color = Color(0xFF1565C0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // 2. SMS / Text Action
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE0F2F1),
                            modifier = Modifier.clickable {
                                try {
                                    val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$rawPhone"))
                                    context.startActivity(smsIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open SMS: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(
                                text = "💬 SMS",
                                color = Color(0xFF00796B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // 3. WhatsApp Action
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.clickable {
                                try {
                                    val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhoneForWa")
                                    val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                                        setPackage("com.whatsapp")
                                    }
                                    context.startActivity(waIntent)
                                } catch (_: Exception) {
                                    // Fallback to browser link if WhatsApp app direct launch fails
                                    try {
                                        val webWaIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhoneForWa"))
                                        context.startActivity(webWaIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "WhatsApp is not available: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = "🟢 WhatsApp",
                                color = Color(0xFF2E7D32),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Right-side actions (View file & Delete item)
            if (attachment.type != AttachmentType.CONTACT) {
                IconButton(onClick = onOpenClick, modifier = Modifier.size(28.dp)) {
                    Text("👁", fontSize = 16.sp)
                }
            }

            IconButton(onClick = onDeleteClick, modifier = Modifier.size(28.dp)) {
                Text("✕", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

private fun loadBitmapThumbnail(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (_: Exception) {
        null
    }
}

private fun openAttachmentFile(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (mimeType.isNotBlank()) mimeType else "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
