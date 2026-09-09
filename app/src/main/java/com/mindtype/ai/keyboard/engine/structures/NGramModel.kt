package com.mindtype.ai.keyboard.engine.structures

/** Immutable, bounded in-memory index for system bigram and trigram data. */
class NGramModel(
    private val bigrams: Map<String, List<WeightedToken>>,
    private val trigrams: Map<TrigramContext, List<WeightedToken>>
) {
    fun bigramsFor(previousWord: String, limit: Int): List<WeightedToken> =
        bigrams[previousWord].orEmpty().take(limit)

    fun trigramsFor(firstWord: String, secondWord: String, limit: Int): List<WeightedToken> =
        trigrams[TrigramContext(firstWord, secondWord)].orEmpty().take(limit)
}

data class TrigramContext(val firstWord: String, val secondWord: String)
