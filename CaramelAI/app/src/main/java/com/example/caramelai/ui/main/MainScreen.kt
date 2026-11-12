package com.example.caramelai.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.caramelai.ui.characters.CharacterListScreen
import com.example.caramelai.ui.characters.CharacterListViewModel
import com.example.caramelai.ui.components.CaramelBottomBar
import com.example.caramelai.ui.components.MainTab
import com.example.caramelai.ui.profile.ProfileScreen
import com.example.caramelai.ui.profile.ProfileViewModel

@Composable
fun MainScreen(
    characterListViewModel: CharacterListViewModel,
    profileViewModel: ProfileViewModel,
    onOpenCharacterChat: (characterId: String, conversationId: String) -> Unit,
    onEditCharacter: (characterId: String) -> Unit,
    onOpenPersonaEditor: (personaId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Chats) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when (tab) {
            MainTab.Chats -> {
                CharacterListScreen(
                    viewModel = characterListViewModel,
                    onOpenCharacter = onOpenCharacterChat,
                    onEditCharacter = onEditCharacter,
                    modifier = Modifier.weight(1f),
                )
            }
            MainTab.Profile -> {
                ProfileScreen(
                    viewModel = profileViewModel,
                    onOpenPersonaEditor = onOpenPersonaEditor,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        CaramelBottomBar(
            selectedTab = tab,
            onSelect = { tab = it },
        )
    }
}
