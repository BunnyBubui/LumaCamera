package th.ogay.luma

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Audits Studio 0.3 screens for problems that can be measured from the view tree: touch targets under
 * 48 dp, clickable views without a label, text cut off by its container, and controls pushed off screen.
 * Each screen is checked at font scale 1.0 and 2.0, and the camera and video screens also in landscape.
 *
 * This is not a TalkBack listening test and not a smoothness measurement. Findings are written to
 * logcat with the tag LumaA11y, one line each: screen | font | orientation | kind | view | detail.
 */
@RunWith(AndroidJUnit4::class)
class StudioAccessibilityTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val density get() = context.resources.displayMetrics.density

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun fontScale(scale: Float) {
        shell("settings put system font_scale $scale")
        SystemClock.sleep(800)
    }

    @After fun restore() {
        fontScale(1.0f)
        runCatching { device.setOrientationNatural(); device.unfreezeRotation() }
    }

    private fun label(v: View): String {
        val text = (v as? TextView)?.text?.toString().orEmpty()
        val desc = v.contentDescription?.toString().orEmpty()
        return listOf(desc, text).firstOrNull { it.isNotBlank() }?.replace("\n", " ")?.take(40) ?: ""
    }

    private fun insideScroll(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            // CenterStrip is a horizontal scroller too: items away from the centre are reached by scrolling it.
            if (p is ScrollView || p is HorizontalScrollView || p is CenterStrip) return true
            p = p.parent
        }
        return false
    }

    /** Walks the view tree on the main thread and returns one line per finding. */
    private fun audit(root: View, where: String): List<String> {
        val issues = mutableListOf<String>()
        val minPx = (48 * density - 0.5f).toInt()
        val screen = Rect(0, 0, root.width, root.height)
        fun visit(v: View) {
            if (!v.isShown || v.width == 0 || v.height == 0) return
            val name = v.javaClass.simpleName
            val clickable = v.isClickable && v.isEnabled
            if (clickable) {
                if (v.width < minPx || v.height < minPx) {
                    issues += "$where | small-target | $name \"${label(v)}\" | %.0fx%.0f dp".format(v.width / density, v.height / density)
                }
                if (label(v).isBlank()) issues += "$where | no-label | $name | clickable view without text or contentDescription"
                val visible = Rect()
                val shown = v.getGlobalVisibleRect(visible)
                if (!insideScroll(v) && (!shown || visible.width() * visible.height() < 0.9 * v.width * v.height || !Rect.intersects(screen, visible))) {
                    issues += "$where | cut-off | $name \"${label(v)}\" | only ${visible.width()}x${visible.height()} of ${v.width}x${v.height} px visible"
                }
            }
            if (v is TextView && v.text.isNotEmpty()) {
                val layout = v.layout
                if (layout != null) {
                    val needed = layout.height + v.compoundPaddingTop + v.compoundPaddingBottom
                    if (needed > v.height + 2) {
                        issues += "$where | text-clipped | $name \"${label(v)}\" | needs %.0f dp, has %.0f dp, %d lines".format(needed / density, v.height / density, layout.lineCount)
                    }
                    if ((0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) {
                        issues += "$where | text-ellipsized | $name \"${label(v)}\""
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return issues
    }

    private inline fun <reified A : Activity> screen(name: String, intent: Intent, font: Float, landscape: Boolean = false): List<String> {
        fontScale(font)
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        SystemClock.sleep(500)
        var found = emptyList<String>()
        ActivityScenario.launch<A>(intent).use { scenario ->
            SystemClock.sleep(3500)
            scenario.onActivity { found = audit(it.window.decorView, "$name | font $font | ${if (landscape) "landscape" else "portrait"}") }
        }
        return found
    }

    private fun documentIntent(): Intent {
        val b = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.WHITE); drawText("LUMA", 200f, 300f, Paint().apply { textSize = 48f }) }
        val file = File(context.filesDir, "a11y-document.jpg")
        PhotoEffects.write(b, file); b.recycle()
        return Intent(context, DocumentActivity::class.java).setData(MediaLedger.privateUri(context, file))
    }

    @Test fun auditStudioScreens() {
        val report = mutableListOf<String>()
        val dm = context.resources.displayMetrics
        report += "display ${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}, API ${android.os.Build.VERSION.SDK_INT}"
        for (font in listOf(1.0f, 2.0f)) {
            report += screen<CameraActivity>("Camera", Intent(context, CameraActivity::class.java), font)
            report += screen<CameraActivity>("Camera", Intent(context, CameraActivity::class.java), font, landscape = true)
            report += screen<VideoActivity>("Video", Intent(context, VideoActivity::class.java), font)
            report += screen<VideoActivity>("Video", Intent(context, VideoActivity::class.java), font, landscape = true)
            report += screen<SpecialVideoActivity>("SlowMotion", Intent(context, SpecialVideoActivity::class.java).putExtra("mode", "SLOW"), font)
            report += screen<RawLabActivity>("RawLab", Intent(context, RawLabActivity::class.java), font)
            report += screen<DocumentActivity>("Document", documentIntent(), font)
            report += screen<ClipEditorActivity>("ClipEditor", Intent(context, ClipEditorActivity::class.java), font)
            report += screen<GalleryActivity>("Gallery", Intent(context, GalleryActivity::class.java), font)
            report += screen<StudioLibraryActivity>("Library", Intent(context, StudioLibraryActivity::class.java), font)
        }
        report += moreSheetAtLargeFont()
        report.forEach { Log.i(TAG, it) }
        // Every line after the display summary is a finding, except the sheet line that reports reachability.
        val findings = report.drop(1).filterNot { it.contains("| reachable |") }
        assertTrue("accessibility findings ‖ " + findings.joinToString(" ‖ "), findings.isEmpty())
    }

    /** Opens the glass options panel at font 2.0 and checks that its last action can be scrolled to. */
    private fun moreSheetAtLargeFont(): List<String> {
        fontScale(2.0f)
        device.setOrientationNatural()
        val out = mutableListOf<String>()
        ActivityScenario.launch<CameraActivity>(Intent(context, CameraActivity::class.java)).use {
            SystemClock.sleep(3500)
            // The panel opens from the handle above the mode bar (swipe up or tap).
            var opened = false
            it.onActivity { activity ->
                val found = ArrayList<View>()
                activity.window.decorView.findViewsWithText(found, "ตัวเลือกกล้อง", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                opened = found.firstOrNull()?.performClick() == true
            }
            if (!opened) { out += "MoreSheet | font 2.0 | portrait | missing | no \"ตัวเลือกกล้อง\" handle"; return out }
            SystemClock.sleep(1500)
            val reached = runCatching { UiScrollable(UiSelector().className("android.widget.ScrollView").scrollable(true)).scrollTextIntoView("ตั้งค่าทั้งหมด") }.getOrDefault(false) ||
                device.hasObject(By.text("ตั้งค่าทั้งหมด"))
            out += "MoreSheet | font 2.0 | portrait | ${if (reached) "reachable" else "unreachable"} | last action \"ตั้งค่าทั้งหมด\""
            // Items cut by the sheet's scroll edge report only their visible part, so they are skipped.
            val viewport = device.findObject(By.clazz("android.widget.ScrollView").scrollable(true))?.visibleBounds
            device.findObjects(By.clickable(true)).forEach { o ->
                val b = o.visibleBounds
                val clipped = viewport != null && (b.top <= viewport.top + 1 || b.bottom >= viewport.bottom - 1)
                if (!clipped && (b.width() < 48 * density - 1 || b.height() < 48 * density - 1)) {
                    out += "MoreSheet | font 2.0 | portrait | small-target | \"${o.contentDescription ?: o.text}\" | %.0fx%.0f dp".format(b.width() / density, b.height() / density)
                }
            }
            device.pressBack()
        }
        return out
    }

    companion object { private const val TAG = "LumaA11y" }
}
