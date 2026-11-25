package fr.xyness.XCore.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * What a merge may and may not write into a file an administrator owns.
 */
class ConfigMergerTest {

    private static final String DEFAULTS = """
            # Top of the file
            debug: false

            # ============================
            #  Stacker
            # ============================
            stacker:
              enabled: true
              # How many
              max: 50
              # Never stacked
              excluded:
                - VILLAGER
                - WARDEN
            cleanup:
              enabled: true
              types:
                - ITEM
              # New with this version
              sounds:
                enabled: true
                silent:
                  - 90
            limits:
              blocks:
                HOPPER: 24
                TNT: 20
            shops:
              food:
                price: 5
                items:
                  - BREAD
              tools:
                price: 20
            """;

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void aFileWrittenFromNothingGetsItsLists() throws Exception {
        YamlConfiguration disk = new YamlConfiguration();
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS), "limits.blocks");

        assertEquals(List.of("VILLAGER", "WARDEN"), disk.getStringList("stacker.excluded"));
        assertEquals(List.of("ITEM"), disk.getStringList("cleanup.types"));
        assertEquals(List.of(90), disk.getIntegerList("cleanup.sounds.silent"));
        assertEquals(50, disk.getInt("stacker.max"));
    }

    @Test
    void aSectionThatComesBackBringsItsLists() throws Exception {
        YamlConfiguration disk = yaml("debug: true\ncleanup:\n  enabled: false\n  types: []\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertEquals(List.of("VILLAGER", "WARDEN"), disk.getStringList("stacker.excluded"));
        assertTrue(disk.getBoolean("debug"), "an existing value is never touched");
        assertFalse(disk.getBoolean("cleanup.enabled"), "an existing value is never touched");
    }

    @Test
    void aListDeletedFromASectionStaysDeleted() throws Exception {
        YamlConfiguration disk = yaml("stacker:\n  enabled: true\n  max: 10\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertFalse(disk.contains("stacker.excluded"), "the administrator removed that list");
        assertEquals(10, disk.getInt("stacker.max"));
    }

    @Test
    void anEmptiedListStaysEmpty() throws Exception {
        YamlConfiguration disk = yaml("stacker:\n  enabled: true\n  excluded: []\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertTrue(disk.contains("stacker.excluded"));
        assertTrue(disk.getStringList("stacker.excluded").isEmpty());
    }

    @Test
    void aNewSectionInsideAnOldOneGetsItsList() throws Exception {
        YamlConfiguration disk = yaml("cleanup:\n  enabled: true\n  types:\n    - ARROW\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertEquals(List.of(90), disk.getIntegerList("cleanup.sounds.silent"));
        assertTrue(disk.getBoolean("cleanup.sounds.enabled"));
        assertEquals(List.of("ARROW"), disk.getStringList("cleanup.types"));
    }

    @Test
    void protectedSectionsAreNeverWritten() throws Exception {
        YamlConfiguration disk = new YamlConfiguration();
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS), "limits.blocks");
        assertFalse(disk.contains("limits.blocks.HOPPER"));

        YamlConfiguration emptied = yaml("limits:\n  blocks: {}\n");
        ConfigMerger.addMissingKeys(emptied, yaml(DEFAULTS), "limits.blocks");
        assertFalse(emptied.contains("limits.blocks.HOPPER"));
    }

    @Test
    void aListingAlreadyOnDiskIsLeftAlone() throws Exception {
        YamlConfiguration disk = yaml("shops:\n  drinks:\n    price: 3\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertFalse(disk.contains("shops.food"), "an entry the administrator renamed must not come back");
        assertFalse(disk.contains("shops.tools"));
        assertEquals(3, disk.getInt("shops.drinks.price"));
    }

    @Test
    void aListingThatIsNewIsWrittenWhole() throws Exception {
        YamlConfiguration disk = yaml("debug: true\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));

        assertEquals(5, disk.getInt("shops.food.price"));
        assertEquals(List.of("BREAD"), disk.getStringList("shops.food.items"));
        assertEquals(20, disk.getInt("shops.tools.price"));
    }

    @Test
    void addedKeysBringTheirComments() throws Exception {
        YamlConfiguration disk = yaml("debug: true\ncleanup:\n  enabled: true\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));
        String written = disk.saveToString();

        assertTrue(written.contains("# How many"), "the comment of an added key");
        assertTrue(written.contains("#  Stacker"), "the heading of a section the merge created");
        assertTrue(written.contains("# New with this version"), "the comment of a new section inside an old one");
        assertTrue(written.contains("# Never stacked"));
    }

    @Test
    void aSecondPassChangesNothing() throws Exception {
        YamlConfiguration disk = yaml("debug: true\n");
        ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS));
        String once = disk.saveToString();

        YamlConfiguration again = yaml(once);
        assertTrue(ConfigMerger.addMissingKeys(again, yaml(DEFAULTS)).isEmpty());
        assertEquals(once, again.saveToString());
    }

    @Test
    void aSectionMadeByARenameGetsItsLists() throws Exception {
        // cleanup.sounds exists on disk only because a renamed key was just moved into it: the
        // administrator never saw the section, so never deleted its list.
        YamlConfiguration disk = yaml("""
                debug: false
                stacker:
                  enabled: true
                  max: 50
                  excluded: []
                cleanup:
                  enabled: true
                  types: []
                  sounds:
                    enabled: false
                """);
        java.util.Set<String> made = java.util.Set.of("cleanup.sounds");
        List<String> added = ConfigMerger.addMissingKeys(disk, yaml(DEFAULTS), made);
        assertTrue(added.contains("cleanup.sounds.silent"), added.toString());
        assertEquals(List.of(90), disk.getIntegerList("cleanup.sounds.silent"));
        assertFalse(disk.getBoolean("cleanup.sounds.enabled"), "the moved value is kept");

        // The same file without that knowledge: the section counts as theirs, the list stays out.
        YamlConfiguration plain = yaml("""
                debug: false
                stacker:
                  enabled: true
                  max: 50
                  excluded: []
                cleanup:
                  enabled: true
                  types: []
                  sounds:
                    enabled: false
                """);
        assertFalse(ConfigMerger.addMissingKeys(plain, yaml(DEFAULTS)).contains("cleanup.sounds.silent"));
    }

    @Test
    void sectionsOfListsEveryDepth() throws Exception {
        java.util.Set<String> sections = ConfigMerger.sectionsOf(yaml(DEFAULTS));
        assertTrue(sections.contains("stacker"));
        assertTrue(sections.contains("cleanup.sounds"));
        assertFalse(sections.contains("stacker.max"));
        assertTrue(ConfigMerger.sectionsOf(null).isEmpty());
    }
}
