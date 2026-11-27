package fr.xyness.XCore.Web.Icons;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * The canvas quads are drawn on: a small software renderer, enough for an inventory slot.
 *
 * <p>The view is the game's own for a slot: straight on, no perspective, the slot one unit wide
 * around the origin with x to the right, y up and z towards the eye. Each texture is sampled
 * without smoothing, as the game does, but every pixel of the icon is the average of a grid of
 * finer ones: at 64 pixels the slanted edges of a block would otherwise be staircases.</p>
 *
 * <p>One instance draws one icon and belongs to the thread that made it.</p>
 *
 * @since 2.1
 */
final class Raster {

    /** Finer samples per pixel, each way. */
    private static final int SUPERSAMPLE = 4;

    /**
     * How close two surfaces must be, in block units, to count as the same surface: the one drawn
     * last then shows, as on the graphics card. A chest's lid and its base share the plane of
     * their sides, and which wins must not depend on rounding.
     */
    private static final double SAME_DEPTH = 1e-6;

    /** Below this opacity a texel is not drawn at all: the game's cut-out threshold, one tenth. */
    private static final int ALPHA_CUTOFF = 26;

    private final int size;
    private final int samples;

    // The nearest solid surface at each sample: how far, what colour, and when it was drawn.
    private final double[] depth;
    private final int[] solid;
    private final int[] solidOrder;

    // See-through fragments — stained glass, ice — kept aside to be laid over in depth order:
    // each sample heads a chain through these arrays.
    private final int[] chain;
    private int[] sheerNext = new int[1024];
    private int[] sheerColour = new int[1024];
    private int[] sheerOrder = new int[1024];
    private double[] sheerDepth = new double[1024];
    private int sheerCount;

    private int order;

    /**
     * @param size The width and height of the icon, in pixels.
     */
    Raster(int size) {
        this.size = size;
        this.samples = size * SUPERSAMPLE;
        this.depth = new double[samples * samples];
        this.solid = new int[samples * samples];
        this.solidOrder = new int[samples * samples];
        this.chain = new int[samples * samples];
        this.sheerPerSample = new byte[samples * samples];
        this.sheerLimit = samples * samples * 8;
        Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        Arrays.fill(chain, -1);
    }

    /** The see-through fragments set aside on each sample, and the most it keeps. */
    private final byte[] sheerPerSample;
    private static final int MAX_SHEER_PER_SAMPLE = 16;

    /** The most see-through fragments one icon keeps in all. */
    private final int sheerLimit;

    /**
     * Draws one quad.
     *
     * @param quad    The quad, in the space of its model.
     * @param pose    What takes the model into the slot.
     * @param sideLit Whether the slot lights it as a block, from the side, or as a flat item.
     */
    void draw(Quad quad, Affine pose, boolean sideLit) {
        order++;
        double[] p = new double[12];
        for (int i = 0; i < 4; i++) {
            pose.point(quad.corners[i * 3], quad.corners[i * 3 + 1], quad.corners[i * 3 + 2], p, i * 3);
        }

        // Every quad here is a parallelogram, so a point inside it is its first corner plus a
        // share s of the edge to the second and a share t of the edge to the fourth. Those two
        // shares give the depth and the spot on the texture without any further geometry.
        double ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        double bx = p[9] - p[0], by = p[10] - p[1], bz = p[11] - p[2];
        double area = ax * by - ay * bx;
        // Facing away, or seen exactly edge-on: the game draws neither.
        if (!(area > 1e-12)) return;

        double[] n = new double[3];
        pose.normal(quad.nx, quad.ny, quad.nz, n);
        double light = Lighting.brightness(n[0], n[1], n[2], sideLit);

        double minX = Math.min(Math.min(p[0], p[3]), Math.min(p[6], p[9]));
        double maxX = Math.max(Math.max(p[0], p[3]), Math.max(p[6], p[9]));
        double minY = Math.min(Math.min(p[1], p[4]), Math.min(p[7], p[10]));
        double maxY = Math.max(Math.max(p[1], p[4]), Math.max(p[7], p[10]));
        int left = Math.max(0, (int) Math.floor((minX + 0.5) * samples));
        int right = Math.min(samples - 1, (int) Math.ceil((maxX + 0.5) * samples));
        int top = Math.max(0, (int) Math.floor((0.5 - maxY) * samples));
        int bottom = Math.min(samples - 1, (int) Math.ceil((0.5 - minY) * samples));
        if (left > right || top > bottom) return;

        double[] uv = quad.uv;
        double ua = uv[2] - uv[0], va = uv[3] - uv[1];
        double ub = uv[6] - uv[0], vb = uv[7] - uv[1];
        Texture texture = quad.texture;
        int tint = quad.tint;
        int tintA = tint >>> 24, tintR = tint >> 16 & 0xFF, tintG = tint >> 8 & 0xFF, tintB = tint & 0xFF;
        // A whisker of tolerance at the edges: two faces meeting on a sample must not both miss it.
        final double edge = 1e-9;

        for (int row = top; row <= bottom; row++) {
            double y = 0.5 - (row + 0.5) / samples - p[1];
            for (int column = left; column <= right; column++) {
                double x = (column + 0.5) / samples - 0.5 - p[0];
                double s = (x * by - y * bx) / area;
                if (s < -edge || s > 1 + edge) continue;
                double t = (ax * y - ay * x) / area;
                if (t < -edge || t > 1 + edge) continue;

                int texel = texture.at(uv[0] + s * ua + t * ub, uv[1] + s * va + t * vb);
                int alpha = (texel >>> 24) * tintA / 255;
                if (alpha < ALPHA_CUTOFF) continue;
                int r = (int) Math.round((texel >> 16 & 0xFF) * tintR / 255.0 * light);
                int g = (int) Math.round((texel >> 8 & 0xFF) * tintG / 255.0 * light);
                int b = (int) Math.round((texel & 0xFF) * tintB / 255.0 * light);
                int colour = alpha << 24 | r << 16 | g << 8 | b;
                double z = p[2] + s * az + t * bz;
                int at = row * samples + column;

                if (alpha >= 255) {
                    if (z > depth[at] - SAME_DEPTH) {
                        if (z > depth[at]) depth[at] = z;
                        solid[at] = colour;
                        solidOrder[at] = order;
                    }
                } else {
                    sheer(at, z, colour);
                }
            }
        }
    }

    /**
     * Sets a see-through fragment aside for its sample. A sample keeps sixteen at most, and an
     * icon eight per sample in all: the game's glass and ice stack two or three, and a file of a
     * thousand see-through cubes would otherwise grow these arrays without end.
     */
    private void sheer(int at, double z, int colour) {
        if (sheerPerSample[at] >= MAX_SHEER_PER_SAMPLE || sheerCount >= sheerLimit) return;
        sheerPerSample[at]++;
        if (sheerCount == sheerNext.length) {
            int room = sheerCount * 2;
            sheerNext = Arrays.copyOf(sheerNext, room);
            sheerColour = Arrays.copyOf(sheerColour, room);
            sheerOrder = Arrays.copyOf(sheerOrder, room);
            sheerDepth = Arrays.copyOf(sheerDepth, room);
        }
        sheerNext[sheerCount] = chain[at];
        sheerColour[sheerCount] = colour;
        sheerOrder[sheerCount] = order;
        sheerDepth[sheerCount] = z;
        chain[at] = sheerCount++;
    }

    /** Whether anything at all was drawn. */
    boolean isEmpty() {
        if (sheerCount > 0) return false;
        for (double d : depth) {
            if (d != Double.NEGATIVE_INFINITY) return false;
        }
        return true;
    }

    /**
     * Settles every sample and averages them down to the icon.
     *
     * @return The icon, with the slot left transparent where nothing was drawn.
     */
    BufferedImage image() {
        // Per sample, as colour already multiplied by its opacity: the only form in which an
        // average of half-covered pixels does not drag the empty ones' black into the edge.
        float[] red = new float[samples * samples];
        float[] green = new float[samples * samples];
        float[] blue = new float[samples * samples];
        float[] opacity = new float[samples * samples];

        int[] pending = new int[16];
        for (int at = 0; at < samples * samples; at++) {
            float r = 0, g = 0, b = 0, a = 0;
            boolean hasSolid = depth[at] != Double.NEGATIVE_INFINITY;
            if (hasSolid) {
                int c = solid[at];
                r = (c >> 16 & 0xFF) / 255f;
                g = (c >> 8 & 0xFF) / 255f;
                b = (c & 0xFF) / 255f;
                a = 1;
            }

            // The see-through fragments in front of the solid surface, farthest first.
            int count = 0;
            for (int f = chain[at]; f >= 0; f = sheerNext[f]) {
                if (hasSolid) {
                    double ahead = sheerDepth[f] - depth[at];
                    if (ahead < -SAME_DEPTH) continue;
                    if (ahead <= SAME_DEPTH && sheerOrder[f] < solidOrder[at]) continue;
                }
                if (count == pending.length) pending = Arrays.copyOf(pending, count * 2);
                int slot = count++;
                while (slot > 0 && behind(f, pending[slot - 1])) {
                    pending[slot] = pending[slot - 1];
                    slot--;
                }
                pending[slot] = f;
            }
            for (int i = 0; i < count; i++) {
                int c = sheerColour[pending[i]];
                float fa = (c >>> 24) / 255f;
                r = (c >> 16 & 0xFF) / 255f * fa + r * (1 - fa);
                g = (c >> 8 & 0xFF) / 255f * fa + g * (1 - fa);
                b = (c & 0xFF) / 255f * fa + b * (1 - fa);
                a = fa + a * (1 - fa);
            }

            red[at] = r;
            green[at] = g;
            blue[at] = b;
            opacity[at] = a;
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float cells = SUPERSAMPLE * SUPERSAMPLE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < SUPERSAMPLE; dy++) {
                    int at = (y * SUPERSAMPLE + dy) * samples + x * SUPERSAMPLE;
                    for (int dx = 0; dx < SUPERSAMPLE; dx++, at++) {
                        r += red[at];
                        g += green[at];
                        b += blue[at];
                        a += opacity[at];
                    }
                }
                if (a <= 0) continue;
                int alpha = Math.round(a / cells * 255);
                if (alpha == 0) continue;
                image.setRGB(x, y, alpha << 24
                        | channel(r / a) << 16
                        | channel(g / a) << 8
                        | channel(b / a));
            }
        }
        return image;
    }

    /** Whether see-through fragment {@code a} lies behind {@code b}: farther, or as far and drawn first. */
    private boolean behind(int a, int b) {
        double gap = sheerDepth[a] - sheerDepth[b];
        if (gap < -SAME_DEPTH) return true;
        if (gap > SAME_DEPTH) return false;
        return sheerOrder[a] < sheerOrder[b];
    }

    private static int channel(float value) {
        int c = Math.round(value * 255);
        return c < 0 ? 0 : Math.min(c, 255);
    }
}
