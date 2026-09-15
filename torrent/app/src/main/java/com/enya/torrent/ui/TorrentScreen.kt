package com.enya.torrent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.torrent.TorrentEngine
import com.enya.torrent.formatBytes
import com.enya.torrent.formatRate
import org.libtorrent4j.TorrentStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(
    onAdd: (String) -> Unit,
    onPickFile: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (hash: String, deleteFiles: Boolean) -> Unit,
    onStopService: () -> Unit,
    onStartService: () -> Unit,
    onRequestStorage: () -> Unit,
    batteryOptimized: Boolean = false,
    onRequestBattery: () -> Unit = {},
    crashLog: String? = null,
    onCopyCrashLog: () -> Unit = {},
    onShareCrashLog: () -> Unit = {},
    onClearCrashLog: () -> Unit = {},
) {
    val torrents by TorrentEngine.torrents.collectAsStateWithLifecycle()
    val running by TorrentEngine.running.collectAsStateWithLifecycle()
    val saveDir by TorrentEngine.saveDir.collectAsStateWithLifecycle()
    val publicAccess by TorrentEngine.publicAccess.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var pendingRemove by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enya Torrent") },
                actions = {
                    IconButton(onClick = { if (running) onStopService() else onStartService() }) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = if (running) "Остановить" else "Запустить",
                            tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Magnet-ссылка, хеш или URL .torrent") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            onAdd(input)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank(),
                ) { Text("Добавить") }
                Button(onClick = onPickFile) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Файл .torrent")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Файлы сохраняются в: ${saveDir?.absolutePath ?: "…"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!publicAccess) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Нет доступа к папке «Загрузки». Пока файлы сохраняются в папку приложения.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onRequestStorage) { Text("Разрешить доступ к Загрузкам") }
                    }
                }
            }
            if (batteryOptimized) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Оптимизация батареи включена: система может останавливать загрузки в фоне.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onRequestBattery) { Text("Не ограничивать в фоне") }
                    }
                }
            }
            crashLog?.let { log ->
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "В прошлый раз приложение упало. Скопируйте лог и отправьте разработчику.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            log.lineSequence().take(6).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onShareCrashLog) { Text("Отправить") }
                            TextButton(onClick = onCopyCrashLog) { Text("Копировать") }
                            TextButton(onClick = onClearCrashLog) { Text("Скрыть") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (torrents.isEmpty()) {
                Text(
                    if (running) "Нет активных загрузок. Вставьте magnet-ссылку или выберите файл."
                    else "Сервис остановлен. Добавьте торрент или нажмите кнопку питания.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(torrents, key = { it.hash }) { item ->
                        TorrentCard(
                            item = item,
                            onPause = { onPause(item.hash) },
                            onResume = { onResume(item.hash) },
                            onRemove = { pendingRemove = item.hash },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    pendingRemove?.let { hash ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Удалить торрент?") },
            text = { Text("Можно удалить только из списка или вместе со скачанными файлами.") },
            confirmButton = {
                TextButton(onClick = { onRemove(hash, true); pendingRemove = null }) {
                    Text("Удалить с файлами", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingRemove = null }) { Text("Отмена") }
                    TextButton(onClick = { onRemove(hash, false); pendingRemove = null }) {
                        Text("Только из списка")
                    }
                }
            },
        )
    }
}

@Composable
private fun TorrentCard(
    item: TorrentEngine.Item,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(stateLabel(item))
                    append(" · ")
                    append("%.1f%%".format(item.progress * 100))
                    if (item.total > 0) {
                        append(" · ").append(formatBytes(item.done)).append(" / ").append(formatBytes(item.total))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "↓ ${formatRate(item.downloadRate.toLong())}  ↑ ${formatRate(item.uploadRate.toLong())}  " +
                    "пиры ${item.peers}  сиды ${item.seeds}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.paused) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Продолжить")
                    }
                } else {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Пауза")
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun stateLabel(item: TorrentEngine.Item): String = when {
    item.error != null -> "Ошибка"
    item.paused -> "Пауза"
    !item.hasMetadata || item.state == TorrentStatus.State.DOWNLOADING_METADATA -> "Получение метаданных"
    item.state == TorrentStatus.State.CHECKING_FILES ||
        item.state == TorrentStatus.State.CHECKING_RESUME_DATA -> "Проверка"
    item.state == TorrentStatus.State.SEEDING -> "Раздача"
    item.finished || item.state == TorrentStatus.State.FINISHED -> "Готово"
    item.state == TorrentStatus.State.DOWNLOADING -> "Загрузка"
    else -> "Ожидание"
}
