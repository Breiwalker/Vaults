# Vaults

A per-player, multi-page vault plugin for **Paper** and **Folia** servers. Each player gets a
configurable number of pages (up to 6 rows each), stored in SQLite, and can optionally share
access to their vault with other players.

- **Java:** 21
- **Server:** Paper / Folia **1.20.5 – 26.3** (`api-version: '1.20.5'`)
- **Storage:** SQLite (WAL mode), with automatic migration from the legacy YAML format
- **Metrics:** bStats (plugin id `29399`) + opt-out server telemetry (relayed to Discord)

---

## Features

- Per-player vaults with **multiple pages** and arrow navigation (previous/next).
- **Configurable** rows per page, total pages, and inventory title.
- **Sharing**: grant or revoke access to your whole vault by player name (stored by UUID, so it
  survives name changes and works for offline players).
- **Admin command** (`/vaultadmin`) to inspect, clear, and manage shares for any player.
- **Update checker** (Modrinth) with console and OP join notifications.
- **bStats** metrics with custom charts (`total_pages`, `vault_users`, `update_checker`); opt-out
  via bStats' own `config/bstats/config.yml`.
- **Telemetry** (separate, opt-out): an anonymous server report relayed to the developer's Discord
  — see [Data collection & telemetry](#data-collection--telemetry).
- Fully **Folia-aware**: all vault work is scheduled through async / global / player schedulers,
  with ordered DB writes and lock-guarded session snapshots.

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Server      | Paper or Folia **1.20.5 – 26.3** |
| Java        | **21** |

> `api-version` declares the **minimum** supported server version (`1.20.5`). Newer Paper
> versions (including the current `26.3` scheme) load it without any upper bound.

The SQLite JDBC driver is bundled via `plugin.yml` `libraries`, so no extra setup is needed.

---

## Installation

1. Download the latest `Vaults-<version>.jar` from the
   [releases](https://modrinth.com/plugin/vaults) page (or build from source).
2. Drop the jar into your server's `plugins/` folder.
3. Restart (or run `/plugman load Vaults`).
4. Optionally edit `plugins/Vaults/config.yml`.

---

## Commands

### `/vault` — players only

| Command | Description |
|---------|-------------|
| `/vault` | Open your own vault at page 1. |
| `/vault <player>` | Open another player's vault (only if they shared it with you, or you have `vaults.admin`). |
| `/vault share <player>` | Share your vault with a player (works for offline players by name). |
| `/vault unshare <player>` | Revoke a player's access to your vault. |
| `/vault shared` | List the players who have shared their vault with you. |

### `/vaultadmin` — op / console (`va` alias)

| Command | Description |
|---------|-------------|
| `/vaultadmin open <player> [page]` | Open a player's vault (player-only). |
| `/vaultadmin shares <player>` | Show who the player shared with, and who shared with them. |
| `/vaultadmin unshare <owner> <guest>` | Revoke a share between two players. |
| `/vaultadmin clear <player> <page\|all> [confirm]` | Delete a page, or all pages (requires `confirm`). |
| `/vaultadmin reload` | Reload the config and run the config migrator. |
| `/vaultadmin info <player>` | Show pages used, storage size, and share counts. |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `vaults.admin` | op | Access to all of `/vaultadmin` and open any vault. |
| `vaults.update` | op | Receive update notifications on join. |

---

## Configuration

`plugins/Vaults/config.yml` (schema version `3`; older files are migrated automatically and new
keys are merged in without clobbering your custom values).

```yaml
vault-settings:
  rows: 6            # rows per page (1-6)
  total-pages: 2     # pages per vault
  title: "Vault %page%"

update-checker:
  enabled: true
  channel: "release"       # release | beta | alpha | all
  notify-console: true
  notify-ops-on-join: true
  notify-permission: "vaults.update"

telemetry:
  enabled: true            # opt out with false; separate from bStats
  endpoint: "https://vaults-reports.breiwalker.workers.dev/report"
  heartbeat-minutes: 360   # 6h
  report-errors: true      # report Vaults' own exceptions only

messages:
  prefix: "&8[&6Vaults&8] "
  # ... every message is customizable (legacy color codes)
```

All messages use legacy color codes (`&a`, `&c`, etc.). Navigation button labels
(`nav-next`, `nav-prev`) intentionally do **not** inherit the prefix.

---

## Data collection & telemetry

Vaults uses two **independent** collection channels. Both are anonymous and can be disabled.

### 1. bStats (aggregate, already opt-out)

bStats (plugin id `29399`) collects aggregate statistics only: player count, plugin version,
online-mode, Java/OS/CPU, and server software + Minecraft version. Custom charts:

- `total_pages` — configured vault page count
- `vault_users` — bucketed number of players with at least one stored page
  (`0`, `1-10`, `11-100`, `101-1000`, `1000+`)
- `update_checker` — whether the update checker is enabled

Opt out globally via bStats' own `config/bstats/config.yml`. Vaults does not touch that file.

### 2. Relayed server report (opt-out, default on)

When `telemetry.enabled` is `true` (default) Vaults POSTs a structured JSON report to the public
relay `https://vaults-reports.breiwalker.workers.dev/report` (a Cloudflare Worker) which formats
it and forwards it to the developer's Discord. The report is sent at startup (~30 s after enable),
on a `heartbeat-minutes` cadence, and immediately when Vaults itself hits an error (rate-limited
and de-duplicated).

The report contains:

- MOTD, server software brand/version, and Minecraft version
- Plugin name + version, player counts (online/max), online-mode
- The full list of installed plugin names + versions
- For errors only: the exception type, message, stack trace (truncated) and a short signature

Redaction before sending: the server data-folder path becomes `<server>`, UUIDs become `[uuid]`,
and online player names become `[player]`. **No server IP, player names or UUIDs are sent.** Free
text is clamped (MOTD ≤ 256, error message ≤ 512, stack ≤ 4000 chars).

Opt out (no requests at all) with:

```yaml
telemetry:
  enabled: false
```

(Blank the `endpoint` to disable it too.) You may also point `endpoint` at your own relay. The
Worker holds the Discord webhook as a server-side secret, so it never ships in the jar; see
[`relay/README.md`](relay/README.md) to deploy it.

---

## Building from source

The project uses Gradle (`shadowJar`) and ships a **wrapper pinned to Gradle 9.8.0** (the
`run-paper` 3.x plugin used for the smoke test requires Gradle 9.7+). Use **JDK 21**, then:

```bash
./gradlew build          # Windows: gradlew.bat build
                         # -> build/libs/Vaults-<version>.jar (shadowJar)
./gradlew runServer      # Windows: gradlew.bat runServer  (Paper version pinned in build.gradle)
```

The bStats classes are relocated to `com.BreiwalkerSMP.Vaults.libs.bstats` in the shaded jar.

The project compiles against `paper-api:1.20.6` (the earliest cleanly-resolvable snapshot; `1.20.5`'s
POM references a deleted `adventure-bom` snapshot). `api-version: '1.20.5'` is still the declared
minimum — the code only uses APIs present in 1.20.5.
