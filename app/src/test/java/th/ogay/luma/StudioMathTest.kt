package th.ogay.luma

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMathTest {
    @Test fun speechCaptionStaysOneSecondThenStops() {
        val out = StudioMath.holdCues(listOf(StudioMath.Cue(0, 2000, "a"), StudioMath.Cue(10000, 12000, "b")))
        assertEquals(3000L, out[0].to); assertEquals(13000L, out[1].to)
    }
    @Test fun speechCaptionNeverRunsIntoNextLine() {
        val out = StudioMath.holdCues(listOf(StudioMath.Cue(0, 2000, "a"), StudioMath.Cue(2400, 4000, "b")))
        assertEquals(2400L, out[0].to)
    }
    @Test fun longVoiceIsNotCutButHoldIsCapped() {
        val out = StudioMath.holdCues(listOf(StudioMath.Cue(0, 9000, "a")))
        assertEquals(9000L, out[0].to)
    }

    @Test fun orderPutsTheOpenedClipAfterTheLeadingClips() {
        assertEquals(listOf(0, 1, 2, 3), StudioMath.order(0, 3))
        assertEquals(listOf(1, 2, 0, 3), StudioMath.order(2, 3))
        assertEquals(listOf(1, 2, 3, 0), StudioMath.order(9, 3))
        assertEquals(listOf(0), StudioMath.order(1, 0))
    }

    @Test fun glideHoldsOutsideAndEasesBetweenKeyframes() {
        val times = longArrayOf(1000, 2000, 4000)
        val values = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f))
        assertArrayEquals(floatArrayOf(0f, 1f), StudioMath.glide(times, values, 0), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f), StudioMath.glide(times, values, 9000), 1e-6f)
        // Half way is half way; a quarter of the way is less than a quarter because the move starts softly.
        assertEquals(0.5f, StudioMath.glide(times, values, 1500)[0], 1e-6f)
        assertEquals(0.15625f, StudioMath.glide(times, values, 1250)[0], 1e-6f)
        assertEquals(0.5f, StudioMath.glide(times, values, 3000)[1], 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), StudioMath.glide(times, values, 2000), 1e-6f)
        val e = intArrayOf(StudioMath.EASE_LINEAR, StudioMath.EASE_HOLD, 0)
        assertEquals(0.25f, StudioMath.glide(times, values, 1250, e)[0], 1e-6f)
        assertEquals(1f, StudioMath.glide(times, values, 3999, e)[1], 1e-6f)
        assertEquals(0.125f, StudioMath.ease(StudioMath.EASE_IN, 0.5f), 1e-6f)
        assertEquals(0.875f, StudioMath.ease(StudioMath.EASE_OUT, 0.5f), 1e-6f)
    }

    @Test fun fadeRisesAtTheStartAndFallsAtTheEnd() {
        assertEquals(0f, StudioMath.fade(0, 3000, 1000, 500), 1e-6f)
        assertEquals(0.5f, StudioMath.fade(500, 3000, 1000, 500), 1e-6f)
        assertEquals(1f, StudioMath.fade(1500, 3000, 1000, 500), 1e-6f)
        assertEquals(0.5f, StudioMath.fade(2750, 3000, 1000, 500), 1e-6f)
        assertEquals(1f, StudioMath.fade(0, 3000, 0, 0), 1e-6f)
    }

    @Test fun packKeepsOverlappingBarsOnSeparateLanes() {
        assertArrayEquals(intArrayOf(0, 1, 0), StudioMath.pack(listOf(0L to 1000L, 500L to 1500L, 1000L to 2000L), 4))
        // With one lane only, everything shares it.
        assertArrayEquals(intArrayOf(0, 0), StudioMath.pack(listOf(0L to 1000L, 500L to 1500L), 1))
        assertArrayEquals(intArrayOf(0, 1, 1), StudioMath.pack(listOf(0L to 1000L, 0L to 1000L, 0L to 1000L), 2))
    }

    @Test fun srtIsReadAndWrittenBack() {
        val file = 0xFEFF.toChar().toString() + "1\r\n00:00:01,000 --> 00:00:02,500\r\nสวัสดี\r\n\r\n2\r\n00:01:00.250 --> 00:01:03.000\r\n<i>two</i>\r\nlines\r\n\r\nbroken block\r\n\r\n4\r\n00:00:05,000 --> 00:00:04,000\r\nends before it starts\r\n"
        val cues = StudioMath.parseSrt(file)
        assertEquals(listOf(StudioMath.Cue(1000, 2500, "สวัสดี"), StudioMath.Cue(60250, 63000, "two\nlines")), cues)
        assertEquals(cues, StudioMath.parseSrt(StudioMath.writeSrt(cues)))
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nสวัสดี\n", StudioMath.writeSrt(cues.take(1)))
    }
}

class StudioAudioTest {
    @Test fun duckLowersMusicInsideSpansWithSlopes() {
        val spans = listOf(1000L to 2000L)
        assertEquals(1f, StudioAudio.duck(0, spans), 1e-6f)
        assertEquals(0.3f, StudioAudio.duck(1500, spans), 1e-6f)
        assertEquals(0.65f, StudioAudio.duck(850, spans), 1e-6f)
        assertEquals(0.65f, StudioAudio.duck(2150, spans), 1e-6f)
        assertEquals(1f, StudioAudio.duck(2400, spans), 1e-6f)
    }
    @Test fun limiterKeepsQuietSoundAndNeverPassesOne() {
        assertEquals(0.5, StudioAudio.limit(0.5), 1e-9)
        assertEquals(-0.5, StudioAudio.limit(-0.5), 1e-9)
        assertTrue(StudioAudio.limit(5.0) < 1.0); assertTrue(StudioAudio.limit(-5.0) > -1.0)
        assertTrue(StudioAudio.limit(0.95) > 0.9)
    }
    @Test fun highPassRemovesSteadyOffsetAndShelfAtZeroDbChangesNothing() {
        val hp = StudioAudio.Biquad.highPass(48000, 100.0)
        var y = 0.0; repeat(48000) { y = hp.run(0, 0.5) }
        assertEquals(0.0, y, 1e-4)
        val flat = StudioAudio.Biquad.lowShelf(48000, 150.0, 0.0)
        assertEquals(0.3, flat.run(0, 0.3), 1e-9)
    }
}

class StudioMatteTest {
    @Test fun greenKeyClearsGreenAndKeepsSkin() {
        val px = intArrayOf(0xff00ff00.toInt(), 0xff10e020.toInt(), 0xffd8a080.toInt())
        StudioMatte.apply(px, 3, 1, StudioMatte.Spec(0xff00ff00.toInt(), 0.4f, StudioMatte.NONE, 0f, 0f))
        assertEquals(0, px[0] ushr 24); assertEquals(0, px[1] ushr 24); assertEquals(255, px[2] ushr 24)
    }
    @Test fun ellipseKeepsCentreAndClearsCorners() {
        val w = 21; val px = IntArray(w * w) { 0xffffffff.toInt() }
        StudioMatte.apply(px, w, w, StudioMatte.Spec(0, 0f, StudioMatte.ELLIPSE, 0.1f, 0f))
        assertEquals(255, px[10 * w + 10] ushr 24); assertEquals(0, px[0] ushr 24); assertEquals(0, px[w * w - 1] ushr 24)
    }
    @Test fun roundedCornersCutOnlyTheCorners() {
        val w = 40; val px = IntArray(w * w) { 0xffffffff.toInt() }
        StudioMatte.apply(px, w, w, StudioMatte.Spec(0, 0f, StudioMatte.NONE, 0f, 0.5f))
        assertEquals(0, px[0] ushr 24); assertEquals(255, px[20 * w + 2] ushr 24); assertEquals(255, px[2 * w + 20] ushr 24)
    }
}

class StudioAnalysisTest {
    @Test fun sceneCutsFindTheJumpsOnly() {
        val d = FloatArray(40) { 0.02f }; d[10] = 0.6f; d[11] = 0.3f; d[25] = 0.5f
        assertEquals(listOf(2000L, 5000L), StudioMath.sceneCuts(d, 200))
    }
    @Test fun keepRangesTakeOutLongQuietWithPadding() {
        val p = FloatArray(100) { if (it in 20 until 40) 0.01f else 0.5f } // 50 ms steps: quiet from 1000 to 2000 ms
        assertEquals(listOf(0L to 1150L, 1850L to 5000L), StudioMath.keepRanges(p, 50, 0, 5000))
        val short = FloatArray(100) { if (it in 20 until 30) 0.01f else 0.5f } // only 500 ms of quiet: kept
        assertEquals(listOf(0L to 5000L), StudioMath.keepRanges(short, 50, 0, 5000))
    }
    @Test fun beatsFollowLoudRises() {
        val p = FloatArray(80) { if (it % 10 == 0) 0.9f else 0.1f }
        assertEquals(listOf(500L, 1000L, 1500L, 2000L, 2500L, 3000L, 3500L), StudioMath.beats(p, 50))
    }
}
