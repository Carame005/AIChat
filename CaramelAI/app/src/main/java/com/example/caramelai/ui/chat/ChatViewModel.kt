package com.example.caramelai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatStreamEvent
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.CharacterRepository
import com.example.caramelai.domain.ChatRepository
import com.example.caramelai.domain.PersonaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val draft: String = "",
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val editingMessageId: String? = null,
    val editingText: String = "",
)

class ChatViewModel(
    private val conversationId: String,
    private val characterId: String,
    private val chatRepository: ChatRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val character: StateFlow<CharacterEntity?> =
        characterRepository.observeById(characterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val conversation: StateFlow<ConversationEntity?> =
        chatRepository.observeConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> =
        chatRepository.observeMessages(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val personas: StateFlow<List<PersonaEntity>> =
        personaRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> =
        chatRepository.observeConversations(characterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activePersona: StateFlow<PersonaEntity?> =
        combine(
            conversation,
            settingsRepository.settings,
            personas,
        ) { conv, settings, all ->
            val id = conv?.personaId ?: settings.defaultPersonaId
            all.firstOrNull { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>()
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private var generationJob: Job? = null

    fun updateDraft(value: String) = _uiState.update { it.copy(draft = value) }

    fun sendOrContinue() {
        val text = _uiState.value.draft.trim()
        if (_uiState.value.isGenerating) return
        if (text.isEmpty()) {
            continueReply()
        } else {
            send(text)
        }
    }

    fun send(text: String = _uiState.value.draft.trim()) {
        if (text.isEmpty() || _uiState.value.isGenerating) return
        viewModelScope.launch {
            chatRepository.addUserMessage(conversationId, text)
            _uiState.update { it.copy(draft = "") }
            generate(
                continueMode = false,
                appendToMessageId = null,
            )
        }
    }

    fun continueReply() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId)
            when {
                last == null -> return@launch
                // No reply yet (e.g. deleted last assistant): answer the pending user turn.
                last.role == MessageRole.USER -> generate(
                    continueMode = false,
                    appendToMessageId = null,
                )
                // Truncated / "keep going": append onto the last assistant bubble.
                last.role == MessageRole.ASSISTANT -> generate(
                    continueMode = true,
                    appendToMessageId = last.id,
                )
                else -> generate(
                    continueMode = false,
                    appendToMessageId = null,
                )
            }
        }
    }

    fun stop() {
        // Capture before cancel: generate()'s finally clears streamingText.
        val partial = _uiState.value.streamingText
        val appendToId = pendingAppendToMessageId
        generationJob?.cancel()
        generationJob = null
        pendingAppendToMessageId = null
        viewModelScope.launch {
            if (partial.isNotBlank()) {
                if (appendToId != null) {
                    chatRepository.appendToAssistantMessage(conversationId, appendToId, partial)
                } else {
                    chatRepository.saveAssistantMessage(conversationId, partial)
                }
            }
            _uiState.update { it.copy(isGenerating = false, streamingText = "") }
        }
    }

    fun regenerate() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId) ?: return@launch
            if (last.role == MessageRole.ASSISTANT) {
                chatRepository.deleteLastAssistantMessage(conversationId)
            }
            generate(
                continueMode = false,
                appendToMessageId = null,
            )
        }
    }

    fun beginEdit(message: MessageEntity) {
        _uiState.update {
            it.copy(editingMessageId = message.id, editingText = message.content)
        }
    }

    fun updateEditingText(value: String) = _uiState.update { it.copy(editingText = value) }

    fun cancelEdit() = _uiState.update {
        it.copy(editingMessageId = null, editingText = "")
    }

    fun commitEdit() {
        val id = _uiState.value.editingMessageId ?: return
        val text = _uiState.value.editingText
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val updated = chatRepository.editMessage(conversationId, id, text) ?: run {
                cancelEdit()
                return@launch
            }
            cancelEdit()
            // Editing a user turn replaces that prompt: drop later messages and reply again.
            if (updated.role == MessageRole.USER) {
                generate(
                    continueMode = false,
                    appendToMessageId = null,
                )
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            chatRepository.deleteMessage(conversationId, messageId)
        }
    }

    fun setPersona(personaId: String?) {
        viewModelScope.launch {
            chatRepository.setConversationPersona(conversationId, personaId)
        }
    }

    fun createNewChat() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val conversation = chatRepository.createConversation(
                characterId = characterId,
                personaId = conversation.value?.personaId ?: settings.defaultPersonaId,
            )
            _events.emit(ChatEvent.OpenConversation(conversation.id))
        }
    }

    fun openConversation(id: String) {
        viewModelScope.launch {
            _events.emit(ChatEvent.OpenConversation(id))
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
    }

    fun branchFrom(messageId: String) {
        viewModelScope.launch {
            runCatching {
                chatRepository.branchConversation(conversationId, messageId)
            }.onSuccess { branched ->
                _events.emit(ChatEvent.OpenConversation(branched.id))
            }.onFailure {
                _events.emit(ChatEvent.Error(it.message ?: "No se pudo crear la rama"))
            }
        }
    }

    fun exportCharacter() {
        viewModelScope.launch {
            val current = characterRepository.getById(characterId)
            if (current == null) {
                _events.emit(ChatEvent.Error("Personaje no encontrado"))
                return@launch
            }
            val json = characterRepository.exportJson(current)
            _events.emit(ChatEvent.Exported(json))
        }
    }

    private var pendingAppendToMessageId: String? = null

    private fun generate(
        continueMode: Boolean,
        appendToMessageId: String?,
    ) {
        generationJob?.cancel()
        pendingAppendToMessageId = appendToMessageId
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, streamingText = "") }
            try {
                val settings = settingsRepository.settings.first()
                val buffer = StringBuilder()
                var finishReason: String? = null
                chatRepository.streamReply(
                    conversationId = conversationId,
                    settings = settings,
                    characterId = characterId,
                    continueMode = continueMode,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Chunk -> {
                            buffer.append(event.text)
                            _uiState.update { it.copy(streamingText = buffer.toString()) }
                        }
                        is ChatStreamEvent.Done -> {
                            finishReason = event.finishReason
                        }
                    }
                }
                val finalText = buffer.toString()
                if (finalText.isNotBlank()) {
                    val targetId = appendToMessageId
                    if (targetId != null) {
                        chatRepository.appendToAssistantMessage(
                            conversationId,
                            targetId,
                            finalText,
                        )
                    } else {
                        chatRepository.saveAssistantMessage(conversationId, finalText.trim())
                    }
                }
                if (finishReason.equals("length", ignoreCase = true)) {
                    _events.emit(
                        ChatEvent.Error(
                            "Respuesta cortada por el límite de tokens. Pulsa Continuar para seguir.",
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _events.emit(ChatEvent.Error(t.message ?: "Generation failed"))
            } finally {
                if (generationJob === this.coroutineContext[Job]) {
                    pendingAppendToMessageId = null
                    _uiState.update { it.copy(isGenerating = false, streamingText = "") }
                }
            }
        }
    }

    sealed interface ChatEvent {
        data class Error(val message: String) : ChatEvent
        data class OpenConversation(val conversationId: String) : ChatEvent
        data class Exported(val json: String) : ChatEvent
    }
}
