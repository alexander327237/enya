package com.enya.pdfreader.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.enya.pdfreader.ui.home.HomeScreen
import com.enya.pdfreader.ui.reader.ReaderScreen

/**
 * Two-screen app: the home screen with recent files, and the reader for one document.
 * No navigation library needed; the open document survives rotation via rememberSaveable.
 */
@Composable
fun App(externalUri: Uri?, onExternalConsumed: () -> Unit) {
    var openUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(externalUri) {
        if (externalUri != null) {
            openUri = externalUri.toString()
            onExternalConsumed()
        }
    }

    val current = openUri
    if (current == null) {
        HomeScreen(onOpen = { openUri = it.toString() })
    } else {
        ReaderScreen(
            uri = Uri.parse(current),
            onClose = { openUri = null },
        )
    }
}
