# XCore

Core framework for the X ecosystem of Minecraft plugins. Provides centralized database management, multi-layer caching, cross-server synchronization, GUI framework, language system, Vault integration, web dashboard, and an addon loading system.

## Features

- **3-Layer Cache**: Caffeine (L1 local) + Redis (L2 shared) + Database (L3 persistent)
- **Cross-Server Sync**: Redis Pub/Sub or database polling, multiplexed for all addons
- **Addon System**: Loaded from `plugins/XCore/addons/`, full lifecycle (load/enable/disable/reload)
- **Multi-Database**: MySQL, PostgreSQL, SQLite with HikariCP connection pooling
- **GUI Framework**: YAML-driven inventory management with custom model data, item models, sounds, actions, permissions, blink animations, pagination
- **Language System**: Per-addon MiniMessage lang files with automatic default merging
- **Web Dashboard**: Built-in HTTP server with REST API, module system for addons
- **Built-in Economy**: Multi-currency system with Vault provider, transactions, exchange, interest
- **Vault Integration**: Registers as Vault economy provider, shared by all addons
- **PlaceholderAPI**: Core placeholders + addon-specific expansions
- **Folia Compatible**: Full region threading support via SchedulerAdapter
- **Table & Query Builder**: Fluent API for database operations across MySQL/PostgreSQL/SQLite
- **Bedrock Detection**: Geyser/Floodgate support for Bedrock players

## Requirements

- Paper 1.21.1+
- Java 21+
- Vault (required for economy)
- PlaceholderAPI (optional)

## Official Addons

| Addon | Description |
|-------|-------------|
| XBans | Sanctions, IP security, alt detection, AI moderation, warden |
| XLogin | Login/register authentication, sessions, player protection |
| XEssentials | Utility commands, private messaging, ignore system |
| XChat | Chat channels, formatting, mentions, filters, anti-spam |
| XStats | Player statistics (21+ gameplay metrics) |
| XLevels | Level/XP progression system with prestige |
| XFriends | Friend system with messaging and blocking |
| XCosmetics | Cosmetics: particles, pets, capes, hats, balloons |
| XCrates | Loot crates with physical animations |
| XAuctionHouse | Auction house with search and tax system |
| XKits | Item kits with cooldowns |
| XWarps | Public teleportation points |
| XHomes | Player homes |
| XMailbox | Messaging and item sending |
| XMissions | Daily/weekly missions |
| XShops | Player shops |
| XGuis | Custom YAML-based menus |
| XRanks | Hierarchical rank system |
| XQuests | Quest system |
| XBackpack | Virtual backpack |
| XEnderchest | Enhanced ender chest with pages |

## Installation

1. Place `XCore.jar` in `plugins/`
2. Place addon JARs in `plugins/XCore/addons/`
3. Start the server
4. Configure `plugins/XCore/config.yml`
5. Each addon has its own config at `plugins/XCore/addons/<name>/config.yml`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/xcore` | XCore info (version, addons, database) | `xcore.admin` |
| `/xcore stats` | Cache hit rates, database statistics | `xcore.admin` |
| `/xcore addons` | List loaded addons with their state | `xcore.admin` |
| `/xcore reload` | Reload configuration and language | `xcore.admin` |
| `/xcore reload <addon>` | Reload a specific addon | `xcore.admin` |
| `/xcore clear-cache` | Invalidate all cache regions | `xcore.admin` |
| `/xcore player <name>` | Detailed player information | `xcore.admin` |

### Economy Commands (requires Vault)

| Command | Description | Permission |
|---------|-------------|------------|
| `/coins` | View all your balances | - |
| `/coins balance [player] [currency]` | View balance | - |
| `/coins pay <player> <amount> [currency]` | Send money | - |
| `/coins set <player> <amount> [currency]` | Set balance | `xcore.economy.admin` |
| `/coins add <player> <amount> [currency]` | Add balance | `xcore.economy.admin` |
| `/coins remove <player> <amount> [currency]` | Remove balance | `xcore.economy.admin` |
| `/coins exchange <from> <to> <amount>` | Exchange currencies | - |
| `/coins history [player] [currency] [page]` | Transaction history | - |
| `/coins top [currency]` | Top balances | - |
| `/coins reload` | Reload economy config | `xcore.economy.admin` |

## Configuration

```yaml
# Database type: sqlite, mysql, postgresql
database-type: sqlite

# MySQL / PostgreSQL settings
database:
  host: localhost
  port: 3306
  name: xcore
  username: root
  password: ""
  pool-size: 10

# Cross-server (multi-server network)
cross-server:
  enabled: false
  server-name: "default"
  redis:
    enabled: false
    host: localhost
    port: 6379
  sync:
    poll-interval-seconds: 3
    retention-seconds: 300

# Web dashboard
web-dashboard:
  enabled: false
  port: 8085
  token: "CHANGE_ME_TO_A_SECURE_TOKEN"
  metrics-public: true

# Economy (requires Vault, auto-enabled if Vault is present)
economy:
  enabled: true
  currencies:
    coins:
      symbol: "$"
      symbol-position: BEFORE
      decimals: 2
      starting-balance: 0.00
      max-balance: 1000000000
      vault: true    # Primary Vault currency
    gems:
      symbol: "✦"
      symbol-position: AFTER
      decimals: 0
      starting-balance: 0
      max-balance: 0
      vault: false
  exchange:
    enabled: true
    rates:
      coins-to-gems: 100
      gems-to-coins: 80
```

## Web API

All endpoints require `Authorization: Bearer <token>` (except `/api/metrics` if configured public).

| Endpoint | Description |
|----------|-------------|
| `GET /api/modules` | List registered web modules |
| `GET /api/metrics` | Server metrics (uptime, players, cache) |
| Addon modules | Each addon registers its own routes under `/api/<name>/` |

## PlaceholderAPI

| Placeholder | Description |
|-------------|-------------|
| `%xcore_name%` | Player name |
| `%xcore_uuid%` | Player server UUID |
| `%xcore_last_login%` | Last login timestamp |
| `%xcore_last_logout%` | Last logout timestamp |
| `%xcore_balance%` | Formatted Vault currency balance |
| `%xcore_balance_raw%` | Raw balance (no formatting) |
| `%xcore_balance_<id>%` | Formatted balance for specific currency |
| `%xcore_balance_<id>_raw%` | Raw balance for specific currency |

## Addon Development

### Dependency (Gradle)

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.xyness:XCore:1.0.1'
}
```

### addon.yml

```yaml
name: MyAddon
version: '1.0.0'
author: 'YourName'
main: com.example.myaddon.MyAddonAddon
description: What this addon does
depend: []
soft-depend: []
```

### Main Class

```java
public class MyAddon extends XAddon {

    @Override
    public boolean onEnable() {
        saveDefaultConfig();
        // Listeners, commands, tables...
        return true;
    }

    @Override
    public void onDisable() { }

    @Override
    public void onReload() { }
}
```

### Available APIs

| Method | Description |
|--------|-------------|
| `api()` | XCore API (database, cache, sync, GUI, vault, web) |
| `core()` | XCore JavaPlugin instance (for Bukkit registrations) |
| `scheduler()` | Folia-compatible SchedulerAdapter |
| `logger()` | Addon-scoped logger |
| `lang()` | Addon language namespace (MiniMessage) |
| `guiRegistry()` | GUI definition registry (loaded from YAML) |
| `getConfig()` | Addon config.yml |
| `getDataFolder()` | `plugins/XCore/addons/<name>/` |

### GUI Framework

XCore provides a YAML-driven GUI system supporting custom model data, item models, sounds, click actions, and per-item permissions. All addons use this shared framework.

#### GUI Definition YAML

Place YAML files in `src/main/resources/guis/`. Each file defines one GUI screen:

```yaml
gui-title: "gui-title-key"        # Lang key for the inventory title
rows: 6                            # Inventory rows (1-6)
slots: [0,1,2,...,44]              # Slots reserved for paginated content
slots-sound: "minecraft:ui.button.click"  # Default click sound for page slots

items:
  BackPage:
    slot: 48
    material: ARROW
    # material: PLAYER_HEAD:texture_hash    # Custom skull texture
    target-title: "previous-title"          # Lang key for display name
    target-lore: "previous-lore"            # Lang key for lore template
    target-button-on: "previous-button-on"  # Lang key for blink ON state
    target-button-off: "previous-button-off"
    permission: "myaddon.gui.navigate"      # Optional, blocks click if missing
    sound: "minecraft:ui.button.click"      # Per-item click sound
    custom_model_data_value: 0              # Resource pack custom model data
    item_model_key: "my_pack:my_item"       # 1.20.5+ item model key
    actions:                                # Click actions (optional)
      left:
        - "command:mycommand"
      right:
        - "message:<green>Hello!"
      shift_left:
        - "console:say {player} clicked"
  Back:
    slot: 49
    material: CHEST
    target-title: "back-title"
    target-lore: "back-lore"
    target-button-on: "back-button-on"
    target-button-off: "back-button-off"
    sound: "minecraft:ui.button.click"
  NextPage:
    slot: 50
    material: ARROW
    target-title: "next-title"
    target-lore: "next-lore"
    target-button-on: "next-button-on"
    target-button-off: "next-button-off"
    sound: "minecraft:ui.button.click"
```

#### Loading GUI Definitions

```java
// In onEnable()
File guisFolder = new File(getDataFolder(), "guis");
if (!guisFolder.exists()) {
    guisFolder.mkdirs();
    saveDefaultResource("guis/my_gui.yml");
}
guiRegistry().clearAndPutAll(GuiLoader.loadAll(guisFolder, logger()));

// In onReload()
File guisFolder = new File(getDataFolder(), "guis");
guiRegistry().clearAndPutAll(GuiLoader.loadAll(guisFolder, logger()));
```

#### Using GuiDefinition in GUI Classes

```java
import fr.xyness.XCore.Gui.*;

// Load definition
GuiDefinition def = addon.guiRegistry().get("my_gui");

// Create inventory from definition
Inventory inv = Bukkit.createInventory(holder, def.getRows() * 9,
    lang.getComponent(def.getTitleKey(), "page", "1", "max", "5"));

// Create nav button from GuiItem (applies custom model data, item model, item flags)
GuiItem itemDef = def.itemAt(48);
ItemStack navItem = guiUtils.createItemFromDef(itemDef, title, lore);

// Permission-aware button text
String perm = itemDef.getPermission();
boolean hasPerm = perm == null || perm.isBlank() || player.hasPermission(perm);
String btnOnKey = itemDef.getButtonOnKey() != null && !itemDef.getButtonOnKey().isBlank()
    ? itemDef.getButtonOnKey() : "default-button-on";
String btnOffKey = itemDef.getButtonOffKey() != null && !itemDef.getButtonOffKey().isBlank()
    ? itemDef.getButtonOffKey() : "default-button-off";
String btn = hasPerm
    ? (blinkState ? lang.getMessageString(btnOnKey) : lang.getMessageString(btnOffKey))
    : (blinkState ? lang.getMessageString("gui-btn-no-perm-on") : lang.getMessageString("gui-btn-no-perm-off"));
```

#### GuiUtils Methods

| Method | Description |
|--------|-------------|
| `createItem(Material, Component, List<Component>)` | Create item with name, lore, all ItemFlags |
| `createItemFromDef(GuiItem, Component, List<Component>)` | Create item from definition (handles custom head, custom model data, item model) |
| `createItemFromDef(GuiItem, Component, List<Component>, Player)` | Same with Bedrock player check for skull textures |
| `updateGuiItem(Inventory, slot, Component, List<Component>)` | Update existing item's name/lore in-place |
| `buildNavLore(LangNamespace, loreKey, offKey, onKey, check, replacements...)` | Build navigation button lore with blink state |
| `createPlayerHeadWithTexture(String, Component, List<Component>)` | Create player skull with custom skin texture |
| `setCustomModelDataSafe(ItemMeta, int)` | Apply custom model data (1.21.5+ API with fallback) |
| `setItemModelSafe(ItemMeta, String)` | Apply item model key (1.20.5+) |
| `playSound(Player, String)` | Play sound from namespaced key (e.g. `minecraft:ui.button.click`) |
| `noItalic(Component)` / `noItalic(List<Component>)` | Strip italic from components |

#### Handling Clicks in GuiListener

```java
import fr.xyness.XCore.Gui.*;

// Add to your listener — handles sound, actions, and permission in one call
private boolean handleCommonFeatures(Player player, int slot, ClickType click, GuiDefinition def) {
    GuiItem guiItem = def != null ? def.itemAt(slot) : null;

    // Play sound (per-item, or fall back to GUI-level default)
    if (guiItem != null && guiItem.getSound() != null && !guiItem.getSound().isBlank()) {
        GuiUtils.playSound(player, guiItem.getSound());
    } else if (def != null && def.getSound() != null && !def.getSound().isBlank()) {
        GuiUtils.playSound(player, def.getSound());
    }

    // Execute custom actions defined in YAML
    if (guiItem != null) {
        ClickKind clickKind = ClickKind.fromBukkit(click);
        List<GuiAction> actions = guiItem.getActions(clickKind);
        for (GuiAction action : actions) {
            action.execute(player);
        }
    }

    // Permission gate — return false to block further handling
    if (guiItem != null && guiItem.getPermission() != null && !guiItem.getPermission().isBlank()) {
        return player.hasPermission(guiItem.getPermission());
    }
    return true;
}

// Usage in click handler:
@EventHandler
public void onInventoryClick(InventoryClickEvent event) {
    // ... holder check, cancel event ...
    GuiDefinition def = addon.guiRegistry().get("my_gui");
    if (!handleCommonFeatures(player, event.getSlot(), event.getClick(), def)) return;
    // ... handle specific button logic ...
}
```

#### Blink Animation Pattern

```java
boolean[] check = {true};
Object blinkTask = scheduler().runAsyncTaskTimer(() -> {
    // Update nav button lore with blink state
    GuiItem navDef = def.itemAt(48);
    if (navDef != null) {
        List<Component> lore = buildNavLore(lang, navDef, check[0], player);
        guiUtils.updateGuiItem(inv, navDef.getSlot(), null, lore);
    }
    check[0] = !check[0];
}, 0L, 10L);
```

## Building

```bash
git clone https://github.com/Xyness/XCore.git
cd XCore
./gradlew clean shadowJar
```

Output: `build/libs/XCore-1.0.1.jar`

## License

MIT License
