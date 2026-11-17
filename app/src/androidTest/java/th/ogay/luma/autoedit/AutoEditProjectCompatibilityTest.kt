package th.ogay.luma.autoedit

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import th.ogay.luma.StudioProjects
import th.ogay.luma.autoedit.core.*

@RunWith(AndroidJUnit4::class)
class AutoEditProjectCompatibilityTest {
    private fun sample() = EditDecisionList(
        planId = "compat-test",
        presetSnapshot = AutoEditPreset.gamingDefault(),
        analysisVersion = "phase1",
        sourceAssets = listOf(MediaAssetRef("a1", "content://game/video",
            5_000_000, 1920, 1080)),
        clips = listOf(ClipDecision("c1", "a1", 0, 2_000_000,
            0, "retained original", 0.6))
    )

    @Test fun existingStudioStateCanRoundtripWithAdditiveAutoEditData() {
        val legacy = Bundle().apply {
            putString("caption", "existing manual edit")
            putFloat("speed", 1.5f)
        }
        val augmented = AutoEditProjectAdapter.attach(legacy, sample())
        assertFalse(legacy.containsKey(AutoEditProjectAdapter.BUNDLE_KEY))
        val persisted = StudioProjects.encode(augmented)
        val restored = StudioProjects.decode(persisted)
        assertEquals("existing manual edit", restored.getString("caption"))
        assertEquals(1.5f, restored.getFloat("speed"), 0f)
        val result = AutoEditProjectAdapter.restore(restored)
        assertTrue(result is AutoEditProjectAdapter.Restore.Loaded)
        assertEquals(sample(), (result as AutoEditProjectAdapter.Restore.Loaded).plan)
    }

    @Test fun legacyProjectWithoutAutoEditKeyRemainsValid() {
        val legacy = Bundle().apply { putString("caption", "old project") }
        val restored = StudioProjects.decode(StudioProjects.encode(legacy))
        assertTrue(AutoEditProjectAdapter.restore(restored) is AutoEditProjectAdapter.Restore.Missing)
        assertEquals("old project", restored.getString("caption"))
    }

    @Test fun malformedMetadataIsNotAllowedToRewriteLegacyEdits() {
        val legacy = Bundle().apply {
            putString("caption", "keep this")
            putString(AutoEditProjectAdapter.BUNDLE_KEY, "{bad json")
        }
        val result = AutoEditProjectAdapter.restore(legacy)
        assertTrue(result is AutoEditProjectAdapter.Restore.Invalid)
        assertEquals("keep this", legacy.getString("caption"))
    }
}
