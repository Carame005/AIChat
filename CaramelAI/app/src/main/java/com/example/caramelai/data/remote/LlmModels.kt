package com.example.caramelai.data.remote

/**
 * Catalog of models CaramelAI knows how to request from llama-server router mode.
 * [id] must match GET /v1/models (llama.cpp --models-dir uses the stem, without ".gguf").
 */
data class KnownLlmModel(
    val id: String,
)

object LlmModels {
    val known: List<KnownLlmModel> = listOf(
        KnownLlmModel(id = "Chaos-Unknown-12b.Q4_K_M"),
        KnownLlmModel(id = "Chaos-Unknown-12b.Q5_K_M"),
    )

    val defaultId: String = known.first().id

    fun find(id: String): KnownLlmModel? {
        val normalized = normalizeId(id)
        return known.find { it.id == normalized }
    }

    /** Router model ids are the GGUF filename without extension. */
    fun normalizeId(id: String): String =
        id.substringAfterLast('/').removeSuffix(".gguf")
}
