package com.mindtype.ai.keyboard.engine

import com.mindtype.ai.keyboard.engine.structures.RadixTrie
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLanguageModelTest {
    @Test
    fun prefixSearchIsRankedWhenPrefixEndsInsideCompressedEdge() {
        val trie = RadixTrie(cacheSize = 4)
        trie.insert("app", 8)
        trie.insert("apple", 20)
        trie.insert("apply", 10)
        trie.insert("banana", 100)
        trie.freeze()

        assertEquals(listOf("apple", "apply"), trie.searchPrefix("appl", 2).map { it.token })
    }

    @Test
    fun normalizationHandlesPunctuationCaseAndUnicodeLetters() {
        assertEquals("hello", TextNormalizer.normalizeToken("  HELLO!  "))
        assertEquals("l'été", TextNormalizer.normalizeToken("L'ÉTÉ"))
        assertEquals("can't", TextNormalizer.normalizeToken("can't,"))
    }

    @Test
    fun editorContextSeparatesActiveAndCompletedWords() {
        val context = TextNormalizer.editorContext("Hello, world ")

        assertEquals("", context.activePrefix)
        assertEquals("world", context.previousWord)
        assertEquals("hello", context.wordBeforePrevious)
    }
}
