package com.enya.txtvoice.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.txtvoice.R
import com.enya.txtvoice.data.EngineType
import com.enya.txtvoice.tts.Player
import com.enya.txtvoice.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    val state by Player.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var autoScroll by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(bookId) { Player.load(bookId) }
    val loaded = state.book?.id == bookId && state.segments.isNotEmpty()

    LaunchedEffect(state.index, loaded, autoScroll) {
        if (loaded && autoScroll) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(state.index, scrollOffset = -viewport / 3)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            painterResource(R.drawable.ic_autoscroll),
                            contentDescription = stringResource(R.string.auto_scroll),
                            tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SleepTimerMenu(state.sleepEndsAt)
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        bottomBar = {
            PlayerBar(
                state = state,
                rate = settings?.rate ?: 1f,
                onRate = settingsViewModel::setRate
            )
        }
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val fontSize = (settings?.fontSize ?: 18).sp
        val highlight = MaterialTheme.colorScheme.primaryContainer
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            itemsIndexed(state.segments, key = { _, s -> s.index }) { i, seg ->
                val isCurrent = i == state.index
                Text(
                    text = seg.text,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (seg.paragraphStart && i > 0) 12.dp else 0.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCurrent) highlight else MaterialTheme.colorScheme.surface)
                        .clickable { Player.seekTo(i) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SleepTimerMenu(sleepEndsAt: Long?) {
    var expanded by remember { mutableStateOf(false) }
    val active = sleepEndsAt != null && sleepEndsAt > System.currentTimeMillis()
    IconButton(onClick = { expanded = true }) {
        Icon(
            painterResource(R.drawable.ic_timer),
            contentDescription = stringResource(R.string.sleep_timer),
            tint = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.sleep_off)) }, onClick = {
            Player.setSleepTimer(null); expanded = false
        })
        listOf(10, 15, 30, 45, 60, 90).forEach { m ->
            DropdownMenuItem(text = { Text(stringResource(R.string.sleep_minutes, m)) }, onClick = {
                Player.setSleepTimer(m); expanded = false
            })
        }
    }
}

@Composable
private fun PlayerBar(state: Player.State, rate: Float, onRate: (Float) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val statusLine = when {
                state.status == Player.Status.ERROR && state.error != null ->
                    stringResource(R.string.error_prefix, state.error)
                state.status == Player.Status.LOADING -> stringResource(R.string.loading)
                state.finished -> stringResource(R.string.finished)
                else -> null
            }
            val total = state.segments.size
            val percent = state.book?.let { b ->
                val offset = state.current?.start ?: 0
                if (b.length > 0) (offset * 100L / b.length).toInt() else 0
            } ?: 0
            val sleepLeft = state.sleepEndsAt?.let { ((it - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0) }
            val engineLabel = stringResource(
                if (state.engine == EngineType.SYSTEM) R.string.engine_label_system else R.string.engine_label_openai
            )
            val info = buildString {
                append("${state.index + 1} / $total  •  $percent%  •  $engineLabel")
                if (sleepLeft != null) append("  •  ").append(stringResource(R.string.sleep_remaining, sleepLeft))
            }
            Text(
                statusLine ?: info,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status == Player.Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            var local by remember(rate) { mutableFloatStateOf(rate) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_speed), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onRate(local) },
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(String.format("%.2fx", local), style = MaterialTheme.typography.labelMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { Player.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.previous), modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = { Player.togglePlayPause() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    when (state.status) {
                        Player.Status.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp
                        )
                        Player.Status.PLAYING -> Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.pause), modifier = Modifier.size(40.dp))
                        else -> Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.play), modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { Player.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.next), modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
