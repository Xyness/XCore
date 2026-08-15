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
- **Built-in Economy**: Multi-currency system with Vault provider, transactions, exchange, interest.
  Balance changes are applied atomically in the database (`col = col +/- ?`, guarded on available
  funds) and serialized per player, so concurrent operations cannot create or destroy money.
- **Vault Integration**: Registers as Vault economy provider, shared by all addons
- **PlaceholderAPI**: Core placeholders + addon-specific expansions
- **Folia Compatible**: Full region threading support via SchedulerAdapter
- **Table & Query Builder**: Fluent API for database operations across MySQL/PostgreSQL/SQLite
- **Bedrock Detection**: Geyser/Floodgate support for Bedrock players
- **Per-Addon Profiling**: every addon runs under XCore's plugin identity, so a timings report blames
  XCore for all of them. `/xcore profile` measures each addon's event handlers separately and names
  the one costing tick time
- **Tick-Thread Watchdog**: with `debug: true`, any database access made from a tick thread is
  reported once per call site — the most common way a plugin quietly turns a healthy server into a
  stuttering one
- **UUID Migration**: a player returning under a different `server_uuid` (offline ↔ online mode, or a
  proxy that rewrites it) has their existing row **moved** rather than duplicated, so balances,
  addon columns and history follow them

## Requirements

- Paper 1.21.1+
- Java 21+
- Vault (optional -- only needed to expose the economy to other plugins)
- PlaceholderAPI (optional)

## Official Addons

| Addon | Description |
|-------|-------------|
| [XBans](https://builtbybit.com/resources/xbans.102982/) | Sanctions, IP security, alt detection, AI moderation, warden |
| [XLogin](https://builtbybit.com/resources/xlogin.103391/) | Login/register authentication, auto-premium login, sessions, player protection, proxy & Bedrock support |
| [XAuctionHouse](https://builtbybit.com/resources/xauctionhouse.103084/) | Advanced auction house system with bid system, search, favorites, filters |
| XAntiLag | Chunk caps, mob and item stackers, clearlag, AFK detection, a TPS/MSPT monitor and a dashboard |

## Installation

1. Place `XCore.jar` in `plugins/`
2. Place addon JARs in `plugins/XCore/addons/`
3. Start the server
4. Configure `plugins/XCore/config.yml`
5. Each addon has its own config at `plugins/XCore/addons/<name>/config.yml`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/xcore` | Overview: version, uptime, database, addons, dashboard | `xcore.admin` |
| `/xcore stats` | Cache hit rates, database and Mojang API counters | `xcore.admin` |
| `/xcore addons` | List loaded addons with their state | `xcore.admin` |
| `/xcore dashboard` | One-click login link for the web dashboard | `xcore.admin` |
| `/xcore dashboard revoke` | Close every dashboard session you opened | `xcore.admin` |
| `/xcore reload` | Reload the core configuration, every language file, the economy and all addons | `xcore.admin` |
| `/xcore reload <addon>` | Reload a specific addon | `xcore.admin` |
| `/xcore clear-cache` | Invalidate all cache regions | `xcore.admin` |
| `/xcore player <name>` | Detailed player information | `xcore.admin` |
| `/xcore profile` | Time spent per addon and per event handler | `xcore.admin` |
| `/xcore profile on\|off\|reset` | Turn sampling on or off without a restart | `xcore.admin` |
| `/xcore diag` | Write a full support report to `plugins/XCore/` | `xcore.admin` |

### Economy Commands

Aliases: `/economy`, `/coins`, `/money`. The command was `/coins` before 1.0.7 and the
alias keeps signs, NPCs and scripts working.

| Command | Description | Permission |
|---------|-------------|------------|
| `/eco` | View all your balances | - |
| `/eco balance [player] [currency]` | View balance | `xcore.economy.balance.others` for others |
| `/eco pay <player> <amount> [currency]` | Send money | - |
| `/eco set <player> <amount> [currency]` | Set balance | `xcore.economy.admin` |
| `/eco add <player> <amount> [currency]` | Add balance | `xcore.economy.admin` |
| `/eco remove <player> <amount> [currency]` | Remove balance | `xcore.economy.admin` |
| `/eco reset <player> [currency\|all]` | Reset to the starting balance | `xcore.economy.admin` |
| `/eco resetall [currency\|all] confirm` | Reset every player, online and offline | `xcore.economy.admin` |
| `/eco exchange <from> <to> <amount>` | Exchange currencies | - |
| `/eco history [player] [currency] [page]` | Transaction history | - |
| `/eco top [currency]` | Top balances | - |
| `/eco reload` | Reload economy config | `xcore.economy.admin` |

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
# Nothing to configure to log in: /xcore dashboard hands out a one-click link.
# Authentication is rate limited to 10 failed attempts per IP.
web-dashboard:
  enabled: false
  port: 8085
  session-ttl-hours: 24    # how long a /xcore dashboard link stays valid, 0 = until revoked
  metrics-public: true
  cors-origin: "*"         # set your panel URL if the dashboard is reachable from the internet
  public-url: ""           # what /xcore dashboard links to, when a proxy or domain sits in front

# Economy (Vault is optional: without it the economy still runs, it just is not
# published to other plugins)
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

## Language

One setting drives the whole installation:

```yaml
# config.yml
language: "en"   # en, fr — every addon follows it
```

XCore reads `lang/<code>.yml`, and so does every addon: an addon has no language setting of its
own, so switching to `fr` translates the core, the addons and the web dashboard in one edit. An
addon that does not ship the chosen language falls back to English and says so once in the console.

Console output stays in English regardless — logs are for the operator and for issue reports, not
for players.

| File | Contents |
|------|----------|
| `lang/<code>.yml` | Everything said in game, `/eco` included — its keys are prefixed `eco-` |
| `lang/web_<code>.yml` | The dashboard's shared vocabulary |
| `addons/<name>/lang/<code>.yml` | That addon's messages |
| `addons/<name>/lang/web_<code>.yml` | That addon's dashboard strings |

The dashboard keeps its own files because they are served whole to the browser on a public
endpoint; the economy no longer does, since it speaks to the same players through the same setting.
An addon's dashboard strings live with the addon: `/api/lang` merges the core's vocabulary with
every registered module's file, and the core wins on the rare overlap.

A message added by a new release is appended to your file on startup; existing values are never
overwritten.

## Config maintenance (addons)

`XAddon` exposes two halves of the same job:

| Method | Effect |
|--------|--------|
| `updateConfigWithDefaults(protected...)` | Adds keys the bundled default has and the disk file lacks. Never overwrites a value. |
| `pruneObsoleteConfigKeys(protected...)` | Removes keys the bundled default no longer has — the ones a refactor left behind, inert. |
| `pruneObsoleteKeys(file, protected...)` | Same, for any file (`lang.yml`, …). |

Pruning copies the file to `<name>.pre-prune.bak` before the first removal and logs every key it
drops. *Protected sections* are the ones whose keys the administrator invents — world names,
material maps, shop entries — which the defaults cannot enumerate; they are never added to and
never pruned. YAML lists are values, so a list the user extended is kept whole.

## Web API

### Logging in

Run `/xcore dashboard` in game. It replies with a link that opens the dashboard already
authenticated — the page takes the token out of the address bar on arrival, so it does not linger
in the history or in a screenshot. There is no token to configure anywhere. Links expire after
`session-ttl-hours` and `/xcore dashboard revoke` closes every session you opened.

Sessions are stored as SHA-256 hashes in `web-sessions.json`; a copy of that file lets nobody in.
A script that needs API access gets its token the same way — run the command, take the token out of
the link, and revoke it when the script is retired.

### Endpoints

All endpoints require `Authorization: Bearer <session token>` (except `/api/metrics` if configured
public). `/api/metrics` answers anonymous callers with the server name, uptime, player count and
TPS, and adds versions, memory, storage and cache figures only for an authenticated one.
Requests are rate limited per IP; ten failed authentications in a minute temporarily lock the source out.

| Endpoint | Description |
|----------|-------------|
| `GET /api/lang` | Dashboard strings in the configured language (public, rate limited) |
| `GET /api/modules` | Registered web modules and their page descriptors |
| `GET /api/metrics` | Server metrics (uptime, players, cache) |
| `GET/POST /api/<addon>/config/raw` | Read or replace an addon's `config.yml` — the YAML is parsed before anything is written, and the addon is reloaded after |
| Addon modules | Each addon registers its own routes under `/api/<name>/` |

## Addon APIs

An addon can publish an API other addons compile against. XCore looks a missing class up across the
other addons' loaders before giving up, which is what makes that possible at all: each addon loads
from its own JAR, so without it the publisher and the consumer would hold two different `Class`
objects of the same name — and a listener registered on one would never receive an event fired on
the other.

The addon ships the API classes in its own JAR; consumers depend on the published artifact with
`compileOnly` and must not shade it. `XAntiLag-API` is the reference implementation of the pattern.

## Dashboard conventions

The dashboard renders what modules declare, so these conventions are all a module needs to know:

| Convention | Effect |
|---|---|
| `field-<key>` in `lang/web_<code>.yml` | Names a payload field. `player_uuid` and `playerUuid` resolve to the same label |
| `value-<identifier>` | Names a value, e.g. `value-buy-now` → "Achat direct" |
| `itemDetails` on a row | Renders the Minecraft item cell: sprite, hover tooltip, enchantments, durability |
| `translationKey` inside `itemDetails` | The item is named in the reader's own language |
| `.paged(n)` on a spec | Pages the table. Server-side when the endpoint answers `total`/`page`, in the browser otherwise |

Two endpoints exist so the browser never talks to a third party: `/api/sprite/<material>.png` and
`/api/mclang`, both fetched server-side, cached, and pinned to the server's own Minecraft version.
`/api/mclang` answers in the language of the player whose `/xcore dashboard` link is being used.

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
    compileOnly 'com.github.Xyness:XCore:1.0.4'
}
```

### addon.yml

```yaml
name: MyAddon
version: '1.0.0'
author: 'YourName'
main: com.example.myaddon.MyAddonAddon
description: What this addon does
# Where initUpdater() reads the remote version.yml. Optional: without it the checker
# falls back to https://raw.githubusercontent.com/Xyness/MyAddon/refs/heads/main/version.yml
update-url: 'https://raw.githubusercontent.com/you/MyAddon/refs/heads/main/version.yml'
depend: []
soft-depend: []
```

### Main Class

```java
public class MyAddon extends XAddon {

    @Override
    public boolean onEnable() {
        // Writes config.yml on first run, then adds any new top-level keys
        // introduced by future versions. Pass user-managed map sections
        // (key:value entries the user adds/removes) so their contents are
        // never re-injected — listed paths are skipped entirely.
        updateConfigWithDefaults("limits.blocks", "limits.entities");
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
| `saveDefaultConfig()` | Write bundled `config.yml` to disk if missing (no merge) |
| `updateConfigWithDefaults(String... protectedSections)` | Write defaults on first run, then add new top-level keys on later runs. Paths listed in `protectedSections` are skipped entirely — useful for user-managed maps (e.g. `chunk-limits.blocks`, `shops`) where removed entries must stay removed |

### Shared utilities

| Class | What it is for |
|-------|----------------|
| `fr.xyness.XCore.Utils.WorldConfig` | Per-world overrides (`worlds.<name>.<key>`) resolved once at load, so a listener never builds a path string or walks the YAML tree |
| `fr.xyness.XCore.Utils.Formats` | Durations, thousands separators, compact numbers, byte sizes — one implementation instead of one per addon |
| `fr.xyness.XCore.Gui.BlinkCache` | Holds both faces of a blinking item, so the timer stops rebuilding what only alternates |
| `fr.xyness.XCore.Utils.Profiler` | Per-addon timings behind `/xcore profile` |

### Query builder

```java
api().query("table").select("*").whereIn("uuid", uuids).executeAsync();
api().query("table").update().setRelative("score", 5).where("uuid", id).executeUpdateAsync();
api().query("a").leftJoin("b", "a.id", "b.a_id").select("a.name", "b.score").executeAsync();
api().query("table").insert().addRow(Map.of(...)).addRow(Map.of(...)).executeBatchAsync();
api().query("table").where("a", 1).or().where("b", 2).executeAsync();
```

`setRelative` applies the arithmetic inside the database (`col = COALESCE(col, 0) + ?`), which is
what keeps two servers from both reading the old value and writing back a total that loses the
other's change.

### Dashboard Pages

A `WebModule` describes its pages rather than shipping JavaScript for them. XCore serves the
description on `/api/modules` and the browser builds the page — nothing about an addon lives in the
dashboard's code, and adding a page never means releasing a new XCore.

```java
@Override
public List<WebPage> getPages() {
    return List.of(
        new WebPage("bans", "bans", "ban", WebPageSpec.table("/api/myaddon/bans")
            .titleKey("myaddon-bans")          // every ...Key is a lang/web_<code>.yml key
            .dataKeys("bans")                  // where the rows are in the payload
            .emptyKey("no-records-found")
            .search("search-players")          // adds ?search=
            .paged(50)                         // adds ?page=&limit=
            .actions(WebPageSpec.action("unban", "/api/myaddon/unban")
                .style("danger")
                .idFrom("player_name")         // names the row in the confirmation
                .send("player", "player_name"))
            .form("ban-player", "/api/myaddon/ban",
                WebPageSpec.field("player", "player", true).placeholderKey("player-name"),
                WebPageSpec.field("reason", "reason", false).placeholderKey("reason"))),
        new WebPage("statistics", "stats", "chart", WebPageSpec.stats("/api/myaddon/stats")
            .titleKey("myaddon-statistics")
            .tile("total-bans", "red", "totalBans")
            .toggles("feature-toggles", "no-feature-toggles-available", "features")
            .chart("daily-volume", "", "days", "date", "value", "no-chart-data-available")),
        new WebPage("configuration", "config", "settings", WebPageSpec.config()));
}
```

| Page kind | Built by |
|-----------|----------|
| `WebPageSpec.table(endpoint)` | Rows, with optional search, filters, paging, row actions, a creation form and a page-wide button |
| `WebPageSpec.stats(endpoint)` | Statistic tiles, an enabled/disabled list, a key/value details card and a bar chart |
| `WebPageSpec.config()` | The raw `config.yml` editor, served by XCore for every module |

A label with no entry in `lang/web_<code>.yml` renders as the key itself, so a page still works
untranslated. Omitting the descriptor entirely (`new WebPage(name, path, icon)`) falls back to the
generic table renderer.

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

Output: `build/libs/XCore-1.0.4.jar`

## License

MIT License
