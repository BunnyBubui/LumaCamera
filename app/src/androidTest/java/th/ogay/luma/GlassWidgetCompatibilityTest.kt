package th.ogay.luma

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassWidgetCompatibilityTest {
    @Test fun sliderHandlesEmptyAutoOnlyAndDisabledAccessibilityActions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val slider = GlassSlider(inst.targetContext)
            slider.values = emptyList()
            val empty = AccessibilityNodeInfo.obtain()
            slider.onInitializeAccessibilityNodeInfo(empty)
            assertNull(empty.rangeInfo)
            assertFalse(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))

            slider.values = listOf("AUTO")
            slider.lo = 1 // No manual values are exposed by this camera.
            slider.index = 0
            val auto = AccessibilityNodeInfo.obtain()
            slider.onInitializeAccessibilityNodeInfo(auto)
            assertEquals(0f, auto.rangeInfo.min, 0f)
            assertEquals(0f, auto.rangeInfo.max, 0f)
            assertEquals("AUTO", AccessibilityNodeInfoCompat.wrap(auto).stateDescription.toString())
            assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(0, slider.index)

            slider.isEnabled = false
            assertFalse(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
            empty.recycle()
            auto.recycle()
        }
    }
}
