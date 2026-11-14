package com.example.caramelai.ui.characters

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.R
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.remote.ChatMessageDto
import com.example.caramelai.data.remote.GenerationParams
import com.example.caramelai.data.remote.LlmClient
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.CharacterRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CharacterField {
    Name,
    Description,
    Personality,
    Scenario,
    Greeting,
    ExampleDialogue,
    SystemPrompt,
}

data class CharacterEditorUiState(
    val id: String = "",
    val name: String = "",
    val avatarUri: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val loaded: Boolean = false,
    val step: Int = 0,
    val generatingField: CharacterField? = null,
    val fieldError: String? = null,
    val exportJson: String? = null,
)

class CharacterEditorViewModel(
    application: Application,
    private val characterId: String,
    private val characterRepository: CharacterRepository,
    private val llmClient: LlmClient,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(CharacterEditorUiState(id = characterId))
    val uiState: StateFlow<CharacterEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>()
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            characterRepository.observeById(characterId).collect { character ->
                if (character != null) {
                    _uiState.update {
                        character.toUiState().copy(
                            step = it.step,
                            generatingField = it.generatingField,
                            fieldError = it.fieldError,
                        )
                    }
                }
            }
        }
    }

    fun setStep(step: Int) = _uiState.update { it.copy(step = step.coerceIn(0, 2)) }
    fun nextStep() = _uiState.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
    fun prevStep() = _uiState.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun updateName(value: String) = _uiState.update { it.copy(name = value) }
    fun updateAvatarUri(value: String?) = _uiState.update { it.copy(avatarUri = value) }
    fun updateDescription(value: String) = _uiState.update { it.copy(description = value) }
    fun updatePersonality(value: String) = _uiState.update { it.copy(personality = value) }
    fun updateScenario(value: String) = _uiState.update { it.copy(scenario = value) }
    fun updateGreeting(value: String) = _uiState.update { it.copy(greeting = value) }
    fun updateExampleDialogue(value: String) = _uiState.update { it.copy(exampleDialogue = value) }
    fun updateSystemPrompt(value: String) = _uiState.update { it.copy(systemPrompt = value) }

    fun generateField(field: CharacterField) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(generatingField = field, fieldError = null) }
            val settings = settingsRepository.settings.first()
            val prompt = buildGenerationPrompt(field, state)
            val result = llmClient.complete(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = com.example.caramelai.data.remote.LlmModels.normalizeId(settings.modelId),
                messages = listOf(
                    ChatMessageDto(
                        role = "system",
                        content = "You help create chatbot character cards. Reply with ONLY the requested field text, no quotes or labels.",
                    ),
                    ChatMessageDto(role = "user", content = prompt),
                ),
                params = GenerationParams(
                    temperature = 0.9f,
                    topP = 0.95f,
                    topK = 40,
                    minP = 0.05f,
                    repeatPenalty = 1.05f,
                    maxTokens = 400,
                ),
            )
            result
                .onSuccess { text ->
                    _uiState.update { current ->
                        when (field) {
                            CharacterField.Name -> current.copy(name = text.take(40), generatingField = null)
                            CharacterField.Description -> current.copy(description = text, generatingField = null)
                            CharacterField.Personality -> current.copy(personality = text, generatingField = null)
                            CharacterField.Scenario -> current.copy(scenario = text, generatingField = null)
                            CharacterField.Greeting -> current.copy(greeting = text, generatingField = null)
                            CharacterField.ExampleDialogue -> current.copy(exampleDialogue = text, generatingField = null)
                            CharacterField.SystemPrompt -> current.copy(systemPrompt = text, generatingField = null)
                        }
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            generatingField = null,
                            fieldError = err.message
                                ?: getApplication<Application>().getString(R.string.editor_generate_failed),
                        )
                    }
                }
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.name.isBlank()) {
                _events.emit(
                    EditorEvent.Error(
                        getApplication<Application>().getString(R.string.editor_name_required),
                    ),
                )
                return@launch
            }
            characterRepository.save(state.toEntity())
            _events.emit(EditorEvent.Saved)
        }
    }

    fun export() {
        viewModelScope.launch {
            val entity = _uiState.value.toEntity()
            val json = characterRepository.exportJson(entity)
            _uiState.update { it.copy(exportJson = json) }
            _events.emit(EditorEvent.Exported(json))
        }
    }

    private fun buildGenerationPrompt(field: CharacterField, state: CharacterEditorUiState): String {
        val context = buildString {
            if (state.name.isNotBlank()) appendLine("Name: ${state.name}")
            if (state.description.isNotBlank()) appendLine("Description: ${state.description}")
            if (state.personality.isNotBlank()) appendLine("Personality: ${state.personality}")
            if (state.scenario.isNotBlank()) appendLine("Scenario: ${state.scenario}")
            if (state.greeting.isNotBlank()) appendLine("Greeting: ${state.greeting}")
        }
        val request = when (field) {
            CharacterField.Name -> "Invent a short, memorable character name."
            CharacterField.Description -> "Write a vivid character description (2-4 sentences)."
            CharacterField.Personality -> "Write personality traits and speaking style."
            CharacterField.Scenario -> "Write the roleplay scenario / setting."
            CharacterField.Greeting -> "Write the character's first message to start a chat. Use *actions* and \"dialogue\"."
            CharacterField.ExampleDialogue -> "Write a short example dialogue between {{user}} and {{char}} using *actions* and quotes."
            CharacterField.SystemPrompt -> "Write an optional system prompt that steers the character's behavior."
        }
        return "Known fields:\n$context\n\n$request"
    }

    private fun CharacterEntity.toUiState() = CharacterEditorUiState(
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
        loaded = true,
    )

    private fun CharacterEditorUiState.toEntity() = CharacterEntity(
        id = id,
        name = name.trim(),
        avatarUri = avatarUri,
        description = description,
        personality = personality,
        scenario = scenario,
        greeting = greeting,
        exampleDialogue = exampleDialogue,
        systemPrompt = systemPrompt,
        createdAt = createdAt,
    )

    sealed interface EditorEvent {
        data object Saved : EditorEvent
        data class Exported(val json: String) : EditorEvent
        data class Error(val message: String) : EditorEvent
    }
}
