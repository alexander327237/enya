package com.enya.pdfreader.ui.home

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enya.pdfreader.R
import com.enya.pdfreader.data.RecentFile
import com.enya.pdfreader.data.RecentFilesStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val store = remember { RecentFilesStore.get(context) }
    val recents by store.files.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Provider does not support persistable grants; we can still read it now.
            }
            onOpen(uri)
        }
    }
    val pick = { picker.launch(arrayOf("application/pdf")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.open_pdf)) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = pick,
            )
        },
    ) { padding ->
        if (recents.isEmpty()) {
            EmptyState(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                item {
                    Text(
                        stringResource(R.string.recent_files),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recents, key = { it.uri }) { file ->
                    RecentRow(
                        file = file,
                        onClick = { onOpen(Uri.parse(file.uri)) },
                        onRemove = { store.remove(file.uri) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PdfBadge(size = 72.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.no_recent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_recent_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecentRow(file: RecentFile, onClick: () -> Unit, onRemove: () -> Unit) {
    val subtitle = buildString {
        if (file.pageCount > 0) {
            append(stringResource(R.string.page_of, file.lastPage + 1, file.pageCount))
        }
        if (file.lastOpened > 0) {
            if (isNotEmpty()) append(" · ")
            append(
                stringResource(
                    R.string.last_opened,
                    DateUtils.getRelativeTimeSpanString(file.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                )
            )
        }
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (subtitle.isNotEmpty()) Text(subtitle) },
        leadingContent = { PdfBadge(size = 40.dp) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_from_list))
            }
        },
    )
}

@Composable
private fun PdfBadge(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .background(Color(0xFFC62828), RoundedCornerShape(size / 5)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "PDF",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = if (size > 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
        )
    }
}
