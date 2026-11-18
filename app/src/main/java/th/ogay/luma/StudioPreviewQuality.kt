package th.ogay.luma

import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/** Preview-only decisions. The export path must always receive [Source.uri], never a proxy URI. */
class StudioPreviewQuality(private val settings: Settings = Settings()) {
    enum class DynamicRange { SDR, HDR_PQ, HDR_HLG, UNKNOWN }
    enum class Mode { ORIGINAL, PROXY }
    enum class Boundary { NONE, CLIP_START, PAUSED_REPOSITION, RECOVERY }

    /** Raw/encoded dimensions (before rotation). Callers must not swap dimensions twice. */
    data class Source(
        val uri: String, val bytes: Long, val modifiedMs: Long, val revision: String? = null,
        val width: Int, val height: Int, val rotationDegrees: Int, val durationUs: Long,
        val frameRate: Float, val dynamicRange: DynamicRange,
        val colorStandard: Int = 0, val colorTransfer: Int = 0, val colorRange: Int = 0,
        val hasAudio: Boolean = true
    ) {
        init { require(uri.isNotBlank()); require(width > 0 && height > 0 && durationUs > 0)
            require(rotationDegrees in setOf(0, 90, 180, 270)); require(bytes >= -1 && modifiedMs >= -1) }
        /** A URI without a trustworthy revision must not be cached (content providers may mutate it). */
        val cacheable: Boolean get() = !revision.isNullOrBlank() || (bytes > 0L && modifiedMs > 0L)
        val identity: String get() = studioPreviewHash(listOf(uri, bytes, modifiedMs, revision.orEmpty(), width, height,
            rotationDegrees, durationUs, frameRate, dynamicRange.name, colorStandard, colorTransfer, colorRange, hasAudio).joinToString("|") { "${it.toString().length}:$it" })
    }

    /** H.264/SDR profile. Width/height refer to encoded, not display-oriented, pixels. */
    data class Profile(val width: Int, val height: Int, val fps: Int, val bitrate: Int = 2_000_000, val version: Int = 1) {
        init { require(width >= 2 && width % 2 == 0 && height >= 2 && height % 2 == 0)
            require(fps in 1..60 && bitrate in 100_000..20_000_000 && version > 0) }
        val identity: String get() = studioPreviewHash("h264|${width}x$height|$fps|$bitrate|$version|sdr")
    }

    data class Settings(
        /** Off until a real device verifies decoder, frame continuity, colors and original audio. */
        val enabled: Boolean = false, val verifiedOnDevice: Boolean = false,
        val highPressureWindows: Int = 3, val recoveryWindows: Int = 8,
        val cooldownMs: Long = 30_000L
    ) {
        init { require(highPressureWindows > 0 && recoveryWindows > 0 && cooldownMs >= 0) }
    }

    /** Only measured/rendered frame callbacks are valid inputs; UI-tick timings are not decoded frames. */
    data class RenderWindow(val renderedFrames: Int, val lateFrames: Int) {
        init { require(renderedFrames >= 0 && lateFrames in 0..renderedFrames) }
    }

    data class Decision(
        val mode: Mode, val videoUri: String, val originalAudioUri: String,
        /** Position of the source after trim/speed mapping; the integration layer owns that calculation. */
        val sourcePositionUs: Long, val playbackPositionUs: Long, val reason: String,
        /** Only true at a safe boundary; NONE never asks the player to switch surfaces. */
        val mayHandoff: Boolean = false
    )

    private var slowStreak = 0
    private var goodStreak = 0
    private var lastActivatedAtMs = Long.MIN_VALUE

    fun observe(window: RenderWindow) {
        if (window.renderedFrames < 60) return // no proxy decision from a tiny or unverified sample
        val lateFraction = window.lateFrames.toDouble() / window.renderedFrames
        if (lateFraction >= 0.20) { slowStreak++; goodStreak = 0 }
        else if (lateFraction <= 0.03) { goodStreak++; slowStreak = 0 }
        else { slowStreak = 0; goodStreak = 0 }
    }

    /** Call only after the player actually switches; decisions do not mutate playback state. */
    fun activated(atMonotonicMs: Long) {
        lastActivatedAtMs = atMonotonicMs
        slowStreak = 0; goodStreak = 0
    }

    /** Never upscale or transcode HDR/unknown color profiles silently. */
    fun profileFor(source: Source): Profile? {
        if (!source.cacheable || source.dynamicRange != DynamicRange.SDR) return null
        // Restrict initial encoder to explicitly reported BT.709 / SDR video transfer.
        // Unknown, wide-gamut or HDR metadata requires device-specific color validation.
        if (source.colorStandard != 1 || source.colorTransfer != 3) return null
        val longest = maxOf(source.width, source.height).toDouble()
        val shortest = minOf(source.width, source.height).toDouble()
        val scale = min(1.0, min(1280.0 / longest, 720.0 / shortest))
        if (scale >= 0.95) return null
        val w = ((source.width * scale / 2).roundToInt() * 2).coerceAtLeast(2)
        val h = ((source.height * scale / 2).roundToInt() * 2).coerceAtLeast(2)
        val fps = if (source.frameRate.isFinite() && source.frameRate > 0f) min(30, source.frameRate.roundToInt().coerceAtLeast(1)) else 30
        return Profile(w, h, fps)
    }

    /** Safe-boundary-only switching, with cooldown and no proxy use until explicitly device-validated. */
    fun choose(source: Source, proxy: StudioProxyCache.Entry?, current: Mode, boundary: Boundary,
        atMonotonicMs: Long, sourcePositionUs: Long): Decision {
        val sourceUs = sourcePositionUs.coerceIn(0L, source.durationUs)
        val original = Decision(Mode.ORIGINAL, source.uri, source.uri, sourceUs, sourceUs, "original")
        if (boundary == Boundary.NONE) return original.copy(reason = "defer-until-boundary") // caller must ignore while playing
        val profile = profileFor(source)
        if (!settings.enabled || !settings.verifiedOnDevice) return original.copy(reason = "feature-gated", mayHandoff = true)
        if (profile == null) return original.copy(reason = "unsafe-or-unhelpful-source", mayHandoff = true)
        if (proxy == null || !proxy.matches(source, profile) || (source.hasAudio && !proxy.hasAudio)) {
            return original.copy(reason = "cache-miss-or-invalid", mayHandoff = true)
        }
        val since = if (lastActivatedAtMs == Long.MIN_VALUE || atMonotonicMs < lastActivatedAtMs) Long.MAX_VALUE
            else atMonotonicMs - lastActivatedAtMs
        val switchAllowed = since >= settings.cooldownMs || boundary == Boundary.RECOVERY
        val useProxy = when (current) {
            Mode.PROXY -> !(goodStreak >= settings.recoveryWindows && switchAllowed)
            Mode.ORIGINAL -> slowStreak >= settings.highPressureWindows && switchAllowed
        }
        if (!useProxy) return original.copy(reason = if (current == Mode.PROXY) "recovered" else "original-or-cooldown", mayHandoff = true)
        return Decision(Mode.PROXY, proxy.file.toURI().toString(), source.uri, sourceUs,
            proxy.timestampMap.toProxyUs(sourceUs), "measured-pressure-or-hold", mayHandoff = true)
    }
}

internal fun studioPreviewHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }