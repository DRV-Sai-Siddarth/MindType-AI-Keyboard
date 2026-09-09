package com.mindtype.ai.keyboard.engine

import android.content.Context
import com.mindtype.ai.keyboard.engine.structures.EmojiIndex
import com.mindtype.ai.keyboard.engine.structures.NGramModel
import com.mindtype.ai.keyboard.engine.structures.RadixTrie
import com.mindtype.ai.keyboard.engine.structures.TrigramContext
import com.mindtype.ai.keyboard.engine.structures.WeightedToken
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Loads the read-only system language model from APK assets. The asset producer, not
 * application code, owns all words, frequencies, n-grams, and emoji mappings.
 *
 * TSV schemas (UTF-8, one header row):
 * dictionary: word<TAB>frequency
 * bigrams: previous_word<TAB>next_word<TAB>frequency
 * trigrams: first_word<TAB>second_word<TAB>next_word<TAB>frequency
 */
object AssetDataLoader {
    data class AssetConfiguration(
        val dictionary: String = "language/dictionary.tsv.gz",
        val bigrams: String = "language/bigrams.tsv.gz",
        val trigrams: String = "language/trigrams.tsv.gz",
        val emojis: String = "language/emojis.json"
    )

    data class SystemLanguageModel(
        val trie: RadixTrie,
        val nGrams: NGramModel,
        val emojiIndex: EmojiIndex
    )

    fun load(context: Context, config: AssetConfiguration): SystemLanguageModel {
        val trie = RadixTrie(cacheSize = MAX_CANDIDATES_PER_CONTEXT)
        readTsv(context, config.dictionary) { columns ->
            if (columns.size >= 2) {
                val word = TextNormalizer.normalizeToken(columns[0])
                val frequency = columns[1].toPositiveFrequency()
                if (word.isNotEmpty() && frequency != null) trie.insert(word, frequency)
            }
        }
        trie.freeze()

        val bigrams = HashMap<String, MutableList<WeightedToken>>()
        readTsv(context, config.bigrams) { columns ->
            if (columns.size >= 3) {
                val previous = TextNormalizer.normalizeToken(columns[0])
                val next = TextNormalizer.normalizeToken(columns[1])
                val frequency = columns[2].toPositiveFrequency()
                if (previous.isNotEmpty() && next.isNotEmpty() && frequency != null) {
                    bigrams.appendBounded(previous, WeightedToken(next, frequency))
                }
            }
        }

        val trigrams = HashMap<TrigramContext, MutableList<WeightedToken>>()
        readTsv(context, config.trigrams) { columns ->
            if (columns.size >= 4) {
                val first = TextNormalizer.normalizeToken(columns[0])
                val second = TextNormalizer.normalizeToken(columns[1])
                val next = TextNormalizer.normalizeToken(columns[2])
                val frequency = columns[3].toPositiveFrequency()
                if (first.isNotEmpty() && second.isNotEmpty() && next.isNotEmpty() && frequency != null) {
                    trigrams.appendBounded(TrigramContext(first, second), WeightedToken(next, frequency))
                }
            }
        }

        return SystemLanguageModel(
            trie = trie,
            nGrams = NGramModel(bigrams.toImmutableRankedMap(), trigrams.toImmutableRankedMap()),
            emojiIndex = loadEmojiIndex(context, config.emojis)
        )
    }

    private fun <K> MutableMap<K, MutableList<WeightedToken>>.appendBounded(
        key: K,
        candidate: WeightedToken
    ) {
        // Bound every context during loading. This protects startup memory even if an
        // accidentally malformed asset contains an enormous fan-out.
        val candidates = getOrPut(key) { ArrayList(MAX_CANDIDATES_PER_CONTEXT) }
        val existing = candidates.indexOfFirst { it.token == candidate.token }
        if (existing >= 0) {
            if (candidate.frequency > candidates[existing].frequency) candidates[existing] = candidate
        } else if (candidates.size < MAX_CANDIDATES_PER_CONTEXT) {
            candidates += candidate
        } else {
            val worst = candidates.indices.minWithOrNull { left, right ->
                rank(candidates[left], candidates[right])
            } ?: return
            if (rank(candidate, candidates[worst]) > 0) candidates[worst] = candidate
        }
    }

    private fun <K> Map<K, MutableList<WeightedToken>>.toImmutableRankedMap(): Map<K, List<WeightedToken>> =
        entries.associate { (context, candidates) ->
            context to candidates.sortedWith { left, right -> -rank(left, right) }
        }

    private fun loadEmojiIndex(context: Context, assetName: String): EmojiIndex {
        val mappings = LinkedHashMap<String, List<String>>()
        open(context, assetName).bufferedReader(Charsets.UTF_8).use { reader ->
            val root = JSONObject(reader.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val sourceKey = keys.next()
                val key = TextNormalizer.normalizeToken(sourceKey)
                if (key.isEmpty()) continue
                val values = root.optJSONArray(sourceKey) ?: continue
                val emojis = ArrayList<String>(values.length())
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf { it.isNotBlank() }?.let(emojis::add)
                }
                if (emojis.isNotEmpty()) mappings[key] = emojis
            }
        }
        return EmojiIndex.fromKeywordMap(mappings)
    }

    private fun readTsv(context: Context, assetName: String, consume: (List<String>) -> Unit) {
        open(context, assetName).bufferedReader(Charsets.UTF_8).use { reader ->
            var isFirstRecord = true
            reader.forEachLine { line ->
                if (line.isBlank() || line.startsWith('#')) return@forEachLine
                val columns = line.split('\t')
                // A header is optional, but never interpreted as data.
                if (isFirstRecord && columns.lastOrNull()?.toPositiveFrequency() == null) {
                    isFirstRecord = false
                    return@forEachLine
                }
                isFirstRecord = false
                consume(columns)
            }
        }
    }

    private fun open(context: Context, assetName: String): InputStream {
        val raw = context.assets.open(assetName)
        return if (assetName.endsWith(".gz", ignoreCase = true)) GZIPInputStream(raw) else raw
    }

    private fun String.toPositiveFrequency(): Int? = trim().toLongOrNull()
        ?.coerceIn(1L, Int.MAX_VALUE.toLong())
        ?.toInt()

    /** Positive means left ranks before right. */
    private fun rank(left: WeightedToken, right: WeightedToken): Int = when {
        left.frequency != right.frequency -> left.frequency.compareTo(right.frequency)
        else -> right.token.compareTo(left.token)
    }

    private const val MAX_CANDIDATES_PER_CONTEXT = 12
}
