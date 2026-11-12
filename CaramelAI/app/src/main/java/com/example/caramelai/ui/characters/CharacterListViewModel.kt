package com.example.caramelai.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.CharacterWithActivity
import com.example.caramelai.data.settings.AppSettings
import com.example.caramelai.data.settings.BotListLayout
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.CharacterRepository
import com.example.caramelai.domain.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CharacterListViewModel(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val characters: StateFlow<List<CharacterWithActivity>> =
        characterRepository.observeAllByActivity()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _events = MutableSharedFlow<ListEvent>()
    val events: SharedFlow<ListEvent> = _events.asSharedFlow()

    fun createCharacter() {
        viewModelScope.launch {
            val created = characterRepository.createBlank()
            _events.emit(ListEvent.OpenEditor(created.id))
        }
    }

    fun deleteCharacter(id: String) {
        viewModelScope.launch {
            characterRepository.delete(id)
        }
    }

    fun importJson(raw: String) {
        viewModelScope.launch {
            if (raw.isBlank()) {
                _events.emit(ListEvent.Error("Could not read character file"))
                return@launch
            }
            runCatching { characterRepository.importJson(raw) }
                .onSuccess { _events.emit(ListEvent.Imported(it.name)) }
                .onFailure { _events.emit(ListEvent.Error(it.message ?: "Import failed")) }
        }
    }

    fun setLayout(layout: BotListLayout) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(botListLayout = layout) }
        }
    }

    fun openLatestChat(characterId: String) {
        viewModelScope.launch {
            val settings = settings.value
            val conversation = chatRepository.findOrCreateLatestConversation(
                characterId = characterId,
                defaultPersonaId = settings.defaultPersonaId,
            )
            _events.emit(ListEvent.OpenChat(characterId, conversation.id))
        }
    }

    sealed interface ListEvent {
        data class OpenEditor(val characterId: String) : ListEvent
        data class OpenChat(val characterId: String, val conversationId: String) : ListEvent
        data class Imported(val name: String) : ListEvent
        data class Error(val message: String) : ListEvent
    }
}

fun CharacterWithActivity.toEntity() = CharacterEntity(
    id = id,
    name = name,
    avatarUri = avatarUri,
    description = description,
    personality = personality,
    scenario = scenario,
    greeting = greeting,
    exampleDialogue = exampleDialogue,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
