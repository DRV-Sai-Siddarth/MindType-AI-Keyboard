package com.mindtype.ai.keyboard.engine

import java.text.Normalizer
import java.util.Locale

/** Shared, locale-neutral normalization for assets, database keys, and editor text. */
object TextNormalizer {
    const val MAX_TOKEN_LENGTH = 64

    data class EditorContext(
        val activePrefix: String,
        val previousWord: String?,
        val wordBeforePrevious: String?
    )

    fun normalizeToken(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = StringBuilder(minOf(normalized.length, MAX_TOKEN_LENGTH))
        normalized.forEach { character ->
            if (isWordCharacter(character) || isJoiner(character)) result.append(character)
        }
        return result.toString().trim { isJoiner(it) }.take(MAX_TOKEN_LENGTH)
    }

    fun editorContext(textBeforeCursor: String): EditorContext {
        if (textBeforeCursor.isEmpty()) return EditorContext("", null, null)
        val tokens = ArrayList<String>(3)
        val token = StringBuilder()
        textBeforeCursor.forEach { character ->
            if (isWordCharacter(character) || isJoiner(character)) {
                token.append(character)
            } else if (token.isNotEmpty()) {
                normalizeToken(token.toString()).takeIf { it.isNotEmpty() }?.let(tokens::add)
                token.clear()
            }
        }
        val hasActiveToken = token.isNotEmpty()
        if (hasActiveToken) normalizeToken(token.toString()).takeIf { it.isNotEmpty() }?.let(tokens::add)

        val active = if (hasActiveToken) tokens.lastOrNull().orEmpty() else ""
        val completedCount = tokens.size - if (active.isEmpty()) 0 else 1
        return EditorContext(
            activePrefix = active,
            previousWord = tokens.getOrNull(completedCount - 1),
            wordBeforePrevious = tokens.getOrNull(completedCount - 2)
        )
    }

    fun isWordBoundary(committedText: String): Boolean =
        committedText.any { !isWordCharacter(it) && !isJoiner(it) }

    fun isWordCharacter(character: Char): Boolean = character.isLetterOrDigit() || isCombiningMark(character)

    private fun isCombiningMark(character: Char): Boolean = when (Character.getType(character)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isJoiner(character: Char): Boolean = character == '\'' || character == '\u2019' || character == '-'
}
