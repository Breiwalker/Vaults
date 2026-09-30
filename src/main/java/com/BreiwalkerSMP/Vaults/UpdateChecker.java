package com.BreiwalkerSMP.Vaults;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Modrinth update checker.
 *
 * <p>Fetches the published versions of the plugin and returns the newest version that
 * is newer than the currently running one, filtered by release channel, loader and
 * (when possible) the server's Minecraft version. Never throws: failures are logged
 * and result in {@code null}.</p>
 */
public final class UpdateChecker {

    private static final String PROJECT_SLUG = "vaults";
    private static final String API_URL = "https://api.modrinth.com/v2/project/%s/version";
    private static final List<String> SUPPORTED_LOADERS = List.of("paper", "folia", "bukkit", "spigot", "purpur");

    private final Logger log;
    private final String currentVersion;
    private final String minecraftVersion;
    private final String channel;
    private final String userAgent;

    /**
     * @param channel one of {@code release}, {@code beta}, {@code alpha} or {@code all}.
     */
    public UpdateChecker(Logger log, String currentVersion, String minecraftVersion, String channel) {
        this.log = log;
        this.currentVersion = currentVersion == null ? "0" : currentVersion;
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        this.channel = channel == null ? "release" : channel.toLowerCase(Locale.ROOT);
        this.userAgent = "BreiwalkerSMP/Vaults/" + currentVersion + " (contact: BreiwalkerSMP)";
    }

    /** Returns the newest compatible version newer than the current one, or {@code null}. */
    public String check() {
        JsonArray versions;
        try {
            versions = fetchVersions();
        } catch (Exception ex) {
            log.warning("Failed to check for updates: " + ex.getMessage());
            return null;
        }

        if (versions == null || versions.size() == 0) return null;

        // Prefer a version tagged for this exact Minecraft version.
        String best = findNewest(versions, true);
        if (best == null && !minecraftVersion.isEmpty()) {
            // Fall back to the newest compatible build of any game version rather than staying silent.
            best = findNewest(versions, false);
            if (best != null) {
                log.info("No version tagged for Minecraft " + minecraftVersion
                        + "; considering the newest compatible build (" + best + ").");
            }
        }
        return best;
    }

    private String findNewest(JsonArray versions, boolean requireGameVersion) {
        String best = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            if (!isListed(v)) continue;
            if (!matchesChannel(v)) continue;
            if (!matchesLoader(v)) continue;
            if (requireGameVersion && !matchesGameVersion(v)) continue;

            String num = getVersionNumber(v);
            if (num == null || num.isEmpty()) continue;
            if (compareVersions(num, currentVersion) <= 0) continue;
            if (best == null || compareVersions(num, best) > 0) best = num;
        }
        return best;
    }

    private JsonArray fetchVersions() throws IOException {
        URL url = new URL(String.format(API_URL, PROJECT_SLUG));
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", userAgent);
        con.setRequestProperty("Accept", "application/json");
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        int code = con.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Modrinth API returned HTTP " + code);
        }

        try (InputStreamReader reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonArray.class);
        }
    }

    private String getVersionNumber(JsonObject v) {
        return v.has("version_number") && !v.get("version_number").isJsonNull()
                ? v.get("version_number").getAsString() : null;
    }

    private boolean isListed(JsonObject v) {
        if (!v.has("status")) return true;
        return "listed".equals(v.get("status").getAsString());
    }

    private boolean matchesChannel(JsonObject v) {
        String type = v.has("version_type") && !v.get("version_type").isJsonNull()
                ? v.get("version_type").getAsString() : "release";
        switch (channel) {
            case "all":   return true;
            case "alpha": return true;                          // release + beta + alpha
            case "beta":  return !"alpha".equals(type);         // release + beta
            case "release":
            default:      return "release".equals(type);
        }
    }

    private boolean matchesLoader(JsonObject v) {
        if (!v.has("loaders") || v.get("loaders").isJsonNull()) return true;
        JsonArray loaders = v.getAsJsonArray("loaders");
        for (int i = 0; i < loaders.size(); i++) {
            if (SUPPORTED_LOADERS.contains(loaders.get(i).getAsString())) return true;
        }
        return false;
    }

    private boolean matchesGameVersion(JsonObject v) {
        if (minecraftVersion.isEmpty()) return true;
        if (!v.has("game_versions") || v.get("game_versions").isJsonNull()) return true;
        JsonArray gameVersions = v.getAsJsonArray("game_versions");
        for (int i = 0; i < gameVersions.size(); i++) {
            if (minecraftVersion.equals(gameVersions.get(i).getAsString())) return true;
        }
        return false;
    }

    // ─── Version comparison (semantic-version aware) ─────────────────────

    /** Returns a negative/zero/positive value if {@code a} is older/equal/newer than {@code b}. */
    static int compareVersions(String a, String b) {
        String[] ca = coreAndPrerelease(a);
        String[] cb = coreAndPrerelease(b);
        int cmp = compareNumeric(ca[0], cb[0]);
        if (cmp != 0) return cmp;
        return comparePrerelease(ca[1], cb[1]);
    }

    private static String[] coreAndPrerelease(String v) {
        if (v == null) return new String[]{"", ""};
        String s = v.trim();
        if (s.length() > 1 && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        int split = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '+') { split = i; break; }
        }
        if (split < 0) return new String[]{s, ""};
        return new String[]{s.substring(0, split), s.substring(split + 1)};
    }

    private static int compareNumeric(String a, String b) {
        int[] na = toNumbers(a), nb = toNumbers(b);
        int max = Math.max(na.length, nb.length);
        for (int i = 0; i < max; i++) {
            int x = i < na.length ? na[i] : 0;
            int y = i < nb.length ? nb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] toNumbers(String core) {
        if (core == null || core.isEmpty()) return new int[]{0};
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].replaceAll("[^0-9]", "");
            try { out[i] = p.isEmpty() ? 0 : Integer.parseInt(p); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static int comparePrerelease(String a, String b) {
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty && bEmpty) return 0;
        if (aEmpty) return 1;    // a release is newer than a pre-release
        if (bEmpty) return -1;

        String[] ai = a.split("\\."), bi = b.split("\\.");
        int max = Math.min(ai.length, bi.length);
        for (int i = 0; i < max; i++) {
            int c = compareIdentifier(ai[i], bi[i]);
            if (c != 0) return c;
        }
        return Integer.compare(ai.length, bi.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNum = isNumeric(a), bNum = isNumeric(b);
        if (aNum && bNum) return Long.compare(parseLongSafe(a), parseLongSafe(b));
        if (aNum) return -1;     // numeric identifiers sort before alphanumeric
        if (bNum) return 1;
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static long parseLongSafe(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        try { return Long.parseLong(s.substring(i)); }
        catch (NumberFormatException e) { return 0; }
    }
}
