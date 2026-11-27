package fr.xyness.XCore.Web.Icons;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Draws an item's icon the way the inventory shows it.
 *
 * <p>A third of the game's items have no texture of their own name to show: a stair, a slab, a
 * wall, a chest or a bed is a shape, described by a model that borrows the textures of the block
 * it is made of. The game turns that model a quarter round, tilts it and lights it from the side;
 * this does the same, from the same files — the item's definition, its model and its parents, and
 * the textures they name — so that the dashboard shows what a player sees in a slot.</p>
 *
 * <p>Nothing here touches the disk or the network: every file comes through the
 * {@link AssetSource}, and the caller is the one that caches them. What is kept in memory is
 * bounded — the models and textures many items share, the least recently used dropped first.
 * Safe to call from several threads at once.</p>
 *
 * @since 2.1
 */
public final class IconRenderer {

    /** The width and height of every icon drawn, in pixels. */
    public static final int SIZE = 64;

    /**
     * The renderer's revision, in the name of the folder its icons are kept in on disk. Raised
     * whenever a change draws an icon differently, so the icons drawn before are drawn again.
     */
    public static final int REVISION = 1;

    /** How many parsed models are kept: the parents — a cube, a stair, a flat item — serve hundreds of items each. */
    private static final int MODEL_CACHE_SIZE = 512;

    /** How many decoded textures are kept: planks and stone are drawn as a block, a stair, a slab and a wall. */
    private static final int TEXTURE_CACHE_SIZE = 256;

    /** Stands in the caches for a file the source does not have, so that it is asked once. */
    private static final Object MISSING = new Object();

    private static final int[] NO_TINTS = {};

    /** The colour of grass where the game's own colour map cannot be read: a temperate, wet place. */
    private static final int DEFAULT_GRASS = 0xFF7CBD6B;

    /**
     * A plain block: one texture on six faces, posed as the game poses every block in a slot.
     * Used when an item has a texture to show but no model saying what shape it is.
     */
    private static final Model CUBE = Model.parse(JsonParser.parseString(
            "{\"display\":{\"gui\":{\"rotation\":[30,225,0],\"scale\":[0.625,0.625,0.625]}},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
            + "\"down\":{\"texture\":\"#all\"},\"up\":{\"texture\":\"#all\"},"
            + "\"north\":{\"texture\":\"#all\"},\"south\":{\"texture\":\"#all\"},"
            + "\"west\":{\"texture\":\"#all\"},\"east\":{\"texture\":\"#all\"}}}]}").getAsJsonObject());

    private final AssetSource source;

    private final Map<String, Object> models = lru(MODEL_CACHE_SIZE);
    /** Access-ordered; trimmed by {@link #keep}, by count and by pixels together. */
    private final Map<String, Object> textures = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true));

    /** The pixels of the textures kept, and the most they may add up to: sixteen megabytes. */
    private long texturePixels;
    private static final long TEXTURE_PIXEL_BUDGET = 4L << 20;

    /**
     * @param source Where models and textures are read.
     */
    public IconRenderer(AssetSource source) {
        this.source = source;
    }

    /** A map that forgets what was used longest ago, safe to share between threads. */
    private static Map<String, Object> lru(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<String, Object>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > capacity;
            }
        });
    }

    /**
     * Draws one item.
     *
     * @param itemId The item's id without namespace, in lower case: {@code oak_stairs}.
     * @return A PNG of {@value #SIZE} by {@value #SIZE} pixels, or {@code null} when the item has
     *         nothing to draw from.
     * @throws IOException When the source could not be reached.
     */
    public byte[] render(String itemId) throws IOException {
        Drawn drawn = draw(itemId);
        return drawn == null ? null : png(drawn.image);
    }

    /** An icon, and which of the ways of finding one produced it. */
    static final class Drawn {

        final BufferedImage image;

        /** Where the picture came from, then what it is: {@code definition:3d}, {@code model:flat}, {@code fallback:material}. */
        final String how;

        Drawn(BufferedImage image, String how) {
            this.image = image;
            this.how = how;
        }
    }

    /**
     * Draws one item, trying in turn everything that can say what it looks like.
     *
     * @return The icon, or {@code null} when nothing could.
     */
    Drawn draw(String itemId) throws IOException {
        // The id ends up in a file path: only what an item is ever called goes further.
        if (itemId == null || !itemId.matches("[a-z0-9_]{1,96}")) return null;

        // The item's definition, on the versions that have one: it alone knows that a trident is
        // a flat sprite in a slot and that a red bed is a bed in red.
        JsonObject definition = json("items/" + itemId + ".json");
        if (definition != null) {
            Drawn drawn = null;
            try {
                drawn = paint(pieces(ItemDefinition.layers(definition)), "definition");
            } catch (RuntimeException malformed) {
                // A definition that cannot be followed is treated like one that is not there.
            }
            if (drawn != null) return drawn;
        }

        // The item's model, which was the whole story before definitions and still exists beside
        // most of them.
        if (model("item/" + itemId) != null) {
            Drawn drawn = null;
            try {
                Piece piece = modelPiece("item/" + itemId, LegacyItems.tints(itemId, () -> grass(0.5, 1.0)), null, itemId);
                if (piece != null) drawn = paint(List.of(piece), "model");
            } catch (RuntimeException malformed) {
                // As above.
            }
            if (drawn != null) return drawn;
        }

        // No model at all: whatever texture carries the item's name, or failing that its material's.
        Texture own = texture("item/" + itemId, true);
        if (own != null) {
            Drawn drawn = paint(List.of(Piece.flat(List.of(new Sprite(own, Mesh.WHITE)))), "fallback");
            if (drawn != null) return new Drawn(drawn.image, "fallback:item-texture");
        }
        Texture block = texture("block/" + itemId, true);
        if (block != null) {
            Drawn drawn = shaped(itemId, "block/" + itemId, block);
            if (drawn != null) return new Drawn(drawn.image, "fallback:block-texture");
        }
        for (String material : Materials.guesses(itemId)) {
            Texture texture = texture("block/" + material, true);
            if (texture == null) continue;
            Drawn drawn = shaped(itemId, "block/" + material, texture);
            if (drawn != null) return new Drawn(drawn.image, "fallback:material");
        }
        return null;
    }

    // **************************************************************************
    // *                       From layers to an image                          *
    // **************************************************************************

    /** One texture of a flat item and the colour it is multiplied by. */
    private static final class Sprite {

        final Texture texture;
        final int tint;

        Sprite(Texture texture, int tint) {
            this.texture = texture;
            this.tint = tint;
        }
    }

    /** One model of an item, ready to be drawn: its quads and where they stand in the slot. */
    private static final class Piece {

        final List<Quad> quads;
        final Affine pose;
        final boolean sideLit;

        /** The layers of a flat item that faces the eye squarely, or {@code null} for anything else. */
        final List<Sprite> sprites;

        /** Whether this is one of the models written out by hand rather than read from a file. */
        final boolean special;

        private Piece(List<Quad> quads, Affine pose, boolean sideLit, List<Sprite> sprites, boolean special) {
            this.quads = quads;
            this.pose = pose;
            this.sideLit = sideLit;
            this.sprites = sprites;
            this.special = special;
        }

        static Piece solid(List<Quad> quads, Affine pose, boolean sideLit, boolean special) {
            return new Piece(quads, pose, sideLit, null, special);
        }

        /**
         * A flat item as nearly all of them are: untouched by any pose, filling the slot. Such a
         * piece can be painted pixel for pixel without going through the renderer, and still
         * carries its quads for the rare item that mixes it with something that cannot.
         */
        static Piece flat(List<Sprite> sprites) {
            List<Quad> quads = new ArrayList<>();
            for (Sprite sprite : sprites) Mesh.sprite(sprite.texture, sprite.tint, quads);
            return new Piece(quads, centred(Model.Display.NONE, null), false, sprites, false);
        }
    }

    /**
     * What takes a model from its own block into the slot: the definition's placement within the
     * block if it gives one, the block centred on the slot, then the model's pose.
     */
    private static Affine centred(Model.Display gui, Affine placement) {
        Affine pose = gui.pose().translate(-0.5, -0.5, -0.5);
        return placement == null ? pose : pose.multiply(placement);
    }

    /** Makes each layer of a definition ready to draw; those that come to nothing are dropped. */
    private List<Piece> pieces(List<ItemDefinition.Layer> layers) throws IOException {
        List<Piece> pieces = new ArrayList<>();
        for (ItemDefinition.Layer layer : layers) {
            Piece piece = layer.special != null
                    ? specialPiece(layer.special, layer.base == null ? null : resolve(model(layer.base)), layer.placement)
                    : modelPiece(layer.model, tints(layer.tints), layer.placement, null);
            if (piece != null) pieces.add(piece);
        }
        return pieces;
    }

    /**
     * Paints pieces into an icon.
     *
     * @param via Which step found them, for {@link Drawn#how}.
     * @return The icon, or {@code null} when the pieces leave the slot empty.
     */
    /** The most faces one icon may have to be drawn: a game block has six per cube, a few hundred at most. */
    private static final int MAX_QUADS = 8192;

    private static Drawn paint(List<Piece> pieces, String via) {
        if (pieces.isEmpty()) return null;
        long faces = 0;
        for (Piece piece : pieces) faces += piece.quads == null ? 0 : piece.quads.size();
        // Work in proportion to the faces: a file with tens of thousands of them is not drawn.
        if (faces > MAX_QUADS) return null;
        boolean flat = true;
        boolean special = false;
        for (Piece piece : pieces) {
            flat &= piece.sprites != null;
            special |= piece.special;
        }

        if (flat) {
            List<Sprite> sprites = new ArrayList<>();
            for (Piece piece : pieces) sprites.addAll(piece.sprites);
            BufferedImage image = compose(sprites);
            return image == null ? null : new Drawn(image, via + ":flat");
        }

        Raster raster = new Raster(SIZE);
        for (Piece piece : pieces) {
            for (Quad quad : piece.quads) raster.draw(quad, piece.pose, piece.sideLit);
        }
        if (raster.isEmpty()) return null;
        return new Drawn(raster.image(), via + (special ? ":special" : ":3d"));
    }

    /**
     * Lays the layers of a flat item over one another, each pixel of the texture becoming a
     * square of the icon: no smoothing, as in the game.
     *
     * @return The icon, or {@code null} when every pixel came out transparent.
     */
    private static BufferedImage compose(List<Sprite> sprites) {
        int[] out = new int[SIZE * SIZE];
        boolean any = false;
        for (Sprite sprite : sprites) {
            Texture texture = sprite.texture;
            int tint = sprite.tint;
            int tintA = tint >>> 24, tintR = tint >> 16 & 0xFF, tintG = tint >> 8 & 0xFF, tintB = tint & 0xFF;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int texel = texture.at((x + 0.5) / SIZE, (y + 0.5) / SIZE);
                    int alpha = (texel >>> 24) * tintA / 255;
                    // The game drops what is under a tenth opaque rather than blend it.
                    if (alpha < 26) continue;
                    int r = (texel >> 16 & 0xFF) * tintR / 255;
                    int g = (texel >> 8 & 0xFF) * tintG / 255;
                    int b = (texel & 0xFF) * tintB / 255;
                    int under = out[y * SIZE + x];
                    int underA = under >>> 24;
                    if (alpha < 255 && underA > 0) {
                        // Colour over colour, each weighted by how much of it is there.
                        int keep = underA * (255 - alpha) / 255;
                        int total = alpha + keep;
                        r = (r * alpha + (under >> 16 & 0xFF) * keep) / total;
                        g = (g * alpha + (under >> 8 & 0xFF) * keep) / total;
                        b = (b * alpha + (under & 0xFF) * keep) / total;
                        alpha = total;
                    }
                    out[y * SIZE + x] = alpha << 24 | r << 16 | g << 8 | b;
                    any = true;
                }
            }
        }
        if (!any) return null;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIZE, SIZE, out, 0, SIZE);
        return image;
    }

    /** Encodes an icon as a PNG, in memory: ImageIO.write() would spool it through a temporary file. */
    private static byte[] png(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(image);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // **************************************************************************
    // *                         Models and their parts                         *
    // **************************************************************************

    /** A model with everything it inherits filled in. */
    private static final class Resolved {

        /** What stands at the very top of the chain of parents. */
        static final int PLAIN = 0, GENERATED = 1, ENTITY = 2;

        final Map<String, String> textures;
        final List<Model.Element> elements;
        final Model.Display gui;
        final boolean sideLit;
        final int root;

        Resolved(Map<String, String> textures, List<Model.Element> elements, Model.Display gui, boolean sideLit, int root) {
            this.textures = textures;
            this.elements = elements;
            this.gui = gui;
            this.sideLit = sideLit;
            this.root = root;
        }

        /**
         * The texture a variable stands for, following {@code #other} from one variable to the next.
         *
         * @param variable The variable, with or without its {@code #}.
         * @return The texture's path under {@code textures/}, or {@code null} when no model in the
         *         chain sets it.
         */
        String texture(String variable) {
            String key = variable.startsWith("#") ? variable.substring(1) : variable;
            for (int hops = 0; hops < 32; hops++) {
                String value = textures.get(key);
                if (value == null) return null;
                if (!value.startsWith("#")) return Model.stripNamespace(value);
                key = value.substring(1);
            }
            return null;
        }
    }

    /**
     * Walks up a model's parents, each level filling in only what the ones below left unsaid.
     *
     * @return The whole model, or {@code null} when there is none to start from.
     */
    private Resolved resolve(Model first) throws IOException {
        if (first == null) return null;
        Map<String, String> textures = new HashMap<>();
        List<Model.Element> elements = null;
        Model.Display gui = null;
        Boolean sideLit = null;
        int root = Resolved.PLAIN;

        Model current = first;
        for (int depth = 0; current != null && depth < 32; depth++) {
            for (Map.Entry<String, String> entry : current.textures.entrySet()) {
                textures.putIfAbsent(entry.getKey(), entry.getValue());
            }
            if (elements == null) elements = current.elements;
            if (gui == null) gui = current.gui;
            if (sideLit == null) sideLit = current.sideLit;

            String parent = current.parent;
            if (parent == null) break;
            if (parent.startsWith("builtin/")) {
                // Not files: the game's two ways of drawing an item without boxes.
                if (parent.equals("builtin/generated")) root = Resolved.GENERATED;
                else if (parent.equals("builtin/entity")) root = Resolved.ENTITY;
                break;
            }
            current = model(parent);
        }
        // An item is lit as a block unless one of its models says otherwise; flat items do.
        return new Resolved(textures, elements, gui == null ? Model.Display.NONE : gui, sideLit == null || sideLit, root);
    }

    /**
     * Makes an ordinary model ready to draw.
     *
     * @param name      The model, as {@code block/oak_stairs}.
     * @param tints     The item's tints, by index.
     * @param placement Where the definition moves the model within its block, or {@code null}.
     * @param legacyId  The item, when the model is the item's own on a version that drew chests
     *                  and banners from a model saying only "in code"; {@code null} otherwise.
     * @return The piece, or {@code null} when the model draws nothing.
     */
    private Piece modelPiece(String name, int[] tints, Affine placement, String legacyId) throws IOException {
        Resolved model = resolve(model(name));
        if (model == null) return null;

        if (model.root == Resolved.GENERATED) {
            // A flat item: its textures stacked, layer0 at the bottom, each tinted by its number.
            List<Sprite> sprites = new ArrayList<>();
            for (int layer = 0; layer < 16; layer++) {
                String path = model.texture("layer" + layer);
                if (path == null) break;
                int tint = layer < tints.length ? tints[layer] : Mesh.WHITE;
                Texture texture = texture(path, true);
                if (texture != null && tint >>> 24 != 0) sprites.add(new Sprite(texture, tint));
            }
            if (sprites.isEmpty()) return null;
            if (placement == null && model.gui.isNone()) return Piece.flat(sprites);
            List<Quad> quads = new ArrayList<>();
            for (Sprite sprite : sprites) Mesh.sprite(sprite.texture, sprite.tint, quads);
            return Piece.solid(quads, centred(model.gui, placement), model.sideLit, false);
        }

        if (model.root == Resolved.ENTITY) {
            JsonObject special = legacyId == null ? null : LegacyItems.special(legacyId);
            return specialPiece(special, model, placement);
        }

        if (model.elements == null || model.elements.isEmpty()) {
            // A model that poses and draws nothing. Since definitions exist that is all the model
            // of a chest or a banner is, the definition saying the rest; reached without one, the
            // item's name still tells which of them it is.
            JsonObject special = legacyId == null ? null : LegacyItems.special(legacyId);
            return special == null ? null : specialPiece(special, model, placement);
        }
        List<Quad> quads = new ArrayList<>();
        Mesh.boxes(model.elements, variable -> {
            String path = model.texture(variable);
            return path == null ? null : texture(path, true);
        }, tints, quads);
        if (quads.isEmpty()) return null;
        return Piece.solid(quads, centred(model.gui, placement), model.sideLit, false);
    }

    /**
     * Makes one of the items drawn in code ready to draw.
     *
     * @param special   What it is, as a definition says it; {@code null} when nothing does.
     * @param base      The model it takes its pose from, or {@code null}.
     * @param placement Where the definition puts it in its block, or {@code null} to use the place
     *                  the renderer of old gave it.
     * @return The piece. For a kind not known here, a stand-in from the base model's particle
     *         texture — the block it breaks into — rather than nothing; {@code null} only when
     *         there is not even that.
     */
    private Piece specialPiece(JsonObject special, Resolved base, Affine placement) throws IOException {
        Model.Display gui = base == null ? Model.Display.NONE : base.gui;
        boolean sideLit = base == null || base.sideLit;

        SpecialModels.Built built = null;
        if (special != null) {
            try {
                built = SpecialModels.build(special, path -> texture(path, false));
            } catch (RuntimeException malformed) {
                // Falls through to the stand-in.
            }
        }
        if (built != null && !built.quads.isEmpty()) {
            return Piece.solid(built.quads, centred(gui, placement != null ? placement : built.placement), sideLit, true);
        }

        String particle = base == null ? null : base.texture("particle");
        Texture texture = particle == null ? null : texture(particle, true);
        if (texture == null) return null;
        // An item's texture stands for itself; a block's is shown as the block.
        if (particle.startsWith("item/")) return Piece.flat(List.of(new Sprite(texture, Mesh.WHITE)));
        return cube(texture);
    }

    /** A plain block of one texture. */
    private Piece cube(Texture texture) throws IOException {
        Resolved model = resolve(CUBE);
        List<Quad> quads = new ArrayList<>();
        Mesh.boxes(model.elements, variable -> texture, NO_TINTS, quads);
        return Piece.solid(quads, centred(model.gui, null), true, false);
    }

    /**
     * Draws an item nothing describes from a texture alone, in the shape its name gives away.
     *
     * <p>Reached when the source has the material but not the item: a server one version ahead
     * of the mirror, whose new slab is made of a stone the mirror already knows. The game's own
     * template for that shape is used when there is one, a plain block otherwise — and a texture
     * with holes in it, a sapling's or a rail's, is shown flat, as the game shows those.</p>
     */
    private Drawn shaped(String itemId, String path, Texture texture) throws IOException {
        Materials.Shape shape = Materials.shape(itemId);
        if (shape != null && !shape.flat) {
            Map<String, String> variables = new HashMap<>();
            for (String variable : shape.variables) variables.put(variable, path);
            try {
                Resolved model = resolve(Model.childOf(shape.template, variables));
                if (model != null && model.elements != null && !model.elements.isEmpty()) {
                    List<Quad> quads = new ArrayList<>();
                    Mesh.boxes(model.elements, variable -> {
                        String found = model.texture(variable);
                        return found == null ? null : texture(found, true);
                    }, NO_TINTS, quads);
                    Drawn drawn = quads.isEmpty() ? null
                            : paint(List.of(Piece.solid(quads, centred(model.gui, null), model.sideLit, false)), "fallback");
                    if (drawn != null) return drawn;
                }
            } catch (RuntimeException malformed) {
                // The template is not usable: a plain block will do.
            }
        }
        boolean flat = shape != null ? shape.flat : !texture.isOpaque();
        Piece piece = flat ? Piece.flat(List.of(new Sprite(texture, Mesh.WHITE))) : cube(texture);
        return paint(List.of(piece), "fallback");
    }

    // **************************************************************************
    // *                                Tints                                   *
    // **************************************************************************

    /** The colours a definition's tint sources give a plain item, by index. */
    private int[] tints(JsonArray sources) throws IOException {
        if (sources == null || sources.size() == 0) return NO_TINTS;
        int[] out = new int[sources.size()];
        for (int i = 0; i < out.length; i++) out[i] = tint(sources.get(i));
        return out;
    }

    private int tint(JsonElement element) throws IOException {
        if (!element.isJsonObject()) return Mesh.WHITE;
        JsonObject tint = element.getAsJsonObject();
        return switch (ItemDefinition.bare(ItemDefinition.string(tint, "type"))) {
            case "constant" -> colour(tint.get("value"));
            case "grass" -> grass(number(tint, "temperature", 0.5), number(tint, "downfall", 1.0));
            // A dye, a potion, a firework, a map, a team, custom data: all colours an item is
            // given later. A fresh one shows the default its definition names.
            default -> colour(tint.get("default"));
        };
    }

    /** A colour written as one number, or as red, green and blue from 0 to 1. */
    private static int colour(JsonElement written) {
        try {
            if (written == null) return Mesh.WHITE;
            if (written.isJsonPrimitive()) return 0xFF000000 | written.getAsInt() & 0xFFFFFF;
            if (written.isJsonArray() && written.getAsJsonArray().size() >= 3) {
                JsonArray parts = written.getAsJsonArray();
                int colour = 0xFF;
                for (int i = 0; i < 3; i++) {
                    double part = Math.max(0, Math.min(1, parts.get(i).getAsDouble()));
                    colour = colour << 8 | (int) Math.floor(part * 255);
                }
                return colour;
            }
        } catch (RuntimeException unreadable) {
            // A colour that is not one leaves the texture as it is, rather than cost the whole icon.
        }
        return Mesh.WHITE;
    }

    private static double number(JsonObject node, String key, double fallback) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }

    /**
     * The colour of grass in a climate, read where the game reads it: a triangle of greens in
     * {@code textures/colormap/grass.png}, warm to the left, wet towards the top.
     */
    private int grass(double temperature, double downfall) throws IOException {
        temperature = Math.max(0, Math.min(1, temperature));
        downfall = Math.max(0, Math.min(1, downfall)) * temperature;
        Texture map = texture("colormap/grass", false);
        if (map == null || map.width != 256 || map.height != 256) return DEFAULT_GRASS;
        int x = (int) ((1 - temperature) * 255);
        int y = (int) ((1 - downfall) * 255);
        return 0xFF000000 | map.argb[y * 256 + x];
    }

    // **************************************************************************
    // *                          Reading the source                            *
    // **************************************************************************

    /**
     * A model file, parsed once and kept.
     *
     * @param name The model, as {@code block/stairs}; {@code null} for one outside the game's namespace.
     * @return The model, or {@code null} when the source has none by that name or it cannot be read as one.
     */
    private Model model(String name) throws IOException {
        if (name == null || !name.matches("[a-z0-9_./-]{1,120}") || name.contains("..")) return null;
        Object known = models.get(name);
        if (known != null) return known == MISSING ? null : (Model) known;
        Model model = null;
        JsonObject json = json("models/" + name + ".json");
        if (json != null) {
            try {
                model = Model.parse(json);
            } catch (RuntimeException malformed) {
                // Kept as missing: asking again would only fail again.
            }
        }
        models.put(name, model == null ? MISSING : model);
        return model;
    }

    /**
     * A texture, decoded once and kept.
     *
     * @param path       The texture under {@code textures/}, without extension: {@code block/stone}.
     * @param firstFrame Whether a tall strip is an animation; see {@link Texture#decode}.
     * @return The texture, or {@code null} when the source has none by that name.
     */
    private Texture texture(String path, boolean firstFrame) throws IOException {
        if (path == null || !path.matches("[a-z0-9_./-]{1,120}") || path.contains("..")) return null;
        // Kept apart by how they were cut, although no texture is ever wanted both ways.
        String key = firstFrame ? path : path + "#whole";
        Object known = textures.get(key);
        if (known != null) return known == MISSING ? null : (Texture) known;
        Texture texture = Texture.decode(source.read("textures/" + path + ".png"), firstFrame);
        keep(key, texture);
        return texture;
    }

    /**
     * Keeps a decoded texture, and lets the oldest go while the kept ones hold more pixels than
     * the budget: the count alone allowed hundreds of megabytes of large textures.
     */
    private void keep(String key, Texture texture) {
        synchronized (textures) {
            Object before = textures.put(key, texture == null ? MISSING : texture);
            if (before instanceof Texture old) texturePixels -= old.argb.length;
            if (texture != null) texturePixels += texture.argb.length;
            var oldest = textures.entrySet().iterator();
            while ((texturePixels > TEXTURE_PIXEL_BUDGET || textures.size() > TEXTURE_CACHE_SIZE) && oldest.hasNext()) {
                Object value = oldest.next().getValue();
                if (value == texture) continue;
                if (value instanceof Texture gone) texturePixels -= gone.argb.length;
                oldest.remove();
            }
        }
    }

    /** A JSON file of the source, or {@code null} when it has none by that name or it is not an object. */
    private JsonObject json(String path) throws IOException {
        byte[] bytes = source.read(path);
        if (bytes == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
