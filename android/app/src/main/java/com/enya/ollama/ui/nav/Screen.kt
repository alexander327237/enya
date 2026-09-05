package com.enya.ollama.ui.nav

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object Chat : Screen("chat/{chatId}") {
        const val ARG_CHAT_ID = "chatId"
        fun createRoute(chatId: Long) = "chat/$chatId"
    }
}
