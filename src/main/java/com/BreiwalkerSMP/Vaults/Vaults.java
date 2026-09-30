package com.BreiwalkerSMP.Vaults;

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
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class Vaults extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final boolean IS_FOLIA =
            hasClass("io.papermc.paper.threadedregions.RegionScheduler");

    private final Map<String, VaultSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<VaultSession>> loadingFutures = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();

    private NamespacedKey navKey;
    private Executor asyncExecutor;
    private DatabaseManager db;
    private volatile CompletableFuture<Void> dbInitFuture;

    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;

    // ─── Holder & Session (unchanged) ────────────────────────────────────

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
        final UUID owner; final int page; final Inventory inventory;
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
        ConfigMigrator.migrate(this);

        navKey = new NamespacedKey(this, "nav");

        if (IS_FOLIA) {
            asyncExecutor = r -> Bukkit.getAsyncScheduler().runNow(this, t -> r.run());
        } else {
            asyncExecutor = r -> Bukkit.getScheduler().runTaskAsynchronously(this, r);
        }

        // ─── Database init (async, then migrate) ─────────────────────────
        File dbFile    = new File(getDataFolder(), "vaults.db");
        File yamlDir   = new File(getDataFolder(), "userdata");
        db = new DatabaseManager(getLogger(), dbFile, yamlDir);

        dbInitFuture = CompletableFuture.runAsync(() -> {
            try {
                db.open();
                db.migrateFromYamlIfNeeded();
            } catch (SQLException ex) {
                getLogger().severe("Failed to open database: " + ex.getMessage());
                runOnMain(() -> getServer().getPluginManager().disablePlugin(this));
            }
        }, asyncExecutor);

        var cmd = getCommand("vault");
        if (cmd == null) {
            getLogger().severe("Command 'vault' missing from plugin.yml!");
        } else {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);
        checkForUpdates();
    }

    @Override
    public void onDisable() {
        // Wait for the async DB init to settle so we never close after it opens.
        if (dbInitFuture != null) {
            try { dbInitFuture.get(10, TimeUnit.SECONDS); }
            catch (Exception ignored) { }
        }

        // Synchronously flush every open session so nothing is lost on reload/crash-shutdown.
        for (VaultSession s : sessions.values()) {
            try {
                byte[] blob = snapshotToBlob(s.inventory);
                db.savePage(s.owner, s.page, blob);
            } catch (Exception ex) {
                getLogger().warning("Failed to flush vault " + s.owner + " p" + s.page + ": " + ex.getMessage());
            }
        }
        sessions.clear();
        loadingFutures.clear();

        // Drain any in-flight async saves before closing the connection.
        for (CompletableFuture<Void> pending : pendingSaves.values()) {
            try { pending.get(5, TimeUnit.SECONDS); }
            catch (Exception ignored) { }
        }
        pendingSaves.clear();

        if (db != null) db.close();
    }

    // ─── Update checker ──────────────────────────────────────────────────

    private void checkForUpdates() {
        if (!getConfig().getBoolean("update-checker.enabled", true)) return;
        CompletableFuture.runAsync(() -> {
            String channel = getConfig().getString("update-checker.channel", "release");
            UpdateChecker checker = new UpdateChecker(getLogger(), getDescription().getVersion(),
                    serverMinecraftVersion(), channel);
            String newVersion = checker.check();
            if (newVersion == null) return;

            latestVersion = newVersion;
            updateAvailable = true;

            if (getConfig().getBoolean("update-checker.notify-console", true)) {
                getLogger().info("§eA new version of Vaults is available: §f" + newVersion
                        + " §7(you have " + getDescription().getVersion() + ")");
                getLogger().info("§7Download: §bhttps://modrinth.com/plugin/vaults");
            }
        }, asyncExecutor);
    }

    private String serverMinecraftVersion() {
        try {
            String v = Bukkit.getMinecraftVersion();
            return v != null ? v : "";
        } catch (Throwable t) {
            return "";
        }
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

    // ─── Messages (unchanged) ────────────────────────────────────────────

    private Component msg(String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "");
        return LegacyComponentSerializer.legacySection()
                .deserialize(prefix + applyReplacements(getConfig().getString("messages." + key, key), replacements));
    }

    private Component rawMsg(String key, String... replacements) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(applyReplacements(getConfig().getString("messages." + key, key), replacements));
    }

    private String applyReplacements(String s, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            s = s.replace(replacements[i], replacements[i + 1]);
        }
        return s;
    }

    // ─── Threading helpers (unchanged) ───────────────────────────────────

    private static boolean hasClass(String cn) {
        try { Class.forName(cn); return true; } catch (ClassNotFoundException e) { return false; }
    }

    private String key(UUID owner, int page) { return owner + ":" + page; }

    private void runOnMain(Runnable r) {
        if (IS_FOLIA) Bukkit.getGlobalRegionScheduler().run(this, t -> r.run());
        else if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(this, r);
    }

    private void runOnPlayer(Player p, Runnable r) {
        if (IS_FOLIA) p.getScheduler().run(this, t -> r.run(), null);
        else Bukkit.getScheduler().runTask(this, r);
    }

    // ─── Command handling (unchanged) ────────────────────────────────────

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
                    db.addShare(self, target.getUniqueId());
                    runOnPlayer(player, () -> player.sendMessage(
                            msg("vault-shared", "%player%", target.getName())));
                }, asyncExecutor);
                return true;
            }

            final String lookupName = args[0];
            CompletableFuture.runAsync(() -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(lookupName);
                if (!target.hasPlayedBefore()) {
                    runOnPlayer(player, () -> player.sendMessage(msg("player-offline")));
                    return;
                }
                UUID targetUUID = target.getUniqueId();
                boolean allowed = player.isOp()
                        || player.hasPermission("vaults.admin")
                        || db.isSharedWith(targetUUID, player.getUniqueId());
                if (allowed) runOnPlayer(player, () -> openVault(player, 1, targetUUID));
                else runOnPlayer(player, () -> player.sendMessage(msg("no-permission")));
            }, asyncExecutor);
            return true;
        }

        openVault(player, 1, player.getUniqueId());
        return true;
    }

    // ─── Vault open logic (unchanged except load path) ───────────────────

    public void openVault(Player player, int page, UUID targetUUID) {
        final int totalPages = Math.max(1, getConfig().getInt("vault-settings.total-pages", 2));
        if (page < 1 || page > totalPages) return;

        final int rows = Math.max(1, Math.min(6, getConfig().getInt("vault-settings.rows", 6)));
        final String rawTitle = getConfig().getString("vault-settings.title", "Vault %page%")
                .replace("%page%", String.valueOf(page));
        final Component title = LegacyComponentSerializer.legacySection().deserialize(rawTitle);

        final String cacheKey = key(targetUUID, page);

        VaultSession cached = sessions.get(cacheKey);
        if (cached != null) { openSession(player, cached, totalPages); return; }

        CompletableFuture<VaultSession> future = loadingFutures.computeIfAbsent(cacheKey, k -> {
            CompletableFuture<VaultSession> created = new CompletableFuture<>();
            CompletableFuture.supplyAsync(() -> loadItems(targetUUID, page), asyncExecutor)
                    .whenComplete((items, ex) -> {
                        if (ex != null) {
                            created.completeExceptionally(ex);
                            return;
                        }
                        runOnMain(() -> {
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
                        });
                    });
            created.whenComplete((s, ex) -> loadingFutures.remove(cacheKey, created));
            return created;
        });

        future.thenAccept(session -> {
            sessions.putIfAbsent(cacheKey, session);
            VaultSession actual = sessions.get(cacheKey);
            if (!player.isOnline()) return;
            runOnPlayer(player, () -> { if (player.isOnline()) openSession(player, actual, totalPages); });
        }).exceptionally(ex -> {
            getLogger().warning("Failed to load vault " + cacheKey + ": " + ex);
            if (player.isOnline())
                runOnPlayer(player, () -> player.sendMessage(msg("vault-load-failed")));
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
        } finally { lock.unlock(); }
        player.openInventory(session.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    // ─── Persistence (now DB-backed) ─────────────────────────────────────

    private ItemStack[] loadItems(UUID owner, int page) {
        byte[] blob = db.loadPage(owner, page);
        if (blob == null) return new ItemStack[0];
        try {
            return itemStackArrayFromBytes(blob);
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

    private byte[] snapshotToBlob(Inventory inv) throws Exception {
        return itemStackArrayToBytes(snapshot(inv));
    }

    private void saveVaultContent(UUID ownerUUID, int page, Inventory inv) {
        final String cacheKey = key(ownerUUID, page);

        ItemStack[] snap;
        VaultSession session = sessions.get(cacheKey);
        if (session != null) {
            session.lock.lock();
            try { snap = snapshot(inv); } finally { session.lock.unlock(); }
        } else {
            snap = snapshot(inv);
        }
        final ItemStack[] finalSnap = snap;

        // Chain so newer saves always run after older ones.
        CompletableFuture<Void> prev = pendingSaves.getOrDefault(cacheKey, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> chained = prev.thenRunAsync(() -> {
            try {
                db.savePage(ownerUUID, page, itemStackArrayToBytes(finalSnap));
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

    // ─── Serialization (now BLOB, no Base64 in DB) ───────────────────────

    public byte[] itemStackArrayToBytes(ItemStack[] items) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(os)) {
            out.writeInt(items.length);
            for (ItemStack item : items) out.writeObject(item);
            out.flush();
            return os.toByteArray();
        }
    }

    public ItemStack[] itemStackArrayFromBytes(byte[] bytes) throws Exception {
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream in = new BukkitObjectInputStream(is)) {
            int len = in.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) items[i] = (ItemStack) in.readObject();
            return items;
        }
    }

    // ─── Events (unchanged) ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;
        ReentrantLock lock = sessionLock(holder);
        if (lock != null) lock.lock();
        try {
            Player p = (Player) e.getWhoClicked();
            int slot = e.getRawSlot(), size = e.getInventory().getSize();

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
                e.setCancelled(true); e.setCurrentItem(null);
            }
            if (e.getCursor() != null && isNavItem(e.getCursor())) {
                e.setCancelled(true); e.setCursor(null);
            }
        } finally { if (lock != null) lock.unlock(); }
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
        } finally { if (lock != null) lock.unlock(); }
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

    // ─── Sharing (now DB-backed) ─────────────────────────────────────────

    private void showSharedList(Player p) {
        final UUID self = p.getUniqueId();
        CompletableFuture.runAsync(() -> {
            List<String> lines = new ArrayList<>();
            for (UUID owner : db.ownersSharingWith(self)) {
                String name = Bukkit.getOfflinePlayer(owner).getName();
                lines.add(getConfig().getString("messages.shared-list-entry", "&7- &f%player%")
                        .replace("%player%", name != null ? name : "Unknown"));
            }
            runOnPlayer(p, () -> {
                p.sendMessage(msg("shared-list-header"));
                if (lines.isEmpty()) p.sendMessage(msg("shared-list-empty"));
                else for (String l : lines)
                    p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(l));
            });
        }, asyncExecutor);
    }

    // ─── Tab Completion (unchanged) ──────────────────────────────────────

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