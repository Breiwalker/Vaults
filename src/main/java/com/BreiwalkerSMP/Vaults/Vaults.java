package com.BreiwalkerSMP.Vaults;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

public class Vaults extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final boolean IS_FOLIA =
            hasClass("io.papermc.paper.threadedregions.RegionScheduler");

    /** Authoritative in-memory sessions (may have multiple viewers). */
    private final Map<String, VaultSession> sessions = new ConcurrentHashMap<>();
    /** In-flight loads so concurrent openers share the same result. */
    private final Map<String, CompletableFuture<VaultSession>> loadingFutures = new ConcurrentHashMap<>();
    /** Per-key chained save futures (latest snapshot always wins). */
    private final Map<String, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();

    private File userDataFolder;
    private NamespacedKey navKey;
    private Executor asyncExecutor;

    // ─── Update checker state ───────────────────────────────────────────
    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;

    // ─── Holder & Session ────────────────────────────────────────────────

    public static final class VaultHolder implements InventoryHolder {
        private final UUID ownerUUID;
        private final int page;
        private Inventory inventory;

        public VaultHolder(UUID ownerUUID, int page) { this.ownerUUID = ownerUUID; this.page = page; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        public UUID getOwnerUUID() { return ownerUUID; }
        public int getPage() { return page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class VaultSession {
        final UUID owner;
        final int page;
        final Inventory inventory;
        final ReentrantLock lock = new ReentrantLock();

        VaultSession(UUID owner, int page, Inventory inv) {
            this.owner = owner; this.page = page; this.inventory = inv;
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        userDataFolder = new File(getDataFolder(), "userdata");
        if (!userDataFolder.exists() && !userDataFolder.mkdirs()) {
            getLogger().severe("Could not create userdata directory!");
        }

        navKey = new NamespacedKey(this, "nav");

        if (IS_FOLIA) {
            asyncExecutor = r -> Bukkit.getAsyncScheduler().runNow(this, t -> r.run());
        } else {
            asyncExecutor = r -> Bukkit.getScheduler().runTaskAsynchronously(this, r);
        }

        var cmd = getCommand("vault");
        if (cmd == null) {
            getLogger().severe("Command 'vault' missing from plugin.yml!");
        } else {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);

        // Start update check
        checkForUpdates();
    }

    @Override
    public void onDisable() {
        for (VaultSession s : sessions.values()) {
            try {
                String serialised = itemStackArrayToBase64(snapshot(s.inventory));
                writePageToDisk(s.owner, s.page, serialised);
            } catch (Exception ex) {
                getLogger().warning("Failed to flush vault " + s.owner + " p" + s.page + ": " + ex.getMessage());
            }
        }
        sessions.clear();
        loadingFutures.clear();
        pendingSaves.clear();
    }

    // ─── Update Checker ──────────────────────────────────────────────────

    private void checkForUpdates() {
        if (!getConfig().getBoolean("update-checker.enabled", true)) return;

        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/vaults/version");
                HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("User-Agent", "Vaults/" + getDescription().getVersion());
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonArray array = new Gson().fromJson(reader, JsonArray.class);
                    if (array != null && array.size() > 0) {
                        JsonObject latest = array.get(0).getAsJsonObject();
                        latestVersion = latest.get("version_number").getAsString();
                        updateAvailable = isNewerVersion(getDescription().getVersion(), latestVersion);
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("Failed to check for updates: " + ex.getMessage());
                return;
            }

            if (updateAvailable && getConfig().getBoolean("update-checker.notify-console", true)) {
                getLogger().info("§eA new version of Vaults is available: §f" + latestVersion
                        + " §7(you have " + getDescription().getVersion() + ")");
                getLogger().info("§7Download: §bhttps://modrinth.com/plugin/vaults");
            }
        }, asyncExecutor);
    }

    private boolean isNewerVersion(String current, String latest) {
        int[] cur = parseVersion(current);
        int[] lat = parseVersion(latest);
        int max = Math.max(cur.length, lat.length);
        for (int i = 0; i < max; i++) {
            int c = i < cur.length ? cur[i] : 0;
            int l = i < lat.length ? lat[i] : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    private int[] parseVersion(String v) {
        if (v == null) return new int[]{0};
        String clean = v.replaceAll("^[vV]", "").split("[-+]")[0];
        String[] parts = clean.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!updateAvailable) return;
        if (!getConfig().getBoolean("update-checker.notify-ops-on-join", true)) return;

        Player p = e.getPlayer();
        String perm = getConfig().getString("update-checker.notify-permission", "vaults.update");
        if (!p.isOp() && !p.hasPermission(perm)) return;

        runOnPlayer(p, () -> {
            p.sendMessage(msg("update-available",
                    "%version%", latestVersion,
                    "%current%", getDescription().getVersion()));
            p.sendMessage(rawMsg("update-download"));
        });
    }

    // ─── Message helpers ─────────────────────────────────────────────────

    private Component msg(String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + key, key);
        for (int i = 0; i < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacySection().deserialize(prefix + raw);
    }

    private Component rawMsg(String key, String... replacements) {
        String raw = getConfig().getString("messages." + key, key);
        for (int i = 0; i < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    // ─── Threading helpers ───────────────────────────────────────────────

    private static boolean hasClass(String cn) {
        try { Class.forName(cn); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private String key(UUID owner, int page) { return owner + ":" + page; }

    private void runOnMain(Runnable r) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(this, t -> r.run());
        } else if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    private void runOnPlayer(Player p, Runnable r) {
        if (IS_FOLIA) {
            p.getScheduler().run(this, t -> r.run(), null);
        } else {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    // ─── Command handling ────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }

        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("shared")) { showSharedList(player); return true; }

            if (sub.equals("share") && args.length >= 2) {
                final UUID self = player.getUniqueId();
                final String targetName = args[1];
                CompletableFuture.runAsync(() -> {
                    Player target = Bukkit.getPlayerExact(targetName);
                    if (target == null) {
                        runOnPlayer(player, () -> player.sendMessage(msg("player-offline")));
                        return;
                    }
                    addSharedPlayer(self, target.getUniqueId());
                    runOnPlayer(player, () -> player.sendMessage(
                            msg("vault-shared", "%player%", target.getName())));
                }, asyncExecutor);
                return true;
            }

            final String lookupName = args[0];
            CompletableFuture.runAsync(() -> {
                UUID targetUUID = Bukkit.getOfflinePlayer(lookupName).getUniqueId();
                boolean allowed = player.isOp()
                        || player.hasPermission("vaults.admin")
                        || isSharedWith(targetUUID, player.getUniqueId());
                if (allowed) {
                    runOnPlayer(player, () -> openVault(player, 1, targetUUID));
                } else {
                    runOnPlayer(player, () -> player.sendMessage(msg("no-permission")));
                }
            }, asyncExecutor);
            return true;
        }

        openVault(player, 1, player.getUniqueId());
        return true;
    }

    // ─── Vault open logic ────────────────────────────────────────────────

    public void openVault(Player player, int page, UUID targetUUID) {
        final int totalPages = Math.max(1, getConfig().getInt("vault-settings.total-pages", 2));
        if (page < 1 || page > totalPages) return;

        final int rows = Math.max(1, Math.min(6, getConfig().getInt("vault-settings.rows", 6)));
        final String rawTitle = getConfig().getString("vault-settings.title", "Vault %page%")
                .replace("%page%", String.valueOf(page));
        final Component title = LegacyComponentSerializer.legacySection().deserialize(rawTitle);

        final String cacheKey = key(targetUUID, page);

        VaultSession cached = sessions.get(cacheKey);
        if (cached != null) {
            openSession(player, cached, totalPages);
            return;
        }

        CompletableFuture<VaultSession> future = loadingFutures.get(cacheKey);
        if (future == null) {
            CompletableFuture<ItemStack[]> loadFuture = CompletableFuture.supplyAsync(
                    () -> loadItems(targetUUID, page), asyncExecutor);

            CompletableFuture<VaultSession> created = new CompletableFuture<>();
            loadFuture.thenAccept(items -> runOnMain(() -> {
                try {
                    VaultHolder holder = new VaultHolder(targetUUID, page);
                    Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
                    holder.setInventory(inv);
                    int max = Math.min(items.length, inv.getSize());
                    for (int i = 0; i < max; i++) {
                        ItemStack it = items[i];
                        if (it != null && !isNavItem(it)) inv.setItem(i, it);
                    }
                    created.complete(new VaultSession(targetUUID, page, inv));
                } catch (Throwable t) {
                    created.completeExceptionally(t);
                }
            }));

            CompletableFuture<VaultSession> existing = loadingFutures.putIfAbsent(cacheKey, created);
            if (existing != null) {
                future = existing;
            } else {
                future = created;
                created.whenComplete((s, ex) -> loadingFutures.remove(cacheKey, created));
            }
        }

        future.thenAccept(session -> {
            sessions.putIfAbsent(cacheKey, session);
            VaultSession actual = sessions.get(cacheKey);
            if (!player.isOnline()) return;
            runOnPlayer(player, () -> {
                if (player.isOnline()) openSession(player, actual, totalPages);
            });
        }).exceptionally(ex -> {
            getLogger().warning("Failed to load vault " + cacheKey + ": " + ex);
            if (player.isOnline()) {
                runOnPlayer(player, () -> player.sendMessage(msg("vault-load-failed")));
            }
            return null;
        });
    }

    private void openSession(Player player, VaultSession session, int totalPages) {
        ReentrantLock lock = session.lock;
        lock.lock();
        try {
            Inventory inv = session.inventory;
            int size = inv.getSize();
            inv.setItem(size - 1, session.page < totalPages
                    ? createNavItem(Material.ARROW, rawMsg("nav-next"), "next") : null);
            inv.setItem(size - 9, session.page > 1
                    ? createNavItem(Material.ARROW, rawMsg("nav-prev"), "prev") : null);
        } finally {
            lock.unlock();
        }
        player.openInventory(session.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    // ─── Persistence ─────────────────────────────────────────────────────

    private ItemStack[] loadItems(UUID owner, int page) {
        File f = getPlayerFile(owner);
        if (!f.exists()) return new ItemStack[0];
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            String data = cfg.getString("pages.page-" + page);
            if (data == null || data.isEmpty()) return new ItemStack[0];
            return itemStackArrayFromBase64(data);
        } catch (Exception ex) {
            getLogger().warning("Failed to deserialize vault " + owner + " p" + page + ": " + ex.getMessage());
            return new ItemStack[0];
        }
    }

    private ItemStack[] snapshot(Inventory inv) {
        ItemStack[] raw = inv.getContents();
        ItemStack[] out = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != null && !isNavItem(raw[i])) out[i] = raw[i].clone();
        }
        return out;
    }

    private void saveVaultContent(UUID ownerUUID, int page, Inventory inv) {
        final String cacheKey = key(ownerUUID, page);

        ItemStack[] snap;
        VaultSession session = sessions.get(cacheKey);
        if (session != null) {
            session.lock.lock();
            try { snap = snapshot(inv); }
            finally { session.lock.unlock(); }
        } else {
            snap = snapshot(inv);
        }
        final ItemStack[] finalSnap = snap;

        CompletableFuture<Void> prev = pendingSaves.getOrDefault(cacheKey, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> chained = prev.thenRunAsync(() -> {
            try {
                writePageToDisk(ownerUUID, page, itemStackArrayToBase64(finalSnap));
            } catch (Exception ex) {
                getLogger().warning("Failed to save vault " + cacheKey + ": " + ex.getMessage());
            }
        }, asyncExecutor);

        pendingSaves.put(cacheKey, chained);
        chained.whenComplete((v, e) -> {
            pendingSaves.remove(cacheKey, chained);
            tryEvict(cacheKey);
        });
    }

    private void writePageToDisk(UUID owner, int page, String serialised) throws IOException {
        File f = getPlayerFile(owner);
        FileConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        cfg.set("pages.page-" + page, serialised);
        cfg.save(f);
    }

    private void tryEvict(String cacheKey) {
        Runnable check = () -> {
            VaultSession s = sessions.get(cacheKey);
            if (s == null) return;
            if (!s.inventory.getViewers().isEmpty()) return;
            CompletableFuture<Void> p = pendingSaves.get(cacheKey);
            if (p != null && !p.isDone()) return;
            sessions.remove(cacheKey, s);
        };
        if (!IS_FOLIA && Bukkit.isPrimaryThread()) check.run();
        else runOnMain(check);
    }

    // ─── Serialization ───────────────────────────────────────────────────

    public String itemStackArrayToBase64(ItemStack[] items) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(os)) {
            out.writeInt(items.length);
            for (ItemStack item : items) out.writeObject(item);
            out.flush();
            return Base64.getEncoder().encodeToString(os.toByteArray());
        }
    }

    public ItemStack[] itemStackArrayFromBase64(String data) throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(data);
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream in = new BukkitObjectInputStream(is)) {
            int len = in.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) items[i] = (ItemStack) in.readObject();
            return items;
        }
    }

    // ─── Events ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;
        ReentrantLock lock = sessionLock(holder);
        if (lock != null) lock.lock();
        try {
            Player p = (Player) e.getWhoClicked();
            int slot = e.getRawSlot();
            int size = e.getInventory().getSize();

            if (slot == size - 1 || slot == size - 9) {
                e.setCancelled(true);
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && isNavItem(clicked)) {
                    String nav = clicked.getItemMeta().getPersistentDataContainer()
                            .get(navKey, PersistentDataType.STRING);
                    if (nav != null) {
                        int nextPage = holder.getPage() + ("next".equals(nav) ? 1 : -1);
                        runOnPlayer(p, () -> openVault(p, nextPage, holder.getOwnerUUID()));
                    }
                }
                return;
            }

            if (e.getCurrentItem() != null && isNavItem(e.getCurrentItem())) {
                e.setCancelled(true);
                e.setCurrentItem(null);
            }
            if (e.getCursor() != null && isNavItem(e.getCursor())) {
                e.setCancelled(true);
                e.setCursor(null);
            }
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;
        ReentrantLock lock = sessionLock(holder);
        if (lock != null) lock.lock();
        try {
            if (isNavItem(e.getOldCursor())) { e.setCancelled(true); return; }
            int size = e.getInventory().getSize();
            for (int slot : e.getRawSlots()) {
                if (slot == size - 1 || slot == size - 9) { e.setCancelled(true); return; }
            }
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;
        Inventory inv = e.getInventory();
        String k = key(holder.getOwnerUUID(), holder.getPage());
        saveVaultContent(holder.getOwnerUUID(), holder.getPage(), inv);
        if (inv.getViewers().isEmpty()) tryEvict(k);
    }

    private ReentrantLock sessionLock(VaultHolder holder) {
        VaultSession s = sessions.get(key(holder.getOwnerUUID(), holder.getPage()));
        return s != null ? s.lock : null;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private File getPlayerFile(UUID u) { return new File(userDataFolder, u + ".yml"); }

    private boolean isNavItem(ItemStack i) {
        return i != null && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(navKey, PersistentDataType.STRING);
    }

    private ItemStack createNavItem(Material m, Component name, String tag) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, tag);
        item.setItemMeta(meta);
        return item;
    }

    // ─── Sharing ─────────────────────────────────────────────────────────

    private void addSharedPlayer(UUID owner, UUID guest) {
        File f = getPlayerFile(owner);
        FileConfiguration c = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        List<String> list = c.getStringList("shared-with");
        if (!list.contains(guest.toString())) {
            list.add(guest.toString());
            c.set("shared-with", list);
            try { c.save(f); }
            catch (IOException ex) { getLogger().warning("Failed to save share for " + owner + ": " + ex.getMessage()); }
        }
    }

    private boolean isSharedWith(UUID owner, UUID guest) {
        File f = getPlayerFile(owner);
        if (!f.exists()) return false;
        return YamlConfiguration.loadConfiguration(f)
                .getStringList("shared-with").contains(guest.toString());
    }

    private void showSharedList(Player p) {
        final UUID self = p.getUniqueId();
        CompletableFuture.runAsync(() -> {
            List<String> lines = new ArrayList<>();
            File[] files = userDataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File f : files) {
                    if (YamlConfiguration.loadConfiguration(f).getStringList("shared-with").contains(self.toString())) {
                        String base = f.getName().substring(0, f.getName().length() - 4);
                        try {
                            UUID uuid = UUID.fromString(base);
                            String name = Bukkit.getOfflinePlayer(uuid).getName();
                            lines.add(getConfig().getString("messages.shared-list-entry", "&7- &f%player%")
                                    .replace("%player%", name != null ? name : "Unknown"));
                        } catch (IllegalArgumentException ignored) { /* not a uuid file */ }
                    }
                }
            }
            runOnPlayer(p, () -> {
                p.sendMessage(msg("shared-list-header"));
                if (lines.isEmpty()) p.sendMessage(msg("shared-list-empty"));
                else for (String l : lines) p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(l));
            });
        }, asyncExecutor);
    }

    // ─── Tab Completion ──────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String opt : List.of("share", "shared")) if (opt.startsWith(prefix)) out.add(opt);
            return out;
        }
        return null;
    }
}