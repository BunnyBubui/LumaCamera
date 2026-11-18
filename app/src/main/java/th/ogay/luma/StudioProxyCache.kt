package th.ogay.luma

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** A preview-only full-source proxy cache. Never pass [Entry.file] to VideoEdit/composition/export. */
class StudioProxyCache(
    private val directory: File,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val maxEntries: Int = 16,
    private val maxSingleBytes: Long = 96L * 1024 * 1024,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lumora-preview-proxy").apply { isDaemon = true }
    }
) : Closeable {
    init { require(maxBytes > 0 && maxEntries > 0 && maxSingleBytes in 1..maxBytes && ttlMs > 0) }

    data class Anchor(val sourceUs: Long, val proxyUs: Long)

    /** Measured source/proxy presentation-time anchors (in microseconds), not clip-relative times. */
    class TimestampMap(val anchors: List<Anchor>) {
        init { require(anchors.size >= 2 && anchors.first() == Anchor(0, 0))
            require(anchors.zipWithNext().all { (a, b) -> b.sourceUs > a.sourceUs && b.proxyUs > a.proxyUs }) }
        fun toProxyUs(sourceUs: Long) = interpolate(sourceUs, source = true)
        fun toSourceUs(proxyUs: Long) = interpolate(proxyUs, source = false)
        /** Trim positions refer to the original source; speed curves must be resolved before this call. */
        fun fromTrimmedSourceUs(trimStartUs: Long, sourceElapsedUs: Long): Long =
            toProxyUs(saturatedAdd(trimStartUs.coerceAtLeast(0), sourceElapsedUs.coerceAtLeast(0)))
        private fun interpolate(value: Long, source: Boolean): Long {
            val first = if (source) anchors.first().sourceUs else anchors.first().proxyUs
            val last = if (source) anchors.last().sourceUs else anchors.last().proxyUs
            val position = value.coerceIn(first, last)
            val i = anchors.indexOfLast { (if (source) it.sourceUs else it.proxyUs) <= position }
                .coerceIn(0, anchors.lastIndex - 1)
            val a = anchors[i]; val b = anchors[i + 1]
            val from = if (source) a.sourceUs else a.proxyUs
            val end = if (source) b.sourceUs else b.proxyUs
            val outputFrom = if (source) a.proxyUs else a.sourceUs
            val outputEnd = if (source) b.proxyUs else b.sourceUs
            // Doubles here cannot overflow on long media; result is clamped to valid anchor range.
            return (outputFrom + (position - from).toDouble() * (outputEnd - outputFrom) / (end - from))
                .toLong().coerceIn(outputFrom, outputEnd)
        }
        fun serialized(): String = anchors.joinToString(",") { "${it.sourceUs}:${it.proxyUs}" }
        companion object {
            fun parse(value: String): TimestampMap = TimestampMap(value.split(',').map {
                val fields = it.split(':'); require(fields.size == 2)
                Anchor(fields[0].toLong(), fields[1].toLong())
            })
        }
    }

    /** [hasAudio] confirms the proxy includes an audible track when the source has one. */
    data class Entry(
        val file: File, val sourceKey: String, val profileKey: String,
        val hasAudio: Boolean, val timestampMap: TimestampMap, val createdMs: Long
    ) {
        fun matches(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile): Boolean =
            source.cacheable && source.identity == sourceKey && profile.identity == profileKey && file.isFile && file.length() > 0
    }

    class Cancellation internal constructor() {
        private val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean get() = cancelled.get() || Thread.currentThread().isInterrupted
        fun check() { if (isCancelled) throw java.util.concurrent.CancellationException("proxy cancelled") }
        internal fun cancel() { cancelled.set(true) }
    }
    class Request internal constructor(private val cancellation: Cancellation, private val future: Future<*>) {
        fun cancel() { cancellation.cancel(); future.cancel(true) }
    }

    data class Produced(val timestampMap: TimestampMap, val hasAudio: Boolean)
    /** Implement with a platform encoder on a worker/codec looper; write an MP4 of the FULL original source.
     * Must honor [Cancellation]. Return measured timestamps, not inferred trim-relative offsets.
     * The cache never invokes a producer on the UI thread. */
    fun interface Producer {
        fun create(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile,
            output: File, cancellation: Cancellation): Produced
    }

    private val lock = Any()
    /** Invalidation fences in-flight workers as well as existing cache files. */
    private val generations = HashMap<String, Long>()
    private fun key(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile): String =
        studioPreviewHash("${source.identity}:${profile.identity}")
    private fun video(key: String) = File(directory, "$key.mp4")
    private fun metadata(key: String) = File(directory, "$key.meta")
    private fun sourceHash(uri: String) = studioPreviewHash(uri)

    /** Disk lookup: call on an IO worker, or use [lookupAsync]. Does not start an encoder. */
    fun lookup(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile): Entry? = synchronized(lock) {
        if (!source.cacheable) return@synchronized null
        val k = key(source, profile); val f = video(k); val m = metadata(k)
        if (!f.isFile || !m.isFile) return@synchronized null
        try {
            val p = Properties().apply { m.inputStream().use { load(it) } }
            val created = p.getProperty("created")!!.toLong()
            if (clockMs() - created > ttlMs || clockMs() < created || p.getProperty("key") != k ||
                p.getProperty("source") != source.identity || p.getProperty("profile") != profile.identity ||
                p.getProperty("bytes")!!.toLong() != f.length() || f.length() <= 0) throw IOException("stale proxy")
            val map = TimestampMap.parse(p.getProperty("map")!!)
            if (map.anchors.last().sourceUs != source.durationUs) throw IOException("timestamp mismatch")
            f.setLastModified(clockMs())
            Entry(f, source.identity, profile.identity, p.getProperty("audio").toBoolean(), map, created)
        } catch (_: Exception) { f.delete(); m.delete(); null }
    }

    fun lookupAsync(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile,
        callback: (Entry?) -> Unit): Request {
        val c = Cancellation()
        val f = worker.submit { val found = lookup(source, profile); if (!c.isCancelled) callback(found) }
        return Request(c, f)
    }

    /** Schedule encoding on a dedicated worker. Callback runs on that worker; caller must marshal to UI. */
    fun request(source: StudioPreviewQuality.Source, profile: StudioPreviewQuality.Profile,
        producer: Producer, callback: (Result<Entry>) -> Unit): Request {
        val c = Cancellation()
        val revisionEpoch = synchronized(lock) { generations[source.uri] ?: 0L }
        val future = worker.submit {
            val result = runCatching {
                c.check()
                require(source.cacheable) { "source revision unknown" }
                lookup(source, profile)?.let { return@runCatching it }
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create proxy directory")
                val k = key(source, profile)
                val temp = File(directory, "$k.${UUID.randomUUID()}.part.mp4")
                try {
                    val made = producer.create(source, profile, temp, c)
                    c.check()
                    if (!temp.isFile || temp.length() !in 1..maxSingleBytes) throw IOException("proxy too large or empty")
                    if (source.hasAudio && !made.hasAudio) throw IOException("original audio missing from proxy")
                    if (made.timestampMap.anchors.last().sourceUs != source.durationUs) throw IOException("invalid source duration map")
                    synchronized(lock) {
                        c.check()
                        if ((generations[source.uri] ?: 0L) != revisionEpoch)
                            throw java.util.concurrent.CancellationException("source invalidated")
                        val dst = video(k); val meta = metadata(k)
                        // Promote only finished files. A missing sidecar after a crash is a cache miss.
                        if (!temp.renameTo(dst)) throw IOException("cannot promote proxy")
                        val p = Properties().apply {
                            setProperty("key", k); setProperty("source", source.identity)
                            setProperty("uriHash", sourceHash(source.uri)); setProperty("profile", profile.identity)
                            setProperty("bytes", dst.length().toString()); setProperty("created", clockMs().toString())
                            setProperty("audio", made.hasAudio.toString()); setProperty("map", made.timestampMap.serialized())
                        }
                        val staging = File(directory, "$k.${UUID.randomUUID()}.meta.part")
                        try {
                            staging.outputStream().use { p.store(it, "Lumora preview proxy v1") }
                            if (!staging.renameTo(meta)) throw IOException("cannot promote metadata")
                            c.check()
                        } catch (t: Throwable) { dst.delete(); meta.delete(); throw t }
                        finally { staging.delete() }
                        pruneLocked()
                        lookup(source, profile) ?: throw IOException("proxy not retained")
                    }
                } finally { temp.delete() }
            }
            if (!c.isCancelled) callback(result)
        }
        return Request(c, future)
    }

    /** Invalidates every proxy revision of a given source URI. */
    fun invalidate(uri: String) = synchronized(lock) {
        generations[uri] = (generations[uri] ?: 0L) + 1
        directory.listFiles { file -> file.name.endsWith(".meta") }?.forEach { file ->
            val p = runCatching { Properties().apply { file.inputStream().use { load(it) } } }.getOrNull()
            if (p?.getProperty("uriHash") == sourceHash(uri)) removePair(file.name.removeSuffix(".meta"))
        }
    }

    fun prune() = synchronized(lock) { pruneLocked() }
    private fun pruneLocked() {
        val files = directory.listFiles() ?: return
        val entries = ArrayList<Pair<File, File>>()
        files.filter { it.name.endsWith(".meta") }.forEach { m ->
            val k = m.name.removeSuffix(".meta"); val v = video(k)
            val created = runCatching { Properties().apply { m.inputStream().use { load(it) } }.getProperty("created")!!.toLong() }.getOrNull()
            if (!v.isFile || created == null || clockMs() - created > ttlMs || clockMs() < created) removePair(k)
            else entries += v to m
        }
        files.filter { it.name.endsWith(".mp4") && !metadata(it.name.removeSuffix(".mp4")).isFile }.forEach { it.delete() }
        // No in-flight .part.mp4 can exist here except from the single worker producing this entry.
        var total = entries.sumOf { it.first.length() + it.second.length() }
        var count = entries.size
        entries.sortedBy { it.first.lastModified() }.forEach { (v, m) ->
            if (total > maxBytes || count > maxEntries) {
                total -= v.length() + m.length(); count--; removePair(m.name.removeSuffix(".meta"))
            }
        }
    }
    private fun removePair(key: String) { video(key).delete(); metadata(key).delete() }
    override fun close() { worker.shutdownNow() }
}

private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b