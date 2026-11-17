package th.ogay.luma.autoedit

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import th.ogay.luma.VideoLayer
import th.ogay.luma.autoedit.core.*
import th.ogay.luma.autoedit.effects.*

@RunWith(AndroidJUnit4::class)
class SfxStudioLayerAdapterTest {
    private val media = MediaAssetRef("g", "content://game/recording", 10_000_000, 1920, 1080)
    private val clip = ClipDecision("clip", "g", 0, 10_000_000, 0, "safe", 0.8)
    private fun edl(vararg cues: SfxCue) = EditDecisionList(
        planId = "sound-bridge-test",
        presetSnapshot = AutoEditPreset.gamingDefault(),
        analysisVersion = "fixture", sourceAssets = listOf(media), clips = listOf(clip),
        sfxCues = cues.toList()
    )

    @Test fun convertsDbToLinearSoundLayerWithoutChangingExistingMusic() {
        val music = VideoLayer(VideoLayer.SOUND, Uri.parse("content://music/track"),
            0, 10_000, music = true, volume = 0.8f)
        val result = SfxStudioLayerAdapter.adapt(
            edl(SfxCue("impact", "content://sfx/impact", 2_000_000, -12.0, "kill")),
            existingLayers = listOf(music),
            probe = SfxDurationProbe { 800_000L }
        )
        assertEquals(1, result.addedCount)
        assertEquals(music, result.layers.first())
        val sfx = result.layers.last()
        assertEquals(VideoLayer.SOUND, sfx.kind)
        assertFalse(sfx.music)
        assertEquals(2000L, sfx.at)
        assertEquals(800L, sfx.length)
        assertEquals(Math.pow(10.0, -12.0 / 20.0), sfx.volume.toDouble(), 0.0001)
        assertTrue(sfx.fadeOut > 0)
    }

    @Test fun missingAudioAndExternalNetworkUriAreRejected() {
        val result = SfxStudioLayerAdapter.adapt(
            edl(
                SfxCue("broken", "content://sfx/gone", 1_000_000, -15.0, "impact"),
                SfxCue("remote", "https://example.com/sound.mp3", 2_000_000, -15.0, "impact")
            ),
            emptyList(), probe = SfxDurationProbe { null }
        )
        assertEquals(0, result.addedCount)
        assertEquals(2, result.rejected.size)
    }

    @Test fun repeatedApplicationDoesNotDoubleTheSameSound() {
        val p = edl(SfxCue("impact", "content://sfx/impact", 2_000_000, -12.0, "kill"))
        val first = SfxStudioLayerAdapter.adapt(p, emptyList(), SfxDurationProbe { 500_000L })
        val second = SfxStudioLayerAdapter.adapt(p, first.layers, SfxDurationProbe { 500_000L })
        assertEquals(1, first.addedCount)
        assertEquals(0, second.addedCount)
        assertEquals(1, second.layers.size)
        assertEquals("already_on_timeline", second.rejected.single().reason)
    }
}
