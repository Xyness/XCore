package fr.xyness.XCore.Web.Icons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One model file of the game, as it is written: what it says itself, before anything is taken
 * from its parent.
 *
 * <p>Read once and never changed afterwards, so the same instance serves every item that
 * inherits from it and both threads that draw.</p>
 *
 * @since 2.1
 */
final class Model {

    /** The six faces of a box, in the order the game numbers them. */
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    private static final String[] FACE_NAMES = {"down", "up", "north", "south", "west", "east"};

    /** The model this one inherits from, without namespace, or {@code null}. */
    final String parent;

    /** The texture variables this file sets: a path, or {@code #other} for another variable. */
    final Map<String, String> textures;

    /** The boxes this file is made of, or {@code null} when it leaves them to its parent. */
    final List<Element> elements;

    /** The most elements a model may have to be drawn. */
    static final int MAX_ELEMENTS = 512;

    /** How the model is posed in an inventory slot, or {@code null} when left to the parent. */
    final Display gui;

    /** Whether the slot lights it from the side, as a block; {@code null} when left to the parent. */
    final Boolean sideLit;

    private Model(String parent, Map<String, String> textures, List<Element> elements, Display gui, Boolean sideLit) {
        this.parent = parent;
        this.textures = textures;
        this.elements = elements;
        this.gui = gui;
        this.sideLit = sideLit;
    }

    /**
     * Builds a model that only names a parent and its textures: what the game's own files for a
     * stair or a slab amount to, used to draw an item whose files are missing.
     */
    static Model childOf(String parent, Map<String, String> textures) {
        return new Model(parent, Map.copyOf(textures), null, null, null);
    }

    /**
     * Reads a model file.
     *
     * @throws RuntimeException When the file does not have the shape of a model; the caller treats
     *                          that as a model it does not have.
     */
    static Model parse(JsonObject json) {
        String parent = null;
        if (json.has("parent") && json.get("parent").isJsonPrimitive()) {
            parent = stripNamespace(json.get("parent").getAsString());
        }

        Map<String, String> textures = new HashMap<>();
        if (json.has("textures") && json.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    textures.put(entry.getKey(), value.getAsString());
                } else if (value.isJsonObject() && value.getAsJsonObject().has("sprite")
                        && value.getAsJsonObject().get("sprite").isJsonPrimitive()) {
                    // Since 26.x a texture can carry how it is drawn ({"sprite": ..., "force_translucent": true});
                    // the sprite is all an icon needs.
                    textures.put(entry.getKey(), value.getAsJsonObject().get("sprite").getAsString());
                }
            }
        }

        List<Element> elements = null;
        if (json.has("elements") && json.get("elements").isJsonArray()) {
            elements = new ArrayList<>();
            var list = json.getAsJsonArray("elements");
            // The game's most detailed models have a few dozen; a file with more is not drawn.
            if (list.size() > MAX_ELEMENTS) throw new IllegalArgumentException("too many elements: " + list.size());
            for (JsonElement element : list) {
                if (element.isJsonObject()) elements.add(Element.parse(element.getAsJsonObject()));
            }
        }

        Display gui = null;
        if (json.has("display") && json.get("display").isJsonObject()) {
            JsonObject display = json.getAsJsonObject("display");
            if (display.has("gui") && display.get("gui").isJsonObject()) {
                gui = Display.parse(display.getAsJsonObject("gui"));
            }
        }

        Boolean sideLit = null;
        if (json.has("gui_light") && json.get("gui_light").isJsonPrimitive()) {
            sideLit = !"front".equals(json.get("gui_light").getAsString());
        }

        return new Model(parent, Map.copyOf(textures), elements == null ? null : List.copyOf(elements), gui, sideLit);
    }

    /**
     * Drops the {@code minecraft:} in front of a name.
     *
     * @return The bare path, or {@code null} for a name from another namespace: the source only
     *         holds the game's own files.
     */
    static String stripNamespace(String name) {
        if (name == null) return null;
        int colon = name.indexOf(':');
        if (colon < 0) return name;
        return name.startsWith("minecraft:") ? name.substring(colon + 1) : null;
    }

    /** Three numbers, or the fallback when the file gives something else. */
    static double[] vector(JsonElement element, double[] fallback) {
        if (element == null || !element.isJsonArray()) return fallback;
        JsonArray array = element.getAsJsonArray();
        if (array.size() != fallback.length) return fallback;
        double[] out = new double[fallback.length];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsDouble();
        return out;
    }

    /**
     * The pose of a model in one of the places the game shows items; here, the inventory slot.
     */
    static final class Display {

        /** The pose of a model that declares none: drawn as it is, filling the slot. */
        static final Display NONE = new Display(new double[3], new double[3], new double[] {1, 1, 1});

        /** Degrees around x, y and z. */
        final double[] rotation;

        /** Sixteenths of the slot. */
        final double[] translation;

        final double[] scale;

        private Display(double[] rotation, double[] translation, double[] scale) {
            this.rotation = rotation;
            this.translation = translation;
            this.scale = scale;
        }

        static Display parse(JsonObject json) {
            double[] rotation = vector(json.get("rotation"), new double[3]);
            double[] translation = vector(json.get("translation"), new double[3]);
            double[] scale = vector(json.get("scale"), new double[] {1, 1, 1});
            // The game refuses to move a model out of sight or blow it up past four times.
            for (int i = 0; i < 3; i++) {
                translation[i] = Math.max(-80, Math.min(80, translation[i]));
                scale[i] = Math.max(-4, Math.min(4, scale[i]));
            }
            return new Display(rotation, translation, scale);
        }

        boolean isNone() {
            return rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0
                    && translation[0] == 0 && translation[1] == 0 && translation[2] == 0
                    && scale[0] == 1 && scale[1] == 1 && scale[2] == 1;
        }

        /**
         * The pose as a transform of the slot, in which a model's own sixteen-unit cube has
         * already been shrunk to one unit and centred on the origin.
         */
        Affine pose() {
            return new Affine()
                    .translate(translation[0] / 16, translation[1] / 16, translation[2] / 16)
                    .rotateX(Math.toRadians(rotation[0]))
                    .rotateY(Math.toRadians(rotation[1]))
                    .rotateZ(Math.toRadians(rotation[2]))
                    .scale(scale[0], scale[1], scale[2]);
        }
    }

    /** One box of a model. */
    static final class Element {

        /** Opposite corners, in sixteenths of a block. {@code from} may be past {@code to}. */
        final double[] from;
        final double[] to;

        /** How the box is turned, or {@code null}. */
        final Rotation rotation;

        /** The faces that are drawn, by direction; {@code null} where the box has none. */
        final Face[] faces;

        private Element(double[] from, double[] to, Rotation rotation, Face[] faces) {
            this.from = from;
            this.to = to;
            this.rotation = rotation;
            this.faces = faces;
        }

        static Element parse(JsonObject json) {
            double[] from = vector(json.get("from"), new double[3]);
            double[] to = vector(json.get("to"), new double[] {16, 16, 16});
            Rotation rotation = json.has("rotation") && json.get("rotation").isJsonObject()
                    ? Rotation.parse(json.getAsJsonObject("rotation")) : null;
            Face[] faces = new Face[6];
            if (json.has("faces") && json.get("faces").isJsonObject()) {
                JsonObject all = json.getAsJsonObject("faces");
                for (int i = 0; i < 6; i++) {
                    JsonElement face = all.get(FACE_NAMES[i]);
                    if (face != null && face.isJsonObject()) faces[i] = Face.parse(face.getAsJsonObject());
                }
            }
            return new Element(from, to, rotation, faces);
        }
    }

    /** A box turned around a point. */
    static final class Rotation {

        final double[] origin;

        /** Degrees around x, y and z, applied in that order. Older files only ever set one. */
        final double[] angles;

        /** Whether the box is stretched back to the size it had across the block once turned. */
        final boolean rescale;

        private Rotation(double[] origin, double[] angles, boolean rescale) {
            this.origin = origin;
            this.angles = angles;
            this.rescale = rescale;
        }

        static Rotation parse(JsonObject json) {
            double[] origin = vector(json.get("origin"), new double[3]);
            double[] angles = new double[3];
            if (json.has("axis") && json.has("angle")) {
                double angle = json.get("angle").getAsDouble();
                switch (json.get("axis").getAsString()) {
                    case "x" -> angles[0] = angle;
                    case "y" -> angles[1] = angle;
                    case "z" -> angles[2] = angle;
                    default -> { }
                }
            } else {
                // The form newer versions accept: an angle around each axis at once.
                if (json.has("x")) angles[0] = json.get("x").getAsDouble();
                if (json.has("y")) angles[1] = json.get("y").getAsDouble();
                if (json.has("z")) angles[2] = json.get("z").getAsDouble();
            }
            boolean rescale = json.has("rescale") && json.get("rescale").getAsBoolean();
            return new Rotation(origin, angles, rescale);
        }

        boolean isNone() {
            return angles[0] == 0 && angles[1] == 0 && angles[2] == 0;
        }
    }

    /** One face of a box. */
    static final class Face {

        /** The part of the texture shown, in sixteenths, or {@code null} to follow the box. */
        final double[] uv;

        /** The texture variable, as written: {@code #side}. */
        final String texture;

        /** Quarter turns of the texture on the face, in degrees. */
        final int rotation;

        /** Which of the item's tints colours the face, or {@code -1}. */
        final int tint;

        private Face(double[] uv, String texture, int rotation, int tint) {
            this.uv = uv;
            this.texture = texture;
            this.rotation = rotation;
            this.tint = tint;
        }

        static Face parse(JsonObject json) {
            // A face that gives no usable rectangle follows the box, as if it gave none.
            double[] uv = null;
            JsonElement rectangle = json.get("uv");
            if (rectangle != null && rectangle.isJsonArray() && rectangle.getAsJsonArray().size() == 4) {
                uv = vector(rectangle, new double[4]);
            }
            String texture = json.has("texture") && json.get("texture").isJsonPrimitive()
                    ? json.get("texture").getAsString() : null;
            int rotation = json.has("rotation") ? json.get("rotation").getAsInt() : 0;
            rotation = ((rotation % 360) + 360) % 360;
            int tint = json.has("tintindex") ? json.get("tintindex").getAsInt() : -1;
            return new Face(uv, texture, rotation, tint);
        }
    }
}
