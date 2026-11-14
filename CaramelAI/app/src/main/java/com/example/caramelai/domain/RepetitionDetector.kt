package com.example.caramelai.domain

object RepetitionDetector {
    const val STRENGTHEN_THRESHOLD = 0.35

    /**
     * Returns 0..1 score: overlap of word trigrams plus bonus if openings match.
     */
    fun repetitionScore(newReply: String, previousReplies: List<String>): Double {
        if (newReply.isBlank() || previousReplies.isEmpty()) return 0.0
        val newTrigrams = trigrams(newReply)
        if (newTrigrams.isEmpty()) return 0.0
        val newOpening = openingNormalized(newReply)

        var best = 0.0
        for (previous in previousReplies) {
            if (previous.isBlank()) continue
            val prevTrigrams = trigrams(previous)
            if (prevTrigrams.isEmpty()) continue
            val overlap = newTrigrams.intersect(prevTrigrams).size.toDouble()
            val union = newTrigrams.union(prevTrigrams).size.toDouble().coerceAtLeast(1.0)
            var score = overlap / union
            if (newOpening.isNotEmpty() && newOpening == openingNormalized(previous)) {
                score = (score + 0.25).coerceAtMost(1.0)
            }
            if (score > best) best = score
        }
        return best
    }

    fun shouldStrengthenStyle(newReply: String, previousReplies: List<String>): Boolean =
        repetitionScore(newReply, previousReplies) >= STRENGTHEN_THRESHOLD

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s*]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun trigrams(text: String): Set<String> {
        val tokens = tokenize(text)
        if (tokens.size < 3) return emptySet()
        return (0..tokens.size - 3)
            .map { i -> "${tokens[i]} ${tokens[i + 1]} ${tokens[i + 2]}" }
            .toSet()
    }

    private fun openingNormalized(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return tokenize(firstLine).take(5).joinToString(" ")
    }
}
