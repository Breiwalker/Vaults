// Vaults telemetry relay (Cloudflare Worker)
//
// Receives Vaults' structured telemetry POSTs, validates + rate-limits them, formats a
// Discord embed and forwards it to a webhook that is stored as a Worker secret
// (DISCORD_WEBHOOK). The webhook URL is never logged or echoed.
//
// Deploy:  wrangler deploy
// Secret:  wrangler secret put DISCORD_WEBHOOK
// Then set telemetry.endpoint in the plugin config to <worker-url>/report

const MAX_BODY_BYTES = 32 * 1024; // 32 KB
const RATE_LIMIT = 20;            // requests
const RATE_WINDOW_MS = 60 * 1000; // per minute, per client IP

// Best-effort in-memory rate limit (per isolate). For strict limits, back this with a
// Durable Object; for a small public relay this is sufficient.
const buckets = new Map();

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') {
      return new Response('Method Not Allowed', { status: 405 });
    }

    const declared = Number(request.headers.get('content-length') || '0');
    if (declared > MAX_BODY_BYTES) {
      return new Response('Payload Too Large', { status: 413 });
    }

    const ip = request.headers.get('cf-connecting-ip') || 'unknown';
    if (!allow(ip)) {
      return new Response('Too Many Requests', { status: 429, headers: { 'retry-after': '60' } });
    }

    let text;
    try {
      text = await request.text();
    } catch (err) {
      return new Response('Bad Request', { status: 400 });
    }
    if (text.length > MAX_BODY_BYTES) {
      return new Response('Payload Too Large', { status: 413 });
    }

    let data;
    try {
      data = JSON.parse(text);
    } catch (err) {
      return new Response('Bad Request', { status: 400 });
    }

    const invalid = validate(data);
    if (invalid) {
      return new Response(`Bad Request: ${invalid}`, { status: 400 });
    }

    const webhook = env.DISCORD_WEBHOOK;
    if (!webhook) {
      return new Response('Relay not configured', { status: 500 });
    }

    let upstream;
    try {
      upstream = await fetch(webhook, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(buildDiscordPayload(data)),
      });
    } catch (err) {
      return new Response('Upstream error', { status: 502 });
    }

    if (!upstream.ok) {
      return new Response('Upstream error', { status: 502 });
    }
    return new Response(null, { status: 204 });
  },
};

function allow(ip) {
  const now = Date.now();
  let bucket = buckets.get(ip);
  if (!bucket || now > bucket.reset) {
    bucket = { count: 0, reset: now + RATE_WINDOW_MS };
    buckets.set(ip, bucket);
  }
  bucket.count += 1;

  // Opportunistic cleanup so the map can't grow without bound.
  if (buckets.size > 5000) {
    for (const [key, value] of buckets) {
      if (now > value.reset) buckets.delete(key);
    }
  }
  return bucket.count <= RATE_LIMIT;
}

function validate(data) {
  if (!data || typeof data !== 'object') return 'not an object';
  if (data.schema !== 1) return 'unsupported schema';
  for (const field of ['event', 'installId', 'timestamp', 'server', 'plugin']) {
    if (data[field] === undefined || data[field] === null) return `missing ${field}`;
  }
  if (!['startup', 'heartbeat', 'error'].includes(data.event)) return 'unknown event';
  return null;
}

function truncate(value, max) {
  const s = typeof value === 'string' ? value : String(value ?? '');
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

function buildDiscordPayload(data) {
  const server = data.server || {};
  const plugin = data.plugin || {};
  const players = data.players || {};

  const label = truncate(server.motd || '(no MOTD)', 120) || '(no MOTD)';
  const pluginList = (Array.isArray(data.plugins) ? data.plugins : [])
    .map((p) => `${p && p.name ? p.name : '?'} ${p && p.version ? p.version : '?'}`)
    .join(', ');

  const embeds = [{
    title: `Vaults telemetry — ${truncate(data.event, 32)}`,
    description: label,
    color: data.event === 'error' ? 0xe74c3c : 0x3498db,
    timestamp: new Date(Number(data.timestamp) || Date.now()).toISOString(),
    fields: [
      { name: 'MC version', value: truncate(server.minecraftVersion || '?', 32), inline: true },
      { name: 'Software', value: truncate(`${server.brand || '?'} ${server.softwareVersion || ''}`, 64), inline: true },
      { name: 'Vaults', value: truncate(plugin.version || '?', 32), inline: true },
      { name: 'Players', value: `${players.online ?? '?'} / ${players.max ?? '?'}`, inline: true },
      { name: 'Online mode', value: data.onlineMode ? 'yes' : 'no', inline: true },
      { name: 'Install', value: truncate(data.installId || '?', 40), inline: true },
      { name: 'Plugins', value: truncate(pluginList || '(none)', 1000), inline: false },
    ],
  }];

  if (data.error) {
    embeds.push({
      title: `Error: ${truncate(data.error.type || 'Throwable', 200)}`,
      description: truncate(data.error.message || '(no message)', 1000),
      color: 0xe74c3c,
      fields: [
        { name: 'Signature', value: truncate(data.error.signature || '?', 64), inline: true },
        { name: 'Stack', value: `\`\`\`\n${truncate(data.error.stack || '', 1000)}\n\`\`\``, inline: false },
      ],
    });
  }

  return { username: 'Vaults Telemetry', embeds };
}
