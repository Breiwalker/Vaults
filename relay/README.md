# Vaults telemetry relay

A tiny Cloudflare Worker that receives Vaults' structured telemetry reports, validates and
rate-limits them, formats a Discord embed, and forwards it to a Discord webhook. The webhook
URL lives **only** in the Worker as a secret — it never ships in the plugin jar.

This folder is **not** part of the Gradle build. Deploy it independently.

## What it does

1. Accepts only `POST` (405 otherwise).
2. Rejects bodies > 32 KB (413) and non-JSON (400).
3. Validates `schema == 1` and the required fields (`event`, `installId`, `timestamp`, `server`,
   `plugin`); malformed payloads get 400.
4. Rate-limits to **20 requests / minute per client IP** (using `cf-connecting-ip`) → 429.
5. Formats two Discord embeds (server info + Vaults version + players + plugin list; a second
   embed for `error` reports with type/message/truncated stack/signature) and posts them to the
   webhook.
6. Returns `204` on success, `502` if Discord fails (the plugin backs off and drops).
7. Never logs or echoes the Discord URL.

The in-memory rate limit is per-isolate and best-effort; it is plenty for a small relay. If you
need strict global limits, back it with a Durable Object.

## Deploy

Requirements: a [Cloudflare account](https://dash.cloudflare.com/) and the `wrangler` CLI
(`npm install -g wrangler`), plus a Discord channel webhook
(Channel settings → Integrations → Webhooks → New Webhook → Copy URL).

```bash
cd relay
wrangler login
wrangler secret put DISCORD_WEBHOOK     # paste the webhook URL when prompted
wrangler deploy
```

Wrangler prints the deployed URL, e.g. `https://vaults-reports.<your-account>.workers.dev`.
Your endpoint is that URL plus `/report`:

```
https://vaults-reports.<your-account>.workers.dev/report
```

## Wiring it into the plugin

In `plugins/Vaults/config.yml`:

```yaml
telemetry:
  enabled: true
  endpoint: "https://vaults-reports.<your-account>.workers.dev/report"
  heartbeat-minutes: 360
  report-errors: true
```

Set `enabled: false` (or leave `endpoint` blank) to disable reporting entirely. Server owners may
point `endpoint` at their own relay.

## Local testing

```bash
cd relay
# .dev.vars is git-ignored; put DISCORD_WEBHOOK=... in it for `wrangler dev`
echo 'DISCORD_WEBHOOK="https://discord.com/api/webhooks/.../..."' > .dev.vars
wrangler dev
```

Point `telemetry.endpoint` at the local `http://127.0.0.1:8787/report` URL and start the server.
You can also exercise the handler directly:

```bash
curl -i -X POST http://127.0.0.1:8787/report \
  -H 'content-type: application/json' \
  -d '{"schema":1,"event":"startup","installId":"00000000-0000-0000-0000-000000000000","timestamp":1690000000000,"server":{"motd":"A Minecraft Server","brand":"Paper","softwareVersion":"git-Paper-196","minecraftVersion":"1.20.6"},"plugin":{"name":"Vaults","version":"2.2"},"players":{"online":3,"max":100},"onlineMode":true,"plugins":[{"name":"Vaults","version":"2.2"}],"error":null}'
```

Expect `204` and an embed in the test channel; malformed JSON → `400`; a body > 32 KB → `413`;
more than 20 requests in a minute → `429`.

> Note: `wrangler dev` uses HTTP, so the plugin's `endpoint` may start with `http://` for local
> testing. Production should always use `https://`.

## Rotating / disabling

Change the secret (`wrangler secret put DISCORD_WEBHOOK`) or delete the Worker to disable the
relay instantly — no plugin reship required.
