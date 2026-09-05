package com.enya.ollama.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            Text(
                "Ollama server address",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Point this at the machine running Ollama on your local network, e.g. http://192.168.1.42:11434",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)
            )
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::updateServerUrlDraft,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Server URL") }
            )

            Column(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Test connection")
                }
            }

            when (val status = uiState.testStatus) {
                is ConnectionTestStatus.Testing -> Text(
                    "Checking…",
                    modifier = Modifier.padding(top = 12.dp)
                )
                is ConnectionTestStatus.Success -> Text(
                    "Connected — found ${status.modelCount} model(s).",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )
                is ConnectionTestStatus.Failure -> Text(
                    "Failed: ${status.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
                ConnectionTestStatus.Idle -> Unit
            }
        }
    }
}
