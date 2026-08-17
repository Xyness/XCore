# XCore

Framework for the X plugins: shared database, three-layer cache, cross-server sync, GUI framework,
language system, Vault integration, web dashboard and addon loader.

## Features

- Three cache layers: Caffeine locally, Redis shared, database behind them.
- Cross-server sync over Redis Pub/Sub, or database polling without Redis, multiplexed for every
  addon.
- Addons loaded from `plugins/XCore/addons/`, with a load, enable, disable and reload lifecycle.
- MySQL, PostgreSQL or SQLite, pooled through HikariCP.
- YAML-driven GUI framework: custom model data, item models, sounds, actions, permissions, blink
  animations, pagination.
- Language files per addon in MiniMessage, new keys merged automatically.
- Web dashboard with a REST API and a module system for addons.
- Built-in economy: several currencies, a Vault provider, transactions, exchange, interest. Balance
  changes are applied atomically in the database (`col = col +/- ?`, guarded on available funds) and
  serialised per player.
- Registers as the Vault economy provider.
- PlaceholderAPI, core placeholders and per-addon expansions.
- Folia support through the SchedulerAdapter.
- Table and query builder, identical on MySQL, PostgreSQL and SQLite.
- Deliveries: what an addon owes a player but could not hand over is stored and given on join.
- Leaderboards declared once, kept in memory and served as placeholders.
- Network registry: which servers are up, how many players they hold, and where a given player is.
- One Discord sender for the whole installation, with a queue and rate-limit handling.
- Play time per player, counted across sessions.
- Bedrock detection through Geyser and Floodgate.
- Per-addon profiling with `/xcore profile`.
- Tick-thread watchdog: with `debug: true`, database access from a tick thread is reported once per
  call site.
- UUID migration: a player returning under a different `server_uuid` has their row moved rather than
  duplicated, so balances, addon columns and history follow them.

## Requirements

- Paper 1.21.1+
- Java 21+
- Vault, optional, to expose the economy to other plugins
- PlaceholderAPI, optional

## Official addons

| Addon | Description |
|-------|-------------|
| [XBans](https://builtbybit.com/resources/xbans.102982/) | Bans, mutes, warns and jails, with alt detection, IP security and a chat classifier. The same jar runs on the proxy. |
| [XLogin](https://builtbybit.com/resources/xlogin.103391/) | Online mode per player: premium players verified against Mojang, everyone else registers. 2FA, map captcha, cross-server sessions. |
| [XAuctionHouse](https://builtbybit.com/resources/xauctionhouse.103084/) | Buy-now listings and live auctions in any currency, with categories, search, price history and standing alerts. |
| [XAntiLag](https://builtbybit.com/resources/xantilag.105658/) | Chunk caps, mob and item stackers, clearlag and AFK detection, driven by a TPS and MSPT monitor. |

## Installation

1. Put `XCore.jar` in `plugins/`.
2. Put the addon jars in `plugins/XCore/addons/`.
3. Start the server.
4. Edit `plugins/XCore/config.yml`.
5. Each addon has its own config at `plugins/XCore/addons/<name>/config.yml`.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/xcore` | Version, uptime, database, addons, dashboard | `xcore.admin` |
| `/xcore stats` | Cache hit rates, database and Mojang counters | `xcore.admin` |
| `/xcore addons` | Loaded addons and their state | `xcore.admin` |
| `/xcore dashboard` | One-click login link | `xcore.admin` |
| `/xcore dashboard revoke` | Close every session you opened | `xcore.admin` |
| `/xcore reload` | Reload the core config, language files, economy and addons | `xcore.admin` |
| `/xcore reload <addon>` | Reload one addon | `xcore.admin` |
| `/xcore clear-cache` | Empty the player cache and the head cache | `xcore.admin` |
| `/xcore player <name>` | Player details | `xcore.admin` |
| `/xcore profile` | Time per addon and per event handler | `xcore.admin` |
| `/xcore profile on\|off\|reset` | Toggle sampling | `xcore.admin` |
| `/xcore diag` | Write a support report to `plugins/XCore/` | `xcore.admin` |

### Economy

Aliases: `/economy`, `/coins`, `/money`. The command was `/coins` before 1.0.7.

| Command | Description | Permission |
|---------|-------------|------------|
| `/eco` | All your balances | - |
| `/eco balance [player] [currency]` | A balance | `xcore.economy.balance.others` for others |
| `/eco pay <player> <amount> [currency]` | Send money | - |
| `/eco set <player> <amount> [currency]` | Set a balance | `xcore.economy.admin` |
| `/eco add <player> <amount> [currency]` | Add to one | `xcore.economy.admin` |
| `/eco remove <player> <amount> [currency]` | Take from one | `xcore.economy.admin` |
| `/eco reset <player> [currency\|all]` | Back to the starting balance | `xcore.economy.admin` |
| `/eco resetall [currency\|all] confirm` | Every player, online and offline | `xcore.economy.admin` |
| `/eco exchange <from> <to> <amount>` | Exchange currencies | - |
| `/eco history [player] [currency] [page]` | Transaction history | - |
| `/eco top [currency]` | Richest players | - |
| `/eco help` | Subcommands | - |
| `/eco reload` | Reload the economy config | `xcore.economy.admin` |

## Configuration

`plugins/XCore/config.yml`:

```yaml
# Verbose cache, database and API logging. Also turns on the tick-thread watchdog.
debug: false

# Time spent per addon per event handler, readable with /xcore profile.
profiling: false

# Language for XCore and every addon. Bundled: en, fr. An addon may override it with its own
# `language` key. Console logs stay in English.
language: "en"

# sqlite, mysql or postgresql
database-type: sqlite

server-name: "default"

# Move the row of a player returning under a different UUID instead of creating a second one.
migrate-uuid-changes: true

# Ignored for sqlite
database:
  host: localhost
  port: 3306
  name: xcore
  username: root
  password: ""
  ssl: false
  pool-size: 10

# Requires MySQL or PostgreSQL, not compatible with SQLite.
cross-server:
  enabled: false
  # Optional: L2 cache and Pub/Sub sync. Without it, sync falls back to database polling.
  redis:
    enabled: false
    host: localhost
    port: 6379
    password: ""
    database: 0
    ttl: 3600            # seconds, for cached player data
  heartbeat-seconds: 10  # how often a server announces itself to the others
  sync:
    poll-interval-seconds: 3
    retention-seconds: 300

cache:
  max-size: 100000                  # player entries in L1
  ttl-minutes: 60
  mojang-max-size: 5000
  max-api-concurrency: 10
  api-timeout-ms: 2000
  circuit-breaker-threshold: 5      # consecutive Mojang failures before calls stop
  circuit-breaker-open-minutes: 5

# /xcore dashboard hands out a one-click link. Authentication is limited to 10 failed
# attempts per IP per minute.
web-dashboard:
  enabled: true
  port: 8085
  session-ttl-hours: 24    # 0 = until revoked
  metrics-public: true
  cors-origin: "*"
  public-url: ""           # when a proxy or a domain sits in front

# What an addon owes a player but could not hand over. Given on their next join.
delivery:
  on-join: true
  per-join-limit: 20

# The name and picture Discord messages are posted under, when the addon does not set its own.
# Webhook addresses stay in each addon's own config.
integrations:
  discord:
    username: "XCore"
    avatar-url: ""

# Without Vault the economy still runs, it is just not published to other plugins.
economy:
  enabled: true
  per-server-balances: false
  currencies:
    dollar:
      symbol: "$"
      symbol-position: BEFORE  # BEFORE or AFTER
      decimals: 2
      starting-balance: 0.00
      max-balance: 1000000000
      vault: true              # the currency Vault sees; exactly one may hold it
  exchange:
    enabled: false
    rates: {}                  # dollar-to-gems: 100
  scheduled-payouts:
    enabled: false
    interval-minutes: 60
    amount: 10.0
    currency: "dollar"
  interest:
    enabled: false
    rate: 0.01                 # 1% per interval
    interval-minutes: 1440
    currency: "dollar"
```

A new key under `currencies` adds a currency; its balance column is created automatically. Renaming
or removing one is respected: XCore never writes an entry back into a section whose names you chose
yourself.

## Languages

```yaml
# config.yml
language: "en"   # en or fr
```

XCore reads `lang/<code>.yml`, and so does every addon. An addon without the chosen language falls
back to English and says so once in the console. Console output stays in English.

| File | Contents |
|------|----------|
| `lang/<code>.yml` | Everything said in game, `/eco` included, with `eco-` keys |
| `lang/web_<code>.yml` | The dashboard's shared vocabulary |
| `addons/<name>/lang/<code>.yml` | That addon's messages |
| `addons/<name>/lang/web_<code>.yml` | That addon's dashboard strings |

`/api/lang` merges the core's vocabulary with every registered module's file; the core wins on
overlap. New keys are appended at startup, existing values are never overwritten.

## Config maintenance for addons

| Method | Effect |
|--------|--------|
| `updateConfigWithDefaults(protected...)` | Adds keys the bundled default has and the file lacks. Never overwrites a value. |
| `pruneObsoleteConfigKeys(protected...)` | Removes keys the bundled default no longer has. |
| `pruneObsoleteKeys(file, protected...)` | The same, for any file. |

Pruning copies the file to `<name>.pre-prune.bak` and logs every key dropped. Protected sections
(world names, material maps, shop entries) are never added to and never pruned. YAML lists count as
values.

Two more things are left alone on their own, without having to be declared. A section whose children
are all sections is a list of named entries, not a group of settings: once `economy.currencies`,
`shops` or `kits` exists on disk, nothing under it is written back, so renaming an entry does not
bring the old one back on the next start. And a list whose key was deleted stays deleted, unless the
whole section around it is new.

## Web API

### Logging in

`/xcore dashboard` answers with a link that opens the dashboard authenticated. The page strips the
token from the address bar on arrival. Links expire after `session-ttl-hours`;
`/xcore dashboard revoke` closes every session you opened.

Sessions are stored as SHA-256 hashes in `web-sessions.json`. A script needing API access takes its
token from the link.

### Endpoints

Every endpoint needs `Authorization: Bearer <session token>`, except `/api/metrics` when public.
`/api/metrics` answers an anonymous caller with the server name, uptime, player count and TPS, and
adds versions, memory, storage and cache figures for an authenticated one. Rate limited per IP; ten
failed authentications in a minute lock the source out.

| Endpoint | Description |
|----------|-------------|
| `GET /api/lang` | Dashboard strings in the configured language, public |
| `GET /api/modules` | Registered web modules and their page descriptors |
| `GET /api/metrics` | Uptime, players, cache |
| `GET/POST /api/<addon>/config/raw` | Read or replace an addon's `config.yml`. The YAML is parsed before writing, and the addon is reloaded |
| Addon modules | Routes under `/api/<name>/` |

## Addon APIs

An addon can publish an API other addons compile against. XCore looks a missing class up across the
other addons' loaders before giving up: each addon loads from its own jar, so otherwise the
publisher and the consumer would hold two different `Class` objects with the same name.

The addon ships the API classes in its own jar; consumers use `compileOnly` and must not shade it.
`XAntiLag-API` is the reference.

## Dashboard conventions

| Convention | Effect |
|---|---|
| `field-<key>` in `lang/web_<code>.yml` | Names a payload field. `player_uuid` and `playerUuid` resolve to the same label |
| `value-<identifier>` | Names a value: `value-buy-now` becomes "Achat direct" |
| `itemDetails` on a row | Renders the Minecraft item cell: sprite, tooltip, enchantments, durability |
| `translationKey` inside `itemDetails` | Names the item in the reader's language |
| `.paged(n)` on a spec | Pages the table, server-side when the endpoint answers `total` and `page` |

`/api/sprite/<material>.png` and `/api/mclang` are fetched server-side, cached and pinned to the
server's Minecraft version, so the browser never calls a third party. `/api/mclang` answers in the
language of the player whose link is in use.

## PlaceholderAPI

| Placeholder | Returns |
|-------------|---------|
| `%xcore_name%` | Player name |
| `%xcore_uuid%` | Server UUID |
| `%xcore_last_login%` | Last login |
| `%xcore_last_logout%` | Last logout |
| `%xcore_playtime%` | Time connected, all sessions, formatted |
| `%xcore_playtime_seconds%` | The same as a number |
| `%xcore_server%` | This server's name |
| `%xcore_network_online%` | Players connected across the network |
| `%xcore_servers%` | Servers currently up |
| `%xcore_top_<board>_<rank>_name%` | A leaderboard entry, also `_value`, `_raw` and `_uuid` |
| `%xcore_balance%` | Formatted Vault balance |
| `%xcore_balance_raw%` | Unformatted |
| `%xcore_balance_<id>%` | Formatted balance for one currency |
| `%xcore_balance_<id>_raw%` | Unformatted |

An addon publishes its own without writing an expansion class:

```java
placeholders()
    .register("kits", (player, arg) -> String.valueOf(available(player)))
    .register("cooldown", (player, arg) -> Formats.duration(remaining(player, arg)));
placeholders().publish();
```

The identifier is the addon name in lower case, so those become `%mykits_kits%` and
`%mykits_cooldown_starter%`.

## Writing an addon

### Dependency

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.Xyness:XCore:1.0.9'
}
```

### addon.yml

```yaml
name: MyAddon
version: '1.0.0'
author: 'YourName'
main: com.example.myaddon.MyAddonAddon
description: What this addon does
# Where initUpdater() reads the remote version.yml. Without it, update checking stays off.
update-url: 'https://raw.githubusercontent.com/you/MyAddon/refs/heads/main/version.yml'
depend: []
soft-depend: []
```

### Main class

```java
public class MyAddon extends XAddon {

    @Override
    public boolean onEnable() {
        // Writes config.yml on the first run, then adds the keys later versions introduce.
        // Listed paths are skipped entirely: use them for map sections the user manages.
        updateConfigWithDefaults("limits.blocks", "limits.entities");

        // Extracts every bundled translation and loads the one XCore's `language` selects.
        loadLanguage("en", "fr");

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
| `api()` | Database, cache, sync, GUI, Vault, web |
| `core()` | The XCore JavaPlugin, for Bukkit registrations |
| `scheduler()` | The Folia-compatible SchedulerAdapter |
| `logger()` | Logger scoped to the addon |
| `lang()` | The addon's language namespace |
| `guiRegistry()` | GUI definitions loaded from YAML |
| `guiUtils()` | Item building, sounds, heads, click handling |
| `delivery()` | Give an item or money to someone offline, handed over on their next join |
| `leaderboards()` | Declare a ranking once, read it from memory |
| `network()` | Which servers are up, and where a player is |
| `ranks()` | Primary group and numbered permission levels, LuckPerms or Vault |
| `discord()` | The shared webhook sender |
| `cooldowns()` | Per-player cooldowns for this addon |
| `placeholders()` | Publish placeholders without writing an expansion |
| `getConfig()` | The addon's config.yml |
| `getDataFolder()` | `plugins/XCore/addons/<name>/` |
| `saveDefaultConfig()` | Write the bundled `config.yml` if missing, without merging |
| `updateConfigWithDefaults(String... protectedSections)` | Defaults on the first run, new keys later. Listed paths are skipped, and so is anything the administrator names themselves |
| `loadLanguage(String... available)` | Extract the bundled translations and load the chosen one |
| `initUpdater()` / `updater()` | Update checker, reading `update-url` from `addon.yml` |

### Shared utilities

| Class | Purpose |
|-------|---------|
| `fr.xyness.XCore.Utils.WorldConfig` | Per-world overrides (`worlds.<name>.<key>`), resolved once at load |
| `fr.xyness.XCore.Utils.Formats` | Durations both ways, thousands separators, compact numbers, byte sizes |
| `fr.xyness.XCore.Utils.Items` | Items to base64 and back, giving without losing the overflow, counting, taking |
| `fr.xyness.XCore.Utils.Cooldowns` | Per-player cooldowns that expire on their own |
| `fr.xyness.XCore.Utils.Notify` | Titles, action bars and boss bars |
| `fr.xyness.XCore.Utils.ConfigMerger` | Adds new settings to a config without resurrecting what was removed |
| `fr.xyness.XCore.Gui.BlinkCache` | Holds both faces of a blinking item |
| `fr.xyness.XCore.Gui.PagedGui` | Base class for a paginated screen: pages, bar, blink, clicks |
| `fr.xyness.XCore.Commands.CommandHelpers` | Suggestions, offline target resolution, the `-s` flag |
| `fr.xyness.XCore.Utils.Profiler` | Per-addon timings behind `/xcore profile` |
| `fr.xyness.XCore.Database.GuardedDataSource` | With `debug: true`, names any query made from a tick thread |
| `guiUtils().blinkBarItem(blink, lang, itemDef, on, viewer, ...)` | Renders a bar item and caches both faces |
| `GuiUtils.handleCommonFeatures(player, slot, click, def)` | Sound, actions and permission of the clicked item |

### Player columns

```java
api().columnBuilder()
    .addColumn("last_kit", ColumnType.VARCHAR).length(32).defaultValue("").notNull()
    .apply();
```

The `players` table is shared and pushed to Redis in full on every write, by every addon. A value
that grows belongs in its own table. `dropColumn(name)` removes a column once its data has moved;
`core().methods().columnExists(table, column)` says whether there is anything to migrate.

`playerDAO().findPageAsync(offset, limit)` and `countAsync()` walk the table a page at a time. There
is no method returning every row at once.

### Query builder

```java
api().query("table").select("*").whereIn("uuid", uuids).executeAsync();
api().query("table").update().setRelative("score", 5).where("uuid", id).executeUpdateAsync();
api().query("a").leftJoin("b", "a.id", "b.a_id").select("a.name", "b.score").executeAsync();
api().query("table").insert().addRow(Map.of(...)).addRow(Map.of(...)).executeBatchAsync();
api().query("table").where("a", 1).or().where("b", 2).executeAsync();
```

`setRelative` does the arithmetic in the database (`col = COALESCE(col, 0) + ?`), so two servers
cannot both read the old value and write back a total that loses the other's change.

`executeUpdateAsync()` returns the number of rows affected, which is what makes a conditional write
usable: `UPDATE ... WHERE stock > 0` touching one row is a sale, touching none is "out of stock".

```java
// Write the row, or update it if it is already there. The key columns need a unique index.
api().query("homes").insert()
    .set("player_uuid", uuid).set("name", name).set("world", world)
    .upsert("player_uuid", "name")
    .executeUpdateAsync();

// Several statements, all or nothing.
api().tableManager().transaction(conn -> {
    ...
    return true;
});

// Schema changes that must run once, in order, on servers several versions behind.
api().tableManager().migrator("MyAddon")
    .version(1, conn -> { ... })
    .version(2, conn -> { ... })
    .run();
```

### Deliveries

What an addon owes a player but cannot hand over right away: the inventory was full, or the player
was offline. Stored, then given on their next join.

```java
delivery().sendItem(uuid, item, "Auction sale", "XAuctionHouse");
delivery().sendMoney(uuid, "dollar", 250.0, "Vote reward", "XVote");
delivery().countPending(uuid).thenAccept(count -> ...);
```

Money always goes through. An item is only marked as taken once it is really in the inventory, so a
full inventory postpones it instead of losing it.

### Leaderboards

Declared once, refreshed on a timer, read from memory.

```java
leaderboards().define("kills")
    .table("xtools_warzone").value("kills")
    .size(10).refreshEvery(300)
    .register();
```

That publishes `%xcore_top_kills_1_name%` and `%xcore_top_kills_1_value%`, and
`leaderboards().get("kills").top(10)` returns the snapshot without touching the database.

### Network

Every server announces itself on the sync channel, so an addon can know what the network looks like.

```java
network().servers();                  // name, players, TPS, version
network().locate(uuid);               // which server holds this player
network().totalOnline();              // across the network
network().send("lobby", "mychannel", new SyncMessage("KICK", uuid.toString()));
```

### Discord

One queue for the whole installation: retries, and a webhook that answers 429 is left alone for as
long as Discord asks. Each addon keeps its own webhook address in its own config.

```java
discord().send(url, DiscordNotifier.embed()
        .title("Ban")
        .description(player + " was banned by " + staff)
        .color(0xC0362C)
        .field("Reason", reason, false)
        .timestamp()
        .build());
```

`discord().sendRaw(url, json)` takes a payload an addon has already assembled.

### Dashboard pages

A `WebModule` describes its pages; XCore serves the description on `/api/modules` and the browser
builds them.

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

| Page kind | Builds |
|-----------|--------|
| `WebPageSpec.table(endpoint)` | Rows, with optional search, filters, paging, row actions, a creation form and a page-wide button |
| `WebPageSpec.stats(endpoint)` | Statistic tiles, an enabled/disabled list, a key/value card, a bar chart |
| `WebPageSpec.config()` | The raw `config.yml` editor, served by XCore for every module |

Tile colours: `green`, `orange`, `purple`, `red`. Button styles: `danger`, `warning`, `success`,
`secondary`.

A label with no entry in `lang/web_<code>.yml` renders as the key. `new WebPage(name, path, icon)`
without a descriptor falls back to the generic table renderer.

Do not register `<base>/config/raw` yourself: XCore registers it for every module, and a second
context on the same path throws and stops the addon from enabling.

### GUI framework

#### Defining a GUI

Files go in `src/main/resources/guis/`, one per screen:

```yaml
gui-title: "gui-title-key"        # lang key for the inventory title
rows: 6                            # 1 to 6
slots: [0,1,2,...,44]              # slots reserved for paginated content
slots-sound: "minecraft:ui.button.click"

items:
  BackPage:
    slot: 48
    material: ARROW
    # material: PLAYER_HEAD:texture_hash    # custom skull texture
    target-title: "previous-title"          # lang key for the display name
    target-lore: "previous-lore"            # lang key for the lore template
    target-button-on: "previous-button-on"  # lang key for the blink ON face
    target-button-off: "previous-button-off"
    permission: "myaddon.gui.navigate"      # optional, blocks the click without it
    sound: "minecraft:ui.button.click"
    custom_model_data_value: 0
    item_model_key: "my_pack:my_item"       # 1.20.5+
    actions:
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

#### Loading them

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

#### Using a definition

```java
import fr.xyness.XCore.Gui.*;

GuiDefinition def = addon.guiRegistry().get("my_gui");

Inventory inv = Bukkit.createInventory(holder, def.getRows() * 9,
    lang.getComponent(def.getTitleKey(), "page", "1", "max", "5"));

GuiItem itemDef = def.itemAt(48);
ItemStack navItem = guiUtils.createItemFromDef(itemDef, title, lore);
```

#### GuiUtils

| Method | Description |
|--------|-------------|
| `createItem(Material, Component, List<Component>)` | Item with a name, a lore and every ItemFlag |
| `createItemFromDef(GuiItem, Component, List<Component>)` | Item from its definition |
| `createItemFromDef(GuiItem, Component, List<Component>, Player)` | Same, with the Bedrock check for skull textures |
| `blinkBarItem(BlinkCache, LangNamespace, GuiItem, boolean, Player, String...)` | Bar item with both faces cached, permission handling included |
| `updateGuiItem(Inventory, slot, Component, List<Component>)` | Change an existing item's name and lore |
| `buildNavLore(LangNamespace, loreKey, offKey, onKey, check, replacements...)` | Navigation lore for the current blink state |
| `createPlayerHeadWithTexture(String, Component, List<Component>)` | Skull with a custom texture, cached |
| `setCustomModelDataSafe(ItemMeta, int)` | Custom model data, 1.21.5+ with a fallback |
| `setItemModelSafe(ItemMeta, String)` | Item model key, 1.20.5+ |
| `playSound(Player, String)` | Sound from a namespaced key |
| `noItalic(Component)` / `noItalic(List<Component>)` | Strip the italics |

Never mutate the `ItemStack` returned by `inv.getItem(slot)`: it is a live mirror of what the client
holds. `updateGuiItem` clones it.

#### Handling clicks

```java
import fr.xyness.XCore.Gui.*;

@EventHandler
public void onInventoryClick(InventoryClickEvent event) {
    GuiDefinition def = addon.guiRegistry().get("my_gui");
    // Sound, configured actions and permission of the clicked item, in one call
    if (!GuiUtils.handleCommonFeatures(player, event.getSlot(), event.getClick(), def)) return;
    // then the button's own logic
}
```

#### A paginated screen

`PagedGui` covers what a list screen always needs: page maths, the bar at 48 / 49 / 50, the blink
task and its cancellation, and the click routing. XCore listens for these screens itself, so nothing
has to be registered.

```java
public class WarpGui extends PagedGui<Warp> {

    public WarpGui(MyAddon addon) {
        super(addon.scheduler(), addon.guiUtils(), addon.lang(), addon.guiRegistry().get("warps"));
    }

    @Override protected List<Warp> items(Player viewer) { return manager.warps(); }

    @Override protected ItemStack render(Warp warp, Player viewer, boolean blinkOn) {
        return guiUtils().createItemFromDef(itemDef, title(warp), lang().getLore(lore(warp)), viewer);
    }

    @Override protected void onItemClick(Warp warp, Player viewer, ClickType click) {
        manager.teleport(viewer, warp);
    }
}
```

#### Blinking buttons

```java
BlinkCache blink = new BlinkCache();   // one per open GUI
boolean[] check = {true};
Object blinkTask = scheduler().runAsyncTaskTimer(() -> {
    GuiItem navDef = def.itemAt(48);
    if (navDef != null) {
        inv.setItem(navDef.getSlot(),
            guiUtils.blinkBarItem(blink, lang, navDef, check[0], player, "page", "2"));
    }
    check[0] = !check[0];
}, 0L, 10L);
```

Only cache an item whose content depends on nothing but the blink state. An item showing a
countdown, a live price or a remaining time has to keep being rebuilt.

## Building

```bash
git clone https://github.com/Xyness/XCore.git
cd XCore
./gradlew clean shadowJar
```

Output: `build/libs/XCore-1.0.9.jar`.

## License

MIT
