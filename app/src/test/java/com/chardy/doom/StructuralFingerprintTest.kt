package com.chardy.doom

import org.junit.Assert.*
import org.junit.Test

class StructuralFingerprintTest {
    private fun sample(vararg depths: Int): StructuralFingerprint {
        val builder = StructuralFingerprint.Builder()
        depths.forEach { builder.add(it, hasResourceId = true, hasClassName = true, clickable = false, childCount = 0) }
        return requireNotNull(builder.build())
    }

    @Test fun orderDoesNotChangeFingerprintOrSimilarity() {
        val first = sample(0, 1, 1, 2)
        val reordered = sample(2, 1, 0, 1)
        assertEquals(first.opaque, reordered.opaque)
        assertTrue(first.opaque.matches(Regex("[0-9a-f]{64}")))
        assertEquals(1.0, first.similarity(reordered), 0.0)
    }

    @Test fun identicalDisjointAndPartialHaveWeightedJaccardScores() {
        val first = sample(0, 1)
        assertEquals(1.0, first.similarity(sample(0, 1)), 0.0)
        assertEquals(0.0, first.similarity(sample(2, 3)), 0.0)
        assertEquals(1.0 / 3, first.similarity(sample(1, 2)), 0.000001)
        assertEquals(first.similarity(sample(1, 2)), sample(1, 2).similarity(first), 0.0)
    }

    @Test fun duplicatesAreMultiplicityNotExtraUniqueFeatures() {
        assertEquals(0.5, sample(1, 1).similarity(sample(1)), 0.0)
        assertEquals(0.5, sample(0, 1, 1).similarity(sample(0, 0, 1)), 0.0)
        assertNotEquals(sample(1).opaque, sample(1, 1).opaque)
    }

    @Test fun eachStructuralDimensionContributesAndChildCountIsCapped() {
        fun feature(depth: Int = 0, id: Boolean = false, klass: Boolean = false,
                    click: Boolean = false, children: Int = 0) =
            StructuralFingerprint.Builder().apply { add(depth, id, klass, click, children) }.build()!!
        val base = feature()
        listOf(feature(depth = 1), feature(id = true), feature(klass = true),
            feature(click = true), feature(children = 1)).forEach {
            assertEquals(0.0, base.similarity(it), 0.0)
        }
        assertEquals(feature(children = 128).opaque, feature(children = Int.MAX_VALUE).opaque)
    }

    @Test fun emptySampleIsUnavailableAndBuiltVectorsAreImmutable() {
        val builder = StructuralFingerprint.Builder()
        assertNull(builder.build())
        builder.add(0, true, true, false, 0)
        val before = builder.build()!!
        builder.add(1, true, true, false, 0)
        assertEquals(sample(0).opaque, before.opaque)
        assertEquals(0.5, before.similarity(builder.build()!!), 0.0)
    }

    @Test fun builderEnforcesNodeAndDepthBounds() {
        val builder = StructuralFingerprint.Builder()
        repeat(128) { builder.add(8, false, false, false, 0) }
        assertThrows(IllegalArgumentException::class.java) { builder.add(8, false, false, false, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            StructuralFingerprint.Builder().add(9, false, false, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StructuralFingerprint.Builder().add(-1, false, false, false, 0)
        }
    }

    @Test fun unavailableCurrentCannotCreateOrReplaceLabelsAndKeepsExplicitBaselines() {
        val first = sample(0)
        val labeled = StructuralSamples().withCurrent(first).label(SampleLabel.FEED)
        val unavailable = labeled.withCurrent(sample(1)).withCurrent(null)
        assertNull(unavailable.current)
        assertSame(first, unavailable.baselines[SampleLabel.FEED])
        SampleLabel.entries.forEach { assertSame(unavailable, unavailable.label(it)) }
        assertEquals(1, unavailable.baselines.size)
        val fresh = sample(2)
        val recovered = unavailable.withCurrent(fresh).label(SampleLabel.INBOX)
        assertSame(first, recovered.baselines[SampleLabel.FEED])
        assertSame(fresh, recovered.baselines[SampleLabel.INBOX])
    }

    @Test fun onlyExplicitLabelsCreateOrReplaceFourBaselinesAndClearDropsEverything() {
        var state = StructuralSamples()
        assertEquals(state, state.label(SampleLabel.FEED))
        val first = sample(0)
        state = state.withCurrent(first)
        assertTrue(state.baselines.isEmpty())
        SampleLabel.entries.forEach { state = state.label(it) }
        val second = sample(1)
        state = state.withCurrent(second).label(SampleLabel.FEED)
        assertEquals(4, state.baselines.size)
        assertSame(second, state.current)
        assertSame(second, state.baselines[SampleLabel.FEED])
        assertSame(first, state.baselines[SampleLabel.INBOX])
        state = state.withCurrent(null)
        assertEquals(4, state.baselines.size)
        state = state.clear()
        assertNull(state.current)
        assertTrue(state.baselines.isEmpty())
        assertEquals(StructuralSamples(), state)
    }
}
