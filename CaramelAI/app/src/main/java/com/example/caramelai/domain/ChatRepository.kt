package com.example.caramelai.domain

import com.example.caramelai.data.db.ConversationDao
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.data.db.MessageDao
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.PersonaEntity
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
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(
            conversation.copy(
                personaId = personaId,
                updatedAt = System.currentTimeMillis(),
            ),
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
    ): MessageEntity? {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val existing = messages[index]
        val updated = existing.copy(content = content.trim())
        messageDao.update(updated)
        deleteMessagesFrom(messages, index + 1)
        touchConversation(conversationId)
        return updated
    }

    /**
     * Deletes [messageId] and every later message. Removing only a middle
     * message leaves a broken turn order that gets merged and makes the model
     * answer the wrong prompt.
     */
    suspend fun deleteMessage(conversationId: String, messageId: String) {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        deleteMessagesFrom(messages, index)
        touchConversation(conversationId)
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

    suspend fun appendToAssistantMessage(
        conversationId: String,
        messageId: String,
        addition: String,
    ): MessageEntity? {
        val messages = messageDao.getByConversation(conversationId)
        val existing = messages.firstOrNull { it.id == messageId } ?: return null
        if (existing.role != MessageRole.ASSISTANT) return null
        val cleaned = sanitizeAssistantOutput(conversationId, addition)
        val updated = existing.copy(
            content = promptBuilder.joinAssistantContinuation(existing.content, cleaned),
        )
        messageDao.update(updated)
        touchConversation(conversationId)
        return updated
    }

    suspend fun branchConversation(
        sourceConversationId: String,
        upToMessageId: String,
    ): ConversationEntity {
        val source = conversationDao.getById(sourceConversationId)
            ?: error("Conversation not found")
        val messages = messageDao.getByConversation(sourceConversationId)
        val cutIndex = messages.indexOfFirst { it.id == upToMessageId }
        if (cutIndex < 0) error("Message not found")
        val kept = messages.take(cutIndex + 1)
        val now = System.currentTimeMillis()
        val branched = ConversationEntity(
            id = UUID.randomUUID().toString(),
            characterId = source.characterId,
            title = source.title + " (branch)",
            personaId = source.personaId,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsert(branched)
        val copies = kept.mapIndexed { index, message ->
            message.copy(
                id = UUID.randomUUID().toString(),
                conversationId = branched.id,
                createdAt = now + index,
            )
        }
        messageDao.upsertAll(copies)
        return branched
    }

    fun streamReply(
        conversationId: String,
        settings: AppSettings,
        characterId: String,
        continueMode: Boolean = false,
    ): Flow<ChatStreamEvent> = flow {
        val character = characterRepository.getById(characterId)
            ?: error("Character not found")
        val conversation = conversationDao.getById(conversationId)
        val persona = resolvePersona(conversation?.personaId, settings)
        val history = messageDao.getByConversation(conversationId)
        val messages = promptBuilder.build(
            character = character,
            history = history,
            persona = persona,
            fallbackUserName = settings.userName,
            contextSize = settings.contextSize,
            maxTokens = settings.maxTokens,
            continueMode = continueMode,
            globalBehavior = settings.globalBehavior,
        )
        val params = GenerationParams(
            temperature = settings.temperature,
            topP = settings.topP,
            topK = settings.topK,
            minP = settings.minP,
            repeatPenalty = settings.repeatPenalty,
            maxTokens = settings.maxTokens,
        )
        llmClient.streamChat(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            messages = messages,
            params = params,
        ).collect { emit(it) }
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

    private suspend fun sanitizeAssistantOutput(conversationId: String, content: String): String {
        val conversation = conversationDao.getById(conversationId) ?: return content
        val character = characterRepository.getById(conversation.characterId) ?: return content
        return promptBuilder.stripSpeakerPrefix(character.name, content)
    }

    private suspend fun deleteMessagesFrom(messages: List<MessageEntity>, fromIndex: Int) {
        if (fromIndex >= messages.size) return
        for (i in fromIndex until messages.size) {
            messageDao.deleteById(messages[i].id)
        }
    }

    private suspend fun resolvePersona(
        conversationPersonaId: String?,
        settings: AppSettings,
    ): PersonaEntity? {
        val id = conversationPersonaId ?: settings.defaultPersonaId ?: return null
        return personaRepository.getById(id)
    }

    private suspend fun touchConversation(conversationId: String) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
    }
}
