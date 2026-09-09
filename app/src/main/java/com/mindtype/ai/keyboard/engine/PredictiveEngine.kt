package com.mindtype.ai.keyboard.engine

import android.content.Context
import com.mindtype.ai.keyboard.engine.db.UserLearningDatabase
import com.mindtype.ai.keyboard.engine.structures.EmojiIndex
import com.mindtype.ai.keyboard.engine.structures.NGramModel
import com.mindtype.ai.keyboard.engine.structures.RadixTrie
import com.mindtype.ai.keyboard.engine.structures.WeightedToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Offline predictive-text engine.
 *
 * System vocabulary is read-only and loaded from APK assets. Personalized words and
 * n-grams live only in [UserLearningDatabase]. Every lookup and database operation
 * runs on the engine's single background dispatcher; public APIs are safe to call
 * from IME/UI code but must be awaited from a coroutine.
 */
class PredictiveEngine private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val dispatcher: ExecutorCoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "MindType-Predictive") }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val initializeMutex = Mutex()
    private val userDao = UserLearningDatabase.getInstance(applicationContext).userDao()

    @Volatile private var model: AssetDataLoader.SystemLanguageModel? = null
    // Accessed only by [scope], which uses the single engine dispatcher.
    private var learnedEventCount = 0

    @Volatile var isInitialized: Boolean = false
        private set

    /** Starts a non-blocking model load; completion is delivered on the main thread. */
    fun initialize(
        configuration: AssetDataLoader.AssetConfiguration = AssetDataLoader.AssetConfiguration(),
        onComplete: (Result<Unit>) -> Unit = {}
    ) {
        scope.launch {
            val result = runCatching { initializeInternal(configuration) }
            withContext(Dispatchers.Main.immediate) { onComplete(result) }
        }
    }

    /** Awaitable variant for workers and instrumentation tests. */
    suspend fun initializeAndAwait(
        configuration: AssetDataLoader.AssetConfiguration = AssetDataLoader.AssetConfiguration()
    ): Result<Unit> = runCatching { initializeInternal(configuration) }

    /**
     * Returns completions for [rawPrefix], ordered by personal/global relevance. Emoji
     * candidates matching a keyword prefix are included without exceeding [limit].
     */
    suspend fun getWordCompletions(rawPrefix: String, limit: Int = DEFAULT_LIMIT): List<String> =
        withContext(dispatcher) {
            val prefix = TextNormalizer.normalizeToken(rawPrefix)
            val boundedLimit = limit.coerceIn(1, MAX_QUERY_LIMIT)
            val snapshot = model ?: return@withContext emptyList()
            if (prefix.isEmpty()) return@withContext emptyList()

            val rankings = LinkedHashMap<String, Long>()
            userDao.getCompletions(prefix, candidateFetchLimit(boundedLimit)).forEach { user ->
                rankings.mergeMax(user.word, user.frequency.toLong() * USER_WORD_WEIGHT)
            }
            snapshot.trie.searchPrefix(prefix, candidateFetchLimit(boundedLimit)).forEach { system ->
                rankings.mergeMax(system.token, system.frequency.toLong())
            }

            val words = rankings.rankedTokens(boundedLimit)
                .map { applyInitialCase(it, rawPrefix) }
            mergeEmojiCandidates(
                words = words,
                emojis = snapshot.emojiIndex.emojisMatchingPrefix(prefix, boundedLimit),
                limit = boundedLimit
            )
        }

    /** Required bigram API. Uses user history and system bigrams for one-word context. */
    suspend fun getNextWordPredictions(currentWord: String, limit: Int = DEFAULT_LIMIT): List<String> =
        getNextWordPredictions(currentWord, wordBeforeCurrent = null, limit = limit)

    /**
     * Trigram-aware overload. [wordBeforeCurrent] is the word immediately before
     * [currentWord], allowing callers with editor context to use the higher-order
     * system/user model when it exists.
     */
    suspend fun getNextWordPredictions(
        currentWord: String,
        wordBeforeCurrent: String?,
        limit: Int = DEFAULT_LIMIT
    ): List<String> = withContext(dispatcher) {
        val current = TextNormalizer.normalizeToken(currentWord)
        val before = wordBeforeCurrent?.let(TextNormalizer::normalizeToken).orEmpty()
        val boundedLimit = limit.coerceIn(1, MAX_QUERY_LIMIT)
        val snapshot = model ?: return@withContext emptyList()
        if (current.isEmpty()) return@withContext emptyList()

        val rankings = LinkedHashMap<String, Long>()
        userDao.getNextWords(current, candidateFetchLimit(boundedLimit)).forEach { user ->
            rankings.mergeMax(user.nextWord, user.frequency.toLong() * USER_NGRAM_WEIGHT)
        }
        snapshot.nGrams.bigramsFor(current, candidateFetchLimit(boundedLimit)).forEach { system ->
            rankings.mergeMax(system.token, system.frequency.toLong())
        }
        if (before.isNotEmpty()) {
            userDao.getNextWordsForContext(before, current, candidateFetchLimit(boundedLimit)).forEach { user ->
                rankings.mergeMax(user.nextWord, user.frequency.toLong() * USER_TRIGRAM_WEIGHT)
            }
            snapshot.nGrams.trigramsFor(before, current, candidateFetchLimit(boundedLimit)).forEach { system ->
                rankings.mergeMax(system.token, system.frequency.toLong() * TRIGRAM_WEIGHT)
            }
        }

        val words = rankings.rankedTokens(boundedLimit).map { applyInitialCase(it, currentWord) }
        mergeEmojiCandidates(
            words = words,
            emojis = snapshot.emojiIndex.emojisFor(current, boundedLimit),
            limit = boundedLimit
        )
    }

    /** Selects completions or next-word predictions from a bounded editor snapshot. */
    suspend fun getSuggestionsForEditorText(textBeforeCursor: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val context = TextNormalizer.editorContext(textBeforeCursor.takeLast(MAX_EDITOR_CONTEXT_CHARS))
        return if (context.activePrefix.isNotEmpty()) {
            getWordCompletions(context.activePrefix, limit)
        } else if (context.previousWord != null) {
            getNextWordPredictions(context.previousWord, context.wordBeforePrevious, limit)
        } else {
            emptyList()
        }
    }

    /** Queues a local learning write; never blocks a key event or UI frame. */
    fun learnTypingPattern(
        previousWord: String?,
        typedWord: String,
        wordBeforePrevious: String? = null
    ) {
        val typed = TextNormalizer.normalizeToken(typedWord)
        val previous = previousWord?.let(TextNormalizer::normalizeToken)?.ifEmpty { null }
        val first = wordBeforePrevious?.let(TextNormalizer::normalizeToken)?.ifEmpty { null }
        if (typed.isEmpty()) return

        scope.launch {
            userDao.learn(firstWord = first, previousWord = previous, typedWord = typed)
            learnedEventCount++
            if (learnedEventCount % PRUNE_INTERVAL == 0) userDao.pruneToBounds()
        }
    }

    /** Access to the reverse map for emoji UI/search consumers. */
    suspend fun getEmojiKeywords(emoji: String): List<String> = withContext(dispatcher) {
        model?.emojiIndex?.keywordsFor(emoji).orEmpty()
    }

    private suspend fun initializeInternal(configuration: AssetDataLoader.AssetConfiguration) {
        initializeMutex.withLock {
            if (isInitialized) return
            // Build a complete new snapshot before atomically publishing it to readers.
            val loaded = AssetDataLoader.load(applicationContext, configuration)
            model = loaded
            isInitialized = true
        }
    }

    private fun mergeEmojiCandidates(words: List<String>, emojis: List<String>, limit: Int): List<String> {
        val result = ArrayList<String>(limit)
        words.forEach { candidate -> if (result.size < limit && candidate !in result) result += candidate }
        emojis.forEach { candidate -> if (result.size < limit && candidate !in result) result += candidate }
        return result
    }

    private fun applyInitialCase(candidate: String, rawInput: String): String {
        return if (rawInput.firstOrNull()?.isUpperCase() == true && candidate.isNotEmpty()) {
            candidate.replaceFirstChar { it.titlecase() }
        } else candidate
    }

    private fun MutableMap<String, Long>.mergeMax(token: String, score: Long) {
        val safeScore = score.coerceAtMost(Long.MAX_VALUE / 2)
        val existing = this[token]
        if (existing == null || safeScore > existing) this[token] = safeScore
    }

    private fun Map<String, Long>.rankedTokens(limit: Int): List<String> = entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }

    private fun candidateFetchLimit(limit: Int): Int = (limit * 3).coerceAtMost(MAX_QUERY_LIMIT)

    companion object {
        private const val DEFAULT_LIMIT = 3
        private const val MAX_QUERY_LIMIT = 12
        private const val MAX_EDITOR_CONTEXT_CHARS = 256
        private const val USER_WORD_WEIGHT = 10_000L
        private const val USER_NGRAM_WEIGHT = 12_000L
        private const val USER_TRIGRAM_WEIGHT = 15_000L
        private const val TRIGRAM_WEIGHT = 2L
        private const val PRUNE_INTERVAL = 128

        @Volatile private var INSTANCE: PredictiveEngine? = null

        fun getInstance(context: Context): PredictiveEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PredictiveEngine(context.applicationContext).also { INSTANCE = it }
        }
    }
}
