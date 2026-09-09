package com.mindtype.ai.keyboard.engine.structures

/** Immutable bidirectional emoji lookup loaded from the local configuration asset. */
class EmojiIndex private constructor(
    private val keywordToEmojis: Map<String, List<String>>,
    private val emojiToKeywords: Map<String, List<String>>
) {
    fun emojisFor(keyword: String, limit: Int): List<String> = keywordToEmojis[keyword].orEmpty().take(limit)

    fun keywordsFor(emoji: String): List<String> = emojiToKeywords[emoji].orEmpty()

    fun emojisMatchingPrefix(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        return keywordToEmojis.asSequence()
            .filter { (keyword, _) -> keyword.startsWith(prefix) }
            .flatMap { it.value.asSequence() }
            .distinct()
            .take(limit)
            .toList()
    }

    companion object {
        fun fromKeywordMap(source: Map<String, List<String>>): EmojiIndex {
            val forward = source.mapValues { (_, emojis) -> emojis.distinct() }
            val reverse = LinkedHashMap<String, MutableList<String>>()
            forward.forEach { (keyword, emojis) ->
                emojis.forEach { emoji -> reverse.getOrPut(emoji) { ArrayList(2) }.add(keyword) }
            }
            return EmojiIndex(forward, reverse.mapValues { it.value.toList() })
        }

        val EMPTY = EmojiIndex(emptyMap(), emptyMap())
    }
}
