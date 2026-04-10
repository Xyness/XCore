package fr.xyness.XCore.Gui;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import fr.xyness.XCore.Utils.Logger;

/**
 * Loads GUI definitions from YAML files located in a plugin's {@code guis/} directory.
 * <p>
 * Each {@code .yml} file in the folder is parsed into a {@link GuiDefinition} and
 * keyed by the filename (without extension). The loader supports both the modern
 * section-based action format and the legacy colon-delimited format.
 * </p>
 */
public class GuiLoader {

    /**
     * Loads all GUI definitions from {@code .yml} files in the given folder.
     *
     * @param guisFolder The folder containing GUI YAML files.
     * @param logger     The logger used for warnings and debug output.
     * @return A map of GUI names to their parsed {@link GuiDefinition} objects.
     */
    public static Map<String, GuiDefinition> loadAll(File guisFolder, Logger logger) {
        Map<String, GuiDefinition> result = new HashMap<>();
        if (!guisFolder.isDirectory()) {
            logger.sendWarning("GUI folder does not exist: " + guisFolder.getPath());
            return result;
        }

        File[] files = guisFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            logger.sendDebug("No GUI definition files found in: " + guisFolder.getPath());
            return result;
        }

        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            GuiDefinition def = loadSingle(name, yaml, logger);
            if (def != null) {
                result.put(name, def);
                logger.sendDebug("Loaded GUI definition: " + name);
            }
        }

        logger.sendInfo("Loaded " + result.size() + " GUI definition(s).");
        return result;
    }

    /**
     * Parses a single GUI definition from a YAML configuration.
     *
     * @param name   The name of the GUI (derived from the file name).
     * @param yaml   The parsed YAML configuration.
     * @param logger The logger used for warnings.
     * @return The parsed {@link GuiDefinition}, or {@code null} if parsing fails.
     */
    private static GuiDefinition loadSingle(String name, YamlConfiguration yaml, Logger logger) {
        String title = yaml.getString("gui-title", name);
        int rows = yaml.getInt("rows", 6);
        List<Integer> slots = yaml.getIntegerList("slots");
        String slotsSound = yaml.getString("slots-sound", "");

        Map<Integer, GuiItem> items = new HashMap<>();
        ConfigurationSection itemsSection = yaml.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection itemSec = itemsSection.getConfigurationSection(itemKey);
                if (itemSec == null) continue;
                parseItem(itemKey, itemSec, items, logger);
            }
        }

        return new GuiDefinition(name, title, rows, items, slots, slotsSound);
    }

    /**
     * Parses a single item from its configuration section and adds it to the items map.
     * Supports multi-slot items (same item placed in multiple slots).
     *
     * @param itemKey The item key name.
     * @param section The configuration section for this item.
     * @param items   The items map to populate.
     * @param logger  The logger used for warnings.
     */
    private static void parseItem(String itemKey, ConfigurationSection section,
                                  Map<Integer, GuiItem> items, Logger logger) {
        // Parse slot(s)
        List<Integer> slotList;
        if (section.isList("slot")) {
            slotList = section.getIntegerList("slot");
        } else {
            slotList = List.of(section.getInt("slot", 0));
        }

        // Parse material and custom head
        String materialRaw = section.getString("material", "STONE");
        boolean customHead = false;
        String textures = "";
        if (materialRaw.startsWith("PLAYER_HEAD:")) {
            customHead = true;
            textures = materialRaw.substring("PLAYER_HEAD:".length());
            materialRaw = "PLAYER_HEAD";
        }

        int customModelData = section.getInt("custom_model_data_value", 0);
        String itemModelKey = section.getString("item_model_key", null);

        // Parse language keys
        String targetTitle = section.getString("target-title", "");
        String targetLore = section.getString("target-lore", "");
        String targetButtonOn = section.getString("target-button-on", null);
        String targetButtonOff = section.getString("target-button-off", null);

        // Parse permission and sound
        String permission = section.getString("permission", null);
        String sound = section.getString("sound", null);

        // Parse actions
        Map<ClickKind, List<GuiAction>> actions = new EnumMap<>(ClickKind.class);
        ConfigurationSection actionsSec = section.getConfigurationSection("actions");
        if (actionsSec != null) {
            for (String clickKey : actionsSec.getKeys(false)) {
                ClickKind kind = ClickKind.fromString(clickKey);
                List<GuiAction> actionList = new ArrayList<>();

                // List format: actions.left: [{type: close}, {type: msg, value: "hi"}]
                if (actionsSec.isList(clickKey)) {
                    List<Map<?, ?>> rawList = actionsSec.getMapList(clickKey);
                    for (Map<?, ?> entry : rawList) {
                        YamlConfiguration temp = new YamlConfiguration();
                        for (Map.Entry<?, ?> e : entry.entrySet()) {
                            temp.set(e.getKey().toString(), e.getValue());
                        }
                        GuiAction action = GuiActionFactory.fromSection(temp);
                        if (action != null) actionList.add(action);
                    }
                }

                // Section format: actions.left.0: {type: close}
                ConfigurationSection clickSec = actionsSec.getConfigurationSection(clickKey);
                if (clickSec != null) {
                    for (String subKey : clickSec.getKeys(false)) {
                        ConfigurationSection entrySec = clickSec.getConfigurationSection(subKey);
                        if (entrySec != null) {
                            GuiAction action = GuiActionFactory.fromSection(entrySec);
                            if (action != null) actionList.add(action);
                        }
                    }
                }

                if (!actionList.isEmpty()) actions.put(kind, actionList);
            }
        }

        // Legacy action support
        String legacyAction = section.getString("action", null);
        if (legacyAction != null && actions.isEmpty()) {
            GuiAction action = GuiActionFactory.fromLegacy(legacyAction);
            if (action != null) actions.put(ClickKind.LEFT, List.of(action));
        }

        // Create a GuiItem for each slot
        for (int slot : slotList) {
            GuiItem item = new GuiItem(itemKey, slot, materialRaw,
                customHead, textures, customModelData != 0, customModelData, itemModelKey,
                targetTitle, targetLore, targetButtonOn, targetButtonOff,
                permission, sound, actions);
            items.put(slot, item);
        }
    }
}
