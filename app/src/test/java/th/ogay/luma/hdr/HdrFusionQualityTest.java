package th.ogay.luma.hdr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import th.ogay.luma.FrameFusionEngine;

/**
 * Synthetic brackets rendered from a known radiance map through a gamma-2.2 camera with 8-bit
 * quantisation and mild noise. These scenes check behaviour, not photographic quality: real JPEGs
 * add tone curves, local tone mapping, sharpening and compression that are not modelled here.
 */
public class HdrFusionQualityTest {
    private static final int W = 240;
    private static final int H = 180;
    // Bright window, clipped at EV 0 and textured at EV -2.
    private static final int WX0 = 120, WX1 = 180, WY0 = 40, WY1 = 90;
    // Dark corner, nearly black at EV 0 and lifted at EV +2.
    private static final int SX0 = 20, SX1 = 80, SY0 = 110, SY1 = 160;

    interface Scene {
        double radiance(double x, double y);
    }

    private static final Scene ROOM = (x, y) -> {
        double texture = 1 + 0.3 * Math.sin(x / 3.1) * Math.cos(y / 4.3);
        if (x >= WX0 && x < WX1 && y >= WY0 && y < WY1) return 2.0 * texture;
        if (x >= SX0 && x < SX1 && y >= SY0 && y < SY1) return 0.002 * texture;
        double edges = ((int) Math.floor(x / 30) + (int) Math.floor(y / 30)) % 2 == 0 ? 0.25 : 0.12;
        return edges * texture;
    };

    /** Gamma-2.2 camera, slight colour tint so saturation is non-zero, deterministic noise. */
    private static int[] render(Scene scene, double ev, int shiftX, int shiftY, long seed) {
        return render(scene, ev, shiftX, shiftY, seed, W, H);
    }

    private static int[] render(Scene scene, double ev, int shiftX, int shiftY, long seed, int width, int height) {
        Random noise = new Random(seed);
        double gain = Math.pow(2, ev);
        double[] tint = {1.0, 0.92, 0.8};
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double e = scene.radiance(x - shiftX, y - shiftY) * gain;
                int rgb = 0xff000000;
                for (int c = 0; c < 3; c++) {
                    double v = 255 * Math.pow(Math.min(1, e * tint[c]), 1 / 2.2) + noise.nextGaussian() * 0.8;
                    rgb |= (int) Math.max(0, Math.min(255, Math.round(v))) << (16 - 8 * c);
                }
                out[y * width + x] = rgb;
            }
        }
        return out;
    }

    private static int channel(int rgb, int c) {
        return (rgb >>> (16 - 8 * c)) & 255;
    }

    private static double lumaStd(int[] image, int x0, int x1, int y0, int y1) {
        double sum = 0, sq = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int v = HdrFusionQuality.luma(image[y * W + x]);
                sum += v;
                sq += v * v;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(sq / n - mean * mean);
    }

    private static double lumaMean(int[] image, int x0, int x1, int y0, int y1) {
        double sum = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) sum += HdrFusionQuality.luma(image[y * W + x]);
        return sum / ((x1 - x0) * (y1 - y0));
    }

    private static double meanAbsDiff(int[] a, int[] b, int x0, int x1, int y0, int y1) {
        double sum = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                for (int c = 0; c < 3; c++) sum += Math.abs(channel(a[y * W + x], c) - channel(b[y * W + x], c));
            }
        }
        return sum / (3.0 * (x1 - x0) * (y1 - y0));
    }

    private static int maxAbsDiff(int[] a, int[] b) {
        int worst = 0;
        for (int i = 0; i < a.length; i++) {
            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(channel(a[i], c) - channel(b[i], c)));
        }
        return worst;
    }

    private static int[] bracket3(int[][] frames) {
        return HdrFusionQuality.merge(frames, W, H, new int[3], new int[3], new float[]{0, -2, 2});
    }

    @Test
    public void singleFrameIsReturnedUnchanged() {
        int[] frame = render(ROOM, 0, 0, 0, 1);
        int[] out = HdrFusionQuality.merge(new int[][]{frame}, W, H, new int[1], new int[1], new float[1]);
        assertTrue(Arrays.equals(frame, out));
    }

    @Test
    public void identicalFramesReproduceTheFrameIncludingHardClipping() {
        // A 0/255 checkerboard is the worst case for Laplacian ringing; equal weights must cancel it.
        int[] board = new int[W * H];
        for (int i = 0; i < board.length; i++) board[i] = ((i % W / 7 + i / W / 7) % 2 == 0) ? 0xff000000 : 0xffffffff;
        int[] out = HdrFusionQuality.merge(new int[][]{board, board.clone(), board.clone()}, W, H,
            new int[3], new int[3], new float[]{0, 0, 0});
        assertTrue("max difference " + maxAbsDiff(board, out), maxAbsDiff(board, out) <= 1);
    }

    @Test
    public void highlightsAreRecoveredFromTheDarkerFrame() {
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] under = render(ROOM, -2, 0, 0, 2);
        int[] over = render(ROOM, 2, 0, 0, 3);
        int[] out = bracket3(new int[][]{ev0, under, over});
        double before = lumaStd(ev0, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4);
        double after = lumaStd(out, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4);
        double source = lumaStd(under, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4);
        System.out.printf("highlight std: EV0 %.2f, EV-2 %.2f, fused %.2f%n", before, source, after);
        assertTrue("reference window should be clipped, std " + before, before < 1.5);
        assertTrue("window texture std " + after + " vs EV-2 " + source, after > 0.5 * source);
        assertTrue("window must not stay white", lumaMean(out, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4) < 245);
    }

    @Test
    public void shadowsAreLiftedFromTheBrighterFrame() {
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] under = render(ROOM, -2, 0, 0, 2);
        int[] over = render(ROOM, 2, 0, 0, 3);
        int[] out = bracket3(new int[][]{ev0, under, over});
        double before = lumaStd(ev0, SX0 + 4, SX1 - 4, SY0 + 4, SY1 - 4);
        double after = lumaStd(out, SX0 + 4, SX1 - 4, SY0 + 4, SY1 - 4);
        System.out.printf("shadow std: EV0 %.2f, fused %.2f%n", before, after);
        assertTrue("shadow std " + after + " vs reference " + before, after > 1.5 * before);
        assertTrue(lumaMean(out, SX0 + 4, SX1 - 4, SY0 + 4, SY1 - 4) > lumaMean(ev0, SX0 + 4, SX1 - 4, SY0 + 4, SY1 - 4));
    }

    @Test
    public void subjectThatMovedInACandidateDoesNotLeaveAGhost() {
        int mx0 = 40, mx1 = 70, my0 = 30, my1 = 60;
        Scene withVisitor = (x, y) -> x >= mx0 && x < mx1 && y >= my0 && y < my1 ? 0.6 : ROOM.radiance(x, y);
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] under = render(withVisitor, -2, 0, 0, 2);
        int[] over = render(ROOM, 2, 0, 0, 3);
        int[] out = bracket3(new int[][]{ev0, under, over});
        // Inside the block the darker frame is rejected, so the result should match fusing the other two.
        int[] withoutCandidate = HdrFusionQuality.merge(new int[][]{ev0, over}, W, H, new int[2], new int[2], new float[]{0, 2});
        double ghost = meanAbsDiff(out, withoutCandidate, mx0 + 4, mx1 - 4, my0 + 4, my1 - 4);
        // For scale: how far the visitor frame itself departs from a visitor-free frame in that block.
        double visitor = meanAbsDiff(under, render(ROOM, -2, 0, 0, 2), mx0 + 4, mx1 - 4, my0 + 4, my1 - 4);
        System.out.printf("moving subject: |fused - fused without candidate| %.2f (visitor changes the frame by %.1f)%n", ghost, visitor);
        assertTrue("ghost strength " + ghost + " vs visitor " + visitor, ghost < 0.1 * visitor);
        double windowStd = lumaStd(out, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4);
        assertTrue("the rest of the frame must still use the darker exposure", windowStd > 5);
    }

    @Test
    public void translatedCandidateMatchesTheAlignedResultAwayFromTheBorder() {
        int sx = 5, sy = -3;
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        // The scene moves by (+5, -3) in the candidate, so reference (x, y) appears at (x + 5, y - 3).
        int[] shifted = render(ROOM, -2, sx, sy, 2);
        int[] aligned = render(ROOM, -2, 0, 0, 2);
        int[] withShift = HdrFusionQuality.merge(new int[][]{ev0, shifted}, W, H, new int[]{0, sx}, new int[]{0, sy}, new float[]{0, -2});
        int[] withoutShift = HdrFusionQuality.merge(new int[][]{ev0, aligned}, W, H, new int[2], new int[2], new float[]{0, -2});
        double interior = meanAbsDiff(withShift, withoutShift, 24, W - 24, 24, H - 24);
        System.out.printf("translation (5,-3): interior mean |difference| %.3f%n", interior);
        assertTrue("interior difference " + interior, interior < 0.6);
        double windowStd = lumaStd(withShift, WX0 + 4, WX1 - 4, WY0 + 4, WY1 - 4);
        assertTrue("candidate must contribute after translation, std " + windowStd, windowStd > 5);
        // Columns the candidate does not cover keep the reference.
        double uncovered = meanAbsDiff(withShift, ev0, W - sx, W, 10, H - 10);
        assertTrue("uncovered columns differ by " + uncovered, uncovered < 6);
    }

    @Test
    public void wrongTranslationFallsBackToTheReferenceInsteadOfDoublingEdges() {
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] shifted = render(ROOM, -1, 6, 0, 2);
        int[] out = HdrFusionQuality.merge(new int[][]{ev0, shifted}, W, H, new int[2], new int[2], new float[]{0, -1});
        // Vertical checker edge at x = 30 inside the mid-tone area: one step, not two.
        double sharp = 0;
        for (int y = 5; y < 25; y++) {
            sharp += Math.abs(HdrFusionQuality.luma(out[y * W + 31]) - HdrFusionQuality.luma(out[y * W + 28]));
        }
        double reference = 0;
        for (int y = 5; y < 25; y++) {
            reference += Math.abs(HdrFusionQuality.luma(ev0[y * W + 31]) - HdrFusionQuality.luma(ev0[y * W + 28]));
        }
        assertTrue("edge contrast " + sharp + " vs reference " + reference, sharp > 0.8 * reference);
        double doubled = meanAbsDiff(out, ev0, 32, 38, 5, 25);
        assertTrue("second edge strength " + doubled, doubled < 4);
    }

    @Test
    public void flatSceneStaysFlat() {
        Scene flat = (x, y) -> 0.18;
        int[] out = bracket3(new int[][]{render(flat, 0, 0, 0, 1), render(flat, -2, 0, 0, 2), render(flat, 2, 0, 0, 3)});
        // Pixel-level variation is the rendered sensor noise; 12x12 block means expose any blotches or banding.
        double lo = 255, hi = 0;
        for (int by = 0; by < H; by += 12) {
            for (int bx = 0; bx < W; bx += 12) {
                double mean = lumaMean(out, bx, bx + 12, by, by + 12);
                lo = Math.min(lo, mean);
                hi = Math.max(hi, mean);
            }
        }
        assertTrue("block means span " + lo + ".." + hi, hi - lo < 1.0);
        assertTrue("per-pixel noise " + lumaStd(out, 0, W, 0, H), lumaStd(out, 0, W, 0, H) < 1.2);
    }

    @Test
    public void outputIsOpaqueAndSaturatedFramesStayInRange() {
        int[] white = new int[W * H];
        int[] black = new int[W * H];
        Arrays.fill(white, 0x00ffffff);
        Arrays.fill(black, 0x00000000);
        int[] out = HdrFusionQuality.merge(new int[][]{white, black}, W, H, new int[2], new int[2], new float[]{0, -4});
        for (int p : out) assertEquals(0xff, p >>> 24);
        int[] allWhite = HdrFusionQuality.merge(new int[][]{white, white.clone()}, W, H, new int[2], new int[2], new float[]{0, -1});
        for (int p : allWhite) assertEquals(0xffffffff, p);
    }

    @Test
    public void stripsMatchWholeFrameProcessing() {
        // Tall enough that strips keep the same pyramid depth as whole-frame processing.
        int width = 160, height = 1000;
        Scene tall = (x, y) -> ROOM.radiance(x, y % H) * (1 + 0.5 * Math.sin(y / 97.0));
        int[][] frames = {render(tall, 0, 0, 0, 1, width, height), render(tall, -2, 0, 0, 2, width, height),
            render(tall, 2, 0, 0, 3, width, height)};
        long budget = (long) width * HdrFusionQuality.BYTES_PER_REGION_PIXEL * 600;
        HdrFusionQuality.Plan plan = HdrFusionQuality.plan(width, height, budget);
        HdrFusionQuality.Plan single = HdrFusionQuality.plan(width, height, Long.MAX_VALUE);
        assertEquals(single.levels, plan.levels);
        assertTrue("budget should force strips", plan.interior < height);
        assertTrue(plan.bytes(width) <= budget);
        int[] whole = HdrFusionQuality.merge(frames, width, height, new int[3], new int[3], new float[]{0, -2, 2}, Long.MAX_VALUE);
        int[] strips = HdrFusionQuality.merge(frames, width, height, new int[3], new int[3], new float[]{0, -2, 2}, budget);
        int worst = 0;
        for (int i = 0; i < whole.length; i++) {
            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(channel(whole[i], c) - channel(strips[i], c)));
        }
        assertEquals("strips must reproduce whole-frame output (" + plan.levels + " levels, halo " + plan.halo + ")", 0, worst);
    }

    @Test
    public void planFitsTwelveMegapixelsInTheDefaultBudget() {
        HdrFusionQuality.Plan plan = HdrFusionQuality.plan(4000, 3000, HdrFusionQuality.DEFAULT_WORKING_BYTES);
        assertTrue(plan.bytes(4000) <= HdrFusionQuality.DEFAULT_WORKING_BYTES);
        assertTrue("pyramid depth " + plan.levels, plan.levels >= 5);
        assertTrue("recomputed rows " + plan.halo, plan.interior >= 2 * plan.halo);
        assertTrue(plan.interior >= 32);
    }

    @Test
    public void histogramMatchingLetsTranslationSearchHandleExposureDifferences() {
        int sx = 4, sy = -3;
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] under = render(ROOM, -2, sx, sy, 2);
        int[] ref = new int[W * H];
        int[] cand = new int[W * H];
        for (int i = 0; i < ref.length; i++) {
            ref[i] = HdrFusionQuality.luma(ev0[i]);
            cand[i] = HdrFusionQuality.luma(under[i]);
        }
        FrameFusionEngine.Alignment raw = FrameFusionEngine.align(ref, cand, W, H);
        FrameFusionEngine.Alignment matched = FrameFusionEngine.align(ref, HdrFusionQuality.matchHistogram(ref, cand), W, H);
        System.out.printf("alignment error: raw %.1f, histogram-matched %.1f%n", raw.error, matched.error);
        assertEquals(sx, matched.dx);
        assertEquals(sy, matched.dy);
        // MultiFrameFusion discards frames whose alignment error exceeds 22.
        assertTrue("raw error " + raw.error, raw.error > 22);
        assertTrue("matched error " + matched.error, matched.error < 8);
    }

    @Test
    public void invalidInputsAreRejected() {
        int[] f = new int[16];
        expectInvalid(() -> HdrFusionQuality.merge(null, 4, 4, new int[1], new int[1], new float[1]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[0][], 4, 4, new int[0], new int[0], new float[0]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f}, 0, 4, new int[1], new int[1], new float[1]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f}, 5, 4, new int[1], new int[1], new float[1]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f, null}, 4, 4, new int[2], new int[2], new float[2]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f, f}, 4, 4, new int[1], new int[2], new float[2]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f, f}, 4, 4, new int[]{1, 0}, new int[2], new float[2]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f, f}, 4, 4, new int[2], new int[2], new float[]{0, Float.NaN}));
        expectInvalid(() -> HdrFusionQuality.merge(new int[10][16], 4, 4, new int[10], new int[10], new float[10]));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f}, 4, 4, new int[1], new int[1], new float[1], 0));
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{f}, 100_000, 100_000, new int[1], new int[1], new float[1]));
        expectInvalid(() -> HdrFusionQuality.matchHistogram(new int[]{0, 256}, new int[]{1}));
    }

    @Test
    public void candidateWithoutOverlapIsIgnored() {
        int[] ev0 = render(ROOM, 0, 0, 0, 1);
        int[] under = render(ROOM, -2, 0, 0, 2);
        int[] out = HdrFusionQuality.merge(new int[][]{ev0, under}, W, H, new int[]{0, W}, new int[2], new float[]{0, -2});
        assertTrue(maxAbsDiff(ev0, out) <= 1);
        assertNotEquals(0, out[0] >>> 24);
    }

    @Test
    public void rejectsBudgetThatCannotFitAMinimumStrip() {
        int[] frame = new int[64*64];
        expectInvalid(() -> HdrFusionQuality.merge(new int[][]{frame,frame},64,64,new int[2],new int[2],new float[2],1));
    }

    @Test(expected=java.util.concurrent.CancellationException.class)
    public void cooperativelyCancelsBetweenPyramidFrames() {
        int[] frame = render(ROOM,0,0,0,1);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        HdrFusionQuality.merge(new int[][]{frame,frame},W,H,new int[2],new int[2],new float[2],8L<<20,() -> checks.incrementAndGet()>2);
    }

    private static void expectInvalid(Runnable call) {
        try {
            call.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // contract violation reported
        }
    }
}
