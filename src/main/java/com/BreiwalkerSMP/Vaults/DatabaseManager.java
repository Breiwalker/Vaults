package com.BreiwalkerSMP.Vaults;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * SQLite-backed storage for vault pages and shares.
 *
 * <p>Design notes:
 * <ul>
 *   <li>A single {@link Connection} is held open for the plugin's lifetime, protected by
 *       {@link #lock}. SQLite serializes writes internally anyway; this just prevents
 *       interleaving of multi-statement operations.</li>
 *   <li>WAL mode is enabled so reads don't block writes and vice-versa.</li>
 *   <li>All public methods are safe to call from any thread.</li>
 * </ul>
 */
public final class DatabaseManager {

    private static final String MIGRATION_KEY = "yaml_migrated";

    private final Logger log;
    private final File dbFile;
    private final File yamlFolder;      // legacy userdata folder
    private final ReentrantLock lock = new ReentrantLock();

    private Connection connection;

    public DatabaseManager(Logger log, File dbFile, File yamlFolder) {
        this.log = log;
        this.dbFile = dbFile;
        this.yamlFolder = yamlFolder;
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public synchronized void open() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        // Ensure driver is on the classpath before opening.
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found. Add it via 'libraries:' in plugin.yml.", e);
        }

        if (!dbFile.getParentFile().exists() && !dbFile.getParentFile().mkdirs()) {
            throw new SQLException("Could not create plugin data folder: " + dbFile.getParentFile());
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        createSchema();
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            connection.close();
        } catch (SQLException ex) {
            log.warning("Error closing database: " + ex.getMessage());
        } finally {
            connection = null;
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS vault_pages (
                    owner_uuid TEXT    NOT NULL,
                    page       INTEGER NOT NULL,
                    data       BLOB    NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, page)
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS vault_shares (
                    owner_uuid TEXT    NOT NULL,
                    guest_uuid TEXT    NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, guest_uuid)
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS meta (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);
        }
    }

    // ─── Pages ───────────────────────────────────────────────────────────

    /** Returns raw serialized bytes, or {@code null} if the page has never been saved. */
    public byte[] loadPage(UUID owner, int page) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data FROM vault_pages WHERE owner_uuid = ? AND page = ?")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, page);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("data") : null;
            }
        } catch (SQLException ex) {
            log.warning("loadPage failed for " + owner + " p" + page + ": " + ex.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void savePage(UUID owner, int page, byte[] data) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO vault_pages (owner_uuid, page, data, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(owner_uuid, page) DO UPDATE SET
                    data = excluded.data,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, page);
            ps.setBytes(3, data);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("savePage failed for " + owner + " p" + page + ": " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ─── Shares ──────────────────────────────────────────────────────────

    public void addShare(UUID owner, UUID guest) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO vault_shares (owner_uuid, guest_uuid, created_at)
                VALUES (?, ?, ?)
                """)) {
            ps.setString(1, owner.toString());
            ps.setString(2, guest.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("addShare failed: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public boolean isSharedWith(UUID owner, UUID guest) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM vault_shares WHERE owner_uuid = ? AND guest_uuid = ? LIMIT 1")) {
            ps.setString(1, owner.toString());
            ps.setString(2, guest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            log.warning("isSharedWith failed: " + ex.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Returns every owner UUID that has shared their vault with {@code guest}. */
    public List<UUID> ownersSharingWith(UUID guest) {
        List<UUID> out = new ArrayList<>();
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner_uuid FROM vault_shares WHERE guest_uuid = ?")) {
            ps.setString(1, guest.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { out.add(UUID.fromString(rs.getString("owner_uuid"))); }
                    catch (IllegalArgumentException ignored) { /* skip malformed */ }
                }
            }
        } catch (SQLException ex) {
            log.warning("ownersSharingWith failed: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
        return out;
    }

    // ─── Meta ────────────────────────────────────────────────────────────

    public String getMeta(String key) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException ex) {
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void setMeta(String key, String value) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO meta (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("setMeta failed: " + ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ─── YAML Migration ──────────────────────────────────────────────────

    /**
     * One-shot migration from the legacy {@code userdata/*.yml} layout.
     * Runs only if the migration flag isn't set AND the folder exists.
     * On success, the folder is renamed to {@code userdata.backup}.
     */
    public void migrateFromYamlIfNeeded() {
        if ("true".equals(getMeta(MIGRATION_KEY))) return;
        if (yamlFolder == null || !yamlFolder.isDirectory()) {
            setMeta(MIGRATION_KEY, "true");    // nothing to migrate, mark done
            return;
        }
        File[] files = yamlFolder.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) {
            setMeta(MIGRATION_KEY, "true");
            return;
        }

        log.info("Migrating " + files.length + " legacy YAML vault file(s) into SQLite…");
        int migratedPages = 0, migratedShares = 0, failed = 0;

        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4);
            UUID owner;
            try { owner = UUID.fromString(base); }
            catch (IllegalArgumentException ex) { failed++; continue; }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

            // Pages
            if (cfg.isConfigurationSection("pages")) {
                for (String pageKey : cfg.getConfigurationSection("pages").getKeys(false)) {
                    int page;
                    try { page = Integer.parseInt(pageKey); }
                    catch (NumberFormatException ex) { continue; }

                    String b64 = cfg.getString("pages." + pageKey);
                    if (b64 == null || b64.isEmpty()) continue;

                    try {
                        byte[] raw = Base64.getMimeDecoder().decode(b64);
                        savePage(owner, page, raw);
                        migratedPages++;
                    } catch (IllegalArgumentException ex) {
                        log.warning("Skipping corrupt page " + owner + " p" + page + ": " + ex.getMessage());
                        failed++;
                    }
                }
            }

            // Shares
            for (String s : cfg.getStringList("shared-with")) {
                try {
                    addShare(owner, UUID.fromString(s));
                    migratedShares++;
                } catch (IllegalArgumentException ignored) { /* skip */ }
            }
        }

        log.info("Migration complete: " + migratedPages + " page(s), "
                + migratedShares + " share(s), " + failed + " failure(s).");

        // Back up the old folder
        try {
            Path target = yamlFolder.toPath().resolveSibling("userdata.backup");
            Files.move(yamlFolder.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Legacy YAML folder backed up to " + target);
        } catch (IOException ex) {
            log.warning("Could not back up YAML folder: " + ex.getMessage());
        }

        setMeta(MIGRATION_KEY, "true");
    }
}