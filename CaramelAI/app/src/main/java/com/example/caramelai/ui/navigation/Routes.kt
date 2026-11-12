package com.example.caramelai.ui.navigation

object Routes {
    const val Startup = "startup"
    const val Main = "main"
    const val CharacterEditor = "characters/{characterId}/edit"
    const val Chat = "characters/{characterId}/chats/{conversationId}"
    const val PersonaEditor = "personas/{personaId}/edit"
    const val Settings = "settings"

    fun characterEditor(characterId: String) = "characters/$characterId/edit"
    fun chat(characterId: String, conversationId: String) =
        "characters/$characterId/chats/$conversationId"
    fun personaEditor(personaId: String) = "personas/$personaId/edit"
}
