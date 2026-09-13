package com.enya.pdfreader.ui.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enya.pdfreader.R
import com.enya.pdfreader.data.ReaderPrefs
import com.enya.pdfreader.data.RecentFilesStore
import com.enya.pdfreader.pdf.PdfOpenException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BarColor = Color(0xE61E1E1E)
private val ReaderBackground = Color(0xFF303030)

@Composable
fun ReaderScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: ReaderViewModel = viewModel(
        key = "reader:$uri",
        factory = ReaderViewModel.Factory(app, uri),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val close = {
        viewModel.release()
        onClose()
    }
    BackHandler(onBack = close)
    LaunchedEffect(viewModel) { viewModel.ensureOpen() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ReaderBackground)
    ) {
        when (val s = state) {
            ReaderState.Loading -> LoadingView()
            is ReaderState.Error -> ErrorView(s.kind, onClose = close)
            is ReaderState.Ready -> ReaderContent(viewModel, s, onClose = close)
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading), color = Color.White)
    }
}

@Composable
private fun ErrorView(kind: PdfOpenException.Kind, onClose: () -> Unit) {
    val message = when (kind) {
        PdfOpenException.Kind.NO_ACCESS -> R.string.error_no_access
        PdfOpenException.Kind.PASSWORD -> R.string.error_password
        PdfOpenException.Kind.CORRUPT -> R.string.error_corrupt
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.error_title),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(message),
            color = Color(0xFFDDDDDD),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(viewModel: ReaderViewModel, ready: ReaderState.Ready, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RecentFilesStore.get(context) }
    val prefs = remember { ReaderPrefs(context) }
    val scope = rememberCoroutineScope()

    var nightMode by rememberSaveable { mutableStateOf(prefs.nightMode) }
    var vertical by rememberSaveable { mutableStateOf(prefs.verticalScroll) }
    var showBars by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val pageCount = ready.pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = ready.initialPage.coerceIn(0, pageCount - 1)) { pageCount }

    LaunchedEffect(pagerState, viewModel.uri) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            store.updateProgress(viewModel.uri.toString(), page)
        }
    }

    SystemBars(visible = showBars)

    Box(
        Modifier
            .fillMaxSize()
            .background(if (nightMode) Color.Black else ReaderBackground)
    ) {
        val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
            PdfPageView(
                viewModel = viewModel,
                pageIndex = index,
                nightMode = nightMode,
                isCurrent = pagerState.settledPage == index,
                onTap = { showBars = !showBars },
            )
        }
        if (vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                pageContent = pageContent,
            )
        }

        AnimatedVisibility(
            visible = showBars,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            TopAppBar(
                title = {
                    Text(
                        ready.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Text(
                        stringResource(R.string.page_counter, pagerState.currentPage + 1, pageCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.go_to_page)) },
                                onClick = { menuOpen = false; showGoTo = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.night_mode)) },
                                trailingIcon = { Checkbox(checked = nightMode, onCheckedChange = null) },
                                onClick = {
                                    nightMode = !nightMode
                                    prefs.nightMode = nightMode
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vertical_scroll)) },
                                trailingIcon = { Checkbox(checked = vertical, onCheckedChange = null) },
                                onClick = {
                                    vertical = !vertical
                                    prefs.verticalScroll = vertical
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                onClick = { menuOpen = false; shareDocument(context, viewModel.uri) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_with)) },
                                onClick = { menuOpen = false; openWith(context, viewModel.uri) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.file_info)) },
                                onClick = { menuOpen = false; showInfo = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BarColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }

        AnimatedVisibility(
            visible = showBars,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            BottomControls(pagerState = pagerState, pageCount = pageCount)
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = pageCount,
            current = pagerState.currentPage + 1,
            onDismiss = { showGoTo = false },
            onGo = { page ->
                showGoTo = false
                scope.launch { pagerState.scrollToPage(page - 1) }
            },
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.file_info)) },
            text = {
                Column {
                    InfoRow(stringResource(R.string.info_name), ready.name)
                    InfoRow(stringResource(R.string.info_pages), ready.pageCount.toString())
                    InfoRow(stringResource(R.string.info_size), Formatter.formatShortFileSize(context, ready.sizeBytes))
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BottomControls(pagerState: PagerState, pageCount: Int) {
    val scope = rememberCoroutineScope()
    var sliderPos by remember { mutableFloatStateOf(pagerState.currentPage.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        if (!dragging) sliderPos = pagerState.currentPage.toFloat()
    }

    Surface(color = BarColor, contentColor = Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } },
                    enabled = pagerState.currentPage > 0,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_page))
                }
                if (pageCount > 1) {
                    Slider(
                        value = sliderPos,
                        onValueChange = {
                            dragging = true
                            sliderPos = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            scope.launch { pagerState.scrollToPage(sliderPos.roundToInt().coerceIn(0, pageCount - 1)) }
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1)) } },
                    enabled = pagerState.currentPage < pageCount - 1,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_page))
                }
            }
            Text(
                stringResource(R.string.page_of, sliderPos.roundToInt() + 1, pageCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.page_number, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(parsed!!) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onGo(parsed!!) }, enabled = valid) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Hides the status/navigation bars together with the reader chrome for an immersive view. */
@Composable
private fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    DisposableEffect(visible) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) it.show(WindowInsetsCompat.Type.systemBars()) else it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun shareDocument(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
    }
}

private fun openWith(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
    }
}
