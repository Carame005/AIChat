package com.example.caramelai.data.remote

/**
 * Catalog of models CaramelAI knows how to request from llama-server router mode.
 * [id] must match the GGUF filename exposed by GET /v1/models (--models-dir).
 */
data class KnownLlmModel(
    val id: String,
)

object LlmModels {
    val known: List<KnownLlmModel> = listOf(
        KnownLlmModel(id = "Chaos-Unknown-12b.Q4_K_M.gguf"),
        KnownLlmModel(id = "Chaos-Unknown-12b.Q5_K_M.gguf"),
    )

    val defaultId: String = known.first().id

    fun find(id: String): KnownLlmModel? = known.find { it.id == id }
}
