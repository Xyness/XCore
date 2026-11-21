<h1 align="center">XCore</h1>

<p align="center">
  <b>The free framework under XBans, XLogin, XAuctionHouse and XAntiLag:<br>the database, the cache, the cross-server sync and the dashboard they share.</b>
</p>

<p align="center">
  <a href="https://github.com/Xyness/XCore/releases"><img src="https://img.shields.io/badge/version-2.0-4fb3a0?style=flat-square&labelColor=24292f" alt="Version 2.0"></a>
  <img src="https://img.shields.io/badge/Paper-1.21.1%2B-57606a?style=flat-square&labelColor=24292f" alt="Paper 1.21.1+">
  <img src="https://img.shields.io/badge/Folia-supported-57606a?style=flat-square&labelColor=24292f" alt="Folia supported">
  <img src="https://img.shields.io/badge/Java-21%2B-57606a?style=flat-square&labelColor=24292f" alt="Java 21+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-57606a?style=flat-square&labelColor=24292f" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/price-free-4fb3a0?style=flat-square&labelColor=24292f" alt="Free">
</p>

<p align="center">
  <a href="https://github.com/Xyness/XCore/releases"><b>Download</b></a>
  &nbsp;&middot;&nbsp;
  <a href="https://celestis.dev/plugins/xcore/wiki/getting-started">Documentation</a>
  &nbsp;&middot;&nbsp;
  <a href="https://discord.com/invite/6sRTGprM95">Discord</a>
</p>

<br>

Every X plugin needs the same things: somewhere to keep its data, a cache in front of it, a way to reach the other servers of the network, menus, messages in the server's language, a page on the dashboard. XCore does all of that once, for all of them. If a plugin sent you here, put `XCore-2.0.jar` in `plugins/` next to it. On a single server, that is the whole setup.

Your server gets more than the plugins' groundwork, though: an economy every Vault plugin can use, a web dashboard where each member of staff works with their own in-game permissions, and players who keep what they own when their UUID changes. XCore is free and open source under the MIT license, and the base class the X plugins are built on is yours to build on too.

<p align="center">
  <a href="#what-you-get">What you get</a> &middot;
  <a href="#quick-start">Quick start</a> &middot;
  <a href="#the-plugins-built-on-it">The plugins</a> &middot;
  <a href="#commands-and-permissions">Commands</a> &middot;
  <a href="#configuration">Configuration</a> &middot;
  <a href="#build-on-xcore">Build on XCore</a> &middot;
  <a href="#api-reference">API reference</a>
</p>

<br>

## What you get

**One database for every plugin.** SQLite works out of the box, with nothing to set up. Change `database-type` to MySQL or PostgreSQL and every plugin built on XCore follows, through one HikariCP pool and one set of credentials. Two caches sit in front of it: Caffeine in memory, and Redis, shared between servers, if you run one.

**Servers that agree with each other.** On a network, a ban issued in the lobby holds in survival, and a payment made on one server shows on all of them. The plugins' messages travel over Redis Pub/Sub, or through the database every three seconds when there is no Redis. Each channel carries a protocol version, so two servers on different versions of a plugin stop listening to each other instead of misreading each other.

**An economy.** As many currencies as you need, each with its own symbol, decimals, starting balance and ceiling. The one marked `vault: true` is the balance every Vault plugin sees. `/eco` covers payments, exchange between currencies, history and the richest players, and interest or scheduled payouts are one switch away. A balance changes inside the database in a single step, so two servers paying the same player at the same moment cannot lose one of the payments.

### The dashboard

Type `/xcore dashboard` in game and you get a link. Click it and you are in, signed in as yourself: there is no password to set and no token to copy. Each plugin brings its own pages: XBans its sanctions and reports, XAuctionHouse its listings and sales history, XLogin its accounts and sessions, XAntiLag its performance charts. They sit next to XCore's own players list and balances.

Version 2.0 rebuilt it from scratch, with the look of celestis.dev in a light and a dark theme. The overview opens on a block of glass that turns a quarter as you scroll, with a live figure on each face: players online, TPS, memory, plugins. On a phone, tables become cards and the sidebar becomes a sheet. A first visit downloads about 200 KB and later ones about 20 KB, and nothing comes from the internet: fonts, item textures and player faces are all served by your own server.

Each member of staff sees and does only what their in-game permissions allow, and the server checks them on every request. Give a moderator `xcore.dashboard` and `xcore.dashboard.xbans`, and they work on the XBans pages with the XBans permissions they already have: without `xbans.ban`, there is no ban button. Ops keep everything. The nodes are listed under [Dashboard permissions](#dashboard-permissions).

> [!IMPORTANT]
> The dashboard speaks plain HTTP, on port 8085 by default. Before you open it to the internet, put it behind a reverse proxy with HTTPS (nginx, Caddy) and set `bind-address: "127.0.0.1"`, so the proxy is the only way in.

<details>
<summary><b>Signing in, sessions, and what protects them</b></summary>

<br>

`/xcore dashboard` answers with a link that opens the dashboard signed in, acting with the permissions of the player who asked for it. The token travels in the link's fragment (`/#token=…`), which a browser never sends to a server, so no access log or proxy log records it, and the page strips it from the address bar on arrival. The sidebar shows who is signed in, with a sign-out button.

Sessions are stored as SHA-256 hashes in `web-sessions.json`, so a copy of that file lets nobody in. A link stays valid for `session-ttl-hours` (24 by default, a week at most: `0`, or more than 168, counts as 168), and a session closes sooner once its owner has been away from the server for `offline-trust-hours`. `/xcore dashboard revoke` closes every session you opened, and `/xcore dashboard revoke <player>` closes someone else's.

Every address is held to 120 requests a minute. Ten failed authentications from the same address within a minute lock it out, but a request carrying a live session still gets through, so a reverse proxy that shows every reader under one address cannot lock a signed-in admin out.

Every response forbids framing (`X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`) and content sniffing (`X-Content-Type-Options: nosniff`). With several origins in `cors-origin`, each request is answered with its own `Origin` when it is on the list, and with no CORS header otherwise.

More on the [dashboard settings](https://celestis.dev/plugins/xcore/wiki/configuration#web-dashboard).

</details>

### Also in the box

| Feature | What it does |
|---|---|
| Deliveries | An item or money a plugin owes a player who is offline, or whose inventory is full, waits for their next join. A full inventory postpones a delivery; it never loses it. |
| Placeholders | `%xcore_balance%`, `%xcore_playtime%`, `%xcore_network_online%`, and every leaderboard a plugin declares, as `%xcore_top_<board>_<rank>_name%`. |
| One language setting | `language: "fr"` in XCore's config translates XCore, every plugin built on it and the dashboard in one edit. English and French are bundled. |
| UUID changes | Switch between offline and online mode, or move behind a proxy, and players keep their balance, sanctions and auction listings instead of starting from nothing. XCore only moves an account it is sure of: a verified Mojang account, or exactly the same name, capitals included. |
| Bedrock players | Recognised through Geyser and Floodgate. Menus leave out what a Bedrock client cannot draw, such as custom skull textures. |
| Support tools | `/xcore diag` writes the report a support thread starts with. `/xcore profile` shows the tick time each plugin spends, and a database query run on a tick thread is named in the console, with its class and line. |
| Folia | Every plugin built on XCore schedules through it, so the whole family runs on Folia. |

<br>

## Quick start

XCore needs Paper 1.21.1 or newer, or Folia, and Java 21 or newer. Vault and PlaceholderAPI are optional: Vault shows the economy to other plugins, PlaceholderAPI serves the placeholders.

1. **Put `XCore-2.0.jar` in `plugins/`.** It is on the [releases](https://github.com/Xyness/XCore/releases) page; the plugins that run on it go in `plugins/` too.
2. **Start the server.** XCore writes `plugins/XCore/config.yml`, and each plugin its own `config.yml` in `plugins/<name>/`. On a single server, SQLite needs nothing more.
3. **Run `/xcore dashboard`** and open the link. For a network, set MySQL or PostgreSQL and `cross-server` in [`config.yml`](#configuration) first, then restart.

> [!NOTE]
> Addons, the jars that carry an `addon.yml`, still go in `plugins/XCore/addons/`. XBans, XLogin, XAuctionHouse and XAntiLag are not addons any more: their jars go in `plugins/`.

### Upgrading from 1.x

In 1.x the four plugins were addons, loaded by XCore from `plugins/XCore/addons/`. In 2.0 each one is a Paper plugin of its own, and each needs XCore 2.0. The database does not change: same tables, nothing to migrate.

1. Stop the server.
2. In `plugins/`, replace `XCore-1.x.jar` with `XCore-2.0.jar`. XCore always comes first, in this same stop.
3. Put the 2.0 jar of each plugin in `plugins/`, and delete its old jar from `plugins/XCore/addons/`.
4. Start the server.

> [!WARNING]
> Never run XLogin 2.0 on XCore 1.x. It does not load there, and XCore 1.x does not close the server in its place: players get in with no authentication at all. Update XCore to 2.0 in the same stop as the plugins, XCore first.

On that first start, each plugin moves its folder from `plugins/XCore/addons/<name>/` to `plugins/<name>/` with all its settings and data, and says so in the console. When the folder cannot simply be renamed, it is copied, and every file is checked against its original before the old folder goes. If even that fails (a locked file, a full disk), nothing is deleted, the plugin does not start and says why, and the next start tries again. Commands keep their names; only the namespaced forms change, `/xbans:ban` instead of `/xcore:ban`.

XCore can also go in alone, ahead of the plugins: the 1.x addons keep running on XCore 2.0, with a warning in the console, until their 2.0 jar is installed. On a network, update every server and the proxies together. On the dashboard, ops see no change, and staff who are not op need the [new permissions](#dashboard-permissions). Once XCore and XLogin are both on 2.0, a server whose XLogin cannot start [stays closed](https://celestis.dev/plugins/xcore/wiki/upgrading#xlogin-safety) instead of open.

The whole guide, with what the console says, the network case and how to roll back, is on **[Upgrading to 2.0](https://celestis.dev/plugins/xcore/wiki/upgrading)**.

<br>

## The plugins built on it

Four plugins, sold on BuiltByBit. Since 2.0 each one is a Paper plugin of its own: it goes in `plugins/` next to XCore, and it requires XCore 2.0.

| Plugin | What it does |
|---|---|
| **[XBans](https://builtbybit.com/resources/xbans.102982/)** | Bans, mutes, warns and jails, with alt detection, IP security and a chat classifier. The same jar runs on the proxy. |
| **[XLogin](https://builtbybit.com/resources/xlogin.103391/)** | Online mode per player: premium players verified against Mojang, everyone else registers. 2FA, map captcha, cross-server sessions. |
| **[XAuctionHouse](https://builtbybit.com/resources/xauctionhouse.103084/)** | Buy-now listings and live auctions in any currency, with categories, search, price history and standing alerts. |
| **[XAntiLag](https://builtbybit.com/resources/xantilag.105658/)** | Chunk caps, mob and item stackers, clearlag and AFK detection, driven by a TPS and MSPT monitor. |

<br>

## Commands and permissions

| Command | What it does | Permission |
|---|---|---|
| `/xcore` | Version, uptime, database, plugins and addons, dashboard | `xcore.admin` |
| `/xcore stats` | Cache hit rates, database and Mojang counters | `xcore.admin` |
| `/xcore addons` | Plugins and addons built on XCore, with their version and state | `xcore.admin` |
| `/xcore dashboard` | One-click login link | `xcore.dashboard` |
| `/xcore dashboard revoke` | Close every session you opened | `xcore.dashboard` |
| `/xcore dashboard revoke <player>` | Close someone else's sessions, from the console too | `xcore.admin` |
| `/xcore reload` | Reload the core config, language files, economy, plugins and addons | `xcore.admin` |
| `/xcore reload <name>` | Reload one plugin or addon | `xcore.admin` |
| `/xcore clear-cache` | Empty the player cache and the head cache | `xcore.admin` |
| `/xcore player <name>` | Player details | `xcore.admin` |
| `/xcore profile` | Time per plugin, per addon and per event handler | `xcore.admin` |
| `/xcore profile on\|off\|reset` | Toggle sampling | `xcore.admin` |
| `/xcore diag` | Write a support report to `plugins/XCore/`: platform, storage, caches, pools, network, plugins and addons | `xcore.admin` |

`xcore.admin` covers every subcommand except `dashboard`, and gives no dashboard access by itself. Someone with only `xcore.dashboard` gets their link from `/xcore` alone.

> [!TIP]
> Run `/xcore diag` before asking for help. It answers most of the questions a support thread starts with, and names the columns of the `players` table that have grown heavy.

<details>
<summary><b>Economy commands</b></summary>

<br>

Aliases: `/economy`, `/money`. The command was `/coins` before 1.0.7.

| Command | What it does | Permission |
|---|---|---|
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

Every amount is rounded down to the currency's decimals before it is applied, and both sides of a transfer get that same amount: with 2 decimals, a payment of `0.015` moves `0.01`. An amount that rounds to 0 is refused, and the player is told the smallest one the currency counts.

`/eco pay` and `/eco exchange` report success only once the other side has been credited. When the credit does not land, the sender gets the money back and is told so. `/eco add` and `/eco remove` are applied in one step, so two changes at the same moment cannot overwrite each other.

An exchange charges only the cost of what it buys, at the target's decimals, and the remainder stays in the balance: with `coins-to-gems: 100` and gems without decimals, exchanging 250 coins gives 2 gems for 200 coins. Rates are written `<from>-to-<to>: <amount>`, the amount of the source currency worth one of the target. Interest is credited per player, rounded down as well.

Underneath, every balance change runs in the database itself (`col = col +/- ?`, guarded on the available funds) and is serialised per player.

</details>

### Dashboard permissions

The dashboard has nodes of its own, and none of them is registered, so they all default to op. A member of staff needs `xcore.dashboard` to sign in and `xcore.dashboard.<module>` for each plugin's pages. Past that, each thing they do or read there asks for the same permission as the matching command or screen in game.

<details>
<summary><b>Every node, and what each action and page needs</b></summary>

<br>

| Permission | Gives |
|---|---|
| `xcore.dashboard` | Logging in, and the overview. `/xcore dashboard` gives the link, and so does `/xcore` alone for someone without `xcore.admin`. A session lasts only while its owner holds this permission |
| `xcore.dashboard.players` | The players list and profiles, clickable player names and the Recent players card |
| `xcore.dashboard.config` | Reading the configuration editor of the modules the reader can open. Passwords, tokens, API keys and webhook addresses read `<hidden>` |
| `xcore.dashboard.config.save` | Saving a configuration, together with `xcore.dashboard.config`, the module's own node and the plugin's save permission: XBans `xbans.admin`, XLogin `xlogin.admin`, XAuctionHouse `ah.admin`, XAntiLag `xantilag.admin`. A module that names none can only be saved by an operator |
| `xcore.dashboard.<module>` | A module's pages, the module name in lower case: `economy`, `xbans`, `xauctionhouse`, `xlogin`, `xantilag`, third-party modules alike |
| `xcore.dashboard.<module>.manage` | The module's actions that declare no permission of their own: every third-party action; XBans kick, freeze and notes. It does not cover saving a configuration |
| `xcore.admin` | Unchanged: every `/xcore` subcommand except `dashboard`, plus `/xcore dashboard revoke <player>`. No dashboard access by itself |

A configuration can make a plugin run console commands, so saving one is trusted like the console, and every save is logged with its author. Saving `<hidden>` unchanged keeps the stored value.

**Acting.** An action needs, besides the page's own permission, the permission of the matching command in game.

| Action | Permission |
|---|---|
| XBans: ban / unban | `xbans.ban` / `xbans.unban` |
| XBans: IP ban / unban | `xbans.banip` / `xbans.unbanip` |
| XBans: mute / unmute | `xbans.mute` / `xbans.unmute` |
| XBans: IP mute / unmute | `xbans.muteip` / `xbans.unmuteip` |
| XBans: warn / remove a warn | `xbans.warn` / `xbans.unwarn` |
| XBans: resolve or delete a report | `xbans.unreport` |
| XBans: jail / unjail | `xbans.jail` / `xbans.unjail` |
| XBans: watchlist | `xbans.watchlist` |
| XBans: appeals | `xbans.command.appeals` |
| XAuctionHouse: cancel a listing | `ah.admin.remove` or `ah.admin` |
| XAuctionHouse: clear expired, ban, unban | `ah.admin` |
| XLogin: reset a password, disable 2FA, delete an account, invalidate a session | `xlogin.admin` |

**Reading.** A page that shows what not every member of staff may see also needs the permission of the matching screen in game, and a reader without it does not get the page at all. Whatever a plugin shows about one player also needs `xcore.dashboard.players`.

| Page | Permission |
|---|---|
| XBans: bans, IP bans, mutes, IP mutes, warns, reports, jails | `xbans.sanctions` |
| XBans: watchlist | `xbans.watchlist` |
| XBans: appeals | `xbans.command.appeals` |
| XBans: audit log, classifier | `xbans.admin` |
| XBans on a player's profile: profile / history / alts / notes | `xbans.profil` / `xbans.history` / `xbans.command.alts` / `xbans.note`, with `xcore.dashboard.players` |
| XBans: the list of players | `xbans.players` and `xcore.dashboard.players` |
| XLogin: players (accounts), sessions | `xlogin.admin` |
| XLogin on a player's profile | `xlogin.admin` and `xcore.dashboard.players` |
| XAuctionHouse on a player's profile | `ah.admin` and `xcore.dashboard.players` |

With LuckPerms, `xcore.dashboard.*` opens every page but grants no `xbans.*`.

**When a change applies.** Permissions are read when the player asks for the link, which they do in game, and saved with the session. They are read again when the player joins and every minute while they are on this server, and a change applies at the next request. Away from the server, a session goes on its last reading for `offline-trust-hours` (2 by default, 168 at most), then closes, and its owner gets a new link with `/xcore dashboard`; `0` keeps it open only while they are on this server. A rank taken away in the meantime therefore applies within that time at most. `/xcore dashboard revoke <player>` closes someone's sessions at once, and XBans closes the sessions of a player it bans, except for a shadow ban, which closing them would give away.

**After updating from 1.x**, ops see no change. Staff who are not op and only had `xcore.admin` have their sessions closed on the first start: give them `xcore.dashboard` and the nodes of the pages they use, and they ask for a new link.

The same list, on the website: [Dashboard roles](https://celestis.dev/plugins/xcore/wiki/configuration#dashboard-roles).

</details>

<br>

## Configuration

Everything is in `plugins/XCore/config.yml`. On a single server the defaults are enough. For a network, these are the lines that matter:

```yaml
database-type: mysql      # sqlite, mysql or postgresql
server-name: "survival"   # how the other servers know this one

database:
  host: localhost
  port: 3306
  name: xcore
  username: root
  password: ""

cross-server:
  enabled: true           # needs MySQL or PostgreSQL
  redis:
    enabled: false        # optional: without Redis, sync goes through the database
```

`/xcore reload` applies a change, except to the database, Redis, the cache sizes and the dashboard port, which need a restart. Two more switches live next door, in `plugins/XCore/addons.yml`: `addons.<name>: false` turns a plugin or an addon off, and `sync-addons.<name>` decides whether it shares its data across servers.

<details>
<summary><b>The whole config.yml</b></summary>

<br>

```yaml
# Verbose cache, database and API logging. Database access from a tick thread is reported while
# the server runs either way; debug also reports it at startup and shutdown.
debug: false

# Time spent per plugin and addon per event handler, readable with /xcore profile.
profiling: false

# Language for XCore and every plugin built on it. Bundled: en, fr. A plugin may override it with
# its own `language` key. Console logs stay in English.
language: "en"

# sqlite, mysql or postgresql
database-type: sqlite

server-name: "default"

# Move the row of a player returning under a different UUID instead of creating a second one.
# The plugins registered for it move their own data too, before their login checks run.
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
  bind-address: "0.0.0.0"  # "127.0.0.1" behind a reverse proxy on the same machine
  session-ttl-hours: 24    # how long a link stays valid, 168 (a week) at most
  offline-trust-hours: 2   # how long a session lasts while its owner is not on this server, 0 = only while they are
  metrics-public: true
  cors-origin: "*"         # one origin, or several separated by commas
  public-url: ""           # when a proxy or a domain sits in front

# Answer /plugins with Paper's own list plus a section for the addons in plugins/XCore/addons/,
# which the server does not know about. Off hands the command back to the server.
plugins-command:
  override: true

# What a plugin owes a player but could not hand over. Given on their next join.
delivery:
  on-join: true
  per-join-limit: 20

# The name and picture Discord messages are posted under, when the plugin does not set its own.
# Webhook addresses stay in each plugin's own config.
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

A new key under `currencies` adds a currency, and its balance column is created automatically. Renaming or removing one is respected: XCore never writes an entry back into a section whose names you chose yourself.

Every setting, explained: [Configuration](https://celestis.dev/plugins/xcore/wiki/configuration#general).

</details>

### Languages

One setting covers everything: `language: "fr"` in XCore's config translates XCore, every plugin built on it and the dashboard. A plugin can override it with its own `language` key. Messages are written in MiniMessage, and English and French are bundled; a plugin that ships no translation for the chosen language falls back to English and says so once in the console. The console itself stays in English.

| File | Contents |
|---|---|
| `plugins/XCore/lang/<code>.yml` | Everything said in game, `/eco` included, with `eco-` keys |
| `plugins/XCore/lang/web_<code>.yml` | The dashboard's shared vocabulary |
| `plugins/<name>/lang/<code>.yml` | That plugin's messages |
| `plugins/<name>/lang/web_<code>.yml` | That plugin's dashboard strings |

An addon keeps its language files in `plugins/XCore/addons/<name>/lang/`. New keys are appended at startup, and existing values are never overwritten, so your own wording stays. The dashboard gets the core's vocabulary merged with every module's file from `/api/lang`, the core winning on overlap.

### PlaceholderAPI

With PlaceholderAPI installed, XCore registers the `xcore` placeholders, and each plugin built on it publishes its own.

<details>
<summary><b>Every XCore placeholder</b></summary>

<br>

| Placeholder | Returns |
|---|---|
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

</details>

<br>

## Build on XCore

A plugin built on XCore is a Paper plugin like any other: its own jar in `plugins/`, its own timings, its own data folder `plugins/<name>/`. Its main class extends `XPlugin` instead of `JavaPlugin`, and from there the database, the caches, the sync, the menus, the languages and the dashboard are one method call away. XBans, XLogin, XAuctionHouse and XAntiLag are built exactly this way. Addons on `XAddon` are still supported, see [Addons](#addons).

### Add the dependency

XCore is on JitPack. It is already on the server at runtime, so it is `compileOnly` and never shaded.

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.Xyness:XCore:2.0'
}
```

<details>
<summary>Maven</summary>

<br>

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.Xyness</groupId>
        <artifactId>XCore</artifactId>
        <version>2.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

</details>

### Declare the plugin

```yaml
# paper-plugin.yml
name: MyPlugin
version: '1.0.0'
main: com.example.myplugin.MyPlugin
author: 'YourName'
description: What this plugin does
api-version: '1.21.1'
folia-supported: true
# Where initUpdater() reads the remote version.yml. Without it, update checking stays off.
update-url: 'https://raw.githubusercontent.com/you/MyPlugin/refs/heads/main/version.yml'
dependencies:
  server:
    XCore:
      load: BEFORE
      required: true
      join-classpath: true     # XPlugin, the API and XCore's libraries
    PlaceholderAPI:            # optional, and the code uses its classes
      load: BEFORE
      required: false          # write it out: an entry without it is required
      join-classpath: true
```

`join-classpath` does not carry over: XCore depending on Vault does not let the plugin see Vault's classes, so every plugin whose classes the code uses gets an entry of its own. There are no `depend`, `soft-depend` or `commands` keys: commands are registered in code.

### Extend XPlugin

```java
public class MyPlugin extends XPlugin {

    @Override
    protected boolean enable() {
        // Writes config.yml on the first run, then adds the keys later versions introduce.
        // Listed paths are skipped entirely: use them for map sections the user manages.
        updateConfigWithDefaults("limits.blocks", "limits.entities");

        // Extracts every bundled translation and loads the one XCore's `language` selects.
        loadLanguage("en", "fr");

        registerListener(new MyListener(this));

        // Commands go through this plugin's own lifecycle manager, last.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> new MyCommands(this).register(event.registrar()));
        return true;
    }

    @Override
    protected void disable() { }

    @Override
    public void reload() {
        reloadConfig();
        loadLanguage("en", "fr");
    }
}
```

`enable()` returning `false` stops the plugin cleanly, with a line in the console. Everything the plugin sets up belongs to it, its tasks, listeners and commands as much as its dashboard module and sync channels, and goes when the plugin is disabled.

<details>
<summary><b>The lifecycle, in full</b></summary>

<br>

- `load()`, `enable()`, `disable()` and `reload()` take the place of `onLoad`, `onEnable` and `onDisable`, which are final. `enable()` returning `false` stops the plugin cleanly, with a line in the console. `disable()` only runs after an `enable()` that returned `true`. `reload()` is what `/xcore reload` and the dashboard's config editor call.
- `startFailed(String reason)` is called on every path where the plugin does not start: XCore not running, its files not brought over, `load()` throwing, `enable()` returning `false` or throwing. Return `true` to stay enabled with only what was set up there, as an authentication plugin keeping the server closed does. XCore may be gone by then: use the server's API, the plugin's own classes and `logger()` only.
- Its tasks (`scheduler()`), listeners (`registerListener`) and commands belong to it and stop with it. What it handed XCore (web module, sync channels, UUID-change handlers, `placeholders()`) is taken back when it is disabled. Hand-written PlaceholderAPI expansions are not: unregister them in `disable()`.
- Register commands last in `enable()`, after the checks that can return `false`: the server cannot take back commands registered through the lifecycle manager short of disabling the plugin.
- `getConfig()` reads the file as it is on disk, without the bundled defaults attached, as an addon's does. `getDataFolder()` is `plugins/<name>/`; on the first start, the addon folder of the same name, `plugins/XCore/addons/<name>/`, is moved there before `load()` runs.
- XCore still lists it: `/xcore addons`, `/xcore reload`, `/xcore diag`, the dashboard and the `addons.yml` switches cover it.

</details>

### What it gives you

Every call below is available in the main class from `enable()` on. One line each; the [API reference](#api-reference) has the rest.

```java
// The database: one pool, the right SQL for SQLite, MySQL and PostgreSQL
api().query("myplugin_homes").select("*").where("player_uuid", uuid).executeAsync();

// The other servers of the network
core().getSyncManager().registerChannel("myplugin", 1, message -> refresh(message.key()));

// Messages from lang/<code>.yml, in the language XCore is set to
player.sendMessage(lang().getComponent("home-set", "home", home));

// Tasks that run on Paper and Folia alike. Never Bukkit.getScheduler()
scheduler().runEntityTask(player, () -> player.getInventory().addItem(reward));

// An item or money owed to someone offline, handed over on their next join
delivery().sendMoney(uuid, "dollar", 250.0, "Vote reward", "MyPlugin");

// A ranking kept in memory, published as %xcore_top_homes_1_name%
leaderboards().define("homes").table("myplugin_stats").value("homes").size(10).refreshEvery(300).register();

// Placeholders without an expansion class: %myplugin_homes%
placeholders().register("homes", (target, arg) -> String.valueOf(count(target))).publish();

// Where a player is on the network
Optional<String> server = network().locate(uuid);

// One webhook queue for the whole installation, Discord's rate limits handled
discord().send(url, DiscordNotifier.embed().title("Home set").description(home).build());

// Per-player cooldowns that expire on their own: 0 means go ahead
long wait = cooldowns().tryUse("sethome", player.getUniqueId(), 30);

// A player back under a new UUID: move your rows before your login checks run
onUuidChange((oldUuid, newUuid, name) -> CompletableFuture.runAsync(
        () -> moveRows(oldUuid, newUuid), api().getDbExecutor()));

// Pages on the web dashboard, described in Java and drawn by the browser
if (core().getWebPanel() != null) core().getWebPanel().registerModule(new HomesWebModule(this));
```

The full walkthrough, with more examples, is the wiki's [Developer Guide](https://celestis.dev/plugins/xcore/wiki/developers#plugin-structure).

### Addons

`XAddon` is still supported, with the same API, for jars in `plugins/XCore/addons/`. An addon has an `addon.yml`, runs under XCore's plugin identity, and keeps its files in `plugins/XCore/addons/<name>/`.

```yaml
# addon.yml
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

```java
public class MyAddon extends XAddon {

    @Override
    public boolean onEnable() {
        updateConfigWithDefaults();
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

A `depend` entry is met by an addon of that name, or by a plugin of that name installed in `plugins/`. The X plugins enable after every addon: an addon depending on one finds its classes in its own `onEnable()`, but should use it from `ServerLoadEvent` on, or check `isEnabled()` first.

<details>
<summary><b>Moving an addon to XPlugin</b></summary>

<br>

1. Extend `XPlugin` instead of `XAddon`.
2. Rename `onLoad()` to `load()`, `onEnable()` to `enable()`, `onDisable()` to `disable()` and `onReload()` to `reload()`, which stays public. The server's methods are final, so a rename you forget is a compile error.
3. Replace `addon.yml` with a `paper-plugin.yml` that keeps the same `name`: the folder move, the `addons.yml` switches and the profiler keys all go by it.
4. Register the commands through the plugin's own `getLifecycleManager()`. XCore's is closed by the time the plugin enables, and registering on it throws.

Then:

- Keep every `NamespacedKey` created with `core()` as it is: the data already saved under XCore's namespace, on items, entities and players, must stay readable.
- Schedule nothing from `disable()`: the server refuses tasks from a plugin being disabled. Do that work synchronously.
- The plugin is disabled before XCore at shutdown, so `core().isEnabled()` no longer means the server keeps running.
- Read no file or setting in a constructor or a field initializer: they run before the folder is moved, and `getConfig()` throws there.

</details>

<br>

## API reference

Open the block you need. The same material, with more examples, is on the wiki: [Developer Guide](https://celestis.dev/plugins/xcore/wiki/developers#setup), [Database API](https://celestis.dev/plugins/xcore/wiki/database#tables) and [Shared Services](https://celestis.dev/plugins/xcore/wiki/services#deliveries).

<details>
<summary><b>Every method on XPlugin and XAddon</b></summary>

<br>

The same methods, under the same names, on `XPlugin` and on `XAddon`.

| Method | Description |
|---|---|
| `api()` | Database, cache, sync, GUI, Vault, web, UUID changes |
| `core()` | The XCore plugin: sync manager, web panel, settings |
| `scheduler()` | The Folia-compatible SchedulerAdapter. On a plugin, its tasks belong to the plugin and stop with it. `runAsyncTaskLater` runs off the server thread on Paper too: a delayed callback must not touch players or the world directly |
| `logger()` | Logger scoped to the plugin |
| `lang()` | The plugin's language namespace |
| `guiRegistry()` | GUI definitions loaded from YAML |
| `guiUtils()` | Item building, sounds, heads, click handling |
| `delivery()` | Give an item or money to someone offline, handed over on their next join |
| `leaderboards()` | Declare a ranking once, read it from memory |
| `network()` | Which servers are up, and where a player is |
| `ranks()` | Primary group and numbered permission levels, LuckPerms or Vault |
| `discord()` | The shared webhook sender |
| `cooldowns()` | Per-player cooldowns for this plugin |
| `placeholders()` | Publish placeholders without writing an expansion |
| `registerListener(listener)` | A listener that belongs to the plugin, timed by `/xcore profile` and counted by `/xcore diag` |
| `onUuidChange(handler)` | Move this plugin's data when a returning player's UUID changed |
| `renamedKeys(file, renames)` | Keep the value an administrator gave a renamed key |
| `getConfig()` | The plugin's config.yml, as it is on disk |
| `getDataFolder()` | `plugins/<name>/`; an addon's is `plugins/XCore/addons/<name>/` |
| `saveDefaultConfig()` | Write the bundled `config.yml` if missing, without merging |
| `saveDefaultResource(path)` | Write any bundled file if missing |
| `updateConfigWithDefaults(String... protectedSections)` | Defaults on the first run, new keys later. Listed paths are skipped, and so is anything the administrator names themselves |
| `loadLanguage(String... available)` | Extract the bundled translations and load the chosen one |
| `getLanguage()` | The language the plugin speaks: its own `language` key, or XCore's |
| `initUpdater()` / `updater()` | Update checker, reading `update-url` from `paper-plugin.yml` (`addon.yml` for an addon). Check asynchronously: `scheduler().runAsyncTask(() -> updater().checkForUpdates())` |
| `notifyUpdateOnJoin(player, permission)` | Tell a joining player who holds the permission that an update is out |
| `startFailed(reason)`, `getState()`, `isHeld()` | On `XPlugin` only: what happens when the plugin cannot start, and where it stands |

</details>

<details>
<summary><b>Database: tables, player columns, queries, transactions</b></summary>

<br>

Plugins built on XCore share its connection pool and never open their own. The builders write the right dialect for SQLite, MySQL and PostgreSQL.

```java
api().tableManager().createTable("myplugin_homes")
    .column("id", ColumnType.SERIAL)
    .column("player_uuid", ColumnType.CHAR, 36).notNull()
    .column("name", ColumnType.VARCHAR, 32).notNull()
    .index("player_uuid")
    .uniqueIndex("player_uuid", "name")
    .build();
```

**Player columns.** A single value per player can go in the shared `players` table:

```java
api().columnBuilder()
    .addColumn("last_kit", ColumnType.VARCHAR).length(32).defaultValue("").notNull()
    .apply();
```

Two `ColumnType` enums exist: `createTable` takes `fr.xyness.XCore.Database.ColumnType`, `columnBuilder()` takes `fr.xyness.XCore.API.ColumnType`.

The `players` table is pushed to Redis in full on every write, by every plugin. A value that grows belongs in its own table. `dropColumn(name)` removes a column once its data has moved; `core().methods().columnExists(table, column)` says whether there is anything to migrate. `core().playerDAO().findPageAsync(offset, limit)` and `countAsync()` walk the table a page at a time; there is no method returning every row at once.

**Queries.**

```java
api().query("table").select("*").whereIn("uuid", uuids).executeAsync();
api().query("table").update().setRelative("score", 5).where("uuid", id).executeUpdateAsync();
api().query("a").leftJoin("b", "a.id", "b.a_id").select("a.name", "b.score").executeAsync();
api().query("table").insert().addRow(Map.of(...)).addRow(Map.of(...)).executeBatchAsync();
api().query("table").where("a", 1).or().where("b", 2).executeAsync();
```

`setRelative` does the arithmetic in the database (`col = COALESCE(col, 0) + ?`), so two servers cannot both read the old value and write back a total that loses the other's change.

`executeUpdateAsync()` returns the number of rows affected, which is what makes a conditional write usable: `UPDATE ... WHERE stock > 0` touching one row is a sale, touching none is "out of stock".

`execute()`, `executeUpdate()` and `executeCount()` are the same queries run on the calling thread, for code that is already off the main one: a `PagedGui` loading its list, a task on the database pool. They save a `join()`, and a `join()` from the database pool onto the database pool is exactly what deadlocks. From a tick thread they block the server, and the console names the call site.

`orderBy` can be called more than once; the clauses stack in the order given.

**Upserts, transactions, migrations.**

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

**Two pools.** `api().getExecutor()` is for logic, `api().getDbExecutor()` for anything that opens a connection. Never wait on a task of the pool you are running on: with a single pool, a thread ends up waiting for work queued behind itself. A connection taken on a tick thread while the server runs is reported in the console, once per call site, with the class and the line to change.

</details>

<details>
<summary><b>Sync between servers, and channel versions</b></summary>

<br>

```java
core().getSyncManager().registerChannel("myplugin", 1, message -> handle(message));
core().getSyncManager().publish("myplugin", new SyncMessage("UPDATE", id, payload));
```

One transport for every plugin: Redis Pub/Sub when Redis is configured, the `xcore_sync` table polled otherwise. `core().isSyncEnabledFor("MyPlugin")` says whether sync is on for this plugin (`cross-server.enabled`, and `sync-addons.<name>` in `addons.yml`). A plugin's channels are removed when it is disabled.

Every message carries the protocol version of its channel. A message of another version is not handed to the listener, and the console says once, per channel and server, which side is behind. `registerChannel(name, listener)` still exists and means version 0, the version of every XCore 1.x message. Raise the number only when an older listener would misread the messages (an action renamed, a payload laid out differently): while a network is being updated, servers on different versions then stop hearing each other on that channel instead of acting on the wrong data.

</details>

<details>
<summary><b>UUID changes</b></summary>

<br>

When XCore recognises a returning player under a new UUID (a server switched between offline and online mode, or put behind a proxy that changes it), it moves its own row during the player's pre-login, then calls every registered handler. The login waits for them, side by side and five seconds each at most, before the plugins' own login checks run, so those checks already find the data under the new UUID.

```java
onUuidChange((oldUuid, newUuid, name) -> CompletableFuture.runAsync(
        () -> moveMyRows(oldUuid, newUuid), api().getDbExecutor()));
```

- The handler is called on XCore's work pool: return a future at once, and do the SQL on `api().getDbExecutor()`.
- Make the move one transaction, harmless to run twice. Nothing is retried. A handler that fails or runs out of time is reported in the console under the plugin's name, and the login goes on.
- On a network sharing one database, it runs once, on the server where XCore moved the row: move every server's rows, then publish your usual sync message so the other servers drop what they cached.
- Register it in `enable()`; it is taken back when the plugin is disabled. A plugin not built on XCore registers with `api().uuidChanges().register(this, handler)`.
- Pre-login listeners at `LOWEST` run before XCore. Never change a connection's UUID after that priority: XCore moves the data onto the UUID it sees at `LOW`.

A handler is only called for a player XCore is sure of. Only a version-4 UUID proves who connects: online mode, a proxy forwarding Mojang UUIDs, XLogin's REAL mode. A row moves when that verified identity matches the Mojang UUID stored with it, or when the row carries exactly the same name, capitals included. A name in other capitals is another player: an offline `steve` never takes `Steve`'s account, he gets a fresh one and the console says so once. Two Mojang accounts are never matched by name, and when several rows could match, none moves and the console warns.

The whole recognition is bounded: about 10 seconds to find and move the row, 2 more for a move already under way, then 5.5 for the handlers. Past that the login goes on; a move that had not begun waits for the next login, and one already begun finishes in the background.

`PlayerUuidChangeEvent` is fired afterwards, asynchronously, for code that only needs to know; nothing waits for it. `/xcore diag` lists who registered a handler.

</details>

<details>
<summary><b>Config files: defaults, renamed keys, pruning</b></summary>

<br>

| Method | Effect |
|---|---|
| `renamedKeys(file, renames)` | Moves the value of a renamed key to its new name, in `config.yml` or in every `lang/*.yml`, so the administrator's setting survives the rename. Declare it before the merge. |
| `updateConfigWithDefaults(protected...)` | Adds keys the bundled default has and the file lacks. Never overwrites a value. |
| `pruneObsoleteConfigKeys(protected...)` | Removes keys the bundled default no longer has. |
| `pruneObsoleteKeys(file, protected...)` | The same, for any file. |
| `setAsideIfUnreadable(file)` | Copies a file that is not valid YAML to `<name>.unreadable-<millis>` and reports it in the console. `updateConfigWithDefaults` does it for `config.yml`: the defaults are used until the copy is repaired and put back. |

Pruning copies the file to `<name>.pre-prune.bak` and logs every key dropped. Protected sections (world names, material maps, shop entries) are never added to and never pruned. YAML lists count as values.

Two more things are left alone on their own, without having to be declared. A section whose children are all sections is a list of named entries, not a group of settings: once `economy.currencies`, `shops` or `kits` exists on disk, nothing under it is written back, so renaming an entry does not bring the old one back on the next start. And a list whose key was deleted stays deleted, unless the whole section around it is new.

**Renamed keys.** A renamed key otherwise reads as one key to add and one to remove: the merge writes the new name with its default, the prune drops the old one, and the administrator's value is lost. Declare the renames first in `enable()`, before `updateConfigWithDefaults`, `loadLanguage` and `pruneObsoleteKeys`:

```java
renamedKeys("config.yml", Map.of("anti-afk.enabled", "afk.enabled"));
renamedKeys("lang/*.yml", Map.of("lore-buyout", "lore-buyout-drop"));
updateConfigWithDefaults();
loadLanguage("en", "fr");
```

A value moves only when the new key is absent. Nested paths and whole sections move, a chain of renames is followed, and running it twice changes nothing, so keep the declarations from one version to the next. `*` in the last segment covers every language file, the administrator's own included. Each move is logged once. A key that moved to another file is not covered.

</details>

<details>
<summary><b>Deliveries, leaderboards, network, Discord, placeholders, play time</b></summary>

<br>

**Deliveries.** What a plugin owes a player but cannot hand over right away: the inventory was full, or the player was offline. Stored, then given on their next join.

```java
delivery().sendItem(uuid, item, "Auction sale", "XAuctionHouse");
delivery().sendMoney(uuid, "dollar", 250.0, "Vote reward", "XVote");
delivery().countPending(uuid).thenAccept(count -> ...);
```

Money always goes through. An item is only marked as taken once it is really in the inventory, so a full inventory postpones it instead of losing it.

**Leaderboards.** Declared once, refreshed on a timer, read from memory.

```java
leaderboards().define("kills")
    .table("xtools_warzone").value("kills")
    .size(10).refreshEvery(300)
    .register();
```

That publishes `%xcore_top_kills_1_name%` and `%xcore_top_kills_1_value%`, and `leaderboards().get("kills").top(10)` returns the snapshot without touching the database.

**Network.** Every server announces itself on the sync channel, so a plugin can know what the network looks like.

```java
network().servers();                  // name, players, TPS, version
network().locate(uuid);               // which server holds this player
network().totalOnline();              // across the network
network().send("lobby", "mychannel", new SyncMessage("KICK", uuid.toString()));
```

**Discord.** One queue for the whole installation: retries, and a webhook that answers 429 is left alone for as long as Discord asks. Each plugin keeps its own webhook address in its own config.

```java
discord().send(url, DiscordNotifier.embed()
        .title("Ban")
        .description(player + " was banned by " + staff)
        .color(0xC0362C)
        .field("Reason", reason, false)
        .timestamp()
        .build());
```

`discord().sendRaw(url, json)` takes a payload a plugin has already assembled.

**Placeholders.** A plugin publishes its own without writing an expansion class:

```java
placeholders()
    .register("kits", (player, arg) -> String.valueOf(available(player)))
    .register("cooldown", (player, arg) -> Formats.duration(remaining(player, arg)));
placeholders().publish();
```

The identifier is the plugin name in lower case, so for a plugin named MyKits those become `%mykits_kits%` and `%mykits_cooldown_starter%`.

**Play time.** XCore counts how long each player has been connected, across sessions, the session in progress included.

```java
long seconds = api().getPlaytime(uuid);
```

</details>

<details>
<summary><b>Dashboard pages and their permissions</b></summary>

<br>

A `WebModule` gives a name, an icon, its pages and its routes, registered under `/api/<name>/`. XCore serves the description of the pages on `/api/modules`, and the browser builds them.

```java
@Override
public List<WebPage> getPages() {
    return List.of(
        new WebPage("bans", "bans", "ban", WebPageSpec.table("/api/myplugin/bans")
            .titleKey("myplugin-bans")         // every ...Key is a lang/web_<code>.yml key
            .dataKeys("bans")                  // where the rows are in the payload
            .emptyKey("no-records-found")
            .search("search-players")          // adds ?search=
            .paged(50)                         // adds ?page=&limit=
            .readPermission("myplugin.bans")   // what the matching screen asks for in game
            .actions(WebPageSpec.action("unban", "/api/myplugin/unban")
                .style("danger")
                .idFrom("player_name")         // names the row in the confirmation
                .send("player", "player_name")
                .permission("myplugin.unban")) // what /unban asks for in game
            .form("ban-player", "/api/myplugin/ban",
                WebPageSpec.field("player", "player", true).placeholderKey("player-name"),
                WebPageSpec.field("reason", "reason", false).placeholderKey("reason"))
            .formPermission("myplugin.ban")),
        new WebPage("statistics", "stats", "chart", WebPageSpec.stats("/api/myplugin/stats")
            .titleKey("myplugin-statistics")
            .tile("total-bans", "red", "totalBans")
            .toggles("feature-toggles", "no-feature-toggles-available", "features")
            .chart("daily-volume", "", "days", "date", "value", "no-chart-data-available")),
        new WebPage("configuration", "config", "settings", WebPageSpec.config()));
}
```

| Page kind | Builds |
|---|---|
| `WebPageSpec.table(endpoint)` | Rows, with optional search, filters, paging, row actions, a creation form and a page-wide button |
| `WebPageSpec.stats(endpoint)` | Statistic tiles, an enabled/disabled list, a key/value card, a chart (a line for a long time series, columns otherwise, each with a table view) |
| `WebPageSpec.config()` | The raw `config.yml` editor, served by XCore for every module |

Tile colours: `green`, `orange`, `purple`, `red`. Button styles: `danger`, `warning`, `success`, `secondary`. A label with no entry in `lang/web_<code>.yml` renders as the key. `new WebPage(name, path, icon)` without a descriptor falls back to the generic table renderer.

**Acting.** Each thing a page lets the reader change names the permission the matching command asks for in game: `.permission(...)` on an action, `formPermission` for the creation form, `bulkPermission` for the page-wide button. Several nodes mean any one of them. A change that names none needs `xcore.dashboard.<module>.manage`, and every page of the module needs `xcore.dashboard.<module>`; `WebPanel.modulePermission(name)` and `WebPanel.managePermission(name)` give those two nodes. The panel checks them on every request, and leaves out the buttons a reader could not use.

**Reading.** A page that shows what not every member of staff may see takes `readPermission(...)`, the permission of the matching screen in game. It covers every endpoint the page reads, its charts included, and a reader without it does not get the page. A route no page describes, such as the `/api/<module>/player/<name>` routes the player profile reads from every module, is declared by the module, `*` standing for one path segment:

```java
@Override
public List<WebRoute> getRoutePermissions() {
    return List.of(WebRoute.of("/api/myplugin/player/*")
        .requires("myplugin.lookup")                  // each requires() is needed
        .requires(WebPanel.PLAYERS_PERMISSION));      // several nodes in one: any of them
}
```

**Saving the configuration** needs `xcore.dashboard.config.save` and what the module names for it, usually its reload command's permission: from `getConfigSavePermission()`, or with `savePermission(...)` on its configuration page, both being needed when both are set. A module that names none can only have its configuration saved by an operator.

```java
@Override
public List<String> getConfigSavePermission() {
    return List.of("myplugin.admin");   // what /myplugin reload asks for
}
```

Do not register `<base>/config/raw` yourself: XCore registers it for every module, and a second context on the same path throws and stops the plugin from enabling.

`core().getWebPanel().sessionOwner(exchange)` gives the name of the player whose session a request carries, so an action taken from the dashboard is recorded under the admin who took it.

**Conventions.**

| Convention | Effect |
|---|---|
| `field-<key>` in `lang/web_<code>.yml` | Names a payload field. `player_uuid` and `playerUuid` resolve to the same label |
| `value-<identifier>` | Names a value: `value-buy-now` becomes "Achat direct" |
| `itemDetails` on a row | Renders the Minecraft item cell: sprite, tooltip, enchantments, durability |
| `translationKey` inside `itemDetails` | Names the item in the reader's language |
| `.paged(n)` on a spec | Pages the table, server-side when the endpoint answers `total` and `page` |

`/api/sprite/<material>.png` and `/api/mclang` are fetched server-side, cached and pinned to the server's Minecraft version, so the browser never calls a third party. `/api/mclang` answers in the language of the player whose link is in use. Player faces come the same way, from `/api/head/<name>.png`.

</details>

<details>
<summary><b>Web API</b></summary>

<br>

Every endpoint needs `Authorization: Bearer <session token>`, except the public ones below. A script takes its token from a `/xcore dashboard` link and acts with the permissions of the player who asked for it; revoke it with `/xcore dashboard revoke` when the script is retired.

```bash
curl -H "Authorization: Bearer YOUR_SESSION_TOKEN" http://localhost:8085/api/modules
```

| Endpoint | Description |
|---|---|
| `GET /api/lang` | Dashboard strings in the configured language. Public |
| `GET /api/auth` | Whose session the token opens, and what it may open and do |
| `GET /api/modules` | The web modules the session may open, and their page descriptors |
| `GET /api/metrics` | Server name, uptime, player count and TPS, public with `metrics-public: true`. Versions, memory, storage and cache figures are added for an authenticated caller |
| `GET /api/players` | The players list, searchable and paged. Needs `xcore.dashboard.players` |
| `GET /api/head/<name>.png` | A player's face, fetched once by the server and cached. Public, limited per address, and never holding up the rest of the dashboard |
| `GET /api/sprite/<material>.png` | An item's texture, served the same way. Public |
| `GET/POST /api/<module>/config/raw` | Read or replace a module's `config.yml`. The YAML is parsed before anything is written, and the plugin is reloaded. Reading needs `xcore.dashboard.config`, and secrets come as `<hidden>`; saving also needs `xcore.dashboard.config.save` and the module's save permission, and is logged with its author |
| `/api/<name>/...` | Each plugin's own routes |

Every route is held to the permissions of the session's owner. A request missing one is answered `403`, with the permission named:

```json
{"error":"Missing permission: xbans.ban","permission":"xbans.ban","anyOf":["xbans.ban"]}
```

</details>

<details>
<summary><b>Menus: the GUI framework</b></summary>

<br>

**Defining a GUI.** Files go in `src/main/resources/guis/`, one per screen:

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

**Loading them.**

```java
// In enable()
File guisFolder = new File(getDataFolder(), "guis");
if (!guisFolder.exists()) {
    guisFolder.mkdirs();
    saveDefaultResource("guis/my_gui.yml");
}
guiRegistry().clearAndPutAll(GuiLoader.loadAll(guisFolder, logger()));

// In reload()
File guisFolder = new File(getDataFolder(), "guis");
guiRegistry().clearAndPutAll(GuiLoader.loadAll(guisFolder, logger()));
```

**Using a definition.**

```java
import fr.xyness.XCore.Gui.*;

GuiDefinition def = addon.guiRegistry().get("my_gui");

Inventory inv = Bukkit.createInventory(holder, def.getRows() * 9,
    lang.getComponent(def.getTitleKey(), "page", "1", "max", "5"));

GuiItem itemDef = def.itemAt(48);
ItemStack navItem = guiUtils.createItemFromDef(itemDef, title, lore);
```

**GuiUtils.**

| Method | Description |
|---|---|
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

Never mutate the `ItemStack` returned by `inv.getItem(slot)`: it is a live mirror of what the client holds. `updateGuiItem` clones it.

**Handling clicks.**

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

**A paginated screen.** `PagedGui` covers what a list screen always needs: page maths, the bar at 48 / 49 / 50, the blink task and its cancellation, and the click routing. XCore listens for these screens itself, so nothing has to be registered.

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

`items()` runs off the server thread, on every open and every page change, so reading the database in it is the expected thing to do.

Entries are drawn once per page. `itemsBlink()` returns true for a list whose entries have two faces; they then go through the blink cache, which is dropped every `itemCacheTicks()` so a remaining time in a lore still moves.

A list too large to hold in memory, every account a server has ever seen for one, pages in the database instead: override `totalItems(viewer)` with the count and `loadPage(viewer, page, perPage)` with that one page. `items()` is then never called.

**Blinking buttons.**

```java
BlinkCache blink = new BlinkCache();   // one per open GUI
boolean[] check = {true};
// On the player's own thread: the open inventory is theirs, never touched from an async one.
Object blinkTask = scheduler().runEntityTaskTimer(player, () -> {
    GuiItem navDef = def.itemAt(48);
    if (navDef != null) {
        inv.setItem(navDef.getSlot(),
            guiUtils.blinkBarItem(blink, lang, navDef, check[0], player, "page", "2"));
    }
    check[0] = !check[0];
}, 0L, 10L);
```

Only cache an item whose content depends on nothing but the blink state. An item showing a countdown, a live price or a remaining time has to keep being rebuilt.

</details>

<details>
<summary><b>Scheduler and shared utilities</b></summary>

<br>

`scheduler()` runs the same code on Paper and on Folia: `runGlobalTask`, `runEntityTask` and `runAsyncTask`, each with a `Later` and a `Timer` form, `runLocationTask` and `runChunkTask` with a `Later` form, plus `teleportAsync`, `getChunkAtAsync` and `cancelTask`. Never `Bukkit.getScheduler()`. The state of an entity is changed on the thread of its own region, through `runEntityTask(entity, ...)`. Since 2.0, `runAsyncTaskLater` runs off the server thread on Paper too. The whole table is on the wiki: [SchedulerAdapter](https://celestis.dev/plugins/xcore/wiki/developers#scheduler).

| Class | Purpose |
|---|---|
| `fr.xyness.XCore.Utils.WorldConfig` | Per-world overrides (`worlds.<name>.<key>`), resolved once at load |
| `fr.xyness.XCore.Utils.Formats` | Durations both ways, thousands separators, compact numbers, byte sizes |
| `fr.xyness.XCore.Utils.Items` | Items to base64 and back, giving without losing the overflow, counting, taking |
| `fr.xyness.XCore.Utils.Cooldowns` | Per-player cooldowns that expire on their own |
| `fr.xyness.XCore.Utils.Notify` | Titles, action bars and boss bars |
| `fr.xyness.XCore.Utils.ConfigMerger` | Adds new settings to a config without resurrecting what was removed |
| `fr.xyness.XCore.Gui.BlinkCache` | Holds both faces of a blinking item |
| `fr.xyness.XCore.Gui.PagedGui` | Base class for a paginated screen: pages, bar, blink, clicks |
| `fr.xyness.XCore.Commands.CommandHelpers` | Suggestions, offline target resolution, the `-s` flag |
| `fr.xyness.XCore.Utils.Profiler` | Per-plugin and per-addon timings behind `/xcore profile` |
| `fr.xyness.XCore.Database.GuardedDataSource` | Names any query made from a tick thread while the server runs; with `debug: true`, at startup and shutdown too |
| `guiUtils().blinkBarItem(blink, lang, itemDef, on, viewer, ...)` | Renders a bar item and caches both faces |
| `GuiUtils.handleCommonFeatures(player, slot, click, def)` | Sound, actions and permission of the clicked item |
| `LangNamespace.escape(text)` | Neutralises the MiniMessage tags in text a player wrote, before it is passed to a message |
| `scheduler().runEntityTask(player, task, retired)` | Runs `task` on the player's thread, or `retired` instead when they left first, so what it was to hand over can be put back. Exactly one of the two runs |

</details>

<details>
<summary><b>Publishing an API for other plugins</b></summary>

<br>

A plugin can publish an API other plugins compile against. It ships the API classes in its own jar; consumers use `compileOnly` and must not shade them, or they would hold a different `Class` with the same name and a listener registered on it would never fire.

A consumer declares the publisher under `dependencies.server` in its `paper-plugin.yml`, with `join-classpath: true` so the classes are visible to it, or with `depend: [Name]` in a `plugin.yml`. An addon needs no declaration: XCore looks a missing class up across the other addons' loaders, and then the server's plugins, before giving up.

[XAntiLag-API](https://github.com/Xyness/XAntiLag-API) is the reference.

</details>

<br>

## Building from source

```bash
git clone https://github.com/Xyness/XCore.git
cd XCore
./gradlew clean shadowJar
```

The jar lands in `build/libs/XCore-2.0.jar`.

<br>

## Support

Questions, bug reports and ideas go to the [Discord](https://discord.com/invite/6sRTGprM95). Every page of the documentation is on [celestis.dev](https://celestis.dev/plugins/xcore/wiki/getting-started). XCore is released under the [MIT license](LICENSE).

<p align="center">
  <sub>XCore is the framework of a small family: <a href="https://celestis.dev/plugins/xbans">XBans</a>, <a href="https://celestis.dev/plugins/xlogin">XLogin</a>, <a href="https://celestis.dev/plugins/xauctionhouse">XAuctionHouse</a> and <a href="https://celestis.dev/plugins/xantilag">XAntiLag</a>. Made by <a href="https://github.com/Xyness">Xyness</a>.</sub>
</p>
