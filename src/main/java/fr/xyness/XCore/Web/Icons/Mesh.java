package fr.xyness.XCore.Web.Icons;

import java.io.IOException;
import java.util.List;

/**
 * Turns what the game describes — the boxes of a block model, the parts of an entity, the layers
 * of a flat item — into the quads an icon is drawn from.
 *
 * <p>The corner order and the way a texture is laid over each face follow the game's own code
 * to the letter: get either wrong and a furnace shows its front on the wrong side, or a chest its
 * lid texture upside down.</p>
 *
 * @since 2.1
 */
final class Mesh {

    private Mesh() {
    }

    /** Finds the texture a face names, {@code #side} or a path; {@code null} when there is none. */
    @FunctionalInterface
    interface Textures {
        Texture find(String name) throws IOException;
    }

    /** No tint: the texture's own colours. */
    static final int WHITE = 0xFFFFFFFF;

    /**
     * The corners of each face of a box, in the game's order: for every corner, whether it sits
     * at the {@code from} (0) or the {@code to} (1) end of x, y and z. Seen from outside they run
     * top-left, bottom-left, bottom-right, top-right.
     */
    private static final int[][][] CORNERS = {
        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},   // down
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},   // up
        {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},   // north
        {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},   // south
        {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},   // west
        {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},   // east
    };

    /** The way each of the six faces points. */
    private static final double[][] AXES = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},
    };

    // **************************************************************************
    // *                          Boxes of a block model                        *
    // **************************************************************************

    /**
     * Adds the faces of a model's boxes.
     *
     * @param elements The boxes.
     * @param textures Where a face's texture is found. A face whose texture is missing is left out.
     * @param tints    The item's tints, by index.
     * @param out      Where the quads go, in the order the game draws them.
     */
    static void boxes(List<Model.Element> elements, Textures textures, int[] tints, List<Quad> out) throws IOException {
        for (Model.Element element : elements) {
            double[][] ends = {
                {element.from[0] / 16, element.from[1] / 16, element.from[2] / 16},
                {element.to[0] / 16, element.to[1] / 16, element.to[2] / 16},
            };
            Affine turn = turn(element.rotation);

            for (int side = 0; side < 6; side++) {
                Model.Face face = element.faces[side];
                if (face == null || face.texture == null) continue;
                Texture texture = textures.find(face.texture);
                if (texture == null) continue;

                double[] corners = new double[12];
                for (int i = 0; i < 4; i++) {
                    int[] pick = CORNERS[side][i];
                    double x = ends[pick[0]][0], y = ends[pick[1]][1], z = ends[pick[2]][2];
                    if (turn != null) {
                        turn.point(x, y, z, corners, i * 3);
                    } else {
                        corners[i * 3] = x;
                        corners[i * 3 + 1] = y;
                        corners[i * 3 + 2] = z;
                    }
                }

                double[] rect = face.uv != null ? face.uv : defaultUv(side, element.from, element.to);
                double[] uv = new double[8];
                for (int i = 0; i < 4; i++) {
                    // A turned texture hands each corner the spot of the next one round.
                    int k = (i + face.rotation / 90) % 4;
                    uv[i * 2] = (k == 0 || k == 1 ? rect[0] : rect[2]) / 16;
                    uv[i * 2 + 1] = (k == 0 || k == 3 ? rect[1] : rect[3]) / 16;
                }

                double[] normal = Quad.normal(corners);
                if (normal == null) continue;
                int facing = nearestAxis(normal);
                if (element.rotation == null && facing != side) {
                    // A box written inside out — its end before its start — has its faces looking
                    // inwards. The game turns each one round to face the way it really does, and so
                    // must this, or the face is thrown away as the back of something.
                    rewind(corners, uv, facing);
                }

                int tint = face.tint >= 0 && face.tint < tints.length ? tints[face.tint] : WHITE;
                double[] axis = AXES[facing];
                out.add(new Quad(corners, uv, texture, tint, axis[0], axis[1], axis[2]));
            }
        }
    }

    /** The part of the texture a face shows when the model does not say: the box's own outline. */
    private static double[] defaultUv(int side, double[] from, double[] to) {
        return switch (side) {
            case Model.DOWN -> new double[] {from[0], 16 - to[2], to[0], 16 - from[2]};
            case Model.UP -> new double[] {from[0], from[2], to[0], to[2]};
            case Model.SOUTH -> new double[] {from[0], 16 - to[1], to[0], 16 - from[1]};
            case Model.WEST -> new double[] {from[2], 16 - to[1], to[2], 16 - from[1]};
            case Model.EAST -> new double[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
            default -> new double[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
        };
    }

    /** How a box is turned around its origin, or {@code null} when it is not. */
    private static Affine turn(Model.Rotation rotation) {
        if (rotation == null || rotation.isNone()) return null;
        Affine spin = new Affine()
                .rotateZ(Math.toRadians(rotation.angles[2]))
                .rotateY(Math.toRadians(rotation.angles[1]))
                .rotateX(Math.toRadians(rotation.angles[0]));
        if (rotation.rescale) {
            // Stretched so that a plane turned by 45 degrees still reaches the edges of its block:
            // each axis grows by how much the turn shortened its reach along the grid.
            double[] moved = new double[3];
            double[] grow = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                spin.direction(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0, moved);
                double reach = Math.max(Math.abs(moved[0]), Math.max(Math.abs(moved[1]), Math.abs(moved[2])));
                grow[axis] = reach > 1e-9 ? 1 / reach : 1;
            }
            spin.scale(grow[0], grow[1], grow[2]);
        }
        double ox = rotation.origin[0] / 16, oy = rotation.origin[1] / 16, oz = rotation.origin[2] / 16;
        return new Affine().translate(ox, oy, oz).multiply(spin).translate(-ox, -oy, -oz);
    }

    /** The one of the six directions a normal is closest to. */
    private static int nearestAxis(double[] normal) {
        int best = Model.UP;
        double most = 0;
        for (int i = 0; i < 6; i++) {
            double along = normal[0] * AXES[i][0] + normal[1] * AXES[i][1] + normal[2] * AXES[i][2];
            if (along > most) {
                most = along;
                best = i;
            }
        }
        return best;
    }

    /**
     * Puts the corners of a face back in the order of the direction it really faces, each
     * keeping the spot of the texture it had.
     */
    private static void rewind(double[] corners, double[] uv, int facing) {
        double[] low = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < 4; i++) {
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], corners[i * 3 + axis]);
                high[axis] = Math.max(high[axis], corners[i * 3 + axis]);
            }
        }
        double[] oldCorners = corners.clone();
        double[] oldUv = uv.clone();
        for (int i = 0; i < 4; i++) {
            int[] pick = CORNERS[facing][i];
            for (int axis = 0; axis < 3; axis++) {
                corners[i * 3 + axis] = pick[axis] == 0 ? low[axis] : high[axis];
            }
            for (int k = 0; k < 4; k++) {
                if (Math.abs(oldCorners[k * 3] - corners[i * 3]) < 1e-7
                        && Math.abs(oldCorners[k * 3 + 1] - corners[i * 3 + 1]) < 1e-7
                        && Math.abs(oldCorners[k * 3 + 2] - corners[i * 3 + 2]) < 1e-7) {
                    uv[i * 2] = oldUv[k * 2];
                    uv[i * 2 + 1] = oldUv[k * 2 + 1];
                }
            }
        }
    }

    // **************************************************************************
    // *                          Layers of a flat item                         *
    // **************************************************************************

    /**
     * Adds one layer of a flat item as the game builds it: the texture standing upright in the
     * middle of the block, one unit of sixteen thick, readable from both sides.
     *
     * <p>Only the two large faces are made. The game also closes the outline with a rim one
     * pixel deep, which only shows when the model is turned; none of its own flat items is, in
     * an inventory slot.</p>
     */
    static void sprite(Texture texture, int tint, List<Quad> out) {
        double front = 8.5 / 16, back = 7.5 / 16;
        out.add(new Quad(
                new double[] {0, 1, front, 0, 0, front, 1, 0, front, 1, 1, front},
                new double[] {0, 0, 0, 1, 1, 1, 1, 0},
                texture, tint, 0, 0, 1));
        out.add(new Quad(
                new double[] {1, 1, back, 1, 0, back, 0, 0, back, 0, 1, back},
                new double[] {1, 0, 1, 1, 0, 1, 0, 0},
                texture, tint, 0, 0, -1));
    }

    // **************************************************************************
    // *                          Parts of an entity                            *
    // **************************************************************************

    /**
     * Adds a box of an entity model: a chest's lid, a skull, a banner's pole.
     *
     * <p>Entities do not name a texture per face. Each box is unwrapped onto the texture from a
     * single corner, in a cross whose arms are sized by the box: top and bottom side by side
     * above the four sides in a row. Sizes are in sixteenths of a block, as in a block model.</p>
     *
     * @param out     Where the quads go.
     * @param pose    Where the part stands, in the same sixteenths.
     * @param texture The entity's texture.
     * @param texU    The corner of the cross on the texture, in pixels.
     * @param texV    The same, down.
     * @param x       The low corner of the box.
     * @param y       The same.
     * @param z       The same.
     * @param dx      The size of the box, which also sizes its cross on the texture.
     * @param dy      The same.
     * @param dz      The same.
     * @param grow    How far every face is pushed out without changing the texture: a hat over a head.
     * @param mirror  Whether the box is the mirror image of its texture, as a left leg is of a right one.
     * @param sides   Which faces are made, one bit per direction; {@link #ALL_SIDES} for a whole box.
     * @param tint    The colour the texture is multiplied by.
     */
    static void cuboid(List<Quad> out, Affine pose, Texture texture, int texU, int texV,
                       double x, double y, double z, double dx, double dy, double dz,
                       double grow, boolean mirror, int sides, int tint) {
        double x0 = x - grow, y0 = y - grow, z0 = z - grow;
        double x1 = x + dx + grow, y1 = y + dy + grow, z1 = z + dz + grow;
        if (mirror) {
            double swap = x1;
            x1 = x0;
            x0 = swap;
        }

        // The eight corners, named as the game names them.
        double[][] p = {
            {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1},
            {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}, {x0, y0, z0},
        };
        double u0 = texU, u1 = texU + dz, u2 = texU + dz + dx, u3 = texU + dz + dx + dx;
        double u4 = texU + dz + dx + dz, u5 = texU + dz + dx + dz + dx;
        double v0 = texV, v1 = texV + dz, v2 = texV + dz + dy;

        if ((sides & 1 << Model.DOWN) != 0) polygon(out, pose, texture, tint, mirror, p[4], p[3], p[7], p[0], u1, v0, u2, v1);
        if ((sides & 1 << Model.UP) != 0) polygon(out, pose, texture, tint, mirror, p[1], p[2], p[6], p[5], u2, v1, u3, v0);
        if ((sides & 1 << Model.WEST) != 0) polygon(out, pose, texture, tint, mirror, p[7], p[3], p[6], p[2], u0, v1, u1, v2);
        if ((sides & 1 << Model.NORTH) != 0) polygon(out, pose, texture, tint, mirror, p[0], p[7], p[2], p[1], u1, v1, u2, v2);
        if ((sides & 1 << Model.EAST) != 0) polygon(out, pose, texture, tint, mirror, p[4], p[0], p[1], p[5], u2, v1, u4, v2);
        if ((sides & 1 << Model.SOUTH) != 0) polygon(out, pose, texture, tint, mirror, p[3], p[4], p[5], p[6], u4, v1, u5, v2);
    }

    /** Every face of a box. */
    static final int ALL_SIDES = 0b111111;

    private static void polygon(List<Quad> out, Affine pose, Texture texture, int tint, boolean mirror,
                                double[] a, double[] b, double[] c, double[] d,
                                double u0, double v0, double u1, double v1) {
        double[][] points = {a, b, c, d};
        double[] us = {u1, u0, u0, u1};
        double[] vs = {v0, v0, v1, v1};
        double[] corners = new double[12];
        double[] uv = new double[8];
        for (int i = 0; i < 4; i++) {
            // A mirrored box swapped its two x ends, which turned every face inside out: taking
            // the corners backwards turns them outside in again.
            int from = mirror ? 3 - i : i;
            pose.point(points[from][0], points[from][1], points[from][2], corners, i * 3);
            uv[i * 2] = us[from] / texture.width;
            uv[i * 2 + 1] = vs[from] / texture.height;
        }
        Quad quad = Quad.facing(corners, uv, texture, tint);
        if (quad != null) out.add(quad);
    }
}
