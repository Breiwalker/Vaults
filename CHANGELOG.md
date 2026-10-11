# Changelog

All notable changes to Vaults are documented here.

---

## 2.2.1

### Added
- **Channel-aware update notifications** — the update checker now detects whether the newest
  version is a release, beta, or alpha and uses a matching message (`update-available-beta` /
  `update-available-alpha`, falling back to `update-available`) for both the join notice and the
  console notice.

### Changed
- Version bumped to `2.2.1`.

---

## 2.2

### Added
- **Expanded bStats charts** — alongside `total_pages`, the plugin now reports a bucketed
  `vault_users` chart and an `update_checker` on/off chart. The user count is refreshed
  asynchronously every 30 minutes and never queried from the chart supplier.
- **Opt-out server telemetry** (`telemetry:` config section) — an anonymous, redacted server
  report is POSTed to a public relay at startup, on a configurable heartbeat, and immediately
  when Vaults hits an error. Reports are validated and rate-limited; the Discord webhook is kept
  server-side by the relay.
- **Cloudflare Worker relay** (`relay/`) — validates and rate-limits reports and forwards them to
  a Discord webhook. Not part of the Gradle build.
- **`/vaultadmin reload`** now rebuilds the telemetry schedule in place (no duplicate tasks).

### Changed
- Config schema bumped to **version 3**; the new `telemetry` keys are merged in automatically
  without clobbering existing custom values.
- Version bumped to `2.2`.

### Notes
- Telemetry is **on by default** and ships with a working relay endpoint
  (`https://vaults-reports.breiwalker.workers.dev/report`), so it works with zero server-owner
  setup. It sends no server IP, player names or UUIDs; it redacts the data-folder path, UUIDs and
  player names, and clamps free text. Disable with `telemetry.enabled: false` (or a blank
  `endpoint`). This is separate from bStats' own opt-out.

---

## 2.1

### Added
- **`/vaultadmin`** command (alias `va`, permission `vaults.admin`):
  - `open <player> [page]` — open any player's vault (player-only).
  - `shares <player>` — list who a player shared with and who shared with them.
  - `unshare <owner> <guest>` — revoke a share between two players (console-friendly).
  - `clear <player> <page|all> [confirm]` — delete a single page or all pages
    (`all` requires a literal `confirm` argument).
  - `reload` — reload config and re-run the config migrator.
  - `info <player>` — pages used, storage size (KB), and share counts.
- **`/vault unshare <player>`** — revoke access to your vault.
- **bStats metrics** (plugin id `29399`) with a `total_pages` chart.
- Sharing now resolves **offline players by name** (stored by UUID).

### Changed
- `/vault share` now blocks **self-sharing** and reports **duplicate** shares distinctly
  (`share-already`).
- `api-version` set to `1.20.5` as the **minimum** supported server version, so the plugin loads
  on Paper/Folia **1.20.5 through 26.3** (and later); build compiles against `paper-api:1.20.6`.
  Plugin version bumped to `2.1`.
- Config schema bumped to **version 2**; new message keys are merged in automatically without
  clobbering existing custom values.

### Fixed
- **Nav-slot reservation bypass** — reserved navigation slots are now always occupied
  (arrow or a nav-tagged filler), so shift-click / hotbar-swap can no longer deposit items into
  them and cause items to be lost on reopen.
- **Revoked-share navigation** — page turns re-check share permission; if access was revoked the
  inventory closes with `no-permission`.
- **Self-share & duplicate feedback** — sharing now responds correctly to invalid/duplicate inputs.

---

## 2.0 (Alpha)

### Added
- SQLite storage (`vault_pages`, `vault_shares`, `meta`) replacing the legacy YAML layout.
- Automatic one-shot migration from `userdata/*.yml` (Base64 pages + `shared-with`) into SQLite.
- Multi-page vaults with **previous/next** navigation arrows.
- Whole-vault **sharing** (owner → guest) via `/vault share` and `/vault shared`.
- **Update checker** (Modrinth) with console + OP join notifications.
- Config versioning (`ConfigMigrator`) with `config-version` marker.
- Folia support.

---

## 1.8.x and earlier

- 1.8.1 — bug fixes and polish.
- 1.8 — feature/quality update.
- 1.0 Beta – 1.7 — initial releases and iterative improvements.
