package fr.xyness.XCore.Web.Icons;

/**
 * How bright a face is in an inventory slot.
 *
 * <p>The game lights items with two lamps and a floor of ambient light, and turns the pair of
 * lamps one way for blocks ({@code "gui_light": "side"}) and another for flat items
 * ({@code "front"}). The first is what makes a block's top bright, its left side dimmer and its
 * right side darkest; the second leaves a sprite that faces the eye exactly as its texture is.
 * The numbers below are the game's, so a stone block comes out at the same three greys.</p>
 *
 * @since 2.1
 */
final class Lighting {

    private Lighting() {
    }

    /** The share of light every face gets whichever way it points. */
    private static final double AMBIENT = 0.4;

    /** How much each lamp adds to a face turned straight at it. */
    private static final double POWER = 0.6;

    // The two lamps as the game aims them for each kind of item, in the slot's own axes: x to the
    // right, y up, z towards the eye.
    private static final double[] SIDE_FIRST;
    private static final double[] SIDE_SECOND;
    private static final double[] FRONT_FIRST;
    private static final double[] FRONT_SECOND;

    static {
        // Where the lamps stand before the game turns them for a slot.
        double[] first = unit(0.2, 1.0, -0.7);
        double[] second = unit(-0.2, 1.0, 0.7);

        Affine side = new Affine()
                .rotateY(1.0821041).rotateX(3.2375858)
                .rotateY(-Math.PI / 8).rotateX(Math.PI * 3 / 4);
        Affine front = new Affine()
                .scale(1, -1, 1)
                .rotateY(-Math.PI / 8).rotateX(Math.PI * 3 / 4);

        SIDE_FIRST = turned(side, first);
        SIDE_SECOND = turned(side, second);
        FRONT_FIRST = turned(front, first);
        FRONT_SECOND = turned(front, second);
    }

    private static double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }

    private static double[] turned(Affine by, double[] lamp) {
        double[] out = new double[3];
        by.direction(lamp[0], lamp[1], lamp[2], out);
        return out;
    }

    /**
     * The brightness of a face, from 0.4 in full shadow to 1.
     *
     * @param nx      The way the face points in the slot, of length one.
     * @param ny      The same.
     * @param nz      The same.
     * @param sideLit Whether the item is lit as a block rather than as a flat item.
     */
    static double brightness(double nx, double ny, double nz, boolean sideLit) {
        double[] a = sideLit ? SIDE_FIRST : FRONT_FIRST;
        double[] b = sideLit ? SIDE_SECOND : FRONT_SECOND;
        double lit = Math.max(0, a[0] * nx + a[1] * ny + a[2] * nz)
                + Math.max(0, b[0] * nx + b[1] * ny + b[2] * nz);
        return Math.min(1, lit * POWER + AMBIENT);
    }
}
