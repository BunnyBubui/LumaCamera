package th.ogay.luma.hdr;

/**
 * Exposure fusion for bracketed SDR frames (Mertens, Kautz and Van Reeth, 2007) with
 * motion rejection against frame 0 and a ceiling on working memory.
 *
 * <p>The input frames are display-referred JPEG pixels. Their tone curve is unknown, so this
 * class does not reconstruct scene radiance. It blends the best-exposed parts of each frame
 * through a Laplacian pyramid and returns an SDR image. The exposure values decide which frame
 * is brighter; the brightness relation between frames is measured from the pixels themselves
 * with an intensity mapping function built from cumulative histograms.
 *
 * <p>Pure Java without Android dependencies. Each call allocates its own buffers, so concurrent
 * calls are safe.
 */
public final class HdrFusionQuality {
    /** Largest bracket accepted by {@link #merge}. */
    public static final int MAX_FRAMES = 9;
    /** Largest frame accepted by {@link #merge}, in pixels. */
    public static final long MAX_PIXELS = 50_000_000L;
    /** Default ceiling for the buffers this class allocates, excluding the inputs and output. */
    public static final long DEFAULT_WORKING_BYTES = 96L << 20;

    /** Conservative estimate of the bytes allocated per pixel of a processing strip. */
    static final int BYTES_PER_REGION_PIXEL = 64;
    static final int MAX_LEVELS = 7;
    private static final int MIN_INTERIOR_ROWS = 32;
    private static final int HISTOGRAM_SAMPLES = 1 << 18;
    private static final float WELL_EXPOSED_SIGMA = 0.2f;
    private static final float CONTRAST_FLOOR = 0.02f;
    private static final float SATURATION_FLOOR = 0.02f;
    private static final float REFERENCE_FLOOR = 1e-12f;
    private static final int CLIPPED_HIGH = 250;
    private static final int CLIPPED_LOW = 5;
    private static final float[] KERNEL = {1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16};
    /** Well-exposedness factor per 8-bit channel value; the Mertens term is the product over R, G, B. */
    private static final float[] EXPOSED = new float[256];

    static {
        for (int v = 0; v < 256; v++) {
            double d = v / 255.0 - 0.5;
            EXPOSED[v] = (float) Math.exp(-d * d / (2 * WELL_EXPOSED_SIGMA * WELL_EXPOSED_SIGMA));
        }
    }

    /** API-23-compatible cooperative cancellation callback. */
    @FunctionalInterface
    public interface Cancellation { boolean isStopped(); }

    private HdrFusionQuality() {
    }

    /**
     * Fuses an exposure bracket into one SDR frame.
     *
     * @param frames     ARGB8888 row-major frames of equal size; frame 0 is the reference
     * @param width      frame width in pixels
     * @param height     frame height in pixels
     * @param dx         horizontal translation: reference (x, y) maps to candidate (x + dx[i], y + dy[i])
     * @param dy         vertical translation, same convention as {@code dx}
     * @param exposureEv exposure of each frame in EV as reported by the bracket
     * @return ARGB8888 frame of {@code width * height} pixels with opaque alpha
     * @throws IllegalArgumentException when the inputs break the contract above
     */
    public static int[] merge(int[][] frames, int width, int height, int[] dx, int[] dy, float[] exposureEv) {
        return merge(frames, width, height, dx, dy, exposureEv, DEFAULT_WORKING_BYTES);
    }

    /** Same as {@link #merge(int[][], int, int, int[], int[], float[])} with an explicit memory ceiling. */
    public static int[] merge(int[][] frames, int width, int height, int[] dx, int[] dy, float[] exposureEv,
                              long workingBytes) {
        return merge(frames, width, height, dx, dy, exposureEv, workingBytes, () -> false);
    }

    /** Cooperative cancellation between strips and pyramid frames; partial output is never returned. */
    public static int[] merge(int[][] frames, int width, int height, int[] dx, int[] dy, float[] exposureEv,
                              long workingBytes, Cancellation stopped) {
        if (stopped == null) throw new IllegalArgumentException("Cancellation callback is required");
        checkStopped(stopped);
        validate(frames, width, height, dx, dy, exposureEv, workingBytes);
        int count = frames.length;
        int[] out = new int[width * height];
        if (count == 1) {
            for (int i = 0; i < out.length; i++) out[i] = frames[0][i] | 0xff000000;
            return out;
        }
        int[][] mapping = new int[count][];
        for (int i = 1; i < count; i++) {
            mapping[i] = intensityMapping(frames[0], frames[i], width, height, dx[i], dy[i]);
        }
        Plan plan = plan(width, height, workingBytes);
        for (int y0 = 0; y0 < height; y0 += plan.interior) {
            int y1 = Math.min(height, y0 + plan.interior);
            int r0 = Math.max(0, y0 - plan.halo);
            int r1 = Math.min(height, y1 + plan.halo);
            checkStopped(stopped);
            fuseRegion(frames, width, height, dx, dy, exposureEv, mapping, plan.levels, r0, r1, y0, y1, out, stopped);
        }
        checkStopped(stopped);
        return out;
    }

    private static void checkStopped(Cancellation stopped) {
        if (stopped.isStopped()) throw new java.util.concurrent.CancellationException("HDR fusion cancelled");
    }

    /**
     * Remaps candidate luminance (0..255) onto the reference brightness scale by histogram matching.
     * Translation search by absolute difference fails between frames of different exposure; run it on
     * the reference luma and the returned array instead of the raw candidate luma.
     */
    public static int[] matchHistogram(int[] referenceLuma, int[] candidateLuma) {
        if (referenceLuma == null || candidateLuma == null || referenceLuma.length == 0 || candidateLuma.length == 0) {
            throw new IllegalArgumentException("Luma arrays must be non-empty");
        }
        long[] ref = new long[256];
        long[] cand = new long[256];
        for (int v : referenceLuma) ref[checkLuma(v)]++;
        for (int v : candidateLuma) cand[checkLuma(v)]++;
        int[] map = matchCdf(cand, candidateLuma.length, ref, referenceLuma.length);
        int[] out = new int[candidateLuma.length];
        for (int i = 0; i < out.length; i++) out[i] = map[candidateLuma[i]];
        return out;
    }

    static int luma(int rgb) {
        return (77 * ((rgb >>> 16) & 255) + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255) + 128) >> 8;
    }

    private static int checkLuma(int v) {
        if (v < 0 || v > 255) throw new IllegalArgumentException("Luma must be 0..255");
        return v;
    }

    private static void validate(int[][] frames, int width, int height, int[] dx, int[] dy, float[] ev, long budget) {
        if (frames == null || frames.length == 0) throw new IllegalArgumentException("At least one frame is required");
        if (frames.length > MAX_FRAMES) throw new IllegalArgumentException("At most " + MAX_FRAMES + " frames");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Width and height must be positive");
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) throw new IllegalArgumentException("Frame exceeds " + MAX_PIXELS + " pixels");
        if (budget <= 0) throw new IllegalArgumentException("Working memory must be positive");
        if (dx == null || dy == null || ev == null) throw new IllegalArgumentException("dx, dy and exposureEv are required");
        if (dx.length != frames.length || dy.length != frames.length || ev.length != frames.length) {
            throw new IllegalArgumentException("dx, dy and exposureEv need one entry per frame");
        }
        if (dx[0] != 0 || dy[0] != 0) throw new IllegalArgumentException("Frame 0 is the reference and cannot be shifted");
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] == null || frames[i].length != pixels) {
                throw new IllegalArgumentException("Frame " + i + " must hold width * height pixels");
            }
            if (Float.isNaN(ev[i]) || Float.isInfinite(ev[i])) throw new IllegalArgumentException("Exposure " + i + " is not finite");
        }
    }

    static final class Plan {
        final int levels;
        final int interior;
        final int halo;

        Plan(int levels, int interior, int halo) {
            this.levels = levels;
            this.interior = interior;
            this.halo = halo;
        }

        long bytes(int width) {
            return (long) width * (interior + 2L * halo) * BYTES_PER_REGION_PIXEL;
        }
    }

    static int levelsFor(int width, int height) {
        int smallest = Math.min(width, height);
        int levels = 1;
        while (levels < MAX_LEVELS && (smallest >> levels) >= 8) levels++;
        return levels;
    }

    /**
     * Rows outside a strip that can influence it. A chain of n reductions with the 5-tap kernel reaches
     * 2^(n+1) - 2 pixels and the matching expansions reach as far again, so 2^(levels+1) rows make strips
     * equal to whole-frame processing up to float rounding. Four rows cover erosion and contrast taps.
     */
    static int haloFor(int levels) {
        return Math.max(4, 2 << levels);
    }

    /**
     * Chooses strip height and pyramid depth so that a strip fits the budget. The whole frame is one strip
     * when it fits; strips then reproduce whole-frame output except for float rounding. Otherwise the depth drops until each strip is at least twice as tall as the halo on
     * one side, which keeps recomputed halo rows at no more than half of the work. A budget too small for
     * a 32-row strip at depth 1 is rejected with IllegalArgumentException.
     */
    static Plan plan(int width, int height, long budget) {
        int levels = levelsFor(width, height);
        long rowBytes = (long) width * BYTES_PER_REGION_PIXEL;
        if (rowBytes * height <= budget) return new Plan(levels, height, 0);
        if (budget < rowBytes * Math.min(height, MIN_INTERIOR_ROWS + 2L * haloFor(1)))
            throw new IllegalArgumentException("Working budget cannot fit the minimum strip; reduce input size");
        long rows = budget / rowBytes;
        while (true) {
            int halo = haloFor(levels);
            long interior = rows - 2L * halo;
            if (interior >= Math.max(MIN_INTERIOR_ROWS, 2L * halo)) {
                // Strip starts on multiples of the coarsest sampling step keep every pyramid level on the
                // same grid as whole-frame processing.
                long step = 1L << (levels - 1);
                return new Plan(levels, (int) Math.min(height, interior / step * step), halo);
            }
            if (levels == 1) return new Plan(1, (int) Math.min(height, Math.max(MIN_INTERIOR_ROWS, interior)), halo);
            levels--;
        }
    }

    /**
     * For each reference luma value, the candidate luma at the same cumulative-histogram position over
     * the overlapping area. Returns null when the frames overlap too little to estimate it.
     */
    private static int[] intensityMapping(int[] reference, int[] candidate, int width, int height, int dx, int dy) {
        int x0 = Math.max(0, -dx);
        int x1 = Math.min(width, width - dx);
        int y0 = Math.max(0, -dy);
        int y1 = Math.min(height, height - dy);
        if (x1 - x0 < 4 || y1 - y0 < 4) return null;
        long area = (long) (x1 - x0) * (y1 - y0);
        int step = (int) Math.max(1, Math.floor(Math.sqrt(area / (double) HISTOGRAM_SAMPLES)));
        long[] ref = new long[256];
        long[] cand = new long[256];
        long total = 0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                ref[luma(reference[y * width + x])]++;
                cand[luma(candidate[(y + dy) * width + x + dx])]++;
                total++;
            }
        }
        if (total < 64) return null;
        return matchCdf(ref, total, cand, total);
    }

    /** map[v] = smallest target value whose CDF reaches the midpoint of source bin v. */
    private static int[] matchCdf(long[] source, long sourceTotal, long[] target, long targetTotal) {
        int[] map = new int[256];
        long below = 0;
        long reached = target[0];
        int t = 0;
        for (int v = 0; v < 256; v++) {
            double middle = (below + source[v] / 2.0) / sourceTotal;
            while (t < 255 && reached / (double) targetTotal < middle) {
                t++;
                reached += target[t];
            }
            map[v] = t;
            below += source[v];
        }
        return map;
    }

    private static void fuseRegion(int[][] frames, int width, int height, int[] dx, int[] dy, float[] ev,
                                   int[][] mapping, int levels, int r0, int r1, int y0, int y1, int[] out, Cancellation stopped) {
        int rows = r1 - r0;
        int area = width * rows;
        int[] w = new int[levels];
        int[] h = new int[levels];
        w[0] = width;
        h[0] = rows;
        for (int l = 1; l < levels; l++) {
            w[l] = (w[l - 1] + 1) / 2;
            h[l] = (h[l - 1] + 1) / 2;
        }
        int[] refLuma = new int[area];
        for (int y = 0; y < rows; y++) {
            int row = (r0 + y) * width;
            for (int x = 0; x < width; x++) refLuma[y * width + x] = luma(frames[0][row + x]);
        }
        float[] sum = new float[area];
        for (int i = 0; i < frames.length; i++) {
            checkStopped(stopped);
            float[] weight = weights(frames, i, width, height, dx[i], dy[i], ev, mapping[i], refLuma, r0, rows);
            if (weight == null) continue;
            for (int p = 0; p < area; p++) sum[p] += weight[p];
        }
        float[][][] acc = new float[3][levels][];
        for (int c = 0; c < 3; c++) for (int l = 0; l < levels; l++) acc[c][l] = new float[w[l] * h[l]];
        for (int i = 0; i < frames.length; i++) {
            checkStopped(stopped);
            float[] weight = weights(frames, i, width, height, dx[i], dy[i], ev, mapping[i], refLuma, r0, rows);
            if (weight == null) continue;
            for (int p = 0; p < area; p++) weight[p] /= sum[p];
            float[][] gauss = new float[levels][];
            gauss[0] = weight;
            for (int l = 1; l < levels; l++) gauss[l] = reduce(gauss[l - 1], w[l - 1], h[l - 1]);
            for (int c = 0; c < 3; c++) {
                float[] g = sample(frames[i], width, height, dx[i], dy[i], r0, rows, 16 - 8 * c);
                for (int l = 0; l < levels; l++) {
                    float[] lap;
                    float[] next = null;
                    if (l < levels - 1) {
                        next = reduce(g, w[l], h[l]);
                        float[] up = expand(next, w[l + 1], h[l + 1], w[l], h[l]);
                        for (int p = 0; p < g.length; p++) g[p] -= up[p];
                    }
                    lap = g;
                    float[] gw = gauss[l];
                    float[] target = acc[c][l];
                    for (int p = 0; p < lap.length; p++) target[p] += gw[p] * lap[p];
                    g = next;
                }
            }
        }
        float[][] result = new float[3][];
        for (int c = 0; c < 3; c++) {
            float[] image = acc[c][levels - 1];
            for (int l = levels - 2; l >= 0; l--) {
                float[] up = expand(image, w[l + 1], h[l + 1], w[l], h[l]);
                float[] band = acc[c][l];
                for (int p = 0; p < up.length; p++) up[p] += band[p];
                image = up;
            }
            result[c] = image;
        }
        for (int y = y0; y < y1; y++) {
            int local = (y - r0) * width;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int p = local + x;
                out[row + x] = 0xff000000 | (toByte(result[0][p]) << 16) | (toByte(result[1][p]) << 8) | toByte(result[2][p]);
            }
        }
    }

    private static int toByte(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }

    private static int clamp(int v, int low, int high) {
        return v < low ? low : Math.min(high, v);
    }

    /** One colour channel of the candidate in reference coordinates; outside samples repeat the edge. */
    private static float[] sample(int[] frame, int width, int height, int dx, int dy, int r0, int rows, int shift) {
        float[] channel = new float[width * rows];
        for (int y = 0; y < rows; y++) {
            int row = clamp(r0 + y + dy, 0, height - 1) * width;
            for (int x = 0; x < width; x++) {
                channel[y * width + x] = (frame[row + clamp(x + dx, 0, width - 1)] >>> shift) & 255;
            }
        }
        return channel;
    }

    /**
     * Mertens weight (contrast x saturation x well-exposedness) times motion consistency. Returns null
     * for a candidate whose brightness relation to the reference could not be estimated.
     */
    private static float[] weights(int[][] frames, int index, int width, int height, int dx, int dy, float[] ev,
                                   int[] mapping, int[] refLuma, int r0, int rows) {
        if (index > 0 && mapping == null) return null;
        int[] frame = frames[index];
        int area = width * rows;
        // Candidate luma for the strip plus one row above and below, sampled with edge repeat.
        int[] lum = new int[width * (rows + 2)];
        for (int y = -1; y <= rows; y++) {
            int row = clamp(r0 + y + dy, 0, height - 1) * width;
            for (int x = 0; x < width; x++) lum[(y + 1) * width + x] = luma(frame[row + clamp(x + dx, 0, width - 1)]);
        }
        float[] weight = new float[area];
        for (int y = 0; y < rows; y++) {
            int sy = r0 + y + dy;
            boolean rowInside = sy >= 0 && sy < height;
            int srcRow = clamp(sy, 0, height - 1) * width;
            for (int x = 0; x < width; x++) {
                int sx = x + dx;
                if (index > 0 && (!rowInside || sx < 0 || sx >= width)) continue;
                int rgb = frame[srcRow + clamp(sx, 0, width - 1)];
                int ri = (rgb >>> 16) & 255;
                int gi = (rgb >>> 8) & 255;
                int bi = rgb & 255;
                float r = ri / 255f;
                float g = gi / 255f;
                float b = bi / 255f;
                float mean = (r + g + b) / 3;
                float saturation = (float) Math.sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3);
                float exposed = EXPOSED[ri] * EXPOSED[gi] * EXPOSED[bi];
                int p = (y + 1) * width + x;
                int left = x > 0 ? lum[p - 1] : lum[p];
                int right = x < width - 1 ? lum[p + 1] : lum[p];
                float contrast = Math.abs(4 * lum[p] - left - right - lum[p - width] - lum[p + width]) / 255f;
                weight[y * width + x] = (contrast + CONTRAST_FLOOR) * (saturation + SATURATION_FLOOR) * exposed;
            }
        }
        if (index == 0) {
            for (int p = 0; p < area; p++) weight[p] += REFERENCE_FLOOR;
            return weight;
        }
        float[] motion = erode(consistency(lum, width, rows, mapping, refLuma, ev[index] - ev[0]), width, rows);
        for (int p = 0; p < area; p++) weight[p] *= motion[p];
        return weight;
    }

    /**
     * 1 where the candidate agrees with the reference after intensity mapping, falling to 0 where it does
     * not. A 3x3 neighbourhood absorbs half-pixel misregistration at edges. Where the reference is clipped,
     * only a one-sided test is possible: a darker candidate must be at least as bright as the clip level maps
     * to, and a brighter candidate under crushed shadows must be no brighter than the black level maps to.
     */
    private static float[] consistency(int[] lum, int width, int rows, int[] mapping, int[] refLuma, float evDelta) {
        float[] m = new float[width * rows];
        int clippedHigh = mapping[CLIPPED_HIGH];
        int crushedLow = mapping[CLIPPED_LOW];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < width; x++) {
                int ref = refLuma[y * width + x];
                int predicted = mapping[ref];
                int lo = 255;
                int hi = 0;
                int nearest = 255;
                for (int v = 0; v <= 2; v++) {
                    int row = (y + v) * width;
                    for (int u = -1; u <= 1; u++) {
                        int value = lum[row + clamp(x + u, 0, width - 1)];
                        lo = Math.min(lo, value);
                        hi = Math.max(hi, value);
                        nearest = Math.min(nearest, Math.abs(value - predicted));
                    }
                }
                int distance;
                if (ref >= CLIPPED_HIGH && evDelta < 0) distance = Math.max(0, clippedHigh - hi);
                else if (ref <= CLIPPED_LOW && evDelta > 0) distance = Math.max(0, lo - crushedLow);
                else distance = nearest;
                float accept = 10 + 0.06f * predicted;
                float reject = 2.5f * accept;
                m[y * width + x] = distance <= accept ? 1f : distance >= reject ? 0f : (reject - distance) / (reject - accept);
            }
        }
        return m;
    }

    /** 5x5 minimum filter: grows rejected areas so the edges of moving subjects do not leave ghosts. */
    private static float[] erode(float[] m, int width, int rows) {
        float[] tmp = new float[m.length];
        for (int y = 0; y < rows; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float v = 1f;
                for (int k = -2; k <= 2; k++) v = Math.min(v, m[row + clamp(x + k, 0, width - 1)]);
                tmp[row + x] = v;
            }
        }
        float[] out = new float[m.length];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < width; x++) {
                float v = 1f;
                for (int k = -2; k <= 2; k++) v = Math.min(v, tmp[clamp(y + k, 0, rows - 1) * width + x]);
                out[y * width + x] = v;
            }
        }
        return out;
    }

    /** Burt-Adelson 5-tap blur then 2x decimation, edge repeat. */
    static float[] reduce(float[] src, int w, int h) {
        int w2 = (w + 1) / 2;
        int h2 = (h + 1) / 2;
        float[] tmp = new float[w2 * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w2; x++) {
                float v = 0;
                for (int k = 0; k < 5; k++) v += KERNEL[k] * src[row + clamp(2 * x + k - 2, 0, w - 1)];
                tmp[y * w2 + x] = v;
            }
        }
        float[] dst = new float[w2 * h2];
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < w2; x++) {
                float v = 0;
                for (int k = 0; k < 5; k++) v += KERNEL[k] * tmp[clamp(2 * y + k - 2, 0, h - 1) * w2 + x];
                dst[y * w2 + x] = v;
            }
        }
        return dst;
    }

    /** 2x upsampling with the same kernel; the inverse partner of {@link #reduce}. */
    static float[] expand(float[] src, int w2, int h2, int w, int h) {
        float[] tmp = new float[w * h2];
        for (int y = 0; y < h2; y++) {
            int row = y * w2;
            for (int x = 0; x < w; x++) tmp[y * w + x] = upsampleTap(src, row, 1, x, w2);
        }
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) dst[y * w + x] = upsampleTap(tmp, x, w, y, h2);
        }
        return dst;
    }

    private static float upsampleTap(float[] src, int base, int stride, int position, int length) {
        int j = position >> 1;
        if ((position & 1) == 0) {
            return 0.75f * src[base + clamp(j, 0, length - 1) * stride]
                + 0.125f * src[base + clamp(j - 1, 0, length - 1) * stride]
                + 0.125f * src[base + clamp(j + 1, 0, length - 1) * stride];
        }
        return 0.5f * src[base + clamp(j, 0, length - 1) * stride] + 0.5f * src[base + clamp(j + 1, 0, length - 1) * stride];
    }
}
