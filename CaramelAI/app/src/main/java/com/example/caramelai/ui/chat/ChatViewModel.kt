package com.example.caramelai.ui.chat

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.R
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.MessageVariants
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatStreamEvent
import com.example.caramelai.data.settings.SettingsRepository
import com.example.caramelai.domain.CharacterRepository
import com.example.caramelai.domain.ChatRepository
import com.example.caramelai.domain.PersonaRepository
import com.example.caramelai.domain.RepetitionDetector
import com.example.caramelai.domain.SummaryCoordinator
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
    /** When set, that assistant message is being regenerated (hidden while streaming). */
    val regeneratingMessageId: String? = null,
    val showRevisePrompt: Boolean = false,
    val revisePromptDraft: String = "",
    val showMemoryDialog: Boolean = false,
    val memorySummaryDraft: String = "",
    val memoryPinnedDraft: String = "",
)

class ChatViewModel(
    application: Application,
    private val conversationId: String,
    private val characterId: String,
    private val chatRepository: ChatRepository,
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val summaryCoordinator: SummaryCoordinator,
) : AndroidViewModel(application) {
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
        if (text.isEmpty() || !tryBeginGeneration()) return
        viewModelScope.launch {
            chatRepository.addUserMessage(conversationId, text)
            _uiState.update { it.copy(draft = "") }
            generate(
                continueMode = false,
                appendToMessageId = null,
                alreadyBegan = true,
            )
        }
    }

    fun continueReply() {
        if (!tryBeginGeneration()) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId)
            when {
                last == null -> {
                    clearGenerating()
                    return@launch
                }
                // No reply yet (e.g. deleted last assistant): answer the pending user turn.
                last.role == MessageRole.USER -> generate(
                    continueMode = false,
                    appendToMessageId = null,
                    alreadyBegan = true,
                )
                // Truncated / "keep going": append onto the last assistant bubble.
                last.role == MessageRole.ASSISTANT -> generate(
                    continueMode = true,
                    appendToMessageId = last.id,
                    alreadyBegan = true,
                )
                else -> generate(
                    continueMode = false,
                    appendToMessageId = null,
                    alreadyBegan = true,
                )
            }
        }
    }

    fun stop() {
        // Capture before cancel: generate()'s finally clears streamingText.
        val partial = _uiState.value.streamingText
        val appendToId = pendingAppendToMessageId
        val replaceId = pendingReplaceMessageId
        generationJob?.cancel()
        generationJob = null
        pendingAppendToMessageId = null
        pendingReplaceMessageId = null
        viewModelScope.launch {
            if (partial.isNotBlank()) {
                when {
                    appendToId != null ->
                        chatRepository.appendToAssistantMessage(conversationId, appendToId, partial)
                    replaceId != null ->
                        chatRepository.addAssistantVariant(conversationId, replaceId, partial)
                    else ->
                        chatRepository.saveAssistantMessage(conversationId, partial)
                }
            }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                    regeneratingMessageId = null,
                )
            }
        }
    }

    fun regenerate() {
        if (!tryBeginGeneration()) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId)
            if (last == null || last.role != MessageRole.ASSISTANT) {
                clearGenerating()
                return@launch
            }
            val previous = MessageVariants.listOf(last)
            generate(
                continueMode = false,
                appendToMessageId = null,
                replaceMessageId = last.id,
                avoidTexts = previous,
                alreadyBegan = true,
            )
        }
    }

    fun beginRevisePrompt() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId) ?: return@launch
            if (last.role != MessageRole.ASSISTANT) return@launch
            _uiState.update {
                it.copy(showRevisePrompt = true, revisePromptDraft = "")
            }
        }
    }

    fun updateRevisePromptDraft(value: String) =
        _uiState.update { it.copy(revisePromptDraft = value) }

    fun cancelRevisePrompt() = _uiState.update {
        it.copy(showRevisePrompt = false, revisePromptDraft = "")
    }

    fun commitRevisePrompt() {
        val instruction = _uiState.value.revisePromptDraft.trim()
        if (instruction.isEmpty() || !tryBeginGeneration()) return
        viewModelScope.launch {
            val last = chatRepository.getLastMessage(conversationId)
            if (last == null || last.role != MessageRole.ASSISTANT) {
                clearGenerating()
                return@launch
            }
            val previous = MessageVariants.listOf(last)
            _uiState.update { it.copy(showRevisePrompt = false, revisePromptDraft = "") }
            generate(
                continueMode = false,
                appendToMessageId = null,
                replaceMessageId = last.id,
                avoidTexts = previous,
                revisionInstruction = instruction,
                alreadyBegan = true,
            )
        }
    }

    fun selectPreviousVariant(messageId: String) {
        shiftVariant(messageId, delta = -1)
    }

    fun selectNextVariant(messageId: String) {
        shiftVariant(messageId, delta = 1)
    }

    private fun shiftVariant(messageId: String, delta: Int) {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val message = messages.value.firstOrNull { it.id == messageId } ?: return@launch
            if (message.role != MessageRole.ASSISTANT) return@launch
            val variants = MessageVariants.listOf(message)
            if (variants.size <= 1) return@launch
            val next = (message.activeVariantIndex + delta).coerceIn(0, variants.lastIndex)
            if (next == message.activeVariantIndex) return@launch
            chatRepository.setActiveVariant(conversationId, messageId, next)
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
                _events.emit(
                    ChatEvent.Error(
                        it.message ?: getApplication<Application>().getString(R.string.chat_branch_failed),
                    ),
                )
            }
        }
    }

    fun exportCharacter() {
        viewModelScope.launch {
            val current = characterRepository.getById(characterId)
            if (current == null) {
                _events.emit(
                    ChatEvent.Error(
                        getApplication<Application>().getString(R.string.chat_character_missing),
                    ),
                )
                return@launch
            }
            val json = characterRepository.exportJson(current)
            _events.emit(ChatEvent.Exported(json))
        }
    }

    private var pendingAppendToMessageId: String? = null
    private var pendingReplaceMessageId: String? = null
    private var strengthenNextStyleNote: Boolean = false

    fun beginMemoryEdit() {
        val conv = conversation.value
        _uiState.update {
            it.copy(
                showMemoryDialog = true,
                memorySummaryDraft = conv?.summary.orEmpty(),
                memoryPinnedDraft = conv?.pinnedMemory.orEmpty(),
            )
        }
    }

    fun updateMemorySummaryDraft(value: String) =
        _uiState.update { it.copy(memorySummaryDraft = value) }

    fun updateMemoryPinnedDraft(value: String) =
        _uiState.update { it.copy(memoryPinnedDraft = value) }

    fun cancelMemoryEdit() = _uiState.update {
        it.copy(
            showMemoryDialog = false,
            memorySummaryDraft = "",
            memoryPinnedDraft = "",
        )
    }

    fun commitMemoryEdit() {
        val summary = _uiState.value.memorySummaryDraft
        val pinned = _uiState.value.memoryPinnedDraft
        viewModelScope.launch {
            chatRepository.updateConversationMemory(conversationId, summary, pinned)
            cancelMemoryEdit()
        }
    }

    /**
     * Atomically claims generation. Returns false if already generating.
     */
    private fun tryBeginGeneration(): Boolean {
        var claimed = false
        _uiState.update { state ->
            if (state.isGenerating) {
                claimed = false
                state
            } else {
                claimed = true
                state.copy(isGenerating = true)
            }
        }
        return claimed
    }

    private fun clearGenerating() {
        _uiState.update {
            it.copy(
                isGenerating = false,
                streamingText = "",
                regeneratingMessageId = null,
            )
        }
    }

    private fun generate(
        continueMode: Boolean,
        appendToMessageId: String?,
        replaceMessageId: String? = null,
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        alreadyBegan: Boolean = false,
    ) {
        if (!alreadyBegan && !tryBeginGeneration()) return
        generationJob?.cancel()
        pendingAppendToMessageId = appendToMessageId
        pendingReplaceMessageId = replaceMessageId
        val useStrongStyle = strengthenNextStyleNote
        strengthenNextStyleNote = false
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingText = "",
                    regeneratingMessageId = replaceMessageId,
                )
            }
            try {
                val settings = settingsRepository.settings.first()
                val priorAssistant = messages.value
                    .filter { it.role == MessageRole.ASSISTANT && it.id != replaceMessageId }
                    .takeLast(4)
                    .map { it.content }
                val plan = chatRepository.prepareStreamReply(
                    conversationId = conversationId,
                    settings = settings,
                    characterId = characterId,
                    continueMode = continueMode,
                    excludeMessageId = replaceMessageId,
                    avoidTexts = avoidTexts,
                    revisionInstruction = revisionInstruction,
                    strengthenStyleNote = useStrongStyle,
                )
                val buffer = StringBuilder()
                var finishReason: String? = null
                var lastUiAt = 0L
                plan.events.collect { event ->
                    when (event) {
                        is ChatStreamEvent.Chunk -> {
                            buffer.append(event.text)
                            val now = SystemClock.uptimeMillis()
                            if (now - lastUiAt >= STREAM_UI_THROTTLE_MS) {
                                lastUiAt = now
                                _uiState.update { it.copy(streamingText = buffer.toString()) }
                            }
                        }
                        is ChatStreamEvent.Done -> {
                            finishReason = event.finishReason
                        }
                    }
                }
                // Always flush the final buffer so the UI matches what we persist.
                if (buffer.isNotEmpty()) {
                    _uiState.update { it.copy(streamingText = buffer.toString()) }
                }
                val finalText = buffer.toString()
                if (finalText.isNotBlank()) {
                    when {
                        appendToMessageId != null ->
                            chatRepository.appendToAssistantMessage(
                                conversationId,
                                appendToMessageId,
                                finalText,
                            )
                        replaceMessageId != null ->
                            chatRepository.addAssistantVariant(
                                conversationId,
                                replaceMessageId,
                                finalText.trim(),
                            )
                        else ->
                            chatRepository.saveAssistantMessage(conversationId, finalText.trim())
                    }
                    if (!continueMode &&
                        RepetitionDetector.shouldStrengthenStyle(finalText.trim(), priorAssistant)
                    ) {
                        strengthenNextStyleNote = true
                    }
                }
                if (plan.droppedForSummary.isNotEmpty() || plan.newSummaryUpToMessageId != null) {
                    summaryCoordinator.schedule(
                        conversationId = conversationId,
                        settings = settings,
                        characterId = characterId,
                        cutId = plan.newSummaryUpToMessageId,
                        dropped = plan.droppedForSummary,
                    )
                }
                if (finishReason.equals("length", ignoreCase = true)) {
                    _events.emit(
                        ChatEvent.Error(
                            getApplication<Application>().getString(R.string.chat_truncated_hint),
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
                    pendingReplaceMessageId = null
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            streamingText = "",
                            regeneratingMessageId = null,
                        )
                    }
                }
            }
        }
    }

    sealed interface ChatEvent {
        data class Error(val message: String) : ChatEvent
        data class OpenConversation(val conversationId: String) : ChatEvent
        data class Exported(val json: String) : ChatEvent
    }

    companion object {
        private const val STREAM_UI_THROTTLE_MS = 50L
    }
}
