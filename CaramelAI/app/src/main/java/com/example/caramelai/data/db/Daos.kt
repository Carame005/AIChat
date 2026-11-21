package com.example.caramelai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CharacterWithActivity(
    val id: String,
    val name: String,
    val avatarUri: String?,
    val description: String,
    val personality: String,
    val scenario: String,
    val greeting: String,
    val exampleDialogue: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastActivityAt: Long,
)

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query(
        """
        SELECT c.*, COALESCE(MAX(cv.updatedAt), c.updatedAt) AS lastActivityAt
        FROM characters c
        LEFT JOIN conversations cv ON cv.characterId = c.id
        GROUP BY c.id
        ORDER BY lastActivityAt DESC
        """,
    )
    fun observeAllByActivity(): Flow<List<CharacterWithActivity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun observeById(id: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): CharacterEntity?

    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    fun observeById(id: String): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: String): PersonaEntity?

    @Upsert
    suspend fun upsert(persona: PersonaEntity)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC")
    fun observeByCharacter(characterId: String): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun getLatestForCharacter(characterId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query(
        """
        UPDATE conversations
        SET summary = :summary, summaryUpToMessageId = :cutId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateSummary(id: String, summary: String, cutId: String?, updatedAt: Long)

    @Query(
        """
        UPDATE conversations
        SET summary = :summary, pinnedMemory = :pinned, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMemory(id: String, summary: String, pinned: String, updatedAt: Long)

    @Query(
        """
        UPDATE conversations
        SET summary = :summary,
            summaryUpToMessageId = :cutId,
            pinnedMemory = :pinned,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMemoryAndCut(
        id: String,
        summary: String,
        cutId: String?,
        pinned: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET pinnedMemory = :pinned, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updatePinned(id: String, pinned: String, updatedAt: Long)

    @Query(
        """
        UPDATE conversations
        SET previousSummary = :previousSummary,
            summary = '',
            summaryUpToMessageId = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun clearSummarySavingPrevious(
        id: String,
        previousSummary: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET personaId = :personaId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updatePersona(id: String, personaId: String?, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query(
        """
        UPDATE conversations
        SET summary = '', summaryUpToMessageId = NULL, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun clearSummary(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "DELETE FROM messages WHERE id = :id AND conversationId = :conversationId",
    )
    suspend fun deleteInConversation(id: String, conversationId: String)

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId AND createdAt >= :fromCreatedAt
        """,
    )
    suspend fun deleteFrom(conversationId: String, fromCreatedAt: Long)
}
