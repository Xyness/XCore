package fr.xyness.XCore.Web.Icons;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * What Minecraft 1.21.1 to 1.21.3 knew about items in code rather than in files.
 *
 * <p>Before item definitions existed, an item was its model and nothing else: which colour a
 * grass block or a leather cap is tinted, and which items are drawn as a chest or a banner, was
 * decided in the game's own classes. Those decisions are copied here, for the servers that still
 * run those versions.</p>
 *
 * @since 2.1
 */
final class LegacyItems {

    private LegacyItems() {
    }

    /** Works out the colour of grass, which is read from one of the game's files. */
    @FunctionalInterface
    interface Grass {
        int colour() throws IOException;
    }

    private static final int[] NONE = {};

    // Foliage has fixed colours wherever there is no biome to take one from.
    private static final int FOLIAGE = 0xFF48B518;
    private static final int SPRUCE = 0xFF619961;
    private static final int BIRCH = 0xFF80A755;
    private static final int MANGROVE = 0xFF92C648;
    private static final int LILY_PAD = 0xFF71C35C;

    /** Leather as it comes off the workbench, before any dye. */
    private static final int LEATHER = 0xFFA06540;

    /** A potion with nothing brewed into it: water. */
    private static final int WATER = 0xFF385DC6;

    /** A firework star with no colour set. */
    private static final int FIREWORK = 0xFF8A8A8A;

    /** The markings of a map nobody has coloured. */
    private static final int MAP = 0xFF46402E;

    /** Hides the layer it is given to: the game tints an undyed wolf armor's dye layer away. */
    private static final int HIDDEN = 0x00FFFFFF;

    /**
     * The two colours of each spawn egg — shell, then spots — by the mob's name. Up to 1.21.4
     * every egg was the same grey pair of textures; 1.21.5 drew each its own.
     */
    private static final Map<String, int[]> EGGS = new HashMap<>();

    static {
        String table = "allay=daff,adff;armadillo=ad716d,824848;axolotl=fbc1e3,a62d74;bat=4c3e30,f0f0f;"
                + "bee=edc343,43241b;blaze=f6b201,fff87e;bogged=8a9c72,314d1b;breeze=af94df,9166df;"
                + "camel=fcc369,cb9337;cat=efc88e,957256;cave_spider=c424e,a80e0e;chicken=a1a1a1,ff0000;"
                + "cod=c1a76a,e5c48b;cow=443626,a1a1a1;creaking=5f5f5f,fc7812;creeper=da70b,0;"
                + "dolphin=223b4d,f9f9f9;donkey=534539,867566;drowned=8ff1d7,799c65;"
                + "elder_guardian=ceccba,747693;ender_dragon=1c1c1c,e079fa;enderman=161616,0;"
                + "endermite=161616,6e6e6e;evoker=959b9b,1e1c1a;fox=d5b69f,cc6920;frog=d07444,ffc77c;"
                + "ghast=f9f9f9,bcbcbc;glow_squid=95656,85f1bc;goat=a5947c,55493e;guardian=5a8272,f17d30;"
                + "hoglin=c66e55,5f6464;horse=c09e7d,eee500;husk=797061,e6cc94;iron_golem=dbcdc2,74a332;"
                + "llama=c09e7d,995f40;magma_cube=340000,fcfc00;mooshroom=a00f10,b7b7b7;mule=1b0200,51331d;"
                + "ocelot=efde7d,564434;panda=e7e7e7,1b1b22;parrot=da70b,ff0000;phantom=43518a,88ff00;"
                + "pig=f0a5a2,db635f;piglin_brute=592a10,f9f3a4;piglin=995f40,f9f3a4;pillager=532f36,959b9b;"
                + "polar_bear=eeeede,d5d6cd;pufferfish=f6b201,37c3f2;rabbit=995f40,734831;"
                + "ravager=757470,5b5049;salmon=a00f10,e8474;sheep=e7e7e7,ffb5b5;shulker=946794,4d3852;"
                + "silverfish=6e6e6e,303030;skeleton_horse=68684f,e5e5d8;skeleton=c1c1c1,494949;"
                + "slime=51a03e,7ebf6e;sniffer=871e09,25ab70;snow_golem=d9f2f2,81a4a4;spider=342d27,a80e0e;"
                + "squid=223b4d,708899;stray=617677,ddeaea;strider=9c3436,4d494d;tadpole=6d533d,160a00;"
                + "trader_llama=eaa430,456296;tropical_fish=ef6915,fff9ef;turtle=e7e7e7,afaf;"
                + "vex=7a90a4,e8edf1;villager=563c33,bd8b72;vindicator=959b9b,275e61;"
                + "wandering_trader=456296,eaa430;warden=f4649,39d6e0;witch=340000,51a03e;"
                + "wither_skeleton=141414,474d4d;wither=141414,4d72a0;wolf=d7d3d3,ceaf96;"
                + "zoglin=c66e55,e6e6e6;zombie_horse=315234,97c284;zombie=afaf,799c65;"
                + "zombie_villager=563c33,799c65;zombified_piglin=ea9393,4c7129";
        for (String entry : table.split(";")) {
            int equals = entry.indexOf('=');
            int comma = entry.indexOf(',');
            EGGS.put(entry.substring(0, equals), new int[] {
                0xFF000000 | Integer.parseInt(entry.substring(equals + 1, comma), 16),
                0xFF000000 | Integer.parseInt(entry.substring(comma + 1), 16),
            });
        }
    }

    /**
     * The tints the game gave an item, by tint index.
     *
     * @param id    The item.
     * @param grass The colour of grass, asked for only by the few items that take it.
     * @return The tints; empty for the great majority of items, which have none.
     */
    static int[] tints(String id, Grass grass) throws IOException {
        switch (id) {
            case "grass_block", "short_grass", "grass", "fern", "tall_grass", "large_fern":
                return new int[] {grass.colour()};
            case "oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "vine":
                return new int[] {FOLIAGE};
            case "spruce_leaves":
                return new int[] {SPRUCE};
            case "birch_leaves":
                return new int[] {BIRCH};
            case "mangrove_leaves":
                return new int[] {MANGROVE};
            case "lily_pad":
                return new int[] {LILY_PAD};
            case "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots", "leather_horse_armor":
                return new int[] {LEATHER};
            case "wolf_armor":
                return new int[] {Mesh.WHITE, HIDDEN};
            case "potion", "splash_potion", "lingering_potion", "tipped_arrow":
                return new int[] {WATER};
            case "firework_star":
                return new int[] {Mesh.WHITE, FIREWORK};
            case "filled_map":
                return new int[] {Mesh.WHITE, MAP};
            default:
                break;
        }
        if (id.endsWith("_spawn_egg")) {
            int[] egg = EGGS.get(id.substring(0, id.length() - "_spawn_egg".length()));
            if (egg != null) return egg;
        }
        return NONE;
    }

    private static final String[] DYES = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    };

    /**
     * What an item whose model says only "drawn in code" was drawn as, in the words a newer
     * item definition would use.
     *
     * @return The special model, or {@code null} for an item that never was one.
     */
    static JsonObject special(String id) {
        switch (id) {
            case "chest":
                return of("chest", "texture", "normal");
            case "trapped_chest":
                return of("chest", "texture", "trapped");
            case "ender_chest":
                return of("chest", "texture", "ender");
            case "shulker_box":
                return of("shulker_box", "texture", "shulker");
            case "shield":
                return of("shield", null, null);
            case "conduit":
                return of("conduit", null, null);
            case "decorated_pot":
                return of("decorated_pot", null, null);
            case "skeleton_skull":
                return of("head", "kind", "skeleton");
            case "wither_skeleton_skull":
                return of("head", "kind", "wither_skeleton");
            case "zombie_head":
                return of("head", "kind", "zombie");
            case "creeper_head":
                return of("head", "kind", "creeper");
            case "piglin_head":
                return of("head", "kind", "piglin");
            case "dragon_head":
                return of("head", "kind", "dragon");
            case "player_head":
                return of("head", "kind", "player");
            default:
                break;
        }
        for (String dye : DYES) {
            if (id.equals(dye + "_bed")) return of("bed", "texture", dye);
            if (id.equals(dye + "_banner")) return of("banner", "color", dye);
            if (id.equals(dye + "_shulker_box")) return of("shulker_box", "texture", "shulker_" + dye);
        }
        return null;
    }

    private static JsonObject of(String type, String key, String value) {
        JsonObject spec = new JsonObject();
        spec.addProperty("type", type);
        if (key != null) spec.addProperty(key, value);
        return spec;
    }
}
