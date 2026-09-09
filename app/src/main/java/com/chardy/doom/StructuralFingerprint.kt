package com.chardy.doom

import java.security.MessageDigest

/** Pure, bounded multiset of immediately hashed presence/shape features; no Android objects. */
class StructuralFingerprint private constructor(private val vector: Map<String, Int>) {
    val opaque: String = digest(vector.toSortedMap().entries.joinToString(";") { "${it.key}:${it.value}" }.toByteArray(Charsets.US_ASCII))

    /** Weighted Jaccard: sum(min counts) / sum(max counts). Both samples are nonempty. */
    fun similarity(other: StructuralFingerprint): Double {
        val keys = vector.keys + other.vector.keys
        var intersection = 0
        var union = 0
        keys.forEach { key ->
            val left = vector[key] ?: 0
            val right = other.vector[key] ?: 0
            intersection += minOf(left, right)
            union += maxOf(left, right)
        }
        return intersection.toDouble() / union
    }

    class Builder {
        private val vector = mutableMapOf<String, Int>()
        private var nodes = 0

        // Call once per visited node. API cannot accept identifier values, text, or nodes.
        fun add(depth: Int, hasResourceId: Boolean, hasClassName: Boolean, clickable: Boolean, childCount: Int) {
            require(nodes < MAX_NODES && depth in 0..MAX_DEPTH && childCount >= 0)
            val flags = (if (hasResourceId) 1 else 0) or (if (hasClassName) 2 else 0) or (if (clickable) 4 else 0)
            val hash = digest(byteArrayOf(1, depth.toByte(), flags.toByte(), childCount.coerceAtMost(MAX_NODES).toByte()))
            vector[hash] = (vector[hash] ?: 0) + 1
            nodes++
        }

        fun build(): StructuralFingerprint? = if (nodes == 0) null else StructuralFingerprint(vector.toMap())
    }

    companion object {
        const val MAX_NODES = 128
        const val MAX_DEPTH = 8
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

enum class SampleLabel(val title: String) { FEED("Feed"), INBOX("Inbox"), THREAD("Thread"), COMPOSE("Compose") }

/** One current sample and at most four explicit, immutable baseline references; no history. */
data class StructuralSamples private constructor(
    val current: StructuralFingerprint?,
    val baselines: Map<SampleLabel, StructuralFingerprint>
) {
    constructor() : this(null, emptyMap())
    fun withCurrent(sample: StructuralFingerprint?) = StructuralSamples(sample, baselines)
    fun label(label: SampleLabel): StructuralSamples = current?.let {
        StructuralSamples(it, baselines + (label to it))
    } ?: this
    fun clear() = StructuralSamples()
}
