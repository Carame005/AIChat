package com.example.caramelai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String? = null,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterId")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val title: String,
    val personaId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Rolling long-term memory summarized from messages older than the cut point. */
    val summary: String = "",
    /** Last message id included in [summary]; history after this id is sent raw. */
    val summaryUpToMessageId: String? = null,
    /** User-editable key facts always injected into the system prompt. */
    val pinnedMemory: String = "",
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON array of regenerated texts for this turn. Empty means [content] only. */
    val variantsJson: String = "[]",
    val activeVariantIndex: Int = 0,
)

object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

object MessageVariants {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(variantsJson: String): List<String> {
        if (variantsJson.isBlank() || variantsJson == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(variantsJson)
        }.getOrDefault(emptyList())
    }

    fun encode(variants: List<String>): String =
        json.encodeToString(variants)

    /** Always returns at least the current [MessageEntity.content]. */
    fun listOf(message: MessageEntity): List<String> {
        val decoded = decode(message.variantsJson).filter { it.isNotBlank() }
        return if (decoded.isEmpty()) listOf(message.content) else decoded
    }

    fun withActive(message: MessageEntity, variants: List<String>, activeIndex: Int): MessageEntity {
        val safe = variants.filter { it.isNotBlank() }.ifEmpty { listOf(message.content) }
        val index = activeIndex.coerceIn(0, safe.lastIndex)
        return message.copy(
            content = safe[index],
            variantsJson = encode(safe),
            activeVariantIndex = index,
        )
    }
}
