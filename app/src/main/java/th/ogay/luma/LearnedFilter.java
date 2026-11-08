package th.ogay.luma;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Learned luma at four subpixel phases; bilinear chroma keeps skin tones stable. */
public final class LearnedFilter {
    private final short[] weights = new short[17 * 4 * 25];
    private final byte[] bucket = new byte[511 * 511];

    public LearnedFilter(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        for (int i = 0; i < weights.length; i++) weights[i] = Short.reverseBytes(input.readShort());
        if (input.read() != -1) throw new IOException("Unsupported SR model length");
        for (int y = -255; y <= 255; y++) {
            for (int x = -255; x <= 255; x++) {
                int strength = Math.abs(x) + Math.abs(y);
                int angle = ((int)Math.floor(Math.atan2(y, x) * (8.0 / Math.PI) + 0.5)) & 7;
                bucket[(y + 255)*511 + x + 255] = (byte)(strength < 10 ? 0 : 1 + angle + (strength >= 48 ? 8 : 0));
            }
        }
    }

    private static int luma(int rgb) {
        return (77*((rgb >>> 16) & 255) + 150*((rgb >>> 8) & 255) + 29*(rgb & 255) + 128) >> 8;
    }
    private static int limit(int v) { return Math.max(0, Math.min(255, v)); }
    private static int channel(int a, int b, int c, int d, int shift) {
        return (9*((a >>> shift)&255) + 3*((b >>> shift)&255) + 3*((c >>> shift)&255) + ((d >>> shift)&255) + 8) >> 4;
    }

    /** rgb includes a two-input-pixel halo on every edge. Output is the interior at 2x. */
    public int[] expand(int[] rgb, int stride, int height) {
        if (stride < 5 || height < 5 || rgb.length != stride * height) throw new IllegalArgumentException("Invalid tile");
        int width = stride - 4, rows = height - 4, outStride = width * 2;
        int[] result = new int[outStride * rows * 2];
        int[] y = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) y[i] = luma(rgb[i]);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < width; col++) {
                int p = (row + 2)*stride + col + 2;
                int dx = y[p + 1] - y[p - 1], dy = y[p + stride] - y[p - stride];
                int bank = bucket[(dy + 255)*511 + dx + 255] * 100;
                for (int phase = 0; phase < 4; phase++) {
                    int predicted = 2048, k = bank + 25*phase;
                    for (int j = -2; j <= 2; j++) {
                        int from = p + j*stride - 2;
                        for (int i = 0; i < 5; i++) predicted += weights[k++] * y[from + i];
                    }
                    int hx = ((phase & 1) == 0) ? -1 : 1;
                    int vy = (phase < 2) ? -stride : stride;
                    int a = rgb[p], b = rgb[p+hx], c = rgb[p+vy], d = rgb[p+hx+vy];
                    int r = channel(a,b,c,d,16), g = channel(a,b,c,d,8), blue = channel(a,b,c,d,0);
                    int delta = (predicted >> 12) - ((77*r + 150*g + 29*blue + 128) >> 8);
                    delta = Math.max(-24, Math.min(24, delta));
                    int index = (row*2 + (phase >> 1))*outStride + col*2 + (phase & 1);
                    result[index] = 0xff000000 | (limit(r+delta) << 16) | (limit(g+delta) << 8) | limit(blue+delta);
                }
            }
        }
        return result;
    }
}
