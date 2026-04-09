# XCore

Core framework for the X ecosystem of Minecraft plugins. Provides centralized database management, multi-layer caching, cross-server synchronization, GUI framework, language system, Vault integration, web dashboard, and an addon loading system.

## Features

- **3-Layer Cache**: Caffeine (L1 local) + Redis (L2 shared) + Database (L3 persistent)
- **Cross-Server Sync**: Redis Pub/Sub or database polling, multiplexed for all addons
- **Addon System**: Loaded from `plugins/XCore/addons/`, full lifecycle (load/enable/disable/reload)
- **Multi-Database**: MySQL, PostgreSQL, SQLite with HikariCP connection pooling
- **GUI Framework**: Centralized inventory management with blink animations, pagination, sessions
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

# Redis (optional, enables L2 cache + fast cross-server sync)
redis:
  enabled: false
  host: localhost
  port: 6379

# Cross-server sync (database polling fallback if Redis unavailable)
cross-server-sync:
  enabled: false
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
| `%xcoins_balance%` | Formatted Vault currency balance |
| `%xcoins_balance_raw%` | Raw balance (no formatting) |
| `%xcoins_balance_<id>%` | Formatted balance for specific currency |
| `%xcoins_balance_<id>_raw%` | Raw balance for specific currency |

## Addon Development

### Dependency (Gradle)

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    compileOnly 'com.github.xyness:XCore:1.0.0'
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
| `guiUtils()` | GUI utilities (createItem, noItalic, buildNavLore) |
| `guiRegistry()` | GUI definition registry (loaded from YAML) |
| `getConfig()` | Addon config.yml |
| `getDataFolder()` | `plugins/XCore/addons/<name>/` |

## Building

```bash
git clone https://github.com/Xyness/XCore.git
cd XCore
./gradlew clean shadowJar
```

Output: `build/libs/XCore-1.0.0.jar`

## License

MIT License
