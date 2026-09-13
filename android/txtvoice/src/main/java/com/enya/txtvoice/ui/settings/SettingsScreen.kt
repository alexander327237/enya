package com.enya.txtvoice.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.txtvoice.R
import com.enya.txtvoice.data.EngineType
import com.enya.txtvoice.data.TtsSettings
import com.enya.txtvoice.tts.OpenAiTtsEngine
import com.enya.txtvoice.tts.Player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val player by Player.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        val s = settings
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle(stringResource(R.string.settings_engine))
            EngineOption(stringResource(R.string.engine_system), s.engine == EngineType.SYSTEM) {
                viewModel.setEngine(EngineType.SYSTEM)
            }
            EngineOption(stringResource(R.string.engine_openai), s.engine == EngineType.OPENAI) {
                viewModel.setEngine(EngineType.OPENAI)
            }

            Spacer(Modifier.height(8.dp))
            LabeledSlider(
                label = stringResource(R.string.speed_value, s.rate),
                value = s.rate, range = 0.5f..3.0f, steps = 24,
                onCommit = viewModel::setRate
            )
            LabeledSlider(
                label = stringResource(R.string.settings_pitch, s.pitch),
                value = s.pitch, range = 0.5f..2.0f, steps = 14,
                onCommit = viewModel::setPitch
            )
            LabeledSlider(
                label = stringResource(R.string.settings_font_size, s.fontSize),
                value = s.fontSize.toFloat(), range = 12f..32f, steps = 19,
                onCommit = { viewModel.setFontSize(it.toInt()) }
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            when (s.engine) {
                EngineType.SYSTEM -> SystemSection(viewModel, s)
                EngineType.OPENAI -> OpenAiSection(viewModel, s)
            }

            Spacer(Modifier.height(16.dp))
            val sample = stringResource(R.string.settings_test_text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.testVoice(sample) }, enabled = player.status != Player.Status.LOADING) {
                    Text(stringResource(R.string.settings_test))
                }
                Spacer(Modifier.width(8.dp))
                if (player.status == Player.Status.LOADING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (player.status == Player.Status.ERROR && player.error != null) {
                Text(
                    stringResource(R.string.error_prefix, player.error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SystemSection(viewModel: SettingsViewModel, s: TtsSettings) {
    val context = LocalContext.current
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadVoices() }

    SectionTitle(stringResource(R.string.settings_system_voice))
    val defaultLabel = stringResource(R.string.settings_voice_default)
    val options = listOf(VoiceOption("", defaultLabel)) + voices
    DropdownField(
        label = stringResource(R.string.settings_system_voice),
        value = options.firstOrNull { it.name == s.systemVoice }?.label ?: s.systemVoice.ifBlank { defaultLabel },
        options = options.map { it.label },
        onSelect = { i -> viewModel.setSystemVoice(options[i].name) }
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }) {
        Text(stringResource(R.string.settings_open_system_tts))
    }
}

@Composable
private fun OpenAiSection(viewModel: SettingsViewModel, s: TtsSettings) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(s.openAiBaseUrl) }
    var key by remember { mutableStateOf(s.openAiKey) }
    var model by remember { mutableStateOf(s.openAiModel) }
    var voice by remember { mutableStateOf(s.openAiVoice) }
    var instructions by remember { mutableStateOf(s.openAiInstructions) }
    var cacheSize by remember { mutableStateOf(OpenAiTtsEngine.cacheSizeBytes(context)) }

    SectionTitle(stringResource(R.string.settings_openai_section))
    Text(
        stringResource(R.string.settings_openai_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = baseUrl, onValueChange = { baseUrl = it; viewModel.setOpenAiBaseUrl(it) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        placeholder = { Text(TtsSettings.DEFAULT_BASE_URL) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = key, onValueChange = { key = it; viewModel.setOpenAiKey(it) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = model, onValueChange = { model = it; viewModel.setOpenAiModel(it) },
        label = { Text(stringResource(R.string.settings_model)) },
        placeholder = { Text(TtsSettings.DEFAULT_MODEL) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Box {
        var expanded by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = voice, onValueChange = { voice = it; viewModel.setOpenAiVoice(it) },
            label = { Text(stringResource(R.string.settings_voice)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Text("▾", modifier = Modifier
                    .padding(12.dp)
                    .clickable { expanded = true })
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TtsSettings.OPENAI_VOICES.forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = {
                    voice = v
                    viewModel.setOpenAiVoice(v)
                    expanded = false
                })
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = instructions, onValueChange = { instructions = it; viewModel.setOpenAiInstructions(it) },
        label = { Text(stringResource(R.string.settings_instructions)) },
        placeholder = { Text(stringResource(R.string.settings_instructions_hint)) },
        minLines = 2, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = {
        OpenAiTtsEngine.clearCache(context)
        cacheSize = OpenAiTtsEngine.cacheSizeBytes(context)
    }) {
        Text(stringResource(R.string.settings_clear_cache, formatBytes(cacheSize)))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun EngineOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range,
        steps = steps
    )
}

@Composable
fun DropdownField(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Text("▾", modifier = Modifier.padding(12.dp)) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(i); expanded = false })
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
