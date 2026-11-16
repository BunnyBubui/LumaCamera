package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEditTest {
    private fun style(id: String) = AutoEdit.STYLES.first { it.id == id }
    /** Loudness every 50 ms over [ms]: loud everywhere except in [quiet] ranges. */
    private fun peaks(ms: Long, vararg quiet: LongRange) = FloatArray((ms / 50).toInt() + 2) { i -> val t = i * 50L; if (quiet.any { t in it }) 0.01f else 0.5f }

    @Test fun talkCutsTheSilenceAndKeepsTheSpeech() {
        val c = AutoEdit.ClipInput(0, 0, 10_000, peaks(10_000, 3_000L..6_000L), 50, FloatArray(40), 250)
        val p = AutoEdit.plan(listOf(c), style("talk"))
        assertEquals(2, p.pieces.size)
        assertTrue(p.pieces[0].to in 3_000..3_300 && p.pieces[1].from in 5_700..6_000)
        assertTrue(p.afterMs < p.beforeMs)
    }
    @Test fun viralStaysUnderItsLengthAndKeepsTheOrder() {
        // Two minutes, louder in the middle, with scene changes every 10 s.
        val pk = FloatArray(2_402) { i -> if (i in 1_000..1_400) 0.9f else 0.3f }
        val diffs = FloatArray(480) { i -> if (i % 40 == 0 && i > 0) 0.6f else 0.02f }
        val c = AutoEdit.ClipInput(0, 0, 120_000, pk, 50, diffs, 250)
        val p = AutoEdit.plan(listOf(c), style("viral"))
        assertTrue("length ${p.afterMs}", p.afterMs in 10_000..31_500)
        assertTrue(p.pieces.zipWithNext().all { (a, b) -> a.to <= b.from })
        assertTrue("loud middle picked", p.pieces.any { it.from in 50_000..70_000 })
    }
    @Test fun everyClipOfAMontageShowsUp() {
        val clips = (0 until 4).map { AutoEdit.ClipInput(it, 0, 40_000, peaks(40_000), 50, FloatArray(160), 250) }
        val p = AutoEdit.plan(clips, style("highlight"), targetMs = 20_000)
        assertEquals(setOf(0, 1, 2, 3), p.pieces.map { it.clip }.toSet())
        assertTrue(p.afterMs <= 21_000)
    }
    @Test fun keepAllStylesDoNotCutWithoutSound() {
        val c = AutoEdit.ClipInput(0, 1_000, 9_000, FloatArray(0), 50, FloatArray(32), 250)
        val p = AutoEdit.plan(listOf(c), style("vlog"))
        assertEquals(1, p.pieces.size); assertEquals(1_000, p.pieces[0].from); assertEquals(9_000, p.pieces[0].to)
    }
    @Test fun shotEndsMoveOntoNearbyBeats() {
        val c = AutoEdit.ClipInput(0, 0, 20_000, FloatArray(0), 50, FloatArray(0), 250)
        val pieces = listOf(AutoEdit.Piece(0, 0, 2_000), AutoEdit.Piece(0, 5_000, 7_000), AutoEdit.Piece(0, 9_000, 11_000))
        val s = AutoEdit.snap(pieces, listOf(c), listOf(2_200L, 4_100L))
        assertEquals(2_200, s[0].to)
        assertEquals(6_900, s[1].to) // ends at 2.2 s + 1.9 s = 4.1 s on the timeline
        assertEquals(11_000, s[2].to)
    }
    @Test fun planJsonHasEveryPiece() {
        val p = AutoEdit.Plan(style("vlog"), listOf(AutoEdit.Piece(0, 0, 1_000), AutoEdit.Piece(1, 500, 900)), 2_000)
        assertEquals("{\"style\":\"vlog\",\"transition\":1,\"crop\":\"\",\"pieces\":[{\"clip\":0,\"from\":0,\"to\":1000},{\"clip\":1,\"from\":500,\"to\":900}]}", p.toJson())
    }
    @Test fun briefIsReadFromAChattyAnswer() {
        val b = AutoEdit.parseBrief("Sure! {\"style\": \"viral\", \"seconds\": 20, \"crop\": \"9:16\", \"transition\": \"zoom\", \"captions\": false, \"film\": true, \"order\": \"keep\", \"keep\": [3, 1, 3], \"drop\": [], \"title\": \"ทริปทะเล\"} hope it helps", style("vlog"))!!
        assertEquals("viral", b.style.id); assertEquals(20, b.seconds); assertEquals("9:16", b.crop); assertEquals(3, b.transition)
        assertEquals(false, b.captions); assertEquals(true, b.film); assertEquals("keep", b.order); assertEquals(listOf(3, 1), b.keep); assertEquals("ทริปทะเล", b.title)
    }
    @Test fun badAnswersGiveNoBriefOrSafeDefaults() {
        assertEquals(null, AutoEdit.parseBrief("I cannot help", style("vlog")))
        val b = AutoEdit.parseBrief("{\"style\":\"nonsense\",\"crop\":\"3:7\",\"transition\":\"spin\"}", style("talk"))!!
        assertEquals("talk", b.style.id); assertEquals("", b.crop); assertEquals(0, b.transition); assertEquals("time", b.order)
    }
    @Test fun shotsStayUnderTheLimitAndCoverTheClip() {
        val c = AutoEdit.ClipInput(0, 0, 600_000, peaks(600_000), 50, FloatArray(2_400), 250)
        val sh = AutoEdit.shots(listOf(c), 60)
        assertTrue("count ${sh.size}", sh.size in 2..60)
        assertEquals(600_000L, sh.sumOf { it.len })
    }
    @Test fun keptShotsPlayInTheOrderTheModelGave() {
        val c = AutoEdit.ClipInput(0, 0, 30_000, peaks(30_000), 50, FloatArray(120), 250, listOf(Triple(12_500L, 14_000L, "สวัสดี")))
        val sh = AutoEdit.shots(listOf(c))
        val brief = AutoEdit.Brief(style("vlog"), 0, "", 1, false, false, "keep", listOf(2, 0), emptyList(), "")
        val p = AutoEdit.planFromBrief(listOf(c), sh, brief)
        assertEquals(listOf(sh[2].from, sh[0].from), p.pieces.map { it.from })
        assertTrue(AutoEdit.describe(listOf(c), sh).contains("สวัสดี"))
    }
    @Test fun grammarNamesEveryStyle() {
        val g = AutoEdit.grammar()
        AutoEdit.STYLES.forEach { assertTrue(g.contains("\\\"" + it.id + "\\\"")) }
        assertTrue(g.startsWith("root ::="))
    }
}
