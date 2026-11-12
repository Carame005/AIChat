package com.example.caramelai.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.PersonaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonaEditorUiState(
    val id: String = "",
    val name: String = "",
    val avatarUri: String? = null,
    val description: String = "",
    val isDefault: Boolean = false,
    val loaded: Boolean = false,
)

class PersonaEditorViewModel(
    private val personaId: String,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PersonaEditorUiState(id = personaId))
    val uiState: StateFlow<PersonaEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>()
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val defaultId = settingsRepository.settings.first().defaultPersonaId
            personaRepository.observeById(personaId).collect { persona ->
                if (persona != null) {
                    _uiState.value = PersonaEditorUiState(
                        id = persona.id,
                        name = persona.name,
                        avatarUri = persona.avatarUri,
                        description = persona.description,
                        isDefault = defaultId == persona.id,
                        loaded = true,
                    )
                }
            }
        }
    }

    fun updateName(value: String) = _uiState.update { it.copy(name = value.take(20)) }
    fun updateAvatarUri(value: String?) = _uiState.update { it.copy(avatarUri = value) }
    fun updateDescription(value: String) = _uiState.update { it.copy(description = value.take(750)) }
    fun updateDefault(value: Boolean) = _uiState.update { it.copy(isDefault = value) }

    fun appendChip(text: String) {
        _uiState.update {
            val spacer = if (it.description.isBlank() || it.description.endsWith(" ")) "" else " "
            it.copy(description = (it.description + spacer + text).take(750))
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.name.isBlank()) {
                _events.emit(EditorEvent.Error("El nombre es obligatorio"))
                return@launch
            }
            personaRepository.save(
                PersonaEntity(
                    id = state.id,
                    name = state.name.trim(),
                    avatarUri = state.avatarUri,
                    description = state.description.trim(),
                ),
            )
            val currentDefault = settingsRepository.settings.first().defaultPersonaId
            when {
                state.isDefault -> settingsRepository.update { it.copy(defaultPersonaId = state.id) }
                currentDefault == state.id && !state.isDefault ->
                    settingsRepository.update { it.copy(defaultPersonaId = null) }
            }
            _events.emit(EditorEvent.Saved)
        }
    }

    fun delete() {
        viewModelScope.launch {
            personaRepository.delete(personaId)
            val currentDefault = settingsRepository.settings.first().defaultPersonaId
            if (currentDefault == personaId) {
                settingsRepository.update { it.copy(defaultPersonaId = null) }
            }
            _events.emit(EditorEvent.Deleted)
        }
    }

    sealed interface EditorEvent {
        data object Saved : EditorEvent
        data object Deleted : EditorEvent
        data class Error(val message: String) : EditorEvent
    }
}
