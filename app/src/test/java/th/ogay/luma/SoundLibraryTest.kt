package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoundLibraryTest {
    @Test fun everyBuiltInTrackRendersToTheAskedLengthWithoutClipping() {
        SoundLibrary.BUILT_IN.forEach { t ->
            val a = SoundLibrary.renderMusic(t.id, 12_000)
            assertEquals(t.id, 12 * SoundLibrary.RATE, a.size)
            val peak = a.maxOf { abs(it) }
            assertTrue("${t.id} peak $peak", peak in 0.5f..0.9f)
            assertTrue("${t.id} loud enough in the middle", a.copyOfRange(a.size / 3, a.size / 2).map { abs(it) }.average() > 0.02)
        }
    }
    @Test fun beatsFollowTheTempo() {
        val b = SoundLibrary.beatTimes(120f, 4_000)
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 3_500L), b)
    }
    @Test fun tempoIsFoundFromBeats() {
        val beats = (0 until 20).map { it * 469L } // about 128 BPM
        assertEquals(128f, SoundLibrary.estimateBpm(beats), 0.6f)
        assertEquals(0f, SoundLibrary.estimateBpm(listOf(0L, 1000L)), 0f)
    }
    @Test fun effectsAreShortAndAudible() {
        SoundLibrary.Sfx.entries.forEach { s ->
            val a = SoundLibrary.renderSfx(s)
            assertTrue(s.name, a.size in 1_000..(2 * SoundLibrary.RATE))
            assertTrue(s.name, a.maxOf { abs(it) } > 0.5f)
        }
    }
    @Test fun effectsBedPutsHitsWhereAsked() {
        val bed = SoundLibrary.renderSfxBed(3_000, listOf(2_000L to SoundLibrary.Sfx.POP))
        assertTrue(bed.copyOfRange(0, SoundLibrary.RATE).all { it == 0f })
        assertTrue(bed.copyOfRange(2 * SoundLibrary.RATE, 2 * SoundLibrary.RATE + 2_000).any { abs(it) > 0.1f })
    }
}
