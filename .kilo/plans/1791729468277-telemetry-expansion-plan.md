# Plan: Expand server telemetry (bStats charts + relayed per-server reports)

## Goal

Add richer server reporting in two independent workstreams:

- **A. bStats custom charts** — anonymous, aggregate metrics on the existing bStats dashboard.
- **B. Relayed phone-home** — the plugin POSTs a structured JSON report (server info + Vaults' own errors) to a **public relay URL**, and a **Cloudflare Worker relay** (which holds the real Discord webhook secret server-side) formats and forwards it to your Discord channel.

Everything must be opt-out-able, abuse-resistant, and must never leak secrets or crash the plugin.

---

## Facts established during planning (do not redo)

Current code: `Vaults.java:131` initialises bStats (`org.bstats.bukkit.Metrics`, plugin id `29399`) with one chart `total_pages`. bStats `3.1.0`, relocated to `com.BreiwalkerSMP.Vaults.libs.bstats`. Update checker at `Vaults.java:173`. Config schema is `2` (`ConfigMigrator.CURRENT_CONFIG_VERSION`). Gson is already available (used in `UpdateChecker.java`).

**bStats already collects automatically (no code, nothing to add):** player count (`playerAmount`), plugin version (`pluginVersion`), online-mode (`onlineMode`), Java/OS/CPU, and server software + Minecraft version (`bukkitVersion`).

**bStats Terms §6 explicitly prohibits** collecting IP addresses, server IPs/domains, player names/UUIDs, and free-text fields likely to contain personal data (stack traces). "Server IP" and "plugin errors" therefore **must not** go through bStats. bStats §5 also requires the Metrics class itself be unmodified except package relocation — only `addCustomChart` may be used.

---

## Decisions (locked)

| Question | Decision |
|---|---|
| Channel | **Both**: bStats chart(s) + relayed phone-home |
| Receiver | **Discord webhook, reached via a Cloudflare Worker relay** (webhook secret never ships) |
| Audience | **Public plugin** — the relay URL is public/non-secret and safe to commit |
| "serverip" | **Do NOT send the IP.** Send **MOTD + software brand** as the server label instead |
| Consent | **Opt-out, default ON** (`telemetry.enabled: true`), with a one-time console privacy notice |
| Plugin list | **Full plugin names + versions** |
| Error scope | **Only Vaults' own exceptions** (`report-errors`) |
| Cadence | **Startup + 6h heartbeat + immediately on Vaults error** |
| bStats charts to add | **`vault_users`** (bucketed) and **`update_checker`** (on/off) |
| Webhook secret delivery | **Dev-hosted relay** — Worker keeps the Discord URL; plugin only knows the relay URL |
| Relay host | **Cloudflare Worker** |
| Relay sink | **Forward to Discord only** (no persistence) |

### Recorded risk (user accepted)

Opt-out-ON + full plugin name/version list + MOTD is **identifiable** data (it fingerprints a server) — GDPR/reputation risk. Mandatory mitigations: clear first-run console notice, one-line config opt-out, redaction/truncation, and a `README` privacy section. The relay URL is public by design; the Worker's per-IP rate limiting + validation + the hidden Discord secret are the abuse defenses.

---

## Workstream A — bStats custom charts

1. `DatabaseManager`: add `public int countVaultUsers()` → `SELECT COUNT(DISTINCT owner_uuid) FROM vault_pages` (guard `connection == null`, return `0`, reuse the existing `lock` pattern).
2. `Vaults`: add `private volatile int cachedVaultUsers = 0;`
   - Refresh it **async** at startup and every 30 min (align with bStats' submit interval) via `asyncExecutor`. Never query the DB from a chart supplier.
3. In `initMetrics()` add two charts beside `total_pages`:
   - `new SimplePie("vault_users", () -> bucket(cachedVaultUsers))` — buckets: `0`, `1-10`, `11-100`, `101-1000`, `1000+`.
   - `new SimplePie("update_checker", () -> getConfig().getBoolean("update-checker.enabled", true) ? "enabled" : "disabled")`.
   - Chart suppliers may run on the bStats daemon thread or main thread — only read the cached `volatile`/config.
4. Register chart IDs `vault_users` and `update_checker` on the plugin page at bStats.org (site action, not code).

---

## Workstream B — Relayed phone-home

### B1. New class `TelemetryReporter.java` (`src/main/java/com/BreiwalkerSMP/Vaults/TelemetryReporter.java`)

Responsibilities: build the structured JSON, apply redaction, rate-limit, POST to the relay, and expose `reportStartup()`, `reportHeartbeat()`, `reportError(Throwable)`. Reuse Gson + `HttpsURLConnection` (same style as `UpdateChecker`). **No Discord formatting in the plugin** — the relay formats.

**State file** `plugins/Vaults/telemetry.state` (simple properties/JSON):
- `install-id` — random UUID created on first run (stable per install; never the IP).
- `notice-shown` — boolean for the one-time console notice.
- Persisted independently of the DB so it works before the DB opens.

**Wire payload (plugin → relay)** — `POST` with `Content-Type: application/json`, `User-Agent: BreiwalkerSMP/Vaults/<ver>`, and `X-Vaults-Install: <install-id>`:
```json
{
  "schema": 1,
  "event": "startup|heartbeat|error",
  "installId": "<uuid>",
  "timestamp": 1690000000000,
  "server": { "motd": "...", "brand": "Paper", "softwareVersion": "git-Paper-196", "minecraftVersion": "1.21.11" },
  "plugin": { "name": "Vaults", "version": "2.2" },
  "players": { "online": 3, "max": 100 },
  "onlineMode": true,
  "plugins": [ { "name": "Vaults", "version": "2.2" } ],
  "error": null
}
```
- `server.motd` = `Bukkit.getMotd()`; `brand` = `Bukkit.getName()`; `softwareVersion` = `Bukkit.getVersion()`; `minecraftVersion` = `Bukkit.getMinecraftVersion()`.
- `plugins` = full list from `Bukkit.getPluginManager().getPlugins()` (`name` + `getDescription().getVersion()`).
- `error` when present = `{ "type", "message", "stack", "signature" }`.

**Redaction (before send):**
- Replace the server data-folder path with `<server>`.
- Replace 32-hex UUIDs with `[uuid]` and known online player names with `[player]`.
- Clamp free text: MOTD ≤ 256, error message ≤ 512, stack ≤ 4000 (relay truncates further for Discord).
- Error dedupe: keep signature → last-sent map; suppress the same signature for 6h. `signature` = short hash of `exceptionClass + top frame`.

**Abuse / safety controls:**
- No-op entirely when `telemetry.enabled=false` OR `telemetry.endpoint` is blank.
- Client-side min 1 send / 60s; max 30 / hour.
- Exponential backoff + drop on HTTP 429/5xx; 5s connect/read timeout.
- All network work off the main thread (`asyncExecutor`).
- Wrap everything in `try/catch (Throwable)` — telemetry must never affect the plugin.

**Threading:** gather Bukkit data on the main/global region scheduler (`runOnMain`, Folia-safe), then POST async.

### B2. Wire into `Vaults.java`

- In `onEnable()` after `initMetrics()`: `telemetry = new TelemetryReporter(this); telemetry.init();` (prints the one-time notice; schedules the startup report ~30s after enable and the 6h heartbeat). Do **not** block enable.
- `/vaultadmin reload`: guard so re-init cancels/replaces the prior heartbeat task (no duplicates).
- Feed Vaults' own errors to `telemetry.reportError(t)` at existing catch sites: DB open failure (`Vaults.java:104`), load failure (`:411`), save failure (`:478`), blob (de)serialize failure (`:441`), config-migrate failures, and metrics/telemetry init failures.
- Call `telemetry.shutdown()` in `onDisable()`.

### B3. Config (bump schema `2` → `3`)

`config.yml` additions under a new `telemetry:` section:
```yaml
telemetry:
  enabled: true                          # opt out with false; separate from bStats
  endpoint: "https://vaults-reports.<you>.workers.dev/report"  # public relay; safe to commit
  heartbeat-minutes: 360                 # 6h
  report-errors: true
```
- `ConfigMigrator`: set `CURRENT_CONFIG_VERSION = 3`, add case `2 -> { }` (new keys auto-merge via the existing `migrate` loop — no rename).
- Add the keys with comments to `src/main/resources/config.yml`.
- No `build.gradle` injection needed (the endpoint is not a secret). A server owner may point `endpoint` at their own relay or set `enabled:false`.

### B4. Cloudflare Worker relay (new deliverable)

Create a small Worker (suggest `relay/` folder in this repo, **not** part of the Gradle build; may live in a separate repo):
- `relay/worker.js` — the handler.
- `relay/wrangler.toml` — config + route/custom domain.
- `relay/README.md` — deploy steps.

**Handler behavior:**
1. Accept only `POST`. Reject bodies > 32 KB (413) and non-JSON (400).
2. Validate `schema == 1` and required fields (`event`, `installId`, `timestamp`, `server`, `plugin`); drop malformed with 400.
3. Rate-limit per client IP (e.g. 20/min) → 429 when exceeded. Use `cf-connecting-ip`; a Cache API or Durable Object counter (Durable Object preferred if strictness matters) — small scale, either is fine.
4. Format a Discord embed (server label, MC version, Vaults version, players, online mode, plugin list, install id, event, timestamp; and for `error`, a second embed with type/message/truncated stack/signature) and POST to the Discord webhook.
5. Return `204`. On Discord failure, return 502 (plugin backs off and drops).
6. Never log or echo the Discord URL.

**Secrets / config:** Discord webhook URL stored as a Worker secret (`wrangler secret put DISCORD_WEBHOOK`) — never in the repo or the jar. Deploy with `wrangler deploy`; use a custom domain or the `*.workers.dev` URL as `telemetry.endpoint`.

**Abuse posture:** the relay URL is public, so per-IP rate limiting + size caps + schema validation are the defenses. The Discord webhook stays safe because it never leaves the Worker. Rotate/disable instantly by changing the secret/deploying — no plugin reship.

### B5. Docs

- `README.md`: new "Data collection & telemetry" section — what bStats collects (already anonymous) and exactly what the relayed report sends, with opt-out instructions for both, plus the relay URL.
- `CHANGELOG.md`: new entry under the next version.
- Do not edit bStats' own config or interfere with its opt-out (Terms §5).

---

## Files to change / create

- **Add** `src/main/java/com/BreiwalkerSMP/Vaults/TelemetryReporter.java`
- **Edit** `src/main/java/com/BreiwalkerSMP/Vaults/Vaults.java` (init/shutdown, chart suppliers, cached user count refresh, error hooks)
- **Edit** `src/main/java/com/BreiwalkerSMP/Vaults/DatabaseManager.java` (`countVaultUsers()`)
- **Edit** `src/main/java/com/BreiwalkerSMP/Vaults/ConfigMigrator.java` (`CURRENT_CONFIG_VERSION = 3`, new step)
- **Edit** `src/main/resources/config.yml` (`telemetry:` section)
- **Create** `relay/worker.js`, `relay/wrangler.toml`, `relay/README.md` (Cloudflare Worker)
- **Edit** `README.md`, `CHANGELOG.md`
- No new plugin dependencies (reuse Gson + `HttpsURLConnection`; bStats already present). Worker uses the Cloudflare Workers runtime only.

---

## Validation

1. `gradle build` — compiles; shadowJar produced; bStats relocation intact.
2. Relay local test: `wrangler dev` with a local test `DISCORD_WEBHOOK`; POST a sample payload with `curl` → confirm a well-formed embed in the test channel, 400 on malformed, 413 on oversize, 429 past the rate limit.
3. `gradle runServer` with `telemetry.endpoint` pointed at the local `wrangler dev` URL:
   - `enabled: true` → startup report arrives with MOTD, brand, MC version, Vaults version, online count, online mode, full plugin list, install id.
   - Force an error (e.g. make the DB path unreadable) → error report with redacted stack; a second identical error within 6h is suppressed.
   - `enabled: false` → **zero** requests, no notice spam.
   - Blank `endpoint` → silent no-op.
4. bStats: set `logSentData: true` in `plugins/bStats/config.yml`; confirm `vault_users` and `update_checker` appear and that `vault_users` buckets match DB reality.
5. Config migration: start from a v2 `config.yml` → v3 keys merge in without clobbering customised values.
6. Reload: run `/vaultadmin reload` twice → no duplicate heartbeat tasks or reports.
7. Thread safety: no Bukkit API calls from the POST path; no DB access from a chart supplier.

---

## Risks / open items

- **Privacy:** opt-out-ON with full plugin names + MOTD is identifiable. To lower risk later, switch the plugin list to count-only or make it opt-in — a one-line change in `TelemetryReporter`.
- **Public relay URL:** abusable by design; the Worker's rate limit + validation contain it, and the Discord secret stays hidden. Keep the Worker's rate limit tuned after launch.
- **Discord ToS:** automated posting is allowed but must stay under Discord's rate limits; the Worker should cap its own outbound rate.
- **MOTD content:** owner-chosen free text may itself contain PII; truncated and only sent because telemetry is on.
- **Confirm before shipping:** the relay URL/domain and whether `telemetry.enabled` should really default `true` for the public release.
