package com.example.caramelai.domain

import com.example.caramelai.data.db.PersonaDao
import com.example.caramelai.data.db.PersonaEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PersonaRepository(
    private val personaDao: PersonaDao,
) {
    fun observeAll(): Flow<List<PersonaEntity>> = personaDao.observeAll()

    fun observeById(id: String): Flow<PersonaEntity?> = personaDao.observeById(id)

    suspend fun getById(id: String): PersonaEntity? = personaDao.getById(id)

    suspend fun save(persona: PersonaEntity) {
        personaDao.upsert(persona.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun createBlank(name: String = "New persona"): PersonaEntity {
        val now = System.currentTimeMillis()
        val entity = PersonaEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
        )
        personaDao.upsert(entity)
        return entity
    }

    suspend fun delete(id: String) {
        personaDao.deleteById(id)
    }
}
