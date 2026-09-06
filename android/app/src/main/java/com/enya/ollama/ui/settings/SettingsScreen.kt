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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionLabel = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${info.versionName} (build ${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("unknown")
    }

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
                "A local IP (http://192.168.1.42:11434), or any remote host — including https:// " +
                    "if it's behind a reverse proxy or tunnel.",
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

            Text(
                "Authorization header (optional)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                "For a server that requires auth, e.g. behind a reverse proxy: the full header " +
                    "value, such as \"Bearer sk-...\" or \"Basic <base64>\".",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)
            )
            OutlinedTextField(
                value = uiState.authHeader,
                onValueChange = viewModel::updateAuthHeaderDraft,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Authorization") }
            )

            Column(modifier = Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = { viewModel.save() },
                    enabled = uiState.isDirty,
                    modifier = Modifier.fillMaxWidth()
                ) {
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

            Text(
                "Enya $versionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 32.dp)
            )
        }
    }
}
