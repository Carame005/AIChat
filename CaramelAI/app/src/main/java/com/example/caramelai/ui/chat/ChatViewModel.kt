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
import com.example.caramelai.domain.PromptBuilder
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
    /** True while a pre-reply summary fold is running. */
    val isSummarizing: Boolean = false,
    val editingMessageId: String? = null,
    val editingText: String = "",
    /** When set, that assistant message is being regenerated (hidden while streaming). */
    val regeneratingMessageId: String? = null,
    val showRevisePrompt: Boolean = false,
    val revisePromptDraft: String = "",
    val showMemoryDialog: Boolean = false,
    val memorySummaryDraft: String = "",
    val memoryPinnedDraft: String = "",
    /** Snapshot of summary when the memory dialog opened; used to avoid clobbering. */
    val memorySummaryOriginal: String = "",
    /** Snapshot of summary cut when the memory dialog opened. */
    val memoryCutOriginal: String? = null,
    /** True when a background fold changed memory while the dialog was open. */
    val memoryConflict: Boolean = false,
    /** Saved summary before the last automatic clear (edit/delete before cut). */
    val memoryPreviousSummary: String = "",
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
                // Skip user turn: new assistant message that continues the scene.
                last.role == MessageRole.ASSISTANT -> generate(
                    continueMode = true,
                    appendToMessageId = null,
                    previousForContinue = last.content,
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
        val continuePrevious = pendingContinuePrevious
        generationJob?.cancel()
        generationJob = null
        pendingAppendToMessageId = null
        pendingReplaceMessageId = null
        pendingContinuePrevious = null
        viewModelScope.launch {
            if (partial.isNotBlank()) {
                when {
                    appendToId != null ->
                        chatRepository.appendToAssistantMessage(conversationId, appendToId, partial)
                    replaceId != null ->
                        chatRepository.addAssistantVariant(conversationId, replaceId, partial)
                    continuePrevious != null ->
                        chatRepository.saveContinuationMessage(
                            conversationId,
                            continuePrevious,
                            partial,
                        )
                    else ->
                        chatRepository.saveAssistantMessage(conversationId, partial)
                }
            }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    isSummarizing = false,
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
    private var pendingContinuePrevious: String? = null
    private var strengthenNextStyleNote: Boolean = false

    val isMemoryBusy: StateFlow<Boolean> =
        summaryCoordinator.active
            .combine(uiState) { active, state ->
                conversationId in active || state.isSummarizing
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun beginMemoryEdit() {
        val conv = conversation.value
        val summary = conv?.summary.orEmpty()
        _uiState.update {
            it.copy(
                showMemoryDialog = true,
                memorySummaryDraft = summary,
                memorySummaryOriginal = summary,
                memoryCutOriginal = conv?.summaryUpToMessageId,
                memoryPinnedDraft = conv?.pinnedMemory.orEmpty(),
                memoryConflict = false,
                memoryPreviousSummary = conv?.previousSummary.orEmpty(),
            )
        }
    }

    fun updateMemorySummaryDraft(value: String) =
        _uiState.update { it.copy(memorySummaryDraft = value, memoryConflict = false) }

    fun updateMemoryPinnedDraft(value: String) =
        _uiState.update { it.copy(memoryPinnedDraft = value) }

    fun cancelMemoryEdit() = _uiState.update {
        it.copy(
            showMemoryDialog = false,
            memorySummaryDraft = "",
            memorySummaryOriginal = "",
            memoryCutOriginal = null,
            memoryPinnedDraft = "",
            memoryConflict = false,
            memoryPreviousSummary = "",
        )
    }

    fun restorePreviousSummary() {
        val previous = _uiState.value.memoryPreviousSummary
        if (previous.isBlank()) return
        _uiState.update {
            it.copy(
                memorySummaryDraft = previous,
                memoryConflict = false,
            )
        }
    }

    fun commitMemoryEdit() {
        val summary = _uiState.value.memorySummaryDraft
        val original = _uiState.value.memorySummaryOriginal
        val expectedCut = _uiState.value.memoryCutOriginal
        val pinned = _uiState.value.memoryPinnedDraft
        viewModelScope.launch {
            summaryCoordinator.withConversationLock(conversationId) {
                if (summary == original) {
                    chatRepository.updatePinnedMemory(conversationId, pinned)
                    cancelMemoryEdit()
                } else {
                    when (
                        val result = chatRepository.updateConversationMemory(
                            conversationId = conversationId,
                            summary = summary,
                            pinnedMemory = pinned,
                            expectedSummary = original,
                            expectedCut = expectedCut,
                        )
                    ) {
                        is ChatRepository.MemoryUpdateResult.Updated -> cancelMemoryEdit()
                        is ChatRepository.MemoryUpdateResult.Conflict -> {
                            _uiState.update {
                                it.copy(
                                    memorySummaryDraft = result.current.summary,
                                    memorySummaryOriginal = result.current.summary,
                                    memoryCutOriginal = result.current.summaryUpToMessageId,
                                    memoryPinnedDraft = result.current.pinnedMemory,
                                    memoryPreviousSummary = result.current.previousSummary,
                                    memoryConflict = true,
                                )
                            }
                        }
                    }
                }
            }
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
                isSummarizing = false,
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
        previousForContinue: String = "",
        continueRejectedOpening: String? = null,
        alreadyBegan: Boolean = false,
        strengthenStyleNote: Boolean? = null,
        autoRetried: Boolean = false,
    ) {
        if (!alreadyBegan && !tryBeginGeneration()) return
        generationJob?.cancel()
        pendingAppendToMessageId = appendToMessageId
        pendingReplaceMessageId = replaceMessageId
        pendingContinuePrevious = if (continueMode && previousForContinue.isNotBlank()) {
            previousForContinue
        } else {
            null
        }
        val useStrongStyle = strengthenStyleNote ?: run {
            val flag = strengthenNextStyleNote
            strengthenNextStyleNote = false
            flag
        }
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    isSummarizing = false,
                    streamingText = "",
                    regeneratingMessageId = replaceMessageId,
                )
            }
            try {
                val settings = settingsRepository.settings.first()
                val snapshot = chatRepository.getMessagesSnapshot(conversationId)
                val priorAssistant = snapshot
                    .filter { it.role == MessageRole.ASSISTANT && it.id != replaceMessageId }
                    .takeLast(4)
                    .map { it.content }
                val lastUser = snapshot.lastOrNull { it.role == MessageRole.USER }?.content
                val continuePrevious = if (continueMode) {
                    previousForContinue.ifBlank { priorAssistant.lastOrNull().orEmpty() }
                } else {
                    ""
                }
                val effectiveCut = summaryCoordinator.effectiveCut(conversationId, snapshot)
                val inFlightDropped = summaryCoordinator.pendingDropped(conversationId)
                val plan = chatRepository.prepareStreamReply(
                    conversationId = conversationId,
                    settings = settings,
                    characterId = characterId,
                    continueMode = continueMode,
                    excludeMessageId = replaceMessageId,
                    avoidTexts = avoidTexts,
                    revisionInstruction = revisionInstruction,
                    strengthenStyleNote = useStrongStyle,
                    continueRejectedOpening = continueRejectedOpening,
                    effectiveSummaryCut = effectiveCut,
                    inFlightDropped = inFlightDropped,
                    fold = { cid, foldSettings, charId, cutId, dropped ->
                        summaryCoordinator.foldNow(cid, foldSettings, charId, cutId, dropped)
                    },
                    onSummarizing = { summarizing ->
                        _uiState.update { it.copy(isSummarizing = summarizing) }
                    },
                )
                // Register shadow cut + coalesce ASAP (before streaming) so a follow-up
                // message cannot rediscover the same overflow while the fold LLM runs.
                // Regenerating / revising must never advance the cut.
                // Skip tiny overflows — prepareStreamReply already cleared those from the plan.
                if (replaceMessageId == null) {
                    val promptBuilder = PromptBuilder()
                    val characterName = character.value?.name ?: "Character"
                    val userName = activePersona.value?.name?.takeIf { it.isNotBlank() }
                        ?: settings.userName
                    val pending = promptBuilder.pendingSummaryWork(
                        history = snapshot,
                        currentCut = effectiveCut,
                        proposedCut = plan.newSummaryUpToMessageId,
                        dropped = plan.droppedForSummary,
                    )
                    if (pending.hasWork &&
                        promptBuilder.shouldAdvanceSummaryCut(
                            history = snapshot,
                            currentCut = effectiveCut,
                            dropped = pending.dropped,
                            userName = userName,
                            characterName = characterName,
                        )
                    ) {
                        summaryCoordinator.schedule(
                            conversationId = conversationId,
                            settings = settings,
                            characterId = characterId,
                            cutId = pending.cutId,
                            dropped = pending.dropped,
                        )
                    }
                }
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
                val finalText = buffer.toString().trim()
                if (finalText.isNotBlank()) {
                    val continueEcho = continueMode &&
                        RepetitionDetector.isReanswer(
                            draft = finalText,
                            lastUser = lastUser,
                            lastAssistant = continuePrevious,
                        )
                    if (continueEcho && !autoRetried) {
                        // Discard and retry once with a stronger anti-repetition note.
                        generate(
                            continueMode = true,
                            appendToMessageId = null,
                            previousForContinue = continuePrevious,
                            continueRejectedOpening = PromptBuilder().openingPhrase(finalText),
                            alreadyBegan = true,
                            strengthenStyleNote = true,
                            autoRetried = true,
                        )
                        return@launch
                    }
                    if (continueEcho && autoRetried) {
                        _events.emit(
                            ChatEvent.Error(
                                getApplication<Application>()
                                    .getString(R.string.chat_continue_repeated),
                            ),
                        )
                    } else {
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
                                    finalText,
                                )
                            continueMode && continuePrevious.isNotBlank() ->
                                chatRepository.saveContinuationMessage(
                                    conversationId,
                                    continuePrevious,
                                    finalText,
                                )
                            else ->
                                chatRepository.saveAssistantMessage(conversationId, finalText)
                        }
                        if (RepetitionDetector.shouldStrengthenStyle(finalText, priorAssistant)) {
                            strengthenNextStyleNote = true
                        }
                    }
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
                    pendingContinuePrevious = null
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            isSummarizing = false,
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
