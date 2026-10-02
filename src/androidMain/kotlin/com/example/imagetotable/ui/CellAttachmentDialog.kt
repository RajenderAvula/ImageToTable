package com.example.imagetotable.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.imagetotable.model.AttachmentType
import com.example.imagetotable.model.CellAttachment
import com.example.imagetotable.model.CellAttachmentHelper
import com.example.imagetotable.model.CellChecklistItem
import com.example.imagetotable.model.TableRepository
import com.example.imagetotable.util.StreamingSpeechHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActiveListeningTarget {
    PRIMARY_NOTE,
    ADDITIONAL_NOTE
}

@Composable
fun CellAttachmentDialog(
    rowIndex: Int,
    columnIndex: Int,
    columnName: String,
    initialText: String,
    initialNote: String = "",
    initialAdditionalNote: String = "",
    initialAttachments: List<CellAttachment> = emptyList(),
    initialChecklists: List<CellChecklistItem> = emptyList(),
    onDismiss: () -> Unit,
    onOpenLinkedTable: (targetTableId: String) -> Unit = {},
    onSave: (
        updatedText: String,
        updatedNote: String,
        updatedAdditionalNote: String,
        updatedAttachments: List<CellAttachment>,
        updatedChecklists: List<CellChecklistItem>
    ) -> Unit
) {
    val context = LocalContext.current
    var cellTextState by remember { mutableStateOf(initialText) }
    var cellNoteState by remember { mutableStateOf(initialNote) }
    var additionalNoteState by remember { mutableStateOf(initialAdditionalNote) }

    val attachmentsState = remember { mutableStateListOf<CellAttachment>().apply { addAll(initialAttachments) } }
    val checklistsState = remember { mutableStateListOf<CellChecklistItem>().apply { addAll(initialChecklists) } }

    var newChecklistInput by remember { mutableStateOf("") }
    var manualContactName by remember { mutableStateOf("") }
    var manualContactPhone by remember { mutableStateOf("") }

    var showLinkTableDialog by remember { mutableStateOf(false) }
    var showInAppFileBrowser by remember { mutableStateOf(false) }
    var selectedImagePreviewUri by remember { mutableStateOf<Uri?>(null) }

    val speechHelper = remember { StreamingSpeechHelper(context) }
    var activeListeningTarget by remember { mutableStateOf<ActiveListeningTarget?>(null) }
    var baseTextBeforeSpeech by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            speechHelper.stop()
        }
    }

    var pendingOfflineTarget by remember { mutableStateOf<ActiveListeningTarget?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingOfflineTarget?.let { target ->
                val baseText = if (target == ActiveListeningTarget.PRIMARY_NOTE) cellNoteState else additionalNoteState
                baseTextBeforeSpeech = baseText
                activeListeningTarget = target
                startOfflineSpeechRecognition(target, speechHelper, context,
                    onStart = { _, tgt ->
                        activeListeningTarget = tgt
                    },
                    onPartial = { partial, tgt ->
                        if (tgt == ActiveListeningTarget.PRIMARY_NOTE) {
                            cellNoteState = if (baseTextBeforeSpeech.isBlank()) partial else "$baseTextBeforeSpeech $partial"
                        } else {
                            additionalNoteState = if (baseTextBeforeSpeech.isBlank()) partial else "$baseTextBeforeSpeech $partial"
                        }
                    },
                    onFinal = { final, tgt ->
                        if (tgt == ActiveListeningTarget.PRIMARY_NOTE) {
                            cellNoteState = if (baseTextBeforeSpeech.isBlank()) final else "$baseTextBeforeSpeech $final"
                        } else {
                            additionalNoteState = if (baseTextBeforeSpeech.isBlank()) final else "$baseTextBeforeSpeech $final"
                        }
                        activeListeningTarget = null
                    },
                    onError = { err ->
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        activeListeningTarget = null
                    }
                )
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice typing", Toast.LENGTH_SHORT).show()
        }
        pendingOfflineTarget = null
    }

    var pendingOnlineTarget by remember { mutableStateOf<ActiveListeningTarget?>(null) }
    val onlineSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            if (pendingOnlineTarget == ActiveListeningTarget.PRIMARY_NOTE) {
                cellNoteState = if (cellNoteState.isBlank()) spoken else "$cellNoteState $spoken"
            } else if (pendingOnlineTarget == ActiveListeningTarget.ADDITIONAL_NOTE) {
                additionalNoteState = if (additionalNoteState.isBlank()) spoken else "$additionalNoteState $spoken"
            }
        }
        pendingOnlineTarget = null
    }

    fun startOnlineSpeech(target: ActiveListeningTarget) {
        pendingOnlineTarget = target
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    if (target == ActiveListeningTarget.ADDITIONAL_NOTE) "Speak online for Additional Note..." else "Speak online for Primary Note..."
                )
            }
            onlineSpeechLauncher.launch(intent)
        } catch (e: Exception) {
            pendingOnlineTarget = null
            Toast.makeText(context, "Online speech error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerOfflineSpeech(target: ActiveListeningTarget) {
        if (activeListeningTarget == target) {
            speechHelper.stop()
            activeListeningTarget = null
            return
        }

        if (activeListeningTarget != null) {
            speechHelper.stop()
            activeListeningTarget = null
        }

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            pendingOfflineTarget = target
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val baseText = if (target == ActiveListeningTarget.PRIMARY_NOTE) cellNoteState else additionalNoteState
        baseTextBeforeSpeech = baseText
        activeListeningTarget = target

        speechHelper.start(
            onPartialResult = { partial ->
                if (target == ActiveListeningTarget.PRIMARY_NOTE) {
                    cellNoteState = if (baseTextBeforeSpeech.isBlank()) partial else "$baseTextBeforeSpeech $partial"
                } else {
                    additionalNoteState = if (baseTextBeforeSpeech.isBlank()) partial else "$baseTextBeforeSpeech $partial"
                }
            },
            onFinalResult = { final ->
                if (target == ActiveListeningTarget.PRIMARY_NOTE) {
                    cellNoteState = if (baseTextBeforeSpeech.isBlank()) final else "$baseTextBeforeSpeech $final"
                } else {
                    additionalNoteState = if (baseTextBeforeSpeech.isBlank()) final else "$baseTextBeforeSpeech $final"
                }
                activeListeningTarget = null
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                activeListeningTarget = null
            },
            onListeningStateChanged = { listening ->
                if (!listening && activeListeningTarget == target) {
                    activeListeningTarget = null
                }
            }
        )
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
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
                    mime.startsWith("image/") || name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".jpeg", true) || name.endsWith(".webp", true) -> AttachmentType.IMAGE
                    mime.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".3gp", true) -> AttachmentType.VIDEO
                    mime.startsWith("audio/") || name.endsWith(".mp3", true) || name.endsWith(".m4a", true) || name.endsWith(".wav", true) || name.endsWith(".aac", true) -> AttachmentType.VOICE
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
        } else {
            Toast.makeText(context, "No files selected. Returned to Cell Details.", Toast.LENGTH_SHORT).show()
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val contactUri = result.data?.data
        if (contactUri != null) {
            val (name, phone) = CellAttachmentHelper.queryContactInfo(context, contactUri)
            attachmentsState.add(
                CellAttachment(
                    type = AttachmentType.CONTACT,
                    uriString = if (phone.isNotBlank()) "tel:${phone.trim()}" else contactUri.toString(),
                    displayName = name,
                    detail = phone.trim()
                )
            )
        }
    }

    fun makeCall(phone: String) {
        val clean = phone.trim()
        if (clean.isBlank()) return
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot dial: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendSms(phone: String) {
        val clean = phone.trim()
        if (clean.isBlank()) return
        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")))
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open SMS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWhatsApp(phone: String) {
        val cleanDigits = phone.replace(Regex("[^0-9]"), "")
        if (cleanDigits.isBlank()) return
        try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits"))
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "WhatsApp error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = {
            speechHelper.stop()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
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
                            text = "📎 Cell Details, Notes & Files",
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
                    IconButton(
                        onClick = {
                            speechHelper.stop()
                            onDismiss()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
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
                    // SECTION 1: CELL TEXT, PRIMARY NOTE & ADDITIONAL NOTE
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Cell Content & Notes",
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

                            // Primary Note
                            val isPrimaryListening = activeListeningTarget == ActiveListeningTarget.PRIMARY_NOTE
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Primary Cell Note",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF37474F)
                                        )
                                        if (isPrimaryListening) {
                                            Text(
                                                text = "● Listening...",
                                                color = Color.Red,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { startOnlineSpeech(ActiveListeningTarget.PRIMARY_NOTE) },
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("🌐 Online", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = { triggerOfflineSpeech(ActiveListeningTarget.PRIMARY_NOTE) },
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = if (isPrimaryListening) Color.Red else Color(0xFF0288D1)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isPrimaryListening) "⏹ Stop" else "⚡ Offline",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = cellNoteState,
                                    onValueChange = { cellNoteState = it },
                                    placeholder = { Text("Detailed cell memo or voice-type...", fontSize = 12.sp) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 70.dp, max = 120.dp),
                                    maxLines = 4
                                )
                            }

                            Divider(color = Color(0xFFEEEEEE))

                            // Additional Note
                            val isAdditionalListening = activeListeningTarget == ActiveListeningTarget.ADDITIONAL_NOTE
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Additional Text Note",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF6A1B9A)
                                        )
                                        if (isAdditionalListening) {
                                            Text(
                                                text = "● Listening...",
                                                color = Color.Red,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { startOnlineSpeech(ActiveListeningTarget.ADDITIONAL_NOTE) },
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF8E24AA)),
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("🌐 Online", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = { triggerOfflineSpeech(ActiveListeningTarget.ADDITIONAL_NOTE) },
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = if (isAdditionalListening) Color.Red else Color(0xFF6A1B9A)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isAdditionalListening) "⏹ Stop" else "⚡ Offline",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = additionalNoteState,
                                    onValueChange = { additionalNoteState = it },
                                    placeholder = { Text("Extra notes, customer remarks or voice-type...", fontSize = 12.sp) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 70.dp, max = 120.dp),
                                    maxLines = 4
                                )
                            }
                        }
                    }

                    // SECTION 2: CHECKLISTS
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
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                                ) {
                                    Text("+ Add", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

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
                                                modifier = Modifier.size(24.dp)
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

                    // SECTION 3: CONTACT ENTRY (MANUAL & ADDRESS BOOK) + CALL / SMS / WHATSAPP
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
                                    onClick = {
                                        val pickIntent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                                        contactPickerLauncher.launch(pickIntent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("📖 Pick Addressbook", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // SECTION 4: FILE, MEDIA & LINKED TABLE ATTACHMENTS
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "📄 Attached Media, Files & Tables (${attachmentsState.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF37474F)
                            )

                            // Dedicated Multi-Media Toolbar: Never hidden or pushed off screen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { showInAppFileBrowser = true },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0D47A1)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("📂 Browse Device", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { mediaPickerLauncher.launch(arrayOf("*/*")) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("📁 + All Files", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { mediaPickerLauncher.launch(arrayOf("image/*")) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("🖼 + Image", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { mediaPickerLauncher.launch(arrayOf("video/*")) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("🎬 + Video", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { mediaPickerLauncher.launch(arrayOf("audio/*")) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF57C00)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("🎤 + Audio/Voice", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { showLinkTableDialog = true },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("📊 + Link Table", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (attachmentsState.isEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "No files, media, or tables attached to this cell yet.",
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { showInAppFileBrowser = true },
                                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0D47A1)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("📂 Browse Device", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = { mediaPickerLauncher.launch(arrayOf("*/*")) },
                                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("📁 Attach File", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = { showLinkTableDialog = true },
                                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7B1FA2)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("📊 Link Table", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
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
                                            onOpenLinkedTableClick = {
                                                speechHelper.stop()
                                                onSave(
                                                    cellTextState,
                                                    cellNoteState,
                                                    additionalNoteState,
                                                    attachmentsState.toList(),
                                                    checklistsState.toList()
                                                )
                                                onDismiss()
                                                onOpenLinkedTable(item.detail)
                                            },
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
                        onClick = {
                            speechHelper.stop()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            speechHelper.stop()
                            onSave(
                                cellTextState,
                                cellNoteState,
                                additionalNoteState,
                                attachmentsState.toList(),
                                checklistsState.toList()
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text("Save & Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // IN-APP DEVICE FILE BROWSER MODAL WITH CLEAR REVERT/RETURN BUTTON
    if (showInAppFileBrowser) {
        InAppFileBrowserDialog(
            context = context,
            onDismiss = { showInAppFileBrowser = false },
            onFilesSelected = { selectedFiles ->
                selectedFiles.forEach { file ->
                    val uri = Uri.fromFile(file)
                    val mime = getMimeTypeFromExtension(file.extension)
                    val sizeStr = formatFileSize(file.length())
                    val type = when {
                        mime.startsWith("image/") || file.extension.matches(Regex("(?i)jpg|jpeg|png|webp|bmp")) -> AttachmentType.IMAGE
                        mime.startsWith("video/") || file.extension.matches(Regex("(?i)mp4|mkv|3gp|webm")) -> AttachmentType.VIDEO
                        mime.startsWith("audio/") || file.extension.matches(Regex("(?i)mp3|m4a|wav|aac|ogg")) -> AttachmentType.VOICE
                        mime.contains("pdf") || file.extension.equals("pdf", ignoreCase = true) -> AttachmentType.PDF
                        else -> AttachmentType.FILE
                    }
                    attachmentsState.add(
                        CellAttachment(
                            type = type,
                            uriString = uri.toString(),
                            displayName = file.name,
                            mimeType = mime,
                            detail = sizeStr
                        )
                    )
                }
                showInAppFileBrowser = false
            },
            onLaunchSystemPicker = {
                showInAppFileBrowser = false
                mediaPickerLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    // Modal to Link Existing Table to Cell
    if (showLinkTableDialog) {
        val availableTables = TableRepository.tables
        AlertDialog(
            onDismissRequest = { showLinkTableDialog = false },
            title = {
                Text(
                    text = "Link a Saved Table to Cell",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF6A1B9A)
                )
            },
            text = {
                if (availableTables.isEmpty()) {
                    Text("No saved tables exist. Create tables in the app first.", fontSize = 12.sp, color = Color.Gray)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableTables.forEach { tableItem ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF3E5F5),
                                border = BorderStroke(1.dp, Color(0xFFCE93D8)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        attachmentsState.add(
                                            CellAttachment(
                                                type = AttachmentType.LINKED_TABLE,
                                                uriString = "table:${tableItem.id}",
                                                displayName = tableItem.tableName,
                                                detail = tableItem.id
                                            )
                                        )
                                        showLinkTableDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("📊", fontSize = 18.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tableItem.tableName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4A148C))
                                        Text(
                                            "${tableItem.rows.size} rows • ${tableItem.headers.size} cols • ${tableItem.tableDateTime}",
                                            fontSize = 10.sp,
                                            color = Color.DarkGray
                                        )
                                    }
                                    Text("➕ Link", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7B1FA2))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLinkTableDialog = false }) { Text("Cancel") }
            }
        )
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

// IN-APP DEVICE FILE BROWSER MODAL (ALLOWS EASY EXPLORATION WITH CLEAR REVERT/RETURN BUTTON)
@Composable
private fun InAppFileBrowserDialog(
    context: Context,
    onDismiss: () -> Unit,
    onFilesSelected: (List<File>) -> Unit,
    onLaunchSystemPicker: () -> Unit
) {
    val defaultDir = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .takeIf { it.exists() && it.canRead() }
            ?: context.filesDir
    }

    var currentDir by remember { mutableStateOf(defaultDir) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedFiles = remember { mutableStateListOf<File>() }

    val fileList = remember(currentDir, searchQuery) {
        try {
            val files = currentDir.listFiles().orEmpty()
            files.filter { file ->
                !file.name.startsWith(".") &&
                (searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true))
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF8FAFC)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar with Unmistakable Revert Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D47A1))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📂 Device Storage File Browser",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cannot find file? Use 'Revert to App' anytime.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }

                    // Direct Revert Button in Top Bar
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("⬅ Revert to App", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Quick Navigation Shortcut Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(Color(0xFFECEFF1))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

                    if (downloads.exists()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (currentDir == downloads) Color(0xFF1976D2) else Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.clickable { currentDir = downloads; searchQuery = "" }
                        ) {
                            Text("📥 Downloads", fontSize = 10.sp, color = if (currentDir == downloads) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }

                    if (documents.exists()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (currentDir == documents) Color(0xFF1976D2) else Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.clickable { currentDir = documents; searchQuery = "" }
                        ) {
                            Text("📄 Documents", fontSize = 10.sp, color = if (currentDir == documents) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }

                    if (pictures.exists()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (currentDir == pictures) Color(0xFF1976D2) else Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.clickable { currentDir = pictures; searchQuery = "" }
                        ) {
                            Text("🖼 Pictures", fontSize = 10.sp, color = if (currentDir == pictures) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }

                    if (dcim.exists()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (currentDir == dcim) Color(0xFF1976D2) else Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.clickable { currentDir = dcim; searchQuery = "" }
                        ) {
                            Text("🎬 DCIM", fontSize = 10.sp, color = if (currentDir == dcim) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }

                // Search & Current Directory Breadcrumb
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter files in this directory...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📁 ${currentDir.path.takeLast(35)}",
                            fontSize = 10.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )

                        if (currentDir.parentFile != null && currentDir.parentFile?.canRead() == true) {
                            Button(
                                onClick = { currentDir = currentDir.parentFile!!; searchQuery = "" },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("⬆ Up Folder", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }

                Divider(color = Color(0xFFCFD8DC))

                // File & Folder List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (fileList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No files match '$searchQuery'" else "This folder is empty or not accessible.",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        items(fileList) { item ->
                            val isSelected = selectedFiles.contains(item)
                            val isDir = item.isDirectory

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = if (isSelected) Color(0xFFE3F2FD) else Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        width = if (isSelected) 1.dp else 0.5.dp,
                                        color = if (isSelected) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        if (isDir) {
                                            if (item.canRead()) {
                                                currentDir = item
                                                searchQuery = ""
                                            } else {
                                                Toast.makeText(context, "Permission restricted for folder", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            if (isSelected) selectedFiles.remove(item)
                                            else selectedFiles.add(item)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = when {
                                        isDir -> "📁"
                                        item.extension.matches(Regex("(?i)jpg|jpeg|png|webp|bmp")) -> "🖼"
                                        item.extension.matches(Regex("(?i)mp4|mkv|3gp|webm")) -> "🎬"
                                        item.extension.matches(Regex("(?i)mp3|m4a|wav|aac|ogg")) -> "🎵"
                                        item.extension.equals("pdf", ignoreCase = true) -> "📄"
                                        else -> "📄"
                                    },
                                    fontSize = 18.sp
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isDir) Color(0xFF0D47A1) else Color(0xFF263238),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (isDir) "Folder" else "${formatFileSize(item.length())} • ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.lastModified()))}",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }

                                if (!isDir) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedFiles.add(item) else selectedFiles.remove(item)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Sticky Footer: Revert to App, System SAF Launcher, or Attach Selected
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    elevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Primary Revert Button at Bottom
                            OutlinedButton(
                                onClick = onDismiss,
                                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color(0xFFFFEBEE)),
                                border = BorderStroke(1.dp, Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("⬅ Return to App", color = Color(0xFFD32F2F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Launch Google Drive / SAF Picker
                            Button(
                                onClick = onLaunchSystemPicker,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.1f).height(42.dp)
                            ) {
                                Text("🌐 System Picker", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (selectedFiles.isNotEmpty()) {
                            Button(
                                onClick = { onFilesSelected(selectedFiles.toList()) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text("✓ Attach Selected (${selectedFiles.size} files)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

private fun getMimeTypeFromExtension(ext: String): String {
    return when (ext.lowercase(Locale.getDefault())) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "doc", "docx" -> "application/msword"
        else -> "*/*"
    }
}

private fun startOfflineSpeechRecognition(
    target: ActiveListeningTarget,
    helper: StreamingSpeechHelper,
    context: Context,
    onStart: (baseText: String, target: ActiveListeningTarget) -> Unit,
    onPartial: (partial: String, target: ActiveListeningTarget) -> Unit,
    onFinal: (final: String, target: ActiveListeningTarget) -> Unit,
    onError: (error: String) -> Unit
) {
    onStart("", target)
    helper.start(
        onPartialResult = { partial -> onPartial(partial, target) },
        onFinalResult = { final -> onFinal(final, target) },
        onError = { err -> onError(err) },
        onListeningStateChanged = { /* handled in caller */ }
    )
}

@Composable
private fun AttachmentPreviewCard(
    attachment: CellAttachment,
    onImageClick: () -> Unit,
    onOpenClick: () -> Unit,
    onCallClick: () -> Unit,
    onSmsClick: () -> Unit,
    onWhatsAppClick: () -> Unit,
    onOpenLinkedTableClick: () -> Unit = {},
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = if (attachment.type == AttachmentType.LINKED_TABLE) Color(0xFFF3E5F5) else Color.White,
        border = BorderStroke(0.5.dp, if (attachment.type == AttachmentType.LINKED_TABLE) Color(0xFFBA68C8) else Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (attachment.type) {
                AttachmentType.LINKED_TABLE -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFEDE7F6), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("📊", fontSize = 22.sp) }
                }
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
                AttachmentType.VIDEO -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFEDE7F6), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("🎬", fontSize = 20.sp) }
                }
                AttachmentType.VOICE -> {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("🎤", fontSize = 20.sp) }
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
                        text = if (attachment.type == AttachmentType.LINKED_TABLE) "Linked Table ID: ${attachment.detail.take(8)}..." else attachment.detail,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1
                    )
                }

                if (attachment.type == AttachmentType.CONTACT) {
                    val phoneNum = attachment.detail.trim()
                    if (phoneNum.isNotBlank()) {
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
                    } else {
                        Text(
                            text = "No phone number available",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            if (attachment.type == AttachmentType.LINKED_TABLE) {
                Button(
                    onClick = onOpenLinkedTableClick,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7B1FA2)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("👁 Open Table", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (attachment.type != AttachmentType.CONTACT && attachment.type != AttachmentType.LINKED_TABLE) {
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
