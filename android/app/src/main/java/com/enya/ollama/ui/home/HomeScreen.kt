package com.enya.ollama.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.ollama.data.db.ChatEntity
import com.enya.ollama.data.db.ProjectEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenChat: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelsError by viewModel.modelsError.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var renamingChat by remember { mutableStateOf<ChatEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Re-checks connectivity every time this screen (re)enters composition, e.g. when
    // coming back from Settings after fixing the server address.
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    fun createChat(projectId: Long?) {
        viewModel.quickCreateChat(
            projectId = projectId,
            onCreated = onOpenChat,
            onNoModelsAvailable = {
                scope.launch {
                    snackbarHostState.showSnackbar("No models available — check the server address in Settings.")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Enya") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column {
                FloatingActionButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New chat") },
                        onClick = { showMenu = false; createChat(null) }
                    )
                    DropdownMenuItem(
                        text = { Text("New project") },
                        onClick = { showMenu = false; showNewProjectDialog = true }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (modelsError != null) {
                Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Can't reach the Ollama server.", style = MaterialTheme.typography.titleSmall)
                        Text(modelsError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }

            val unfiledChats = uiState.chats.filter { it.projectId == null }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(uiState.projects, key = { it.id }) { project ->
                    ProjectSection(
                        project = project,
                        chats = uiState.chats.filter { it.projectId == project.id },
                        onOpenChat = onOpenChat,
                        onDeleteChat = viewModel::deleteChat,
                        onRenameChat = { renamingChat = it },
                        onDeleteProject = { viewModel.deleteProject(project) },
                        onAddChat = { createChat(project.id) }
                    )
                }
                if (unfiledChats.isNotEmpty() || uiState.projects.isEmpty()) {
                    item {
                        Text(
                            "Chats",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(unfiledChats, key = { it.id }) { chat ->
                        ChatRow(
                            chat,
                            onClick = { onOpenChat(chat.id) },
                            onDelete = { viewModel.deleteChat(chat) },
                            onRename = { renamingChat = chat }
                        )
                    }
                }
            }
        }
    }

    showNewProjectDialog.takeIf { it }?.let {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, prompt ->
                viewModel.createProject(name, prompt)
                showNewProjectDialog = false
            }
        )
    }

    renamingChat?.let { chat ->
        RenameChatDialog(
            chat = chat,
            onDismiss = { renamingChat = null },
            onRename = { title ->
                viewModel.renameChat(chat, title)
                renamingChat = null
            }
        )
    }
}

@Composable
private fun ProjectSection(
    project: ProjectEntity,
    chats: List<ChatEntity>,
    onOpenChat: (Long) -> Unit,
    onDeleteChat: (ChatEntity) -> Unit,
    onRenameChat: (ChatEntity) -> Unit,
    onDeleteProject: () -> Unit,
    onAddChat: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(project.name, style = MaterialTheme.typography.labelLarge)
            Row {
                IconButton(onClick = onAddChat) { Icon(Icons.Default.Add, contentDescription = "New chat in project") }
                IconButton(onClick = onDeleteProject) { Icon(Icons.Default.Delete, contentDescription = "Delete project") }
            }
        }
        chats.forEach { chat ->
            ChatRow(
                chat,
                onClick = { onOpenChat(chat.id) },
                onDelete = { onDeleteChat(chat) },
                onRename = { onRenameChat(chat) }
            )
        }
    }
}

@Composable
private fun ChatRow(chat: ChatEntity, onClick: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(chat.title, style = MaterialTheme.typography.bodyLarge)
                Text(chat.model, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onRename) { Text("✎") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete chat") }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("Instructions (optional)") },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, systemPrompt.ifBlank { null }) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RenameChatDialog(chat: ChatEntity, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(chat.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onRename(title) }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
