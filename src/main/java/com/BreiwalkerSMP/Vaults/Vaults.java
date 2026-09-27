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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class Vaults extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final boolean IS_FOLIA = hasClass("io.papermc.paper.threadedregions.RegionScheduler");

    // Cache für aktive Inventar-Instanzen
    private final Map<String, Inventory> activeVaultInventories = new ConcurrentHashMap<>();
    // Locks pro Inventar, um Thread-Safety zu garantieren (Folia)
    private final Map<String, ReentrantLock> vaultLocks = new ConcurrentHashMap<>();
    // Laufende asynchrone Ladevorgänge (damit nicht mehrfach geladen wird)
    private final Map<String, CompletableFuture<Inventory>> loadingFutures = new ConcurrentHashMap<>();

    private File userDataFolder;
    private NamespacedKey navKey;

    // Custom Inventory Holder (behebt Title-Anvil-Dupe)
    public static class VaultHolder implements InventoryHolder {
        private final UUID ownerUUID;
        private final int page;

        public VaultHolder(UUID ownerUUID, int page) {
            this.ownerUUID = ownerUUID;
            this.page = page;
        }

        public UUID getOwnerUUID() { return ownerUUID; }
        public int getPage() { return page; }

        @Override
        public Inventory getInventory() { return null; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        userDataFolder = new File(getDataFolder(), "userdata");
        if (!userDataFolder.exists()) userDataFolder.mkdirs();



        navKey = new NamespacedKey(this, "nav");

        getCommand("vault").setExecutor(this);
        getCommand("vault").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ─── Threading-Kompatibilität ─────────────────────────────────────────

    private void runAsync(Runnable runnable) {
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(this, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, runnable);
        }
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (IS_FOLIA) {
            player.getScheduler().run(this, task -> runnable.run(), null);
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ─── Command Handler ──────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length > 0) {
            String sub = args[0].toLowerCase();

            if (sub.equals("shared")) {
                showSharedList(player);
                return true;
            }

            if (sub.equals("share") && args.length >= 2) {
                String targetName = args[1];
                runAsync(() -> {
                    @SuppressWarnings("deprecation")
                    Player target = Bukkit.getPlayer(targetName);
                    if (target == null) {
                        player.sendMessage(Component.text("§cSpieler offline."));
                        return;
                    }
                    addSharedPlayer(player.getUniqueId(), target.getUniqueId());
                    player.sendMessage(Component.text("§aVault geteilt mit §e" + target.getName()));
                });
                return true;
            }

            // Öffnen des Vaults eines anderen Spielers
            String lookupName = args[0];
            runAsync(() -> {
                @SuppressWarnings("deprecation")
                UUID targetUUID = Bukkit.getOfflinePlayer(lookupName).getUniqueId();
                // Berechtigungsprüfung asynchron
                boolean allowed = isSharedWith(targetUUID, player.getUniqueId()) || player.isOp() || player.hasPermission("vaults.admin");
                if (allowed) {
                    runOnPlayer(player, () -> openVault(player, 1, targetUUID));
                } else {
                    player.sendMessage(Component.text("§cKeine Berechtigung."));
                }
            });
            return true;
        }

        // Eigenen Vault öffnen
        openVault(player, 1, player.getUniqueId());
        return true;
    }

    // ─── Vault-Logik (asynchrones Laden, threadsicher) ────────────────────

    public void openVault(Player player, int page, UUID targetUUID) {
        int rows = getConfig().getInt("vault-settings.rows", 6);
        int totalPages = getConfig().getInt("vault-settings.total-pages", 2);
        String rawTitle = getConfig().getString("vault-settings.title", "Vault %page%")
                .replace("%page%", String.valueOf(page));
        Component titleComponent = LegacyComponentSerializer.legacySection().deserialize(rawTitle);

        String cacheKey = targetUUID.toString() + ":" + page;
        Inventory cached = activeVaultInventories.get(cacheKey);
        if (cached != null) {
            // Bereits geladen – nur noch öffnen
            openCachedInventory(player, cached, page, totalPages);
            return;
        }

        // Asynchrones Laden starten
        CompletableFuture<Inventory> future = loadingFutures.get(cacheKey);
        if (future == null) {
            future = CompletableFuture.supplyAsync(() -> {
                        // Inventar-Erstellung & Datenladen (auf asynchronem Thread, da Bukkit.createInventory threadsicher ist)
                        Inventory newInv = Bukkit.createInventory(new VaultHolder(targetUUID, page), rows * 9, titleComponent);
                        File userFile = getPlayerFile(targetUUID);
                        if (userFile.exists()) {
                            FileConfiguration config = YamlConfiguration.loadConfiguration(userFile);
                            String data = config.getString("pages.page-" + page);
                            if (data != null && !data.isEmpty()) {
                                try {
                                    ItemStack[] loaded = itemStackArrayFromBase64(data);
                                    int copyLen = Math.min(loaded.length, newInv.getSize());
                                    for (int i = 0; i < copyLen; i++) {
                                        if (loaded[i] != null && !isNavItem(loaded[i])) {
                                            newInv.setItem(i, loaded[i]);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        return newInv;
                    }, runnable -> runAsync(runnable)) // asynchron ausführen
                    .thenApply(inv -> {
                        // Nach erfolgreichem Laden: registrieren und öffnen
                        runOnPlayer(player, () -> {
                            activeVaultInventories.put(cacheKey, inv);
                            vaultLocks.put(cacheKey, new ReentrantLock());
                            openCachedInventory(player, inv, page, totalPages);
                        });
                        return inv;
                    });
            loadingFutures.put(cacheKey, future);
            future.whenComplete((inv, ex) -> loadingFutures.remove(cacheKey));
        }

        // Dem Spieler Bescheid geben, falls das Laden dauert (optional)
        future.thenAccept(inv -> runOnPlayer(player, () -> openCachedInventory(player, inv, page, totalPages)));
    }

    private void openCachedInventory(Player player, Inventory inv, int page, int totalPages) {
        // Navigations-Buttons auffrischen (ohne Lock, da nur lesend)
        int size = inv.getSize();
        inv.setItem(size - 1, page < totalPages ? createNavItem(Material.ARROW, "§eNext →", "next") : null);
        inv.setItem(size - 9, page > 1 ? createNavItem(Material.ARROW, "§e← Prev", "prev") : null);

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    private void saveVaultContent(UUID ownerUUID, int page, Inventory inv) {
        ReentrantLock lock = vaultLocks.get(ownerUUID.toString() + ":" + page);
        if (lock != null) lock.lock();
        ItemStack[] contents;
        try {
            // Tiefe Kopie während der Lock haltend
            ItemStack[] raw = inv.getContents();
            contents = new ItemStack[raw.length];
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] != null && !isNavItem(raw[i])) {
                    contents[i] = raw[i].clone();
                }
            }
        } finally {
            if (lock != null) lock.unlock();
        }

        ItemStack[] serializationSnapshot = contents; // bereits geklont
        runAsync(() -> {
            String serialised = itemStackArrayToBase64(serializationSnapshot);
            File f = getPlayerFile(ownerUUID);
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("pages.page-" + page, serialised);
            try {
                cfg.save(f);
            } catch (IOException ignored) {}
        });
    }

    // ─── Events (Exploit- und Dupe-Schutz) ────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;
        ReentrantLock lock = vaultLocks.get(holder.getOwnerUUID().toString() + ":" + holder.getPage());
        if (lock != null) lock.lock();
        try {
            Player p = (Player) e.getWhoClicked();
            int slot = e.getRawSlot();
            int size = e.getInventory().getSize();

            // Navigations-Slots blockieren
            if (slot == size - 1 || slot == size - 9) {
                e.setCancelled(true);
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && isNavItem(clicked)) {
                    String nav = clicked.getItemMeta().getPersistentDataContainer().get(navKey, PersistentDataType.STRING);
                    int nextPage = holder.getPage() + ("next".equals(nav) ? 1 : -1);
                    // Seite wechseln (ohne Lock, da openVault eigenen Lock verwendet)
                    openVault(p, nextPage, holder.getOwnerUUID());
                }
                return;
            }

            // Nav-Items aus Inventar entfernen/ablehnen
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
        ReentrantLock lock = vaultLocks.get(holder.getOwnerUUID().toString() + ":" + holder.getPage());
        if (lock != null) lock.lock();
        try {
            // Nav-Item am Cursor? Dann ganzen Drag abbrechen
            if (isNavItem(e.getOldCursor())) {
                e.setCancelled(true);
                return;
            }
            int size = e.getInventory().getSize();
            for (int slot : e.getRawSlots()) {
                if (slot == size - 1 || slot == size - 9) {
                    e.setCancelled(true);
                    return;
                }
            }
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof VaultHolder holder)) return;

        // Inventar speichern (mit Lock)
        saveVaultContent(holder.getOwnerUUID(), holder.getPage(), e.getInventory());

        // Cache nur leeren, wenn wirklich kein Spieler mehr die Seite offen hat
        if (e.getInventory().getViewers().isEmpty()) {
            String cacheKey = holder.getOwnerUUID().toString() + ":" + holder.getPage();
            activeVaultInventories.remove(cacheKey);
            vaultLocks.remove(cacheKey);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // Bukkit ruft InventoryCloseEvent automatisch vorher auf – nichts weiter nötig.
    }

    // ─── Hilfsmethoden & Serialisierung ────────────────────────────────────

    private File getPlayerFile(UUID u) {
        return new File(userDataFolder, u + ".yml");
    }

    private boolean isNavItem(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(navKey, PersistentDataType.STRING);
    }

    private ItemStack createNavItem(Material m, String name, String tag) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name));
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, tag);
        item.setItemMeta(meta);
        return item;
    }

    public String itemStackArrayToBase64(ItemStack[] items) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(os)) {
            out.writeInt(items.length);
            for (ItemStack item : items) out.writeObject(item);
            return Base64Coder.encodeLines(os.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public ItemStack[] itemStackArrayFromBase64(String data) throws Exception {
        try (ByteArrayInputStream is = new ByteArrayInputStream(Base64Coder.decodeLines(data));
             BukkitObjectInputStream in = new BukkitObjectInputStream(is)) {
            int len = in.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) items[i] = (ItemStack) in.readObject();
            return items;
        }
    }

    // ─── Sharing-System ───────────────────────────────────────────────────

    private void addSharedPlayer(UUID owner, UUID guest) {
        runAsync(() -> {
            File f = getPlayerFile(owner);
            FileConfiguration c = YamlConfiguration.loadConfiguration(f);
            List<String> list = c.getStringList("shared-with");
            if (!list.contains(guest.toString())) {
                list.add(guest.toString());
                c.set("shared-with", list);
                try { c.save(f); } catch (IOException ignored) {}
            }
        });
    }

    private boolean isSharedWith(UUID owner, UUID guest) {
        // Synchron, da nur eine kleine Config geladen wird – Aufruf im Async-Context okay
        return YamlConfiguration.loadConfiguration(getPlayerFile(owner))
                .getStringList("shared-with").contains(guest.toString());
    }

    private void showSharedList(Player p) {
        runAsync(() -> {
            p.sendMessage(Component.text("§eVault-Zugriffe:"));
            File[] files = userDataFolder.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (YamlConfiguration.loadConfiguration(f).getStringList("shared-with").contains(p.getUniqueId().toString())) {
                    try {
                        UUID uuid = UUID.fromString(f.getName().replace(".yml", ""));
                        @SuppressWarnings("deprecation")
                        String name = Bukkit.getOfflinePlayer(uuid).getName();
                        p.sendMessage(Component.text("§7- §f" + (name != null ? name : "Unbekannt")));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        });
    }

    // ─── Tab Completer ────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return new ArrayList<>(List.of("share", "shared"));
        return null;
    }
}