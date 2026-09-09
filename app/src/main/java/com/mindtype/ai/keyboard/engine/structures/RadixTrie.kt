package com.mindtype.ai.keyboard.engine.structures

import java.util.PriorityQueue

/** A scored token stored by the immutable system-language model. */
data class WeightedToken(val token: String, val frequency: Int)

/**
 * A compact radix trie for a read-only system dictionary.
 *
 * During [freeze], every node retains only its best `cacheSize` terminal IDs. This
 * keeps lookup work bounded and avoids walking an entire prefix subtree for every
 * keystroke. Build the trie on a background thread, call [freeze], then publish it
 * to readers; mutation after freeze is deliberately rejected.
 */
class RadixTrie(private val cacheSize: Int = DEFAULT_CACHE_SIZE) {
    private class Node(
        var edge: String = "",
        var terminalFrequency: Int = 0,
        var terminalId: Int = NO_WORD
    ) {
        val children = ArrayList<Node>(2)
        var topWordIds: IntArray = IntArray(0)
    }

    private val root = Node()
    private val wordTable = ArrayList<WeightedToken>()
    private var frozen = false

    fun insert(word: String, frequency: Int) {
        check(!frozen) { "A frozen RadixTrie is read-only" }
        if (word.isEmpty() || frequency <= 0) return
        insert(root, word, frequency)
    }

    /** Finalizes compact indexes. Must be called once after all inserts. */
    fun freeze() {
        if (frozen) return
        assignTerminalIds(root, StringBuilder())
        buildTopCache(root)
        frozen = true
    }

    fun searchPrefix(prefix: String, limit: Int): List<WeightedToken> {
        if (!frozen || prefix.isEmpty() || limit <= 0) return emptyList()

        var node = root
        var offset = 0
        while (offset < prefix.length) {
            val child = findChild(node, prefix[offset]) ?: return emptyList()
            val common = commonPrefixLength(child.edge, prefix, offset)
            val remaining = prefix.length - offset
            if (common == remaining) {
                // The prefix may end in the middle of a compressed edge. Every word
                // below this child still shares the requested prefix.
                node = child
                break
            }
            if (common != child.edge.length) return emptyList()
            node = child
            offset += common
        }

        return node.topWordIds.asSequence().take(limit).map(wordTable::get).toList()
    }

    private fun insert(node: Node, remaining: String, frequency: Int) {
        val child = findChild(node, remaining[0])
        if (child == null) {
            node.children += Node(edge = remaining, terminalFrequency = frequency)
            return
        }

        val shared = commonPrefixLength(child.edge, remaining, 0)
        when {
            shared == child.edge.length && shared == remaining.length -> {
                child.terminalFrequency = maxOf(child.terminalFrequency, frequency)
            }
            shared == child.edge.length -> insert(child, remaining.substring(shared), frequency)
            else -> {
                // Split the existing compressed edge at its shared segment.
                val suffix = Node(
                    edge = child.edge.substring(shared),
                    terminalFrequency = child.terminalFrequency,
                    terminalId = child.terminalId
                ).also { it.children += child.children }

                child.edge = child.edge.substring(0, shared)
                child.terminalFrequency = if (shared == remaining.length) frequency else 0
                child.terminalId = NO_WORD
                child.children.clear()
                child.children += suffix
                if (shared < remaining.length) {
                    child.children += Node(
                        edge = remaining.substring(shared),
                        terminalFrequency = frequency
                    )
                }
            }
        }
    }

    private fun assignTerminalIds(node: Node, path: StringBuilder) {
        val originalLength = path.length
        path.append(node.edge)
        if (node.terminalFrequency > 0) {
            node.terminalId = wordTable.size
            wordTable += WeightedToken(path.toString(), node.terminalFrequency)
        }
        node.children.sortBy { it.edge[0] }
        node.children.forEach { assignTerminalIds(it, path) }
        path.setLength(originalLength)
    }

    private fun buildTopCache(node: Node): IntArray {
        val worstFirst = PriorityQueue<Int> { left, right ->
            // The least useful entry is kept at the head for O(log K) eviction.
            compareByRanking(wordTable[left], wordTable[right])
        }
        if (node.terminalId != NO_WORD) worstFirst.offer(node.terminalId)
        node.children.forEach { child ->
            buildTopCache(child).forEach { id ->
                worstFirst.offer(id)
                if (worstFirst.size > cacheSize) worstFirst.poll()
            }
        }
        return worstFirst.toList()
            .sortedWith { left, right -> -compareByRanking(wordTable[left], wordTable[right]) }
            .toIntArray()
            .also { node.topWordIds = it }
    }

    private fun findChild(node: Node, firstCharacter: Char): Node? {
        // Children are sorted only after freeze; a small linear scan is faster while
        // building and binary search avoids scans for every interactive lookup.
        if (!frozen) return node.children.firstOrNull { it.edge[0] == firstCharacter }
        var low = 0
        var high = node.children.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = node.children[middle]
            when {
                candidate.edge[0] < firstCharacter -> low = middle + 1
                candidate.edge[0] > firstCharacter -> high = middle - 1
                else -> return candidate
            }
        }
        return null
    }

    private fun commonPrefixLength(edge: String, value: String, valueOffset: Int): Int {
        val maxLength = minOf(edge.length, value.length - valueOffset)
        for (index in 0 until maxLength) {
            if (edge[index] != value[valueOffset + index]) return index
        }
        return maxLength
    }

    /** Positive means left is a better candidate (higher frequency, then A-Z). */
    private fun compareByRanking(left: WeightedToken, right: WeightedToken): Int = when {
        left.frequency != right.frequency -> left.frequency.compareTo(right.frequency)
        else -> right.token.compareTo(left.token)
    }

    private companion object {
        const val NO_WORD = -1
        const val DEFAULT_CACHE_SIZE = 12
    }
}
