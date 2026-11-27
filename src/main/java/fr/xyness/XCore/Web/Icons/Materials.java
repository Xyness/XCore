package fr.xyness.XCore.Web.Icons;

import java.util.ArrayList;
import java.util.List;

/**
 * Guesses, from an item's name alone, what it is made of and what shape it has.
 *
 * <p>The last resort, for an item the source has neither a definition nor a model for. The game
 * names its building blocks regularly enough — {@code oak_stairs} is made of {@code oak_planks},
 * {@code stone_brick_wall} of {@code stone_bricks} — that the name usually leads to a texture.</p>
 *
 * @since 2.1
 */
final class Materials {

    private Materials() {
    }

    /** A shape an item's name ends with, and the game's own model for it. */
    static final class Shape {

        final String suffix;

        /** The model every item of that shape inherits from, or {@code null} for a flat one. */
        final String template;

        /** The texture variables that model expects, all given the one material. */
        final String[] variables;

        /** Whether the inventory shows the shape as a flat picture of its material: a glass pane. */
        final boolean flat;

        private Shape(String suffix, String template, boolean flat, String... variables) {
            this.suffix = suffix;
            this.template = template;
            this.variables = variables;
            this.flat = flat;
        }
    }

    // Longer endings first where one contains another: a fence gate is not a fence.
    private static final Shape[] SHAPES = {
        new Shape("_stairs", "block/stairs", false, "bottom", "top", "side"),
        new Shape("_slab", "block/slab", false, "bottom", "top", "side"),
        new Shape("_wall", "block/wall_inventory", false, "wall"),
        new Shape("_fence_gate", "block/template_fence_gate", false, "texture"),
        new Shape("_fence", "block/fence_inventory", false, "texture"),
        new Shape("_pressure_plate", "block/pressure_plate_up", false, "texture"),
        new Shape("_button", "block/button_inventory", false, "texture"),
        new Shape("_carpet", "block/carpet", false, "wool"),
        new Shape("_trapdoor", "block/template_trapdoor_bottom", false, "texture"),
        new Shape("_pane", null, true),
    };

    /** The shape an item's name ends with, or {@code null} when it names none known here. */
    static Shape shape(String id) {
        for (Shape shape : SHAPES) {
            if (id.endsWith(shape.suffix)) return shape;
        }
        return null;
    }

    /**
     * The block textures an item is probably made of, most likely first.
     *
     * @param id The item.
     * @return Names under {@code textures/block/}; a handful at most, since each is a file to ask
     *         the source for.
     */
    static List<String> guesses(String id) {
        Shape shape = shape(id);
        String stem = shape == null ? id : id.substring(0, id.length() - shape.suffix.length());
        // Waxing changes nothing to look at.
        boolean waxed = stem.startsWith("waxed_");
        if (waxed) stem = stem.substring("waxed_".length());
        // A name that says nothing about shape says nothing about material either: only the
        // waxed twin of a block is worth a try.
        if (shape == null) return waxed ? List.of(stem) : List.of();

        List<String> out = new ArrayList<>();
        // The few whose material goes by a name of its own.
        switch (stem) {
            case "quartz" -> out.add("quartz_block_side");
            case "smooth_quartz" -> out.add("quartz_block_bottom");
            case "smooth_sandstone" -> out.add("sandstone_top");
            case "smooth_red_sandstone" -> out.add("red_sandstone_top");
            case "petrified_oak" -> out.add("oak_planks");
            case "light_weighted" -> out.add("gold_block");
            case "heavy_weighted" -> out.add("iron_block");
            default -> { }
        }
        if (shape.suffix.equals("_carpet")) out.add(stem + "_wool");
        out.add(stem);
        // brick -> bricks, tile -> tiles; then wood, which is planks; then what is only ever a block.
        out.add(stem + "s");
        out.add(stem + "_planks");
        out.add(stem + "_block");
        return out;
    }
}
