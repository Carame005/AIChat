package com.example.caramelai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.caramelai.CaramelApp
import com.example.caramelai.ui.AppViewModelFactory
import com.example.caramelai.ui.characters.CharacterEditorScreen
import com.example.caramelai.ui.characters.CharacterEditorViewModel
import com.example.caramelai.ui.characters.CharacterListViewModel
import com.example.caramelai.ui.chat.ChatScreen
import com.example.caramelai.ui.chat.ChatViewModel
import com.example.caramelai.ui.main.MainScreen
import com.example.caramelai.ui.profile.PersonaEditorScreen
import com.example.caramelai.ui.profile.PersonaEditorViewModel
import com.example.caramelai.ui.profile.ProfileViewModel
import com.example.caramelai.ui.settings.SettingsScreen
import com.example.caramelai.ui.settings.SettingsViewModel
import com.example.caramelai.ui.startup.StartupScreen
import com.example.caramelai.ui.startup.StartupViewModel

@Composable
fun CaramelNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as CaramelApp
    val container = app.container

    fun goMain() {
        navController.navigate(Routes.Main) {
            popUpTo(Routes.Startup) { inclusive = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Startup,
        modifier = modifier,
    ) {
        composable(Routes.Startup) {
            val factory = remember { AppViewModelFactory(container) }
            val vm: StartupViewModel = viewModel(factory = factory)
            StartupScreen(
                viewModel = vm,
                onConnected = { goMain() },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onContinueOffline = { goMain() },
            )
        }

        composable(Routes.Main) {
            val factory = remember { AppViewModelFactory(container) }
            val listVm: CharacterListViewModel = viewModel(factory = factory)
            val profileVm: ProfileViewModel = viewModel(factory = factory)
            MainScreen(
                characterListViewModel = listVm,
                profileViewModel = profileVm,
                onOpenCharacterChat = { characterId, conversationId ->
                    navController.navigate(Routes.chat(characterId, conversationId))
                },
                onEditCharacter = { id ->
                    navController.navigate(Routes.characterEditor(id))
                },
                onOpenPersonaEditor = { id ->
                    navController.navigate(Routes.personaEditor(id))
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
            )
        }

        composable(
            route = Routes.CharacterEditor,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
        ) { entry ->
            val characterId = entry.arguments?.getString("characterId").orEmpty()
            val factory = remember(characterId) {
                AppViewModelFactory(container, characterId = characterId)
            }
            val vm: CharacterEditorViewModel = viewModel(factory = factory)
            CharacterEditorScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.Chat,
            arguments = listOf(
                navArgument("characterId") { type = NavType.StringType },
                navArgument("conversationId") { type = NavType.StringType },
            ),
        ) { entry ->
            val characterId = entry.arguments?.getString("characterId").orEmpty()
            val conversationId = entry.arguments?.getString("conversationId").orEmpty()
            val factory = remember(characterId, conversationId) {
                AppViewModelFactory(
                    container,
                    characterId = characterId,
                    conversationId = conversationId,
                )
            }
            val vm: ChatViewModel = viewModel(
                key = "$characterId-$conversationId",
                factory = factory,
            )
            ChatScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenConversation = { newId ->
                    navController.navigate(Routes.chat(characterId, newId)) {
                        popUpTo(Routes.chat(characterId, conversationId)) { inclusive = true }
                    }
                },
                onEditCharacter = {
                    navController.navigate(Routes.characterEditor(characterId))
                },
            )
        }

        composable(
            route = Routes.PersonaEditor,
            arguments = listOf(navArgument("personaId") { type = NavType.StringType }),
        ) { entry ->
            val personaId = entry.arguments?.getString("personaId").orEmpty()
            val factory = remember(personaId) {
                AppViewModelFactory(container, personaId = personaId)
            }
            val vm: PersonaEditorViewModel = viewModel(factory = factory)
            PersonaEditorScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Settings) {
            val factory = remember { AppViewModelFactory(container) }
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
