package com.example.caramelai.domain

import com.example.caramelai.data.db.CharacterDao
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.CharacterWithActivity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CharacterRepository(
    private val characterDao: CharacterDao,
) {
    fun observeAll(): Flow<List<CharacterEntity>> = characterDao.observeAll()

    fun observeAllByActivity(): Flow<List<CharacterWithActivity>> =
        characterDao.observeAllByActivity()

    fun observeById(id: String): Flow<CharacterEntity?> = characterDao.observeById(id)

    suspend fun getById(id: String): CharacterEntity? = characterDao.getById(id)

    suspend fun save(character: CharacterEntity) {
        val existing = characterDao.getById(character.id)
        // Use @Upsert (not REPLACE): REPLACE deletes+inserts and CASCADE-wipes chats.
        characterDao.upsert(
            character.copy(
                createdAt = existing?.createdAt ?: character.createdAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun createBlank(): CharacterEntity {
        val now = System.currentTimeMillis()
        val entity = CharacterEntity(
            id = UUID.randomUUID().toString(),
            name = "New character",
            greeting = "Hello! How are you?",
            createdAt = now,
            updatedAt = now,
        )
        characterDao.upsert(entity)
        return entity
    }

    suspend fun delete(id: String) {
        characterDao.deleteById(id)
    }

    suspend fun importJson(raw: String): CharacterEntity {
        val entity = CharacterCardCodec.import(raw)
        characterDao.upsert(entity)
        return entity
    }

    fun exportJson(character: CharacterEntity): String =
        CharacterCardCodec.export(character)
}
