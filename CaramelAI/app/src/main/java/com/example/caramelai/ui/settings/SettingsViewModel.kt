package com.example.caramelai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.settings.AppSettings
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.ConnectionChecker
import com.example.caramelai.domain.ConnectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class SettingsTextState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val userName: String = "",
    val globalBehavior: String = "",
    val seeded: Boolean = false,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val connectionChecker: ConnectionChecker,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _text = MutableStateFlow(SettingsTextState())
    val text: StateFlow<SettingsTextState> = _text.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private var textPersistJob: Job? = null

    init {
        viewModelScope.launch {
            // Read from DataStore directly — never seed from stateIn's placeholder defaults.
            val current = settingsRepository.settings.first()
            _text.update { existing ->
                if (existing.seeded) {
                    existing
                } else {
                    SettingsTextState(
                        baseUrl = current.baseUrl,
                        apiKey = current.apiKey,
                        userName = current.userName,
                        globalBehavior = current.globalBehavior,
                        seeded = true,
                    )
                }
            }
        }
    }

    fun updateBaseUrl(value: String) = updateText { it.copy(baseUrl = value) }
    fun updateApiKey(value: String) = updateText { it.copy(apiKey = value) }
    fun updateUserName(value: String) = updateText { it.copy(userName = value) }
    fun updateGlobalBehavior(value: String) = updateText { it.copy(globalBehavior = value) }

    private fun updateText(transform: (SettingsTextState) -> SettingsTextState) {
        _text.update { transform(it).copy(seeded = true) }
        textPersistJob?.cancel()
        textPersistJob = viewModelScope.launch {
            delay(250)
            persistTextDraft()
        }
    }

    private suspend fun persistTextDraft() {
        val draft = _text.value
        if (!draft.seeded) return
        settingsRepository.update {
            it.copy(
                baseUrl = draft.baseUrl,
                apiKey = draft.apiKey,
                userName = draft.userName,
                globalBehavior = draft.globalBehavior,
            )
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
        }
    }

    fun resetGenerationDefaults() {
        viewModelScope.launch {
            val defaults = AppSettings.defaults()
            settingsRepository.update {
                it.copy(
                    temperature = defaults.temperature,
                    topP = defaults.topP,
                    topK = defaults.topK,
                    minP = defaults.minP,
                    repeatPenalty = defaults.repeatPenalty,
                    maxTokens = defaults.maxTokens,
                    contextSize = defaults.contextSize,
                )
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            textPersistJob?.cancel()
            persistTextDraft()
            _testing.value = true
            when (val result = connectionChecker.check()) {
                is ConnectionResult.Ok -> _events.emit(SettingsEvent.ConnectionOk(result.detail))
                is ConnectionResult.Failed ->
                    _events.emit(SettingsEvent.ConnectionFailed(result.message))
            }
            _testing.value = false
        }
    }

    override fun onCleared() {
        textPersistJob?.cancel()
        runBlocking {
            withContext(Dispatchers.IO) {
                persistTextDraft()
            }
        }
        super.onCleared()
    }

    sealed interface SettingsEvent {
        data class ConnectionOk(val detail: String) : SettingsEvent
        data class ConnectionFailed(val message: String) : SettingsEvent
    }
}
