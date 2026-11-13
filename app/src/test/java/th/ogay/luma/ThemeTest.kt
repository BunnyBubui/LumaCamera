package th.ogay.luma

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @After fun reset() = Theme.set(Theme.DEFAULT)

    @Test fun graphiteIsTheDefaultAndHasNoBlueCast() {
        assertEquals("Graphite", Theme.name(Theme.DEFAULT))
        val bg = Theme.DEFAULT.background; val panel = Theme.DEFAULT.panel
        for (c in intArrayOf(bg, panel, Theme.fill, Theme.fillStrong, Theme.fillPop, Theme.bgTop, Theme.tintTop)) {
            val r = (c shr 16) and 255; val b = c and 255
            assertTrue("blue cast in ${Integer.toHexString(c)}", b - r <= 4)
        }
        assertEquals(0xffff9f45.toInt(), Theme.p.accent)
    }

    @Test fun paletteRoundTripsThroughText() {
        Theme.PRESETS.forEach { assertEquals(it.palette, Palette.decode(it.palette.encode())) }
        assertNull(Palette.decode("nonsense"))
        assertNull(Palette.decode(null))
    }

    @Test fun glassTokensFollowTheChosenPalette() {
        val p = Theme.DEFAULT.copy(accent = 0xff3a7bff.toInt(), text = 0xffeeeeee.toInt())
        Theme.set(p)
        assertEquals(0xff3a7bff.toInt(), Theme.p.accent)
        assertEquals(0x2e3a7bff, Theme.accentSoft)
        assertEquals(0xb8eeeeee.toInt(), Theme.text2)
        assertEquals("กำหนดเอง", Theme.name())
    }

    @Test fun textOnAccentStaysReadable() {
        Theme.set(Theme.DEFAULT.copy(accent = 0xffffd447.toInt()))
        assertTrue(Theme.luminance(Theme.onAccent) < 0.2f)
        Theme.set(Theme.DEFAULT.copy(accent = 0xff2a2a8a.toInt()))
        assertEquals(0xffffffff.toInt(), Theme.onAccent)
    }

    @Test fun hsvAndHexRoundTrip() {
        for (c in intArrayOf(0xffff9f45.toInt(), 0xff0a0a0b.toInt(), 0xffc6f432.toInt(), 0xff5ac8fa.toInt(), 0xffffffff.toInt())) {
            val h = Theme.toHsv(c)
            val back = Theme.fromHsv(h[0], h[1], h[2])
            for (s in intArrayOf(16, 8, 0)) assertTrue(kotlin.math.abs(((back shr s) and 255) - ((c shr s) and 255)) <= 1)
            assertEquals(c, Theme.parseHex(Theme.hex(c)))
        }
        assertNotNull(Theme.parseHex("ff9f45"))
        assertNull(Theme.parseHex("#12345"))
        assertNull(Theme.parseHex("zzzzzz"))
    }
}
