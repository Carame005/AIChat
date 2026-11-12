package com.example.caramelai.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.settings.AppSettings
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.PersonaRepository
import com.example.caramelai.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val personas: StateFlow<List<PersonaEntity>> = personaRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _events = MutableSharedFlow<ProfileEvent>()
    val events: SharedFlow<ProfileEvent> = _events.asSharedFlow()

    fun createPersona() {
        viewModelScope.launch {
            val created = personaRepository.createBlank()
            _events.emit(ProfileEvent.OpenPersonaEditor(created.id))
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(theme = theme) }
        }
    }

    fun setDefaultPersona(personaId: String?) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(defaultPersonaId = personaId) }
        }
    }

    sealed interface ProfileEvent {
        data class OpenPersonaEditor(val personaId: String) : ProfileEvent
    }
}
