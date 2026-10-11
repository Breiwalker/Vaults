package com.BreiwalkerSMP.Vaults;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Relayed phone-home: POSTs a structured, redacted JSON report about this server to a public
 * relay URL, which forwards it (formatted) to a Discord webhook. The plugin never holds the
 * webhook secret.
 *
 * <p>Design constraints:</p>
 * <ul>
 *   <li>Opt-out ({@code telemetry.enabled}), and a silent no-op when the endpoint is blank.</li>
 *   <li>Never blocks or crashes the plugin: every entry point is wrapped in
 *       {@code try/catch (Throwable)} and all network work runs off the main thread.</li>
 *   <li>Bukkit data is gathered on the main / global region thread (Folia-safe), then POSTed async.</li>
 *   <li>Redacts the server data-folder path, UUIDs and online player names; clamps free text.</li>
 *   <li>Client-side rate limit, per-signature error de-duplication and exponential backoff.</li>
 * </ul>
 *
 * <p>Mirrors the never-throw HTTP style of {@link UpdateChecker}.</p>
 */
public final class TelemetryReporter {

    private static final int SCHEMA = 1;
    private static final long STARTUP_DELAY_SECONDS = 30L;
    private static final long HEARTBEAT_DEFAULT_MINUTES = 360L;
    private static final long MIN_ROUTINE_INTERVAL_MS = 60_000L;
    private static final long RATE_WINDOW_MS = 3_600_000L;
    private static final int MAX_SENDS_PER_HOUR = 30;
    private static final long DEDUPE_MS = 6L * 60L * 60L * 1000L;
    private static final long BACKOFF_BASE_MS = 30_000L;
    private static final long BACKOFF_MAX_MS = 15L * 60L * 1000L;

    private static final int MAX_MOTD = 256;
    private static final int MAX_ERROR_MESSAGE = 512;
    private static final int MAX_STACK = 4000;

    /** Dashed or undashed hex UUIDs, as they appear in stack traces / messages. */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|[0-9a-fA-F]{32}");

    private final Vaults plugin;
    private final Logger log;
    private final Gson gson = new Gson();
    private final File stateFile;

    private String installId;
    private boolean noticeShown;

    private volatile boolean enabled = false;
    private volatile String endpoint = "";
    private volatile long heartbeatMinutes = HEARTBEAT_DEFAULT_MINUTES;
    private volatile boolean reportErrors = true;

    private ScheduledTask startupTask;
    private ScheduledTask heartbeatTask;

    private final Object rateLock = new Object();
    private final Deque<Long> sendTimestamps = new ArrayDeque<>();
    private long lastRoutineSendMs = 0L;
    private long backoffUntilMs = 0L;
    private int consecutiveFailures = 0;

    private final Map<String, Long> errorSignatures = new ConcurrentHashMap<>();

    public TelemetryReporter(Vaults plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.stateFile = new File(plugin.getDataFolder(), "telemetry.state");
        try {
            readState();
            ensureInstallId();
            readConfig();
        } catch (Throwable t) {
            log.warning("Telemetry init failed: " + t.getMessage());
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    /** Prints the one-time privacy notice (when active) and schedules startup + heartbeat. */
    public void init() {
        try {
            if (isActive() && !noticeShown) {
                printNotice();
                noticeShown = true;
                saveState();
            }
            schedule();
        } catch (Throwable t) {
            log.warning("Telemetry init failed: " + t.getMessage());
        }
    }

    /** Re-reads config and rebuilds the schedule without duplicating tasks (used by /vaultadmin reload). */
    public void reload() {
        try {
            readConfig();
            schedule();
        } catch (Throwable t) {
            log.warning("Telemetry reload failed: " + t.getMessage());
        }
    }

    /** Cancels the scheduled startup/heartbeat tasks. */
    public void shutdown() {
        cancelTasks();
    }

    private void schedule() {
        cancelTasks();
        if (!isActive()) return;
        try {
            startupTask = Bukkit.getAsyncScheduler().runDelayed(plugin,
                    t -> dispatch("startup", null), STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
            long period = Math.max(1L, heartbeatMinutes);
            heartbeatTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    t -> dispatch("heartbeat", null), period, period, TimeUnit.MINUTES);
        } catch (Throwable t) {
            log.warning("Failed to schedule telemetry: " + t.getMessage());
        }
    }

    private void cancelTasks() {
        if (startupTask != null) {
            try { startupTask.cancel(); } catch (Throwable ignored) { }
            startupTask = null;
        }
        if (heartbeatTask != null) {
            try { heartbeatTask.cancel(); } catch (Throwable ignored) { }
            heartbeatTask = null;
        }
    }

    // ─── Reporting ───────────────────────────────────────────────────────

    /** Reports one of Vaults' own exceptions, subject to de-dup, rate limits and {@code report-errors}. */
    public void reportError(Throwable error) {
        if (error == null) return;
        try {
            if (!isActive() || !reportErrors) return;
            String sig = signature(error);
            long now = System.currentTimeMillis();
            pruneSignatures(now);
            Long last = errorSignatures.get(sig);
            if (last != null && now - last < DEDUPE_MS) return;
            errorSignatures.put(sig, now);
            dispatch("error", error);
        } catch (Throwable ignored) {
        }
    }

    private void dispatch(String event, Throwable error) {
        try {
            if (!isActive()) return;
            // Error reports bypass the routine min-interval (they are the point of the feature)
            // but still count toward the hourly cap and are de-duplicated per signature.
            if (!allowSend(error != null, System.currentTimeMillis())) return;
            // Gather Bukkit data on the main / global region thread; POST async.
            plugin.runOnMain(() -> {
                JsonObject payload;
                try {
                    payload = buildPayload(event, error);
                } catch (Throwable t) {
                    return;
                }
                try {
                    plugin.asyncExecutor().execute(() -> send(payload, event));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void send(JsonObject payload, String event) {
        long now = System.currentTimeMillis();
        try {
            int code = post(gson.toJson(payload));
            if (code >= 200 && code < 300) {
                resetBackoff();
            } else if (code == 429 || code >= 500) {
                increaseBackoff(now);
            } else {
                log.fine("Telemetry relay rejected " + event + " report: HTTP " + code);
            }
        } catch (Throwable t) {
            increaseBackoff(now);
            log.fine("Telemetry send failed (" + event + "): " + t.getMessage());
        }
    }

    private int post(String body) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("User-Agent", "BreiwalkerSMP/Vaults/" + plugin.getDescription().getVersion());
            con.setRequestProperty("X-Vaults-Install", installId == null ? "" : installId);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(bytes);
            }
            int code = con.getResponseCode();
            // Drain the response so the connection can be reused / closed cleanly.
            try (InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[1024];
                    while (in.read(buf) != -1) { /* discard */ }
                }
            }
            return code;
        } finally {
            con.disconnect();
        }
    }

    // ─── Payload ─────────────────────────────────────────────────────────

    private JsonObject buildPayload(String event, Throwable error) {
        List<String> playerNames = new ArrayList<>();
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName() != null) playerNames.add(p.getName());
            }
        } catch (Throwable ignored) {
        }

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("event", event);
        root.addProperty("installId", installId == null ? "" : installId);
        root.addProperty("timestamp", System.currentTimeMillis());

        JsonObject server = new JsonObject();
        server.addProperty("motd", clamp(redact(safeMotd(), playerNames), MAX_MOTD));
        server.addProperty("brand", clamp(redact(safeBrand(), playerNames), 64));
        server.addProperty("softwareVersion", clamp(redact(safeSoftwareVersion(), playerNames), 128));
        server.addProperty("minecraftVersion", clamp(plugin.serverMinecraftVersion(), 64));
        root.add("server", server);

        JsonObject pluginInfo = new JsonObject();
        pluginInfo.addProperty("name", plugin.getName());
        pluginInfo.addProperty("version", plugin.getDescription().getVersion());
        root.add("plugin", pluginInfo);

        JsonObject players = new JsonObject();
        players.addProperty("online", safeOnlineCount());
        players.addProperty("max", safeMaxPlayers());
        root.add("players", players);

        root.addProperty("onlineMode", safeOnlineMode());

        JsonArray pluginArray = new JsonArray();
        for (Plugin p : safePlugins()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            String version = "";
            if (p.getDescription() != null && p.getDescription().getVersion() != null) {
                version = p.getDescription().getVersion();
            }
            o.addProperty("version", version);
            pluginArray.add(o);
        }
        root.add("plugins", pluginArray);

        if (error == null) {
            root.add("error", JsonNull.INSTANCE);
        } else {
            JsonObject e = new JsonObject();
            e.addProperty("type", error.getClass().getName());
            String message = error.getMessage();
            e.addProperty("message", clamp(redact(message == null ? "" : message, playerNames), MAX_ERROR_MESSAGE));
            e.addProperty("stack", clamp(redact(stackTrace(error), playerNames), MAX_STACK));
            e.addProperty("signature", signature(error));
            root.add("error", e);
        }
        return root;
    }

    // ─── Safety helpers ──────────────────────────────────────────────────

    private boolean isActive() {
        return enabled && endpoint != null && !endpoint.isBlank();
    }

    /**
     * Checks and (when allowed) records a send. Routine reports respect the 60s minimum
     * interval; error reports skip it but still share the hourly cap. All sends respect backoff.
     */
    private boolean allowSend(boolean urgent, long now) {
        synchronized (rateLock) {
            if (now < backoffUntilMs) return false;
            while (!sendTimestamps.isEmpty() && now - sendTimestamps.peekFirst() > RATE_WINDOW_MS) {
                sendTimestamps.pollFirst();
            }
            if (sendTimestamps.size() >= MAX_SENDS_PER_HOUR) return false;
            if (!urgent && now - lastRoutineSendMs < MIN_ROUTINE_INTERVAL_MS) return false;
            sendTimestamps.addLast(now);
            lastRoutineSendMs = now;
            return true;
        }
    }

    private void increaseBackoff(long now) {
        synchronized (rateLock) {
            consecutiveFailures = Math.min(consecutiveFailures + 1, 10);
            long backoff = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << (consecutiveFailures - 1));
            backoffUntilMs = now + backoff;
        }
    }

    private void resetBackoff() {
        synchronized (rateLock) {
            consecutiveFailures = 0;
            backoffUntilMs = 0L;
        }
    }

    private void pruneSignatures(long now) {
        if (errorSignatures.size() < 256) return;
        errorSignatures.entrySet().removeIf(e -> now - e.getValue() > DEDUPE_MS);
    }

    private String redact(String input, List<String> playerNames) {
        if (input == null || input.isEmpty()) return "";
        String out = input;
        try {
            String folder = plugin.getDataFolder().getAbsolutePath();
            if (folder != null && !folder.isEmpty()) out = out.replace(folder, "<server>");
        } catch (Throwable ignored) {
        }
        out = UUID_PATTERN.matcher(out).replaceAll("[uuid]");
        for (String name : playerNames) {
            if (name != null && name.length() >= 3) out = out.replace(name, "[player]");
        }
        return out;
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    /** Short, stable hash of the exception class + top frame — safe to send and used for de-dup. */
    private static String signature(Throwable t) {
        String top = "";
        StackTraceElement[] frames = t.getStackTrace();
        if (frames != null && frames.length > 0) top = frames[0].toString();
        return shortHash(t.getClass().getName() + "|" + top);
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ─── Defensive Bukkit accessors (gathered on the main thread) ─────────

    private static String safeMotd() {
        try {
            String s = Bukkit.getMotd();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safeBrand() {
        try {
            String s = Bukkit.getName();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safeSoftwareVersion() {
        try {
            String s = Bukkit.getVersion();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static int safeOnlineCount() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeMaxPlayers() {
        try {
            return Bukkit.getMaxPlayers();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean safeOnlineMode() {
        try {
            return Bukkit.getOnlineMode();
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Plugin> safePlugins() {
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            return plugins == null ? List.of() : List.of(plugins);
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ─── State file (independent of the database) ────────────────────────

    private void readState() {
        try {
            if (!stateFile.exists()) return;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(stateFile.toPath())) {
                props.load(in);
            }
            installId = props.getProperty("install-id");
            noticeShown = Boolean.parseBoolean(props.getProperty("notice-shown", "false"));
        } catch (Throwable t) {
            log.warning("Failed to read telemetry state: " + t.getMessage());
        }
    }

    private void ensureInstallId() {
        if (installId == null || installId.isBlank()) {
            installId = UUID.randomUUID().toString();
            saveState();
        }
    }

    private void saveState() {
        try {
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Properties props = new Properties();
            props.setProperty("install-id", installId == null ? "" : installId);
            props.setProperty("notice-shown", Boolean.toString(noticeShown));
            try (OutputStream out = Files.newOutputStream(stateFile.toPath())) {
                props.store(out, "Vaults telemetry state");
            }
        } catch (Throwable t) {
            log.warning("Failed to write telemetry state: " + t.getMessage());
        }
    }

    // ─── Config / notice ─────────────────────────────────────────────────

    private void readConfig() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("telemetry.enabled", true);
        endpoint = config.getString("telemetry.endpoint", "");
        heartbeatMinutes = Math.max(1L, config.getLong("telemetry.heartbeat-minutes", HEARTBEAT_DEFAULT_MINUTES));
        reportErrors = config.getBoolean("telemetry.report-errors", true);
    }

    private void printNotice() {
        log.info("Vaults telemetry is enabled: an anonymous server report is sent to the relay.");
        log.info("It includes the MOTD, server software, full plugin list, player counts and Vaults errors.");
        log.info("No server IP, player names or UUIDs are sent. Disable with telemetry.enabled: false in config.yml.");
    }
}
