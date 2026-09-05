package com.enya.ollama.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enya.ollama.ui.ViewModelFactory
import com.enya.ollama.ui.chat.ChatScreen
import com.enya.ollama.ui.chat.ChatViewModel
import com.enya.ollama.ui.home.HomeScreen
import com.enya.ollama.ui.home.HomeViewModel
import com.enya.ollama.ui.settings.SettingsScreen
import com.enya.ollama.ui.settings.SettingsViewModel

@Composable
fun EnyaNavGraph(factory: ViewModelFactory) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = viewModel(factory = factory)
            HomeScreen(
                viewModel = viewModel,
                onOpenChat = { chatId -> navController.navigate(Screen.Chat.createRoute(chatId)) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument(Screen.Chat.ARG_CHAT_ID) { type = NavType.LongType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong(Screen.Chat.ARG_CHAT_ID) ?: -1L
            val viewModel: ChatViewModel = viewModel(factory = factory)
            ChatScreen(
                chatId = chatId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
