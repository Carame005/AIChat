package com.example.caramelai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.caramelai.di.AppContainer
import com.example.caramelai.ui.characters.CharacterEditorViewModel
import com.example.caramelai.ui.characters.CharacterListViewModel
import com.example.caramelai.ui.chat.ChatViewModel
import com.example.caramelai.ui.profile.PersonaEditorViewModel
import com.example.caramelai.ui.profile.ProfileViewModel
import com.example.caramelai.ui.settings.SettingsViewModel
import com.example.caramelai.ui.startup.StartupViewModel

class AppViewModelFactory(
    private val container: AppContainer,
    private val characterId: String? = null,
    private val conversationId: String? = null,
    private val personaId: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(StartupViewModel::class.java) ->
                StartupViewModel(container.connectionChecker) as T

            modelClass.isAssignableFrom(CharacterListViewModel::class.java) ->
                CharacterListViewModel(
                    characterRepository = container.characterRepository,
                    chatRepository = container.chatRepository,
                    settingsRepository = container.settingsRepository,
                ) as T

            modelClass.isAssignableFrom(ProfileViewModel::class.java) ->
                ProfileViewModel(
                    personaRepository = container.personaRepository,
                    settingsRepository = container.settingsRepository,
                ) as T

            modelClass.isAssignableFrom(PersonaEditorViewModel::class.java) ->
                PersonaEditorViewModel(
                    personaId = requireNotNull(personaId),
                    personaRepository = container.personaRepository,
                    settingsRepository = container.settingsRepository,
                ) as T

            modelClass.isAssignableFrom(CharacterEditorViewModel::class.java) ->
                CharacterEditorViewModel(
                    characterId = requireNotNull(characterId),
                    characterRepository = container.characterRepository,
                    llmClient = container.llmClient,
                    settingsRepository = container.settingsRepository,
                ) as T

            modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                ChatViewModel(
                    conversationId = requireNotNull(conversationId),
                    characterId = requireNotNull(characterId),
                    chatRepository = container.chatRepository,
                    characterRepository = container.characterRepository,
                    personaRepository = container.personaRepository,
                    settingsRepository = container.settingsRepository,
                ) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    connectionChecker = container.connectionChecker,
                ) as T

            else -> error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
