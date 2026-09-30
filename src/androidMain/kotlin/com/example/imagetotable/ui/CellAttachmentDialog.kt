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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.AttachmentType
import com.example.imagetotable.model.CellAttachment
import com.example.imagetotable.model.CellAttachmentHelper
import com.example.imagetotable.model.CellChecklistItem

@Composable
fun CellAttachmentDialog(
    rowIndex: Int,
    columnIndex: Int,
    columnName: String,
    initialText: String,
    initialNote: String = "",
    initialAttachments: List<CellAttachment> = emptyList(),
    initialChecklists: List<CellChecklistItem> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (
        updatedText: String,
        updatedNote: String,
        updatedAttachments: List<CellAttachment>,
        updatedChecklists: List<CellChecklistItem>
    ) -> Unit
) {
    val context = LocalContext.current
    var cellTextState by remember { mutableStateOf(initialText) }
    var cellNoteState by remember { mutableStateOf(initialNote) }

    val attachmentsState = remember { mutableStateListOf<CellAttachment>().apply { addAll(initialAttachments) } }
    val checklistsState = remember { mutableStateListOf<CellChecklistItem>().apply { addAll(initialChecklists) } }

    var newChecklistInput by remember { mutableStateOf("") }
    var manualContactName by remember { mutableStateOf("") }
    var manualContactPhone by remember { mutableStateOf("") }

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

    // System Phonebook Picker
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

    fun makeCall(phone: String) {
        val clean = phone.trim()
        if (clean.isBlank()) {
            Toast.makeText(context, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot dial: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendSms(phone: String) {
        val clean = phone.trim()
        if (clean.isBlank()) {
            Toast.makeText(context, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWhatsApp(phone: String) {
        val cleanDigits = phone.replace(Regex("[^0-9]"), "")
        if (cleanDigits.isBlank()) {
            Toast.makeText(context, "Enter a valid phone number with country code", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits"))
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "WhatsApp is unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
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
                            text = "📎 Cell Details, Checklists & Files",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Row #${rowIndex + 1} • $columnName",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // SECTION 1: CELL TEXT VALUE & DETAILED NOTES TEXTBOX
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Cell Content & Detailed Notes",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF1565C0)
                            )

                            OutlinedTextField(
                                value = cellTextState,
                                onValueChange = { cellTextState = it },
                                label = { Text("Primary Cell Text") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = cellNoteState,
                                onValueChange = { cellNoteState = it },
                                label = { Text("Detailed Notes / Description / Memo") },
                                placeholder = { Text("Type multi-line notes, instructions, or remarks...", fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 140.dp),
                                maxLines = 5
                            )
                        }
                    }

                    // SECTION 2: MULTIPLE CHECKLISTS
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val doneCount = checklistsState.count { it.isChecked }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "☑ Checklists ($doneCount/${checklistsState.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF2E7D32)
                                )
                                if (checklistsState.isNotEmpty()) {
                                    Text(
                                        text = "${(doneCount * 100 / checklistsState.size)}% done",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Gray
                                    )
                                }
                            }

                            // Checklist Input Bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newChecklistInput,
                                    onValueChange = { newChecklistInput = it },
                                    placeholder = { Text("Add checklist item...", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        if (newChecklistInput.isNotBlank()) {
                                            checklistsState.add(CellChecklistItem(text = newChecklistInput.trim()))
                                            newChecklistInput = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text("+ Add", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            // Checklist Items List
                            if (checklistsState.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    checklistsState.forEachIndexed { idx, item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (item.isChecked) Color(0xFFF1F8E9) else Color(0xFFF8FAFC),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                                                .clickable {
                                                    checklistsState[idx] = item.copy(isChecked = !item.isChecked)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = item.isChecked,
                                                onCheckedChange = { checked ->
                                                    checklistsState[idx] = item.copy(isChecked = checked)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = item.text,
                                                fontSize = 12.sp,
                                                textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                                color = if (item.isChecked) Color.Gray else Color.Black,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = { checklistsState.removeAt(idx) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("✕", color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 3: MANUAL CONTACT ENTRY (CALL, TEXT, WHATSAPP) & PHONEBOOK
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "👤 Contact Actions (Call, SMS, WhatsApp)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF00796B)
                            )

                            // Manual Contact Input Fields
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualContactName,
                                    onValueChange = { manualContactName = it },
                                    label = { Text("Name", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = manualContactPhone,
                                    onValueChange = { manualContactPhone = it },
                                    label = { Text("Phone Number", fontSize = 11.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.weight(1.3f),
                                    singleLine = true
                                )
                            }

                            // Quick Action Buttons for Manual Phone Number
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { makeCall(manualContactPhone) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(2.dp)
                                ) {
                                    Text("📞 Call", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { sendSms(manualContactPhone) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(2.dp)
                                ) {
                                    Text("💬 Text", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { openWhatsApp(manualContactPhone) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.weight(1.2f),
                                    contentPadding = PaddingValues(2.dp)
                                ) {
                                    Text("🟢 WhatsApp", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Save Contact to Cell or Pick from Phonebook
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (manualContactPhone.isNotBlank()) {
                                            attachmentsState.add(
                                                CellAttachment(
                                                    type = AttachmentType.CONTACT,
                                                    uriString = "tel:${manualContactPhone.trim()}",
                                                    displayName = manualContactName.ifBlank { manualContactPhone.trim() },
                                                    detail = manualContactPhone.trim()
                                                )
                                            )
                                            manualContactName = ""
                                            manualContactPhone = ""
                                        } else {
                                            Toast.makeText(context, "Enter phone number first", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("+ Save to Cell", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = { contactPickerLauncher.launch(null) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("📖 Pick Addressbook", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // SECTION 4: FILE & MEDIA ATTACHMENTS
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📄 Attached Files (${attachmentsState.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF37474F)
                                )

                                Button(
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("+ Add Files/Images", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (attachmentsState.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(55.dp)
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No files or contacts attached to this cell.", color = Color.Gray, fontSize = 11.sp)
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    attachmentsState.forEachIndexed { index, item ->
                                        AttachmentPreviewCard(
                                            attachment = item,
                                            onImageClick = { selectedImagePreviewUri = Uri.parse(item.uriString) },
                                            onOpenClick = { openAttachmentFile(context, Uri.parse(item.uriString), item.mimeType) },
                                            onCallClick = { makeCall(item.detail) },
                                            onSmsClick = { sendSms(item.detail) },
                                            onWhatsAppClick = { openWhatsApp(item.detail) },
                                            onDeleteClick = { attachmentsState.removeAt(index) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Sticky Bottom Save / Cancel Footer
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
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSave(
                                cellTextState,
                                cellNoteState,
                                attachmentsState.toList(),
                                checklistsState.toList()
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        modifier = Modifier.weight(1f).height(42.dp)
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
    onCallClick: () -> Unit,
    onSmsClick: () -> Unit,
    onWhatsAppClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                                .size(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(0.5.dp, Color.LightGray, RoundedCornerShape(6.dp))
                                .clickable { onImageClick() },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .background(Color(0xFFE1F5FE), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("🖼", fontSize = 20.sp) }
                    }
                }
                AttachmentType.PDF -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFFFEBEE), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("PDF", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
                AttachmentType.CONTACT -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFE0F2FE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("👤", fontSize = 20.sp) }
                }
                AttachmentType.FILE -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFECEFF1), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("📄", fontSize = 20.sp) }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.displayName,
                    fontSize = 12.sp,
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

                // Interactive Quick Actions for Contacts
                if (attachment.type == AttachmentType.CONTACT && attachment.detail.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFFE3F2FD),
                            modifier = Modifier.clickable { onCallClick() }
                        ) {
                            Text(
                                text = "📞 Call",
                                color = Color(0xFF1565C0),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFFE0F2F1),
                            modifier = Modifier.clickable { onSmsClick() }
                        ) {
                            Text(
                                text = "💬 SMS",
                                color = Color(0xFF00796B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFFE8F5E9),
                            modifier = Modifier.clickable { onWhatsAppClick() }
                        ) {
                            Text(
                                text = "🟢 WA",
                                color = Color(0xFF2E7D32),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (attachment.type != AttachmentType.CONTACT) {
                IconButton(onClick = onOpenClick, modifier = Modifier.size(26.dp)) {
                    Text("👁", fontSize = 15.sp)
                }
            }

            IconButton(onClick = onDeleteClick, modifier = Modifier.size(26.dp)) {
                Text("✕", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
