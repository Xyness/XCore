package fr.xyness.XCore.Web.Icons;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads an item definition — {@code items/<id>.json}, since Minecraft 1.21.4 — down to what the
 * inventory draws for a plain item.
 *
 * <p>A definition is a tree: a compass picks a needle by where it points, a bow a frame by how far
 * it is drawn, a trident a flat sprite in a slot and a model in the hand. The tree is walked here
 * for one fixed situation — an item fresh from the creative menu, lying in an inventory slot, held
 * by nobody, with no custom data — and what is left is a short list of models to draw.</p>
 *
 * @since 2.1
 */
final class ItemDefinition {

    private ItemDefinition() {
    }

    /** One model the item is drawn from. An item is usually one of these; a bed is two. */
    static final class Layer {

        /** The model's name without namespace, or {@code null} for a special model. */
        final String model;

        /** The tint sources as the definition gives them, or {@code null}. */
        final JsonArray tints;

        /** What a special model is — a chest, a banner — or {@code null} for an ordinary one. */
        final JsonObject special;

        /** The ordinary model a special one borrows its pose from, or {@code null}. */
        final String base;

        /**
         * Where the definition moves the model within its block before posing it, or
         * {@code null} when it says nothing — which, for a special model, means a version that
         * still left that to the renderer.
         */
        final Affine placement;

        private Layer(String model, JsonArray tints, JsonObject special, String base, Affine placement) {
            this.model = model;
            this.tints = tints;
            this.special = special;
            this.base = base;
            this.placement = placement;
        }
    }

    /**
     * The models a plain item is drawn from in a slot, in drawing order.
     *
     * @param definition The contents of {@code items/<id>.json}.
     * @return The layers; empty when the definition draws nothing.
     * @throws RuntimeException When the file does not have the shape of a definition.
     */
    static List<Layer> layers(JsonObject definition) {
        List<Layer> out = new ArrayList<>();
        walk(definition.get("model"), null, out, 0);
        return out;
    }

    /** The most layers one definition may pile up: the game's own use two or three. */
    static final int MAX_LAYERS = 32;

    private static void walk(JsonElement element, Affine above, List<Layer> out, int depth) {
        // Sixty-four levels is far past anything real; a file that nests deeper is not followed.
        if (element == null || !element.isJsonObject() || depth > 64 || out.size() >= MAX_LAYERS) return;
        JsonObject node = element.getAsJsonObject();
        Affine placement = placement(node, above);

        switch (bare(string(node, "type"))) {
            case "model" -> {
                String model = Model.stripNamespace(string(node, "model"));
                if (model == null) return;
                JsonArray tints = node.has("tints") && node.get("tints").isJsonArray() ? node.getAsJsonArray("tints") : null;
                out.add(new Layer(model, tints, null, null, placement));
            }
            case "special" -> {
                if (!node.has("model") || !node.get("model").isJsonObject()) return;
                out.add(new Layer(null, null, node.getAsJsonObject("model"),
                        Model.stripNamespace(string(node, "base")), placement));
            }
            case "composite" -> {
                if (!node.has("models") || !node.get("models").isJsonArray()) return;
                for (JsonElement part : node.getAsJsonArray("models")) walk(part, placement, out, depth + 1);
            }
            // Every condition the game knows asks about something a fresh item lacks: it is not
            // broken, damaged, in use or selected, a rod is not cast, a bundle has nothing picked,
            // and it carries no component beyond its own. The answer is always no.
            case "condition" -> walk(node.get("on_false"), placement, out, depth + 1);
            case "select" -> walk(selected(node), placement, out, depth + 1);
            case "range_dispatch" -> walk(dispatched(node), placement, out, depth + 1);
            // Nothing to draw: an empty model, or the item a bundle has selected, which a fresh
            // bundle has not.
            case "empty", "bundle/selected_item" -> { }
            default -> {
                // A kind of node added after this was written. Whatever it decides on, the branch
                // it falls back to is the best guess at what a plain item gets.
                if (node.has("fallback")) walk(node.get("fallback"), placement, out, depth + 1);
                else if (node.has("on_false")) walk(node.get("on_false"), placement, out, depth + 1);
            }
        }
    }

    /**
     * The branch of a {@code select} a plain item takes.
     *
     * <p>Only what is true of every item in a slot has a value. A trim material, a block state or
     * custom data is something a fresh item does not have, and the date — which turns chests into
     * presents over Christmas — is left out on purpose: an icon should not change with the day
     * it happened to be drawn on.</p>
     */
    private static JsonElement selected(JsonObject node) {
        String value = switch (bare(string(node, "property"))) {
            case "display_context" -> "gui";
            case "main_hand" -> "right";
            case "charge_type" -> "none";
            case "context_dimension" -> "overworld";
            case "context_entity_type" -> "player";
            default -> null;
        };
        if (value != null && node.has("cases") && node.get("cases").isJsonArray()) {
            for (JsonElement entry : node.getAsJsonArray("cases")) {
                if (!entry.isJsonObject()) continue;
                JsonObject option = entry.getAsJsonObject();
                JsonElement when = option.get("when");
                if (when == null) continue;
                if (when.isJsonArray()) {
                    for (JsonElement one : when.getAsJsonArray()) {
                        if (matches(one, value)) return option.get("model");
                    }
                } else if (matches(when, value)) {
                    return option.get("model");
                }
            }
        }
        return node.get("fallback");
    }

    private static boolean matches(JsonElement when, String value) {
        return when.isJsonPrimitive() && value.equals(bare(when.getAsString()));
    }

    /**
     * The entry of a {@code range_dispatch} a plain item takes: the one for zero.
     *
     * <p>Everything these measure starts there — damage, how far a bow is drawn, how full a
     * bundle is. For the two that have no rest position it is also the frame their plain model
     * always was, before definitions took over: a compass with its needle straight ahead and a
     * clock at noon, rather than whatever a needle spinning for want of a world happens to show.</p>
     */
    private static JsonElement dispatched(JsonObject node) {
        JsonElement best = null;
        double highest = Double.NEGATIVE_INFINITY;
        if (node.has("entries") && node.get("entries").isJsonArray()) {
            for (JsonElement entry : node.getAsJsonArray("entries")) {
                if (!entry.isJsonObject()) continue;
                JsonObject option = entry.getAsJsonObject();
                double threshold = option.has("threshold") ? option.get("threshold").getAsDouble() : 0;
                // The game takes the last entry whose threshold has been reached.
                if (threshold <= 0 && threshold >= highest) {
                    highest = threshold;
                    best = option.get("model");
                }
            }
        }
        return best != null ? best : node.get("fallback");
    }

    /**
     * What a node's {@code transformation} adds to the ones above it.
     *
     * <p>Newer versions say in the definition where a model sits in its block — a bed's foot one
     * block behind its head, a skull turned the right way up — where older ones had it built into
     * the renderer. It applies to everything below the node that carries it.</p>
     */
    private static Affine placement(JsonObject node, Affine above) {
        JsonElement written = node.get("transformation");
        if (written == null) return above;
        Affine own;
        try {
            own = transformation(written);
        } catch (RuntimeException malformed) {
            return above;
        }
        if (own == null) return above;
        return above == null ? own : new Affine(above).multiply(own);
    }

    /** Reads a transformation in either of the two forms the game accepts. */
    private static Affine transformation(JsonElement written) {
        if (written.isJsonArray()) {
            // A whole matrix, row by row.
            JsonArray matrix = written.getAsJsonArray();
            if (matrix.size() != 16) return null;
            double[] rows = new double[12];
            for (int i = 0; i < 12; i++) rows[i] = matrix.get(i).getAsDouble();
            return Affine.ofRows(rows);
        }
        if (!written.isJsonObject()) return null;
        JsonObject parts = written.getAsJsonObject();
        double[] translation = Model.vector(parts.get("translation"), new double[3]);
        double[] scale = Model.vector(parts.get("scale"), new double[] {1, 1, 1});
        Affine out = new Affine().translate(translation[0], translation[1], translation[2]);
        rotate(out, parts.get("left_rotation"));
        out.scale(scale[0], scale[1], scale[2]);
        rotate(out, parts.get("right_rotation"));
        return out;
    }

    /** Applies a rotation written as a quaternion, or as an axis and an angle around it. */
    private static void rotate(Affine pose, JsonElement written) {
        if (written == null) return;
        if (written.isJsonArray() && written.getAsJsonArray().size() == 4) {
            JsonArray q = written.getAsJsonArray();
            pose.rotate(q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(), q.get(3).getAsDouble());
        } else if (written.isJsonObject()) {
            JsonObject turn = written.getAsJsonObject();
            double[] axis = Model.vector(turn.get("axis"), new double[] {0, 1, 0});
            double length = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            if (!(length > 1e-12) || !turn.has("angle")) return;
            double half = turn.get("angle").getAsDouble() / 2;
            double sin = Math.sin(half) / length;
            pose.rotate(axis[0] * sin, axis[1] * sin, axis[2] * sin, Math.cos(half));
        }
    }

    /** A text field of a node, or the empty string. */
    static String string(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** A name without the {@code minecraft:} the game may or may not write in front of it. */
    static String bare(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }
}
