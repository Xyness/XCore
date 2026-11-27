package fr.xyness.XCore.Web.Icons;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * The items no model file describes: chests, shulker boxes, beds, banners, heads, the shield, the
 * conduit, the decorated pot and the copper golem statue.
 *
 * <p>The game draws these in code, from the same parts as the block or the mob they stand for,
 * and nothing in its files gives their shape. The parts are therefore written out here, box by
 * box, with the numbers of the game's own models: sizes in sixteenths of a block, and the corner
 * of each box's cross on the entity texture. Most are built the way mobs are, with y pointing
 * down; the placement that goes with each one stands it back up inside its block.</p>
 *
 * @since 2.1
 */
final class SpecialModels {

    private SpecialModels() {
    }

    /** A special model ready to be posed. */
    static final class Built {

        /** The parts, in the units of a block, around the model's own origin. */
        final List<Quad> quads;

        /**
         * Where the renderer of old put the model in its block. Versions that say so in the item
         * definition make this redundant, and then theirs is used instead.
         */
        final Affine placement;

        private Built(List<Quad> quads, Affine placement) {
            this.quads = quads;
            this.placement = placement;
        }
    }

    /** Sixteen units to a block: parts are written in the former, quads kept in the latter. */
    private static Affine sixteenths() {
        return new Affine().scale(1 / 16.0, 1 / 16.0, 1 / 16.0);
    }

    /** A part hanging off another: moved, then turned about z, y and x as the game turns its parts. */
    private static Affine part(Affine parent, double x, double y, double z, double xRot, double yRot, double zRot) {
        return new Affine(parent).translate(x, y, z).rotateZ(zRot).rotateY(yRot).rotateX(xRot);
    }

    private static void box(List<Quad> out, Affine pose, Texture texture, int texU, int texV,
                            double x, double y, double z, double dx, double dy, double dz) {
        Mesh.cuboid(out, pose, texture, texU, texV, x, y, z, dx, dy, dz, 0, false, Mesh.ALL_SIDES, Mesh.WHITE);
    }

    /**
     * Builds the model a definition asks for.
     *
     * @param spec     The {@code model} object of a {@code minecraft:special} node: its type and
     *                 whatever that type takes.
     * @param textures Where entity textures are found, by path under {@code textures/} without
     *                 the extension.
     * @return The model, or {@code null} for a type not known here or one whose texture is
     *         missing; the caller then falls back on something plainer.
     */
    static Built build(JsonObject spec, Mesh.Textures textures) throws IOException {
        return switch (ItemDefinition.bare(ItemDefinition.string(spec, "type"))) {
            case "chest" -> chest(textures.find("entity/chest/" + name(spec, "texture", "normal")));
            case "shulker_box" -> shulkerBox(textures.find("entity/shulker/" + name(spec, "texture", "shulker")));
            case "bed" -> bed(textures.find("entity/bed/" + name(spec, "texture", "red")));
            case "banner" -> banner(name(spec, "color", "white"), textures);
            case "head" -> head(name(spec, "kind", "skeleton"), name(spec, "texture", ""), textures);
            case "player_head" -> head("player", "", textures);
            case "shield" -> shield(first(textures, "entity/shield/shield_base_nopattern", "entity/shield_base_nopattern"));
            case "conduit" -> conduit(textures.find("entity/conduit/base"));
            case "decorated_pot" -> decoratedPot(textures.find("entity/decorated_pot/decorated_pot_base"),
                    textures.find("entity/decorated_pot/decorated_pot_side"));
            case "copper_golem_statue" -> copperGolem(textures.find(texturePath(name(spec, "texture",
                    "textures/entity/copper_golem/copper_golem.png"))));
            default -> null;
        };
    }

    /** A name field without its namespace, or the fallback when the definition gives none. */
    private static String name(JsonObject spec, String key, String fallback) {
        String value = Model.stripNamespace(ItemDefinition.string(spec, key));
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** {@code textures/entity/x.png}, as some definitions write a texture, to {@code entity/x}. */
    private static String texturePath(String written) {
        String path = written;
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
        return path;
    }

    /** The first of several places a texture has lived in, across versions. */
    private static Texture first(Mesh.Textures textures, String... paths) throws IOException {
        for (String path : paths) {
            Texture texture = textures.find(path);
            if (texture != null) return texture;
        }
        return null;
    }

    // **************************************************************************
    // *                                Chest                                   *
    // **************************************************************************

    private static Built chest(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();
        box(quads, root, texture, 0, 19, 1, 0, 1, 14, 10, 14);
        // The lid and its lock hinge on the back edge; closed, they are not turned at all.
        Affine lid = part(root, 0, 9, 1, 0, 0, 0);
        box(quads, lid, texture, 0, 0, 1, 0, 0, 14, 5, 14);
        box(quads, lid, texture, 0, 0, 7, -2, 14, 2, 4, 1);
        // Already the right way up and in place: the one special model built like a block.
        return new Built(quads, new Affine());
    }

    // **************************************************************************
    // *                             Shulker box                                *
    // **************************************************************************

    private static Built shulkerBox(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();
        Affine origin = part(root, 0, 24, 0, 0, 0, 0);
        // The base first: the lid comes down over its top half and must be the one that shows.
        box(quads, origin, texture, 0, 28, -8, -8, -8, 16, 8, 16);
        box(quads, origin, texture, 0, 0, -8, -16, -8, 16, 12, 16);
        // A hair under full size, as the game draws it, around the middle of the block.
        Affine placement = new Affine().translate(0.5, 0.5, 0.5).scale(0.9995, 0.9995, 0.9995)
                .scale(1, -1, -1).translate(0, -1, 0);
        return new Built(quads, placement);
    }

    // **************************************************************************
    // *                                 Bed                                    *
    // **************************************************************************

    /**
     * A bed as versions before 26.1 drew it, from one texture per colour. Later ones made the bed
     * an ordinary block model and never get here.
     */
    private static Built bed(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        double quarter = Math.PI / 2;

        // Each half is modelled standing on end, then laid down; the foot goes one block behind.
        Affine head = bedHalf(false);
        box(quads, head, texture, 0, 0, 0, 0, 0, 16, 16, 6);
        box(quads, part(head, 0, 0, 0, quarter, 0, quarter), texture, 50, 6, 0, 6, 0, 3, 3, 3);
        box(quads, part(head, 0, 0, 0, quarter, 0, Math.PI), texture, 50, 18, -16, 6, 0, 3, 3, 3);

        Affine foot = bedHalf(true);
        box(quads, foot, texture, 0, 22, 0, 0, 0, 16, 16, 6);
        box(quads, part(foot, 0, 0, 0, quarter, 0, 0), texture, 50, 0, 0, 6, -16, 3, 3, 3);
        box(quads, part(foot, 0, 0, 0, quarter, 0, quarter * 3), texture, 50, 12, -16, 6, -16, 3, 3, 3);
        return new Built(quads, new Affine());
    }

    private static Affine bedHalf(boolean foot) {
        return new Affine()
                .translate(0, 0.5625, foot ? -1 : 0)
                .rotateX(Math.PI / 2)
                .translate(0.5, 0.5, 0.5).rotateZ(Math.PI).translate(-0.5, -0.5, -0.5)
                .scale(1 / 16.0, 1 / 16.0, 1 / 16.0);
    }

    // **************************************************************************
    // *                                Banner                                  *
    // **************************************************************************

    /** The colour each dye gives cloth, by the dye's name. */
    private static int dye(String name) {
        return switch (name) {
            case "orange" -> 0xF9801D;
            case "magenta" -> 0xC74EBD;
            case "light_blue" -> 0x3AB3DA;
            case "yellow" -> 0xFED83D;
            case "lime" -> 0x80C71F;
            case "pink" -> 0xF38BAA;
            case "gray" -> 0x474F52;
            case "light_gray" -> 0x9D9D97;
            case "cyan" -> 0x169C9C;
            case "purple" -> 0x8932B8;
            case "blue" -> 0x3C44AA;
            case "brown" -> 0x835432;
            case "green" -> 0x5E7C16;
            case "red" -> 0xB02E26;
            case "black" -> 0x1D1D21;
            default -> 0xF9FFFE;
        };
    }

    private static Built banner(String colour, Mesh.Textures textures) throws IOException {
        Texture wood = first(textures, "entity/banner/banner_base", "entity/banner_base");
        if (wood == null) return null;
        // The cloth is the bare banner with its base pattern laid over in the dye's colour, which
        // the game does in two passes over the same flag. Done once on the texture instead.
        Texture cloth = dyed(wood, textures.find("entity/banner/base"), dye(colour));

        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();
        box(quads, root, wood, 44, 0, -1, -42, -1, 2, 42, 2);
        box(quads, root, wood, 0, 42, -10, -44, -1, 20, 2, 2);
        // The flag hangs a touch off the vertical: where its sway starts from.
        Affine flag = part(root, 0, -44, 0, -0.0025 * Math.PI, 0, 0);
        box(quads, flag, cloth, 0, 0, -10, 0, -2, 20, 40, 1);

        // Two thirds of the size of its parts, standing on the floor of the block.
        Affine placement = new Affine().translate(0.5, 0, 0.5).scale(2 / 3.0, -2 / 3.0, -2 / 3.0);
        return new Built(quads, placement);
    }

    /** Lays a pattern over a texture in one colour, where the pattern is opaque. */
    private static Texture dyed(Texture under, Texture pattern, int rgb) {
        if (pattern == null || pattern.width != under.width || pattern.height != under.height) return under;
        int[] out = under.argb.clone();
        for (int i = 0; i < out.length; i++) {
            int over = pattern.argb[i];
            int alpha = over >>> 24;
            if (alpha == 0) continue;
            int base = out[i];
            int r = mix(base >> 16 & 0xFF, (over >> 16 & 0xFF) * (rgb >> 16 & 0xFF) / 255, alpha);
            int g = mix(base >> 8 & 0xFF, (over >> 8 & 0xFF) * (rgb >> 8 & 0xFF) / 255, alpha);
            int b = mix(base & 0xFF, (over & 0xFF) * (rgb & 0xFF) / 255, alpha);
            out[i] = (base & 0xFF000000) | r << 16 | g << 8 | b;
        }
        return new Texture(under.width, under.height, out);
    }

    private static int mix(int under, int over, int alpha) {
        return (over * alpha + under * (255 - alpha)) / 255;
    }

    // **************************************************************************
    // *                            Heads and skulls                            *
    // **************************************************************************

    private static Built head(String kind, String ownTexture, Mesh.Textures textures) throws IOException {
        String path = !ownTexture.isEmpty() ? "entity/" + texturePath(ownTexture) : switch (kind) {
            case "wither_skeleton" -> "entity/skeleton/wither_skeleton";
            case "zombie" -> "entity/zombie/zombie";
            case "creeper" -> "entity/creeper/creeper";
            case "piglin" -> "entity/piglin/piglin";
            case "dragon" -> "entity/enderdragon/dragon";
            case "player" -> "entity/player/wide/steve";
            default -> "entity/skeleton/skeleton";
        };
        Texture texture = textures.find(path);
        if (texture == null) return null;

        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();
        switch (kind) {
            case "dragon" -> dragonHead(quads, root, texture);
            case "piglin" -> piglinHead(quads, root, texture);
            default -> {
                box(quads, root, texture, 0, 0, -4, -8, -4, 8, 8, 8);
                // A player's or a zombie's texture has a second skin for the hat, a quarter of a
                // unit off the head. A mob drawn on the old half-height sheet has none.
                if (texture.height >= texture.width && ("player".equals(kind) || "zombie".equals(kind))) {
                    Mesh.cuboid(quads, root, texture, 32, 0, -4, -8, -4, 8, 8, 8, 0.25, false, Mesh.ALL_SIDES, Mesh.WHITE);
                }
            }
        }
        // Standing on the floor in the middle of the block, face to the south.
        Affine placement = new Affine().translate(0.5, 0, 0.5).scale(1, -1, -1);
        return new Built(quads, placement);
    }

    private static void piglinHead(List<Quad> quads, Affine root, Texture texture) {
        box(quads, root, texture, 0, 0, -5, -8, -4, 10, 8, 8);
        box(quads, root, texture, 31, 1, -2, -4, -5, 4, 4, 1);
        box(quads, root, texture, 2, 4, 2, -2, -5, 1, 2, 1);
        box(quads, root, texture, 2, 0, -3, -2, -5, 1, 2, 1);
        // The ears droop: the angle a piglin's ears rest at when nothing moves them.
        box(quads, part(root, 4.5, -6, 0, 0, 0, -0.7), texture, 51, 6, 0, 0, -2, 1, 5, 4);
        box(quads, part(root, -4.5, -6, 0, 0, 0, 0.7), texture, 39, 6, -1, 0, -2, 1, 5, 4);
    }

    private static void dragonHead(List<Quad> quads, Affine root, Texture texture) {
        // Three quarters of the size it has on the dragon, lowered to sit on the floor.
        Affine head = new Affine(root).translate(0, -0.374375 * 16, 0).scale(0.75, 0.75, 0.75);
        box(quads, head, texture, 176, 44, -6, -1, -24, 12, 5, 16);
        box(quads, head, texture, 112, 30, -8, -8, -10, 16, 16, 16);
        Mesh.cuboid(quads, head, texture, 0, 0, -5, -12, -4, 2, 4, 6, 0, true, Mesh.ALL_SIDES, Mesh.WHITE);
        Mesh.cuboid(quads, head, texture, 112, 0, -5, -3, -22, 2, 2, 4, 0, true, Mesh.ALL_SIDES, Mesh.WHITE);
        box(quads, head, texture, 0, 0, 3, -12, -4, 2, 4, 6);
        box(quads, head, texture, 112, 0, 3, -3, -22, 2, 2, 4);
        // The jaw hangs a little open, as it does on a head nothing is powering.
        box(quads, part(head, 0, 4, -8, 0.2, 0, 0), texture, 176, 65, -6, 0, -16, 12, 4, 16);
    }

    // **************************************************************************
    // *                        Shield, conduit, pot                            *
    // **************************************************************************

    private static Built shield(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();
        box(quads, root, texture, 26, 0, -1, -3, -1, 2, 6, 6);
        box(quads, root, texture, 0, 0, -6, -11, -2, 12, 22, 1);
        // Left around the corner of the block it is modelled on: the item's own pose brings it
        // into the slot.
        return new Built(quads, new Affine().scale(1, -1, -1));
    }

    private static Built conduit(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        box(quads, sixteenths(), texture, 0, 0, -3, -3, -3, 6, 6, 6);
        return new Built(quads, new Affine().translate(0.5, 0.5, 0.5));
    }

    private static Built decoratedPot(Texture base, Texture side) {
        if (base == null) return null;
        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths();

        Affine neck = part(root, 0, 37, 16, Math.PI, 0, 0);
        Mesh.cuboid(quads, neck, base, 0, 0, 4, 17, 4, 8, 3, 8, -0.1, false, Mesh.ALL_SIDES, Mesh.WHITE);
        Mesh.cuboid(quads, neck, base, 0, 5, 5, 20, 5, 6, 1, 6, 0.2, false, Mesh.ALL_SIDES, Mesh.WHITE);
        // The shoulders and the bottom are sheets with no thickness.
        box(quads, part(root, 1, 16, 1, 0, 0, 0), base, -14, 13, 0, 0, 0, 14, 0, 14);
        box(quads, part(root, 1, 0, 1, 0, 0, 0), base, -14, 13, 0, 0, 0, 14, 0, 14);

        if (side != null) {
            // Four sheets, one per wall, each showing the plain side a pot has when no sherd
            // decorates it.
            int north = 1 << Model.NORTH;
            double half = Math.PI / 2;
            Mesh.cuboid(quads, part(root, 1, 16, 15, Math.PI, 0, 0), side, 1, 0, 0, 0, 0, 14, 16, 0, 0, false, north, Mesh.WHITE);
            Mesh.cuboid(quads, part(root, 15, 16, 1, 0, 0, Math.PI), side, 1, 0, 0, 0, 0, 14, 16, 0, 0, false, north, Mesh.WHITE);
            Mesh.cuboid(quads, part(root, 1, 16, 1, 0, -half, Math.PI), side, 1, 0, 0, 0, 0, 14, 16, 0, 0, false, north, Mesh.WHITE);
            Mesh.cuboid(quads, part(root, 15, 16, 15, 0, half, Math.PI), side, 1, 0, 0, 0, 0, 14, 16, 0, 0, false, north, Mesh.WHITE);
        }
        return new Built(quads, new Affine());
    }

    // **************************************************************************
    // *                         Copper golem statue                            *
    // **************************************************************************

    /**
     * The statue in the pose it has as an item: standing.
     *
     * <p>The golem is modelled feet at the origin and, as a statue, turned head down around it.
     * The item's own pose turns it over again, which is why that pose carries a half turn
     * that would otherwise make no sense.</p>
     */
    private static Built copperGolem(Texture texture) {
        if (texture == null) return null;
        List<Quad> quads = new ArrayList<>();
        Affine root = sixteenths().rotateZ(Math.PI);

        Affine body = part(root, 0, -5, 0, 0, 0, 0);
        box(quads, body, texture, 0, 15, -4, -6, -3, 8, 6, 6);

        Affine head = part(body, 0, -6, 0, 0, 0, 0);
        Mesh.cuboid(quads, head, texture, 0, 0, -4, -5, -5, 8, 5, 10, 0.015, false, Mesh.ALL_SIDES, Mesh.WHITE);
        box(quads, head, texture, 56, 0, -1, -2, -6, 2, 3, 2);
        Mesh.cuboid(quads, head, texture, 37, 8, -1, -9, -1, 2, 4, 2, -0.015, false, Mesh.ALL_SIDES, Mesh.WHITE);
        Mesh.cuboid(quads, head, texture, 37, 0, -2, -13, -2, 4, 4, 4, -0.015, false, Mesh.ALL_SIDES, Mesh.WHITE);

        box(quads, part(body, -4, -6, 0, 0, 0, 0), texture, 36, 16, -3, -1, -2, 3, 10, 4);
        box(quads, part(body, 4, -6, 0, 0, 0, 0), texture, 50, 16, 0, -1, -2, 3, 10, 4);
        box(quads, part(root, 0, -5, 0, 0, 0, 0), texture, 0, 27, -4, 0, -2, 4, 5, 4);
        box(quads, part(root, 0, -5, 0, 0, 0, 0), texture, 16, 27, 0, 0, -2, 4, 5, 4);

        Affine placement = new Affine().translate(0.5, 1.5, 0.5).scale(1, -1, -1);
        return new Built(quads, placement);
    }
}
