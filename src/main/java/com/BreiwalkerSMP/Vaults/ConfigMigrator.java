package com.BreiwalkerSMP.Vaults;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Migrates the plugin's {@code config.yml} between schema versions.
 *
 * <p>Every time the config schema changes, bump {@link #CURRENT_CONFIG_VERSION} and add a
 * {@code migrateStep} that renames/moves/removes keys. New keys are merged in automatically
 * from the bundled default config, and a {@code config-version} marker is stored in the file
 * so migrations run exactly once without clobbering user-customized values.</p>
 */
public final class ConfigMigrator {

    public static final int CURRENT_CONFIG_VERSION = 1;

    private ConfigMigrator() {
    }

    public static void migrate(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Configuration defaults = config.getDefaults();

        // Read the version straight from disk so it is unaffected by in-memory defaults.
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        int current = onDisk.getInt("config-version", 0);

        if (current >= CURRENT_CONFIG_VERSION) return;

        // Apply versioned migrations (renames / value transforms).
        for (int version = current; version < CURRENT_CONFIG_VERSION; version++) {
            migrateStep(config, version);
        }

        // Merge in any keys present in the bundled default but missing from the user's file,
        // while preserving every value the user has already customized.
        if (defaults != null) {
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!onDisk.contains(key)) config.set(key, defaults.get(key));
            }
        }

        config.set("config-version", CURRENT_CONFIG_VERSION);
        plugin.saveConfig();
        plugin.getLogger().info("Migrated config from version " + current
                + " to " + CURRENT_CONFIG_VERSION + ".");
    }

    private static void migrateStep(FileConfiguration config, int from) {
        switch (from) {
            // v0 -> v1: baseline versioning for the 2.0 release. No keys were renamed,
            // so this step is empty; new keys are merged in by {@link #migrate}.
            // To add a rename in the future:
            //   if (config.contains("old.key") && !config.contains("new.key")) {
            //       config.set("new.key", config.get("old.key"));
            //       config.set("old.key", null);
            //   }
            case 0 -> { }
            default -> { }
        }
    }
}
