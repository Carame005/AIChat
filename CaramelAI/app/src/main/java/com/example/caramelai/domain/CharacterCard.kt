package com.example.caramelai.domain

import com.example.caramelai.data.db.CharacterEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class CharacterCardV2(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    val spec: String? = null,
    @SerialName("spec_version") val specVersion: String? = null,
    val data: CharacterCardData? = null,
)

@Serializable
data class CharacterCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
)

object CharacterCardCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun export(character: CharacterEntity): String {
        val card = CharacterCardV2(
            name = character.name,
            description = character.description,
            personality = character.personality,
            scenario = character.scenario,
            firstMes = character.greeting,
            mesExample = character.exampleDialogue,
            systemPrompt = character.systemPrompt,
            spec = "chara_card_v2",
            specVersion = "2.0",
            data = CharacterCardData(
                name = character.name,
                description = character.description,
                personality = character.personality,
                scenario = character.scenario,
                firstMes = character.greeting,
                mesExample = character.exampleDialogue,
                systemPrompt = character.systemPrompt,
            ),
        )
        return json.encodeToString(CharacterCardV2.serializer(), card)
    }

    fun import(raw: String, existingId: String? = null): CharacterEntity {
        val card = json.decodeFromString(CharacterCardV2.serializer(), raw)
        val data = card.data
        val name = data?.name?.ifBlank { null } ?: card.name
        val description = data?.description?.ifBlank { null } ?: card.description
        val personality = data?.personality?.ifBlank { null } ?: card.personality
        val scenario = data?.scenario?.ifBlank { null } ?: card.scenario
        val greeting = data?.firstMes?.ifBlank { null } ?: card.firstMes
        val example = data?.mesExample?.ifBlank { null } ?: card.mesExample
        val system = data?.systemPrompt?.ifBlank { null } ?: card.systemPrompt
        require(name.isNotBlank()) { "Character card is missing a name" }
        val now = System.currentTimeMillis()
        return CharacterEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.trim(),
            description = description,
            personality = personality,
            scenario = scenario,
            greeting = greeting,
            exampleDialogue = example,
            systemPrompt = system,
            createdAt = now,
            updatedAt = now,
        )
    }
}
