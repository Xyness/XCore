package fr.xyness.XCore.Web.Icons;

/**
 * A chain of translations, rotations and scalings, built the way the game builds its pose stack.
 *
 * <p>Each call adds a step that applies to a point <em>before</em> the steps already there, which
 * is what lets the code that places a chest or a banner read like the game's own renderer:
 * {@code translate(0.5, 0, 0.5).scale(1, -1, -1)} moves what is drawn after it has been flipped.
 * Angles are in radians and follow the right-hand rule.</p>
 *
 * @since 2.1
 */
final class Affine {

    // The top three rows of the matrix; the fourth is always 0 0 0 1.
    private double m00 = 1, m01, m02, m03;
    private double m10, m11 = 1, m12, m13;
    private double m20, m21, m22 = 1, m23;

    /** The transform that changes nothing. */
    Affine() {
    }

    /** A copy that can be extended without touching the original. */
    Affine(Affine other) {
        m00 = other.m00; m01 = other.m01; m02 = other.m02; m03 = other.m03;
        m10 = other.m10; m11 = other.m11; m12 = other.m12; m13 = other.m13;
        m20 = other.m20; m21 = other.m21; m22 = other.m22; m23 = other.m23;
    }

    Affine translate(double x, double y, double z) {
        m03 += m00 * x + m01 * y + m02 * z;
        m13 += m10 * x + m11 * y + m12 * z;
        m23 += m20 * x + m21 * y + m22 * z;
        return this;
    }

    Affine scale(double x, double y, double z) {
        m00 *= x; m10 *= x; m20 *= x;
        m01 *= y; m11 *= y; m21 *= y;
        m02 *= z; m12 *= z; m22 *= z;
        return this;
    }

    Affine rotateX(double radians) {
        if (radians == 0) return this;
        double c = Math.cos(radians), s = Math.sin(radians);
        return linear(1, 0, 0, 0, c, -s, 0, s, c);
    }

    Affine rotateY(double radians) {
        if (radians == 0) return this;
        double c = Math.cos(radians), s = Math.sin(radians);
        return linear(c, 0, s, 0, 1, 0, -s, 0, c);
    }

    Affine rotateZ(double radians) {
        if (radians == 0) return this;
        double c = Math.cos(radians), s = Math.sin(radians);
        return linear(c, -s, 0, s, c, 0, 0, 0, 1);
    }

    /** Rotates by a quaternion, which need not be normalised: a zero one changes nothing. */
    Affine rotate(double x, double y, double z, double w) {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 1e-12)) return this;
        x /= length; y /= length; z /= length; w /= length;
        return linear(
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y));
    }

    /** Appends another whole chain. */
    Affine multiply(Affine o) {
        double t03 = m00 * o.m03 + m01 * o.m13 + m02 * o.m23 + m03;
        double t13 = m10 * o.m03 + m11 * o.m13 + m12 * o.m23 + m13;
        double t23 = m20 * o.m03 + m21 * o.m13 + m22 * o.m23 + m23;
        linear(o.m00, o.m01, o.m02, o.m10, o.m11, o.m12, o.m20, o.m21, o.m22);
        m03 = t03; m13 = t13; m23 = t23;
        return this;
    }

    /** Appends a step given by its nine rotation and scale terms, row by row. */
    private Affine linear(double a00, double a01, double a02,
                          double a10, double a11, double a12,
                          double a20, double a21, double a22) {
        double n00 = m00 * a00 + m01 * a10 + m02 * a20;
        double n01 = m00 * a01 + m01 * a11 + m02 * a21;
        double n02 = m00 * a02 + m01 * a12 + m02 * a22;
        double n10 = m10 * a00 + m11 * a10 + m12 * a20;
        double n11 = m10 * a01 + m11 * a11 + m12 * a21;
        double n12 = m10 * a02 + m11 * a12 + m12 * a22;
        double n20 = m20 * a00 + m21 * a10 + m22 * a20;
        double n21 = m20 * a01 + m21 * a11 + m22 * a21;
        double n22 = m20 * a02 + m21 * a12 + m22 * a22;
        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
        m20 = n20; m21 = n21; m22 = n22;
        return this;
    }

    /**
     * Sets the twelve terms directly, row by row: the form a definition uses when it gives a
     * matrix instead of the steps that make it.
     */
    static Affine ofRows(double[] rows) {
        Affine a = new Affine();
        a.m00 = rows[0]; a.m01 = rows[1]; a.m02 = rows[2]; a.m03 = rows[3];
        a.m10 = rows[4]; a.m11 = rows[5]; a.m12 = rows[6]; a.m13 = rows[7];
        a.m20 = rows[8]; a.m21 = rows[9]; a.m22 = rows[10]; a.m23 = rows[11];
        return a;
    }

    /** Moves a point, writing its three coordinates at {@code out[at]}. */
    void point(double x, double y, double z, double[] out, int at) {
        out[at] = m00 * x + m01 * y + m02 * z + m03;
        out[at + 1] = m10 * x + m11 * y + m12 * z + m13;
        out[at + 2] = m20 * x + m21 * y + m22 * z + m23;
    }

    /** Turns a direction without moving it: rotation and scale only. */
    void direction(double x, double y, double z, double[] out) {
        out[0] = m00 * x + m01 * y + m02 * z;
        out[1] = m10 * x + m11 * y + m12 * z;
        out[2] = m20 * x + m21 * y + m22 * z;
    }

    /**
     * Turns a surface normal, which does not follow a scaling the way a point does: squashing a
     * box flat leaves its top facing up. The result has length one.
     */
    void normal(double x, double y, double z, double[] out) {
        // The cofactors of the rotation and scale part, which is its inverse transpose up to the
        // determinant: only the direction matters here, and the sign is put back below.
        double c00 = m11 * m22 - m12 * m21, c01 = m12 * m20 - m10 * m22, c02 = m10 * m21 - m11 * m20;
        double c10 = m02 * m21 - m01 * m22, c11 = m00 * m22 - m02 * m20, c12 = m01 * m20 - m00 * m21;
        double c20 = m01 * m12 - m02 * m11, c21 = m02 * m10 - m00 * m12, c22 = m00 * m11 - m01 * m10;
        double nx = c00 * x + c01 * y + c02 * z;
        double ny = c10 * x + c11 * y + c12 * z;
        double nz = c20 * x + c21 * y + c22 * z;
        double sign = (m00 * c00 + m01 * c01 + m02 * c02) < 0 ? -1 : 1;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-12)) {
            out[0] = 0; out[1] = 0; out[2] = 1;
            return;
        }
        out[0] = sign * nx / length;
        out[1] = sign * ny / length;
        out[2] = sign * nz / length;
    }

    /** Whether this changes nothing, to the precision an icon can show. */
    boolean isIdentity() {
        return near(m00, 1) && near(m11, 1) && near(m22, 1)
                && near(m01, 0) && near(m02, 0) && near(m03, 0)
                && near(m10, 0) && near(m12, 0) && near(m13, 0)
                && near(m20, 0) && near(m21, 0) && near(m23, 0);
    }

    private static boolean near(double value, double target) {
        return Math.abs(value - target) < 1e-9;
    }
}
