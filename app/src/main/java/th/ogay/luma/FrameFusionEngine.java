package th.ogay.luma;

/** Translation registration and motion-rejecting, bounded-memory fusion. */
public final class FrameFusionEngine {
    public static final class Alignment {
        public final int dx;
        public final int dy;
        public final double error;

        Alignment(int dx, int dy, double error) {
            this.dx = dx;
            this.dy = dy;
            this.error = error;
        }
    }

    public static int luma(int rgb) {
        return (77 * ((rgb >>> 16) & 255) + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255)) >> 8;
    }

    private static int[] reduce(int[] image, int width, int height) {
        int w = width / 2;
        int h = height / 2;
        int[] smaller = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = y * 2 * width + x * 2;
                smaller[y * w + x] = (image[p] + image[p + 1] + image[p + width] + image[p + width + 1]) / 4;
            }
        }
        return smaller;
    }

    private static double cost(int[] reference, int[] candidate, int w, int h, int dx, int dy) {
        double total = 0;
        int count = 0;
        int border = Math.max(3, Math.max(Math.abs(dx), Math.abs(dy)) + 1);
        for (int y = border; y < h - border; y += 3) {
            for (int x = border; x < w - border; x += 3) {
                total += Math.abs(reference[y * w + x] - candidate[(y + dy) * w + x + dx]);
                count++;
            }
        }
        return count < 16 ? Double.POSITIVE_INFINITY : total / count;
    }

    /** dx/dy map a reference position to the same scene point in the candidate. */
    public static Alignment align(int[] reference, int[] candidate, int width, int height) {
        if (reference.length != width * height || candidate.length != reference.length) {
            throw new IllegalArgumentException("Mismatched image sizes");
        }
        if (width >= 64 && height >= 64) {
            Alignment coarse = align(reduce(reference, width, height), reduce(candidate, width, height), width / 2, height / 2);
            return search(reference, candidate, width, height, coarse.dx * 2, coarse.dy * 2, 2);
        }
        return search(reference, candidate, width, height, 0, 0, 6);
    }

    private static Alignment search(int[] reference, int[] candidate, int w, int h, int cx, int cy, int radius) {
        int bestX = cx;
        int bestY = cy;
        double best = Double.POSITIVE_INFINITY;
        for (int dy = cy - radius; dy <= cy + radius; dy++) {
            for (int dx = cx - radius; dx <= cx + radius; dx++) {
                double value = cost(reference, candidate, w, h, dx, dy) + 0.005 * (Math.abs(dx) + Math.abs(dy));
                if (value < best) {
                    best = value;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        return new Alignment(bestX, bestY, cost(reference, candidate, w, h, bestX, bestY));
    }

    public static final class Accumulator {
        private final int[] reference;
        private final float[] r;
        private final float[] g;
        private final float[] b;
        private final float[] weight;

        public Accumulator(int[] pixels) {
            reference = pixels.clone();
            r = new float[pixels.length];
            g = new float[pixels.length];
            b = new float[pixels.length];
            weight = new float[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                r[i] = (pixels[i] >>> 16) & 255;
                g[i] = (pixels[i] >>> 8) & 255;
                b[i] = pixels[i] & 255;
                weight[i] = 1;
            }
        }

        public void add(int[] aligned, boolean[] valid) {
            if (aligned.length != reference.length || valid.length != reference.length) {
                throw new IllegalArgumentException("Invalid tile size");
            }
            for (int i = 0; i < aligned.length; i++) {
                if (!valid[i]) continue;
                int pixel = aligned[i];
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                int ref = reference[i];
                int difference = Math.max(Math.abs(red - ((ref >>> 16) & 255)),
                    Math.max(Math.abs(green - ((ref >>> 8) & 255)), Math.abs(blue - (ref & 255))));
                // Large local changes retain the reference, avoiding trails on moving subjects.
                if (difference >= 38) continue;
                float confidence = 1f - (difference * difference) / (38f * 38f);
                r[i] += red * confidence;
                g[i] += green * confidence;
                b[i] += blue * confidence;
                weight[i] += confidence;
            }
        }

        public int[] finish() {
            int[] out = new int[reference.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = 0xff000000 | (Math.round(r[i] / weight[i]) << 16)
                    | (Math.round(g[i] / weight[i]) << 8) | Math.round(b[i] / weight[i]);
            }
            return out;
        }
    }
}
