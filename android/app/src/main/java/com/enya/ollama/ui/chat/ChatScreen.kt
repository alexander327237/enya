package com.enya.ollama.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.ollama.ui.components.MessageBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long, viewModel: ChatViewModel, onBack: () -> Unit) {
    LaunchedEffect(chatId) { viewModel.open(chatId) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
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
