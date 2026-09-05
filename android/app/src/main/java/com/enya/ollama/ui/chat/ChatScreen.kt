package com.enya.ollama.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.ollama.ui.components.MessageBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TEXT_ATTACHMENT_BYTES = 200_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long, viewModel: ChatViewModel, onBack: () -> Unit) {
    LaunchedEffect(chatId) { viewModel.open(chatId) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                viewModel.addAttachment(readAttachment(context, uri))
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.chat?.title ?: "Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    ModelSwitcher(
                        currentModel = uiState.chat?.model ?: "",
                        models = uiState.availableModels,
                        expanded = uiState.isModelPickerOpen,
                        onExpandedChange = viewModel::setModelPickerOpen,
                        onSelect = viewModel::switchModel
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (uiState.modelsError != null) {
                Text(
                    "⚠️ ${uiState.modelsError}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message, modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            if (uiState.attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.attachments, key = { it.id }) { attachment ->
                        AttachmentChip(attachment, onRemove = { viewModel.removeAttachment(attachment.id) })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { attachLauncher.launch("*/*") }) {
                    Text("📎")
                }
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        }
                        try {
                            voiceLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("🎤")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }
                )
                if (uiState.isSending) {
                    IconButton(onClick = viewModel::stopGenerating) {
                        Icon(Icons.Default.Close, contentDescription = "Stop generating")
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(input)
                            input = ""
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ModelSwitcher(
    currentModel: String,
    models: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text(currentModel.ifEmpty { "Model" }, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch model")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text("No models available") }, onClick = {}, enabled = false)
            }
            models.forEach { model ->
                DropdownMenuItem(text = { Text(model) }, onClick = { onSelect(model) })
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: ChatAttachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (attachment.kind == AttachmentKind.IMAGE) "🖼️" else "📎")
        Text(
            attachment.name,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 6.dp).widthIn(max = 120.dp)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
        }
    }
}

private suspend fun readAttachment(context: Context, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
    val id = "$uri#${System.nanoTime()}"
    val name = queryFileName(context, uri) ?: uri.lastPathSegment ?: "file"
    val mimeType = context.contentResolver.getType(uri)
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    if (bytes == null) {
        return@withContext ChatAttachment(id = id, name = name, kind = AttachmentKind.UNSUPPORTED)
    }

    if (mimeType?.startsWith("image/") == true) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext ChatAttachment(id = id, name = name, kind = AttachmentKind.IMAGE, imageBase64 = base64)
    }

    if (bytes.size <= MAX_TEXT_ATTACHMENT_BYTES) {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        val looksLikeText = text != null && text.count { it == '\uFFFD' } < (text.length / 20 + 1)
        if (looksLikeText && text != null) {
            return@withContext ChatAttachment(id = id, name = name, kind = AttachmentKind.TEXT, textContent = text)
        }
    }

    ChatAttachment(id = id, name = name, kind = AttachmentKind.UNSUPPORTED)
}

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme != "content") return uri.lastPathSegment
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }.getOrNull()
}
