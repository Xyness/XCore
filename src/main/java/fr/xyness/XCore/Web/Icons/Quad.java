package fr.xyness.XCore.Web.Icons;

/**
 * One flat, four-cornered piece of a model, with the part of a texture stretched over it.
 *
 * <p>Everything an icon is drawn from comes down to these, whether it started as a box of a block
 * model, a part of a chest or a layer of a flat item. The corners are in block units, a block
 * being one unit wide with a corner at the origin, and run counter-clockwise when seen from the
 * side that shows.</p>
 *
 * @since 2.1
 */
final class Quad {

    /** The four corners, three coordinates each. */
    final double[] corners;

    /** Where each corner falls on the texture: across then down, from 0 to 1. */
    final double[] uv;

    final Texture texture;

    /** The colour the texture is multiplied by, as ARGB; white leaves it alone. */
    final int tint;

    /**
     * The way the face is taken to point when it is lit. For a box of a block model the game
     * rounds it to the nearest of the six directions, which is why a slanted plane is lit like
     * the upright one it was turned from.
     */
    final double nx, ny, nz;

    Quad(double[] corners, double[] uv, Texture texture, int tint, double nx, double ny, double nz) {
        this.corners = corners;
        this.uv = uv;
        this.texture = texture;
        this.tint = tint;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    /**
     * A quad lit along the way it really faces, worked out from its corners.
     *
     * @return The quad, or {@code null} when the corners enclose no surface.
     */
    static Quad facing(double[] corners, double[] uv, Texture texture, int tint) {
        double[] n = normal(corners);
        return n == null ? null : new Quad(corners, uv, texture, tint, n[0], n[1], n[2]);
    }

    /**
     * The direction a set of four corners faces, of length one.
     *
     * @return {@code null} when they are in line or all in one place.
     */
    static double[] normal(double[] c) {
        double ax = c[3] - c[0], ay = c[4] - c[1], az = c[5] - c[2];
        double bx = c[9] - c[0], by = c[10] - c[1], bz = c[11] - c[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-12)) return null;
        return new double[] {nx / length, ny / length, nz / length};
    }
}
