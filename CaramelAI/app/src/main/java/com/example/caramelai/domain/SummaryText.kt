package com.example.caramelai.domain

import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole

/** Pure helpers for summary text shaping (testable without Room). */
object SummaryText {
    const val MAX_SUMMARY_CHARS = 2500
    const val PENDING_TRANSCRIPT_CHARS = 2200
    /** Max transcript chars sent to the summarizer (tail = newest dropped beats). */
    const val SUMMARIZER_TRANSCRIPT_CHARS = 4500

    /** Persisted third-person present beat (kept in DB / prompts). */
    const val PRESENT_MARKER = "Current situation:"
    /** Legacy dialogue dump — stripped and never persisted. */
    const val CLOSING_BEAT_MARKER = "Current situation (at memory cut):"
    const val PENDING_EVENTS_MARKER = "Recent events (pending summary):"
    const val OLDER_EVENTS_MARKER =
        "Older events omitted from the live window (NOT the present — live chat turns below are more recent):"

    private val COMPLETE_ENDINGS = setOf('.', '!', '?', '…', '"', '\u201d')
    /** Speaker-label lines like "Carlos: …" — must not land in persisted present. */
    private val DIALOGUE_LINE = Regex("""(?m)^[A-Za-zÀ-ÿ][\wÀ-ÿ' .-]{0,40}:\s+\S""")

    /**
     * Caps length and drops a trailing incomplete sentence from a truncated model output.
     *
     * When capping, **keeps the current situation (the end)** and a short head for
     * origin/names. Never chops the ending first — that made the bot "jump back"
     * several messages after a memory update.
     */
    fun sanitize(text: String, maxChars: Int = MAX_SUMMARY_CHARS): String {
        // Persist only permanent memory — never dialogue dumps.
        var result = stripForPersistence(text).trim()
        if (result.isEmpty()) return result
        if (result.length > maxChars) {
            result = capPreferringCurrentSituation(result, maxChars)
        }
        val last = result.lastOrNull()
        if (last != null && last !in COMPLETE_ENDINGS) {
            // Never chop into the persisted present block — incomplete tails there
            // are fixed by ensurePresentClosing / the present micro-pass.
            val presentAt = result.lastIndexOf(PRESENT_MARKER)
            val cut = result.indexOfLast { it in ".!?" }
            if (cut >= result.length / 3 && (presentAt < 0 || cut >= presentAt)) {
                result = result.take(cut + 1).trimEnd()
            }
        }
        return result
    }

    /**
     * Strips dialogue blocks that must never be stored in DB (legacy closing-beat /
     * pending dumps). Safe for system prompts that still need a transient
     * [OLDER_EVENTS_MARKER] provisional block. Does **not** remove [PRESENT_MARKER].
     */
    fun stripEphemeralSections(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return result
        for (marker in listOf(CLOSING_BEAT_MARKER, PENDING_EVENTS_MARKER)) {
            val index = result.lastIndexOf(marker)
            if (index >= 0) {
                result = result.substring(0, index).trimEnd()
            }
        }
        return result
    }

    /** Like [stripEphemeralSections] plus transient provisional older-events blocks. */
    fun stripForPersistence(text: String): String {
        var result = stripEphemeralSections(text)
        val olderAt = result.lastIndexOf(OLDER_EVENTS_MARKER)
        if (olderAt >= 0) {
            result = result.substring(0, olderAt).trimEnd()
        }
        // Also drop the omit-notice if it was concatenated into a saved field.
        val notice = lengthOmitNotice()
        if (result.endsWith(notice)) {
            result = result.removeSuffix(notice).trimEnd()
        }
        return result
    }

    /**
     * Background body without the persisted [PRESENT_MARKER] section.
     */
    fun stripPresentClosing(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val at = trimmed.lastIndexOf(PRESENT_MARKER)
        if (at < 0) return trimmed
        return trimmed.substring(0, at).trimEnd()
    }

    /**
     * Text after [PRESENT_MARKER], or null if absent / blank.
     */
    fun extractPresentClosing(text: String): String? {
        val trimmed = text.trim()
        val at = trimmed.lastIndexOf(PRESENT_MARKER)
        if (at < 0) return null
        val body = trimmed.substring(at + PRESENT_MARKER.length).trim()
        return body.takeIf { it.isNotBlank() }
    }

    /**
     * True when [text] looks like labeled chat dialogue rather than third-person prose.
     */
    fun looksLikeDialogue(text: String): Boolean =
        DIALOGUE_LINE.containsMatchIn(text.trim())

    /**
     * Replaces any prior present block and appends [present] under [PRESENT_MARKER].
     * [present] must be third-person prose (no speaker labels).
     */
    fun ensurePresentClosing(summary: String, present: String): String {
        val cleanPresent = present.trim()
        if (cleanPresent.isBlank() || looksLikeDialogue(cleanPresent)) {
            return sanitize(stripPresentClosing(summary))
        }
        val background = stripPresentClosing(stripForPersistence(summary)).trim()
        val merged = buildString {
            if (background.isNotBlank()) {
                appendLine(background)
                appendLine()
            }
            appendLine(PRESENT_MARKER)
            append(cleanPresent)
        }
        return sanitize(merged)
    }

    /**
     * Keep a brief background head + the ending (current situation).
     * If a [PRESENT_MARKER] block exists, prefer keeping that whole tail.
     * If that does not fit cleanly, keep the ending only.
     */
    fun capPreferringCurrentSituation(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (maxChars <= 0) return ""
        if (trimmed.length <= maxChars) return trimmed

        val presentAt = trimmed.lastIndexOf(PRESENT_MARKER)
        if (presentAt >= 0) {
            val presentTail = trimmed.substring(presentAt).trim()
            if (presentTail.length >= maxChars) {
                return presentTail.takeLast(maxChars).trimStart().take(maxChars)
            }
            val headPart = trimmed.substring(0, presentAt).trimEnd()
            if (headPart.isEmpty()) return presentTail
            val sep = "\n\n"
            if (headPart.length + sep.length + presentTail.length <= maxChars) {
                return "$headPart$sep$presentTail"
            }
            val ellipsis = "\n…\n"
            val headKeep = (maxChars - presentTail.length - ellipsis.length).coerceAtLeast(0)
            val head = trimToSentenceEnd(headPart.take(headKeep)).trimEnd()
            return if (head.isBlank()) {
                presentTail.take(maxChars)
            } else {
                "$head$ellipsis$presentTail".take(maxChars)
            }
        }

        fun keepTail(): String {
            val raw = trimmed.takeLast(maxChars).trimStart()
            val edged = trimToSentenceStart(raw)
            return (edged.ifEmpty { raw }).take(maxChars)
        }
        if (maxChars < 64) return keepTail()
        val ellipsis = "\n…\n"
        val headBudget = (maxChars * 0.28f).toInt().coerceIn(1, (maxChars / 3).coerceAtLeast(1))
        val tailBudget = (maxChars - headBudget - ellipsis.length).coerceAtLeast(1)
        val head = trimToSentenceEnd(trimmed.take(headBudget)).trimEnd()
        val tail = trimToSentenceStart(trimmed.takeLast(tailBudget)).trimStart()
        if (head.isBlank() || tail.isBlank()) return keepTail()
        val tailAt = trimmed.lastIndexOf(tail)
        if (tailAt in 0 until head.length) return keepTail()
        val combined = "$head$ellipsis$tail"
        return if (combined.length <= maxChars) combined else keepTail()
    }

    fun compactPendingTranscript(
        dropped: List<MessageEntity>,
        userName: String,
        characterName: String,
        maxChars: Int = PENDING_TRANSCRIPT_CHARS,
    ): String {
        val lines = dropped.map { message ->
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            "$speaker: ${message.content.trim()}"
        }
        val joined = lines.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }

    /**
     * Transient prompt-only block. Must never be confused with the live exchange:
     * the labeled chat turns after the system message are always more recent.
     */
    fun mergeProvisionalSummary(existing: String, pending: String): String {
        val cleanExisting = stripEphemeralSections(existing)
        if (pending.isBlank()) return cleanExisting
        return buildString {
            if (cleanExisting.isNotBlank()) {
                appendLine(cleanExisting.trim())
                appendLine()
            }
            appendLine(OLDER_EVENTS_MARKER)
            append(pending.trim())
        }.trim()
    }

    /**
     * Short facts for the summarizer prompt only (not saved into memory as dialogue).
     */
    fun closingBeatFromDropped(
        dropped: List<MessageEntity>,
        userName: String,
        characterName: String,
        maxMessages: Int = 4,
        maxChars: Int = 900,
        maxCharsPerMessage: Int = 280,
    ): String {
        if (dropped.isEmpty() || maxMessages <= 0 || maxChars <= 0) return ""
        val latest = dropped.takeLast(maxMessages)
        val lines = latest.map { message ->
            val speaker = if (message.role == MessageRole.USER) userName else characterName
            val body = message.content.trim().replace(Regex("\\s+"), " ")
            val clipped = if (body.length > maxCharsPerMessage) {
                body.take(maxCharsPerMessage - 1).trimEnd() + "…"
            } else {
                body
            }
            "$speaker: $clipped"
        }
        val joined = lines.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }

    /** Notice used when overflow is too small to fold — no dialogue dump. */
    fun lengthOmitNotice(): String =
        "Note: some older turns were omitted from this prompt for length. " +
            "Continue strictly from the live chat messages below — they are the present."

    private fun trimToSentenceEnd(text: String): String {
        val cut = text.indexOfLast { it in ".!?" }
        return if (cut >= text.length / 3) text.take(cut + 1) else text
    }

    private fun trimToSentenceStart(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return trimmed
        val cut = trimmed.indexOfFirst { it in ".!?" }
        return if (cut in 0 until trimmed.length / 2) {
            trimmed.drop(cut + 1).trimStart()
        } else {
            trimmed
        }
    }
}
