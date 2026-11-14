package com.example.caramelai.domain

import androidx.room.withTransaction
import com.example.caramelai.data.db.AppDatabase
import com.example.caramelai.data.db.ConversationDao
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.data.db.MessageDao
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.MessageVariants
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.data.remote.ChatMessageDto
import com.example.caramelai.data.remote.ChatStreamEvent
import com.example.caramelai.data.remote.GenerationParams
import com.example.caramelai.data.remote.LlmClient
import com.example.caramelai.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class ChatRepository(
    private val characterRepository: CharacterRepository,
    private val personaRepository: PersonaRepository,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val database: AppDatabase,
) {
    fun observeConversations(characterId: String): Flow<List<ConversationEntity>> =
        conversationDao.observeByCharacter(characterId)

    fun observeConversation(id: String): Flow<ConversationEntity?> =
        conversationDao.observeById(id)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)

    suspend fun createConversation(
        characterId: String,
        personaId: String? = null,
    ): ConversationEntity {
        val character = characterRepository.getById(characterId)
            ?: error("Character not found")
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            title = "Chat with ${character.name}",
            personaId = personaId,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsert(conversation)
        if (character.greeting.isNotBlank()) {
            messageDao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversation.id,
                    role = MessageRole.ASSISTANT,
                    content = character.greeting,
                    createdAt = now,
                ),
            )
        }
        return conversation
    }

    suspend fun findOrCreateLatestConversation(
        characterId: String,
        defaultPersonaId: String? = null,
    ): ConversationEntity {
        return conversationDao.getLatestForCharacter(characterId)
            ?: createConversation(characterId, defaultPersonaId)
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    suspend fun setConversationPersona(conversationId: String, personaId: String?) {
        conversationDao.updatePersona(
            id = conversationId,
            personaId = personaId,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateConversationMemory(
        conversationId: String,
        summary: String,
        pinnedMemory: String,
    ) {
        conversationDao.updateMemory(
            id = conversationId,
            summary = summary.trim(),
            pinned = pinnedMemory.trim(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun addUserMessage(conversationId: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content.trim(),
            createdAt = System.currentTimeMillis(),
        )
        messageDao.upsert(message)
        touchConversation(conversationId)
        return message
    }

    /**
     * Updates a message and removes every message that came after it so the
     * model never keeps answering a superseded branch of the chat.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        content: String,
    ): MessageEntity? = database.withTransaction {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return@withTransaction null
        val existing = messages[index]
        val trimmed = content.trim()
        val updated = if (existing.role == MessageRole.ASSISTANT) {
            val variants = MessageVariants.listOf(existing).toMutableList()
            val active = existing.activeVariantIndex.coerceIn(0, variants.lastIndex)
            variants[active] = trimmed
            MessageVariants.withActive(existing, variants, active)
        } else {
            MessageVariants.withActive(
                existing,
                variants = listOf(trimmed),
                activeIndex = 0,
            )
        }
        messageDao.update(updated)
        deleteMessagesFrom(messages, index + 1)
        invalidateSummaryIfNeeded(conversationId, messages, index)
        touchConversation(conversationId)
        updated
    }

    /**
     * Deletes [messageId] and every later message. Removing only a middle
     * message leaves a broken turn order that gets merged and makes the model
     * answer the wrong prompt.
     */
    suspend fun deleteMessage(conversationId: String, messageId: String) {
        database.withTransaction {
            val messages = messageDao.getByConversation(conversationId)
            val index = messages.indexOfFirst { it.id == messageId }
            if (index < 0) return@withTransaction
            invalidateSummaryIfNeeded(conversationId, messages, index)
            deleteMessagesFrom(messages, index)
            touchConversation(conversationId)
        }
    }

    suspend fun deleteLastAssistantMessage(conversationId: String): Boolean {
        val messages = messageDao.getByConversation(conversationId)
        val last = messages.lastOrNull() ?: return false
        if (last.role != MessageRole.ASSISTANT) return false
        messageDao.deleteById(last.id)
        touchConversation(conversationId)
        return true
    }

    suspend fun getLastMessage(conversationId: String): MessageEntity? =
        messageDao.getByConversation(conversationId).lastOrNull()

    suspend fun getMessagesSnapshot(conversationId: String): List<MessageEntity> =
        messageDao.getByConversation(conversationId)

    suspend fun getSummaryCut(conversationId: String): String? =
        conversationDao.getById(conversationId)?.summaryUpToMessageId

    suspend fun appendToAssistantMessage(
        conversationId: String,
        messageId: String,
        addition: String,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val cleaned = sanitizeAssistantOutput(conversationId, addition)
        val joined = promptBuilder.joinAssistantContinuation(existing.content, cleaned)
        val variants = MessageVariants.listOf(existing).toMutableList()
        val index = existing.activeVariantIndex.coerceIn(0, variants.lastIndex)
        variants[index] = joined
        val updated = MessageVariants.withActive(existing, variants, index)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    /** Appends a regenerated reply as a new variant and selects it. */
    suspend fun addAssistantVariant(
        conversationId: String,
        messageId: String,
        content: String,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val cleaned = sanitizeAssistantOutput(conversationId, content)
        if (cleaned.isBlank()) return existing
        val variants = MessageVariants.listOf(existing) + cleaned
        val updated = MessageVariants.withActive(existing, variants, variants.lastIndex)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    suspend fun setActiveVariant(
        conversationId: String,
        messageId: String,
        index: Int,
    ): MessageEntity? {
        val existing = messageDao.getById(messageId) ?: return null
        if (existing.conversationId != conversationId) return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val variants = MessageVariants.listOf(existing)
        if (variants.isEmpty()) return existing
        val updated = MessageVariants.withActive(existing, variants, index)
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    suspend fun branchConversation(
        sourceConversationId: String,
        upToMessageId: String,
    ): ConversationEntity = database.withTransaction {
        val source = conversationDao.getById(sourceConversationId)
            ?: error("Conversation not found")
        val messages = messageDao.getByConversation(sourceConversationId)
        val cutIndex = messages.indexOfFirst { it.id == upToMessageId }
        if (cutIndex < 0) error("Message not found")
        val kept = messages.take(cutIndex + 1)
        val now = System.currentTimeMillis()
        val idMap = kept.associate { it.id to UUID.randomUUID().toString() }
        val branchedSummaryCut = source.summaryUpToMessageId
            ?.takeIf { cutId -> kept.any { it.id == cutId } }
            ?.let { idMap[it] }
        val branched = ConversationEntity(
            id = UUID.randomUUID().toString(),
            characterId = source.characterId,
            title = source.title + " (branch)",
            personaId = source.personaId,
            createdAt = now,
            updatedAt = now,
            summary = source.summary,
            summaryUpToMessageId = branchedSummaryCut,
            pinnedMemory = source.pinnedMemory,
        )
        conversationDao.upsert(branched)
        val copies = kept.mapIndexed { index, message ->
            message.copy(
                id = idMap[message.id] ?: UUID.randomUUID().toString(),
                conversationId = branched.id,
                createdAt = now + index,
            )
        }
        messageDao.upsertAll(copies)
        branched
    }

    data class StreamReplyPlan(
        val events: Flow<ChatStreamEvent>,
        val newSummaryUpToMessageId: String?,
        val droppedForSummary: List<MessageEntity>,
    )

    fun streamReply(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        continueMode: Boolean = false,
        excludeMessageId: String? = null,
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        strengthenStyleNote: Boolean = false,
    ): Flow<ChatStreamEvent> = flow {
        val plan = prepareStreamReply(
            conversationId = conversationId,
            settings = settings,
            characterId = characterId,
            continueMode = continueMode,
            excludeMessageId = excludeMessageId,
            avoidTexts = avoidTexts,
            revisionInstruction = revisionInstruction,
            strengthenStyleNote = strengthenStyleNote,
        )
        plan.events.collect { emit(it) }
    }

    suspend fun prepareStreamReply(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        continueMode: Boolean = false,
        excludeMessageId: String? = null,
        avoidTexts: List<String> = emptyList(),
        revisionInstruction: String? = null,
        strengthenStyleNote: Boolean = false,
    ): StreamReplyPlan {
        val character = characterRepository.getById(characterId)
            ?: error("Character not found")
        val conversation = conversationDao.getById(conversationId)
        val persona = resolvePersona(conversation?.personaId, settings)
        val history = messageDao.getByConversation(conversationId)
            .filter { excludeMessageId == null || it.id != excludeMessageId }
        val built = promptBuilder.buildDetailed(
            character = character,
            history = history,
            persona = persona,
            fallbackUserName = settings.userName,
            contextSize = settings.effectiveContextSize(),
            maxTokens = settings.maxTokens,
            continueMode = continueMode,
            globalBehavior = settings.globalBehavior,
            avoidTexts = avoidTexts,
            revisionInstruction = revisionInstruction,
            summary = conversation?.summary.orEmpty(),
            pinnedMemory = conversation?.pinnedMemory.orEmpty(),
            summaryUpToMessageId = conversation?.summaryUpToMessageId,
            strengthenStyleNote = strengthenStyleNote,
        )
        val rewriting = avoidTexts.isNotEmpty() || !revisionInstruction.isNullOrBlank()
        val params = GenerationParams(
            temperature = if (rewriting) {
                (settings.temperature + 0.15f).coerceAtMost(1.5f)
            } else {
                settings.temperature
            },
            topP = settings.topP,
            topK = settings.topK,
            minP = settings.minP,
            repeatPenalty = settings.repeatPenalty,
            maxTokens = settings.maxTokens,
            repeatLastN = settings.repeatLastN,
            dryMultiplier = if (rewriting) {
                (settings.dryMultiplier + 0.2f).coerceAtMost(2.0f)
            } else {
                settings.dryMultiplier
            },
            dryBase = settings.dryBase,
            dryAllowedLength = settings.dryAllowedLength,
            dryPenaltyLastN = settings.dryPenaltyLastN,
            xtcProbability = settings.xtcProbability,
            xtcThreshold = settings.xtcThreshold,
            idSlot = LlmClient.CHAT_SLOT,
        )
        val events = llmClient.streamChat(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = com.example.caramelai.data.remote.LlmModels.normalizeId(settings.modelId),
            messages = built.messages,
            params = params,
        )
        return StreamReplyPlan(
            events = events,
            newSummaryUpToMessageId = built.newSummaryUpToMessageId,
            droppedForSummary = built.droppedForSummary,
        )
    }

    suspend fun saveAssistantMessage(conversationId: String, content: String): MessageEntity {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = sanitizeAssistantOutput(conversationId, content),
            createdAt = System.currentTimeMillis(),
        )
        messageDao.upsert(message)
        touchConversation(conversationId)
        return message
    }

    /**
     * Folds [dropped] into the conversation summary after a block trim.
     * Safe to call after the user-facing reply is already saved.
     * Returns false if summarization failed or the cut was invalidated mid-flight
     * (so the cut point is left untouched for a later retry).
     */
    suspend fun applySummaryUpdate(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        newCutId: String?,
        dropped: List<MessageEntity>,
    ): Boolean {
        if (dropped.isEmpty() && newCutId == null) return true
        val conversation = conversationDao.getById(conversationId) ?: return false
        val expectedCut = conversation.summaryUpToMessageId
        val character = characterRepository.getById(characterId)
        val persona = resolvePersona(conversation.personaId, settings)
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: settings.userName
        val charName = character?.name ?: "Character"

        val mergedSummary = if (dropped.isEmpty()) {
            conversation.summary
        } else {
            summarizeDropped(
                settings = settings,
                characterName = charName,
                userName = userName,
                previousSummary = conversation.summary,
                dropped = dropped,
            ) ?: return false
        }

        // Re-read: discard if edit/delete invalidated the summary while we were generating.
        val fresh = conversationDao.getById(conversationId) ?: return false
        if (fresh.summaryUpToMessageId != expectedCut) return false
        if (newCutId != null) {
            val stillExists = messageDao.getById(newCutId) != null
            if (!stillExists) return false
        }

        conversationDao.updateSummary(
            id = conversationId,
            summary = mergedSummary,
            cutId = newCutId ?: fresh.summaryUpToMessageId,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    private suspend fun summarizeDropped(
        settings: AppSettings,
        characterName: String,
        userName: String,
        previousSummary: String,
        dropped: List<MessageEntity>,
    ): String? {
        val chunks = SummaryChunker.chunk(dropped, userName, characterName)
        var accumulated = previousSummary
        for (chunk in chunks) {
            val next = summarizeChunk(
                settings = settings,
                characterName = characterName,
                userName = userName,
                previousSummary = accumulated,
                chunk = chunk,
            ) ?: return null
            accumulated = next
        }
        return accumulated.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun summarizeChunk(
        settings: AppSettings,
        characterName: String,
        userName: String,
        previousSummary: String,
        chunk: List<MessageEntity>,
    ): String? {
        val transcript = chunk.joinToString("\n") { message ->
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            "$speaker: ${message.content.trim()}"
        }
        val prompt = buildString {
            appendLine("You are a concise story memory writer for a roleplay chat.")
            appendLine("Merge the previous summary with the new events into one updated summary.")
            appendLine("Keep names, relationships, locations, promises, and plot-critical facts.")
            appendLine("Write in third person, past tense, max 400 tokens. No dialogue.")
            appendLine()
            if (previousSummary.isNotBlank()) {
                appendLine("Previous summary:")
                appendLine(previousSummary.trim())
                appendLine()
            }
            appendLine("New events to fold in:")
            appendLine(transcript)
            appendLine()
            appendLine("Updated summary:")
        }
        val messages = listOf(
            ChatMessageDto(role = MessageRole.USER, content = prompt),
        )
        val params = GenerationParams(
            temperature = 0.3f,
            topP = 1.0f,
            topK = 0,
            minP = 0.05f,
            repeatPenalty = 1.0f,
            maxTokens = 400,
            dryMultiplier = 0f,
            xtcProbability = 0f,
            idSlot = LlmClient.SUMMARY_SLOT,
        )
        return llmClient.complete(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = com.example.caramelai.data.remote.LlmModels.normalizeId(settings.modelId),
            messages = messages,
            params = params,
        ).getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun invalidateSummaryIfNeeded(
        conversationId: String,
        messages: List<MessageEntity>,
        affectedIndex: Int,
    ) {
        val conversation = conversationDao.getById(conversationId) ?: return
        val cutId = conversation.summaryUpToMessageId ?: return
        val cutIndex = messages.indexOfFirst { it.id == cutId }
        if (cutIndex < 0 || affectedIndex <= cutIndex) {
            conversationDao.clearSummary(
                id = conversationId,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun sanitizeAssistantOutput(conversationId: String, content: String): String {
        val conversation = conversationDao.getById(conversationId) ?: return content
        val character = characterRepository.getById(conversation.characterId) ?: return content
        return promptBuilder.stripSpeakerPrefix(character.name, content)
    }

    private suspend fun deleteMessagesFrom(messages: List<MessageEntity>, fromIndex: Int) {
        if (fromIndex >= messages.size) return
        val fromCreatedAt = messages[fromIndex].createdAt
        val conversationId = messages[fromIndex].conversationId
        messageDao.deleteFrom(conversationId, fromCreatedAt)
    }

    private suspend fun resolvePersona(
        conversationPersonaId: String?,
        settings: AppSettings,
    ): PersonaEntity? {
        val id = conversationPersonaId ?: settings.defaultPersonaId ?: return null
        return personaRepository.getById(id)
    }

    private suspend fun touchConversation(conversationId: String) {
        conversationDao.touch(conversationId, System.currentTimeMillis())
    }

}
