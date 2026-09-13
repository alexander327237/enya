package com.enya.txtvoice.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enya.txtvoice.ui.library.LibraryScreen
import com.enya.txtvoice.ui.library.LibraryViewModel
import com.enya.txtvoice.ui.reader.ReaderScreen
import com.enya.txtvoice.ui.settings.SettingsScreen
import com.enya.txtvoice.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

sealed class IncomingIntent {
    data class OpenUri(val uri: Uri) : IncomingIntent()
    data class SharedText(val title: String, val text: String) : IncomingIntent()
    data class OpenBook(val bookId: String) : IncomingIntent()
}

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookId}"
    fun reader(bookId: String) = "reader/$bookId"
}

@Composable
fun AppNavGraph(factory: ViewModelFactory, incoming: MutableStateFlow<IncomingIntent?>) {
    val nav = rememberNavController()
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val pending by incoming.collectAsState()

    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        incoming.value = null
        val bookId = when (p) {
            is IncomingIntent.OpenUri -> libraryVm.importUri(p.uri)?.id
            is IncomingIntent.SharedText -> libraryVm.importText(p.title, p.text)?.id
            is IncomingIntent.OpenBook -> p.bookId
        }
        if (bookId != null) {
            nav.navigate(Routes.reader(bookId)) {
                popUpTo(Routes.LIBRARY)
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                viewModel = libraryVm,
                onOpenBook = { id -> nav.navigate(Routes.reader(id)) { launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            )
        }
        composable(
            Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(
                bookId = bookId,
                settingsViewModel = settingsVm,
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = settingsVm, onBack = { nav.popBackStack() })
        }
    }
}
