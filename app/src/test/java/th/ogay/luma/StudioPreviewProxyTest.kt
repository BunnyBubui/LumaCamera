package th.ogay.luma

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StudioPreviewProxyTest {
    private fun source(uri: String = "content://test/source", modified: Long = 1_000,
        width: Int = 1920, height: Int = 1080, rotation: Int = 0,
        range: StudioPreviewQuality.DynamicRange = StudioPreviewQuality.DynamicRange.SDR,
        audio: Boolean = true) = StudioPreviewQuality.Source(uri, 8_000_000, modified, null,
        width, height, rotation, 10_000_000, 30f, range, 1, 3, 2, audio)
    private fun map() = StudioProxyCache.TimestampMap(listOf(
        StudioProxyCache.Anchor(0, 0), StudioProxyCache.Anchor(5_000_000, 4_980_000),
        StudioProxyCache.Anchor(10_000_000, 9_960_000)))
    private fun dir(): File = Files.createTempDirectory("lumora-proxy-test").toFile()
    private fun producer(counter: AtomicInteger = AtomicInteger()) = StudioProxyCache.Producer { _, _, output, stop ->
        stop.check(); counter.incrementAndGet(); output.writeBytes(ByteArray(512) { 21 });
        StudioProxyCache.Produced(map(), true)
    }
    private fun request(cache: StudioProxyCache, source: StudioPreviewQuality.Source,
        profile: StudioPreviewQuality.Profile, producer: StudioProxyCache.Producer): Result<StudioProxyCache.Entry> {
        val latch = CountDownLatch(1); val result = AtomicReference<Result<StudioProxyCache.Entry>>()
        cache.request(source, profile, producer) { result.set(it); latch.countDown() }
        assertTrue("worker did not finish", latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun profilePreservesEncodedOrientationAndCapsFpsWithoutUpscaling() {
        val quality = StudioPreviewQuality()
        assertEquals(StudioPreviewQuality.Profile(1280, 720, 30), quality.profileFor(source()))
        assertEquals(StudioPreviewQuality.Profile(720, 1280, 30),
            quality.profileFor(source(width = 1080, height = 1920, rotation = 90)))
        assertNull(quality.profileFor(source(width = 1280, height = 720)))
        assertNull(quality.profileFor(source(range = StudioPreviewQuality.DynamicRange.HDR_PQ)))
        assertNull(quality.profileFor(source(range = StudioPreviewQuality.DynamicRange.UNKNOWN)))
        assertNull(quality.profileFor(source().copy(colorStandard = 6)))
        assertNull(quality.profileFor(source().copy(colorTransfer = 7)))
        assertNull(quality.profileFor(source().copy(bytes = -1, modifiedMs = -1)))
        assertEquals(24, quality.profileFor(source().copy(frameRate = 24f))!!.fps)
    }

    @Test fun identityChangesWithRevisionColorRotationAndEncodingProfile() {
        val s = source()
        assertNotEquals(s.identity, s.copy(modifiedMs = s.modifiedMs + 1).identity)
        assertNotEquals(s.identity, s.copy(revision = "provider-generation-2").identity)
        assertNotEquals(s.identity, s.copy(rotationDegrees = 90).identity)
        assertNotEquals(s.identity, s.copy(colorRange = 1).identity)
        val q = StudioPreviewQuality().profileFor(s)!!
        assertNotEquals(q.identity, q.copy(fps = 24).identity)
    }

    @Test fun timestampMappingUsesSourceTrimsAndClampsSafely() {
        val m = map()
        assertEquals(2_490_000, m.toProxyUs(2_500_000))
        assertEquals(7_470_000, m.fromTrimmedSourceUs(5_000_000, 2_500_000))
        assertEquals(7_500_000, m.toSourceUs(7_470_000))
        assertEquals(0, m.toProxyUs(-1))
        assertEquals(9_960_000, m.toProxyUs(Long.MAX_VALUE))
        assertEquals(9_960_000, m.fromTrimmedSourceUs(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(m.serialized(), StudioProxyCache.TimestampMap.parse(m.serialized()).serialized())
        runCatching { StudioProxyCache.TimestampMap(listOf(StudioProxyCache.Anchor(0, 0), StudioProxyCache.Anchor(0, 1))) }
            .onSuccess { fail("duplicate PTS must be rejected") }
    }

    @Test fun featureGatePressureAndSafeBoundariesPreventOscillation() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        val file = File(dir(), "mock.mp4").apply { writeText("proxy bytes") }
        val entry = StudioProxyCache.Entry(file, s.identity, p.identity, true, map(), 0)
        val disabled = StudioPreviewQuality()
        repeat(3) { disabled.observe(StudioPreviewQuality.RenderWindow(100, 40)) }
        assertEquals(StudioPreviewQuality.Mode.ORIGINAL,
            disabled.choose(s, entry, StudioPreviewQuality.Mode.ORIGINAL, StudioPreviewQuality.Boundary.CLIP_START, 40_000, 1_000_000).mode)
        val q = StudioPreviewQuality(StudioPreviewQuality.Settings(enabled = true, verifiedOnDevice = true))
        repeat(3) { q.observe(StudioPreviewQuality.RenderWindow(100, 30)) }
        assertFalse(q.choose(s, entry, StudioPreviewQuality.Mode.ORIGINAL,
            StudioPreviewQuality.Boundary.NONE, 45_000, 2_500_000).mayHandoff)
        val chosen = q.choose(s, entry, StudioPreviewQuality.Mode.ORIGINAL,
            StudioPreviewQuality.Boundary.CLIP_START, 45_000, 2_500_000)
        assertEquals(StudioPreviewQuality.Mode.PROXY, chosen.mode)
        assertEquals(s.uri, chosen.originalAudioUri)
        assertEquals(2_490_000, chosen.playbackPositionUs)
        q.activated(45_000)
        repeat(8) { q.observe(StudioPreviewQuality.RenderWindow(100, 1)) }
        assertEquals(StudioPreviewQuality.Mode.PROXY, q.choose(s, entry,
            StudioPreviewQuality.Mode.PROXY, StudioPreviewQuality.Boundary.CLIP_START, 46_000, 0).mode)
        assertEquals(StudioPreviewQuality.Mode.ORIGINAL, q.choose(s, entry,
            StudioPreviewQuality.Mode.PROXY, StudioPreviewQuality.Boundary.CLIP_START, 76_000, 0).mode)
    }

    @Test fun asyncCreationIsOffCallerThreadAndCacheHitsDoNotRetranscode() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        val main = Thread.currentThread().name; val workerName = AtomicReference<String>()
        val calls = AtomicInteger()
        StudioProxyCache(dir()).use { cache ->
            val produce = StudioProxyCache.Producer { a, b, out, stop ->
                workerName.set(Thread.currentThread().name)
                producer(calls).create(a, b, out, stop)
            }
            assertTrue(request(cache, s, p, produce).isSuccess)
            assertNotEquals(main, workerName.get())
            assertTrue(workerName.get().contains("proxy"))
            assertNotNull(cache.lookup(s, p))
            assertTrue(request(cache, s, p, produce).isSuccess)
            assertEquals(1, calls.get())
        }
    }

    @Test fun invalidateAndMutationNeverReuseStaleProxy() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        StudioProxyCache(dir()).use { cache ->
            assertTrue(request(cache, s, p, producer()).isSuccess)
            assertNotNull(cache.lookup(s, p))
            assertNull(cache.lookup(s.copy(modifiedMs = 1001), p))
            cache.invalidate(s.uri)
            assertNull(cache.lookup(s, p))
        }
    }

    @Test fun cancellationCannotPromotePartialProxy() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        StudioProxyCache(dir()).use { cache ->
            val started = CountDownLatch(1)
            val ticket = cache.request(s, p, StudioProxyCache.Producer { _, _, out, stop ->
                started.countDown(); out.writeText("unfinished")
                while (!stop.isCancelled) Thread.sleep(10)
                stop.check(); StudioProxyCache.Produced(map(), true)
            }) { fail("cancelled job must not issue a successful callback") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            ticket.cancel()
            // The next job is serialized after cancellation and checks that no partial proxy was committed.
            assertTrue(request(cache, s, p, producer()).isSuccess)
            assertEquals(512, cache.lookup(s, p)!!.file.length())
        }
    }


    @Test fun invalidationDuringTranscodeRejectsOldGeneration() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        StudioProxyCache(dir()).use { cache ->
            val started = CountDownLatch(1); val unblock = CountDownLatch(1)
            val finished = CountDownLatch(1); val outcome = AtomicReference<Result<StudioProxyCache.Entry>>()
            cache.request(s, p, StudioProxyCache.Producer { _, _, out, stop ->
                started.countDown()
                if (!unblock.await(5, TimeUnit.SECONDS)) error("test release timeout")
                stop.check(); out.writeBytes(ByteArray(256))
                StudioProxyCache.Produced(map(), true)
            }) { outcome.set(it); finished.countDown() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.invalidate(s.uri)
            unblock.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(outcome.get().isFailure)
            assertNull(cache.lookup(s, p))
        }
    }

    @Test fun missingAudioAndBadMappingFailClosed() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        StudioProxyCache(dir()).use { cache ->
            val noAudio = StudioProxyCache.Producer { _, _, out, _ -> out.writeText("video only")
                StudioProxyCache.Produced(map(), false) }
            assertTrue(request(cache, s, p, noAudio).isFailure)
            assertNull(cache.lookup(s, p))
            val wrongMap = StudioProxyCache.Producer { _, _, out, _ -> out.writeText("bad map")
                StudioProxyCache.Produced(StudioProxyCache.TimestampMap(listOf(
                    StudioProxyCache.Anchor(0, 0), StudioProxyCache.Anchor(1_000_000, 1_000_000))), true) }
            assertTrue(request(cache, s, p, wrongMap).isFailure)
            assertNull(cache.lookup(s, p))
        }
    }

    @Test fun lruIsBoundedAndOldestSourceIsEvicted() {
        var now = 100_000L
        StudioProxyCache(dir(), maxBytes = 1_000_000, maxEntries = 1,
            maxSingleBytes = 10_000, clockMs = { now }).use { cache ->
            val first = source("content://test/first")
            val second = source("content://test/second")
            val q = StudioPreviewQuality()
            val p = q.profileFor(first)!!
            assertTrue(request(cache, first, p, producer()).isSuccess)
            now += 1000
            assertTrue(request(cache, second, p, producer()).isSuccess)
            assertNull(cache.lookup(first, p))
            assertNotNull(cache.lookup(second, p))
        }
    }

    @Test fun exportAlwaysKeepsOriginalSourceIdentity() {
        val s = source(); val p = StudioPreviewQuality().profileFor(s)!!
        val f = File(dir(), "proxy.mp4").apply { writeText("test") }
        val q = StudioPreviewQuality(StudioPreviewQuality.Settings(enabled = true, verifiedOnDevice = true))
        repeat(3) { q.observe(StudioPreviewQuality.RenderWindow(100, 50)) }
        val selected = q.choose(s, StudioProxyCache.Entry(f, s.identity, p.identity, true, map(), 1),
            StudioPreviewQuality.Mode.ORIGINAL, StudioPreviewQuality.Boundary.CLIP_START, 60_000, 1_000_000)
        assertEquals(StudioPreviewQuality.Mode.PROXY, selected.mode)
        assertEquals(s.uri, selected.originalAudioUri)
        assertNotEquals(s.uri, selected.videoUri)
        // VideoEditSpec.source remains the original and is not changed by either Phase 5 class.
        assertEquals("content://test/source", s.uri)
    }
}