package com.BreiwalkerSMP.Vaults;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /vaultadmin} (alias {@code /va}) — op/console management of vaults and shares.
 * Every subcommand is gated behind the single {@code vaults.admin} permission.
 *
 * <p>All database and {@link Bukkit#getOfflinePlayer(String)} lookups happen on the async
 * executor; replies are delivered back on the player's scheduler ({@code runOnPlayer}) or the
 * global/main thread ({@code runOnMain}) depending on the sender.</p>
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("open", "shares", "unshare", "clear", "reload", "info");

    private final Vaults plugin;

    public AdminCommand(Vaults plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open"    -> cmdOpen(sender, args);
            case "shares"  -> cmdShares(sender, args);
            case "unshare" -> cmdUnshare(sender, args);
            case "clear"   -> cmdClear(sender, args);
            case "reload"  -> cmdReload(sender);
            case "info"    -> cmdInfo(sender, args);
            default        -> sender.sendMessage(plugin.msg("admin-usage"));
        }
        return true;
    }

    // ─── Subcommands ─────────────────────────────────────────────────────

    private void cmdOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("admin-player-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return;
        }
        final String name = args[1];
        final int totalPages = Math.max(1, plugin.getConfig().getInt("vault-settings.total-pages", 2));
        final int page = Math.max(1, Math.min(totalPages, parseInt(args.length >= 3 ? args[2] : null, 1)));
        CompletableFuture.runAsync(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            if (!target.hasPlayedBefore()) {
                reply(sender, plugin.msg("player-offline"));
                return;
            }
            UUID owner = target.getUniqueId();
            String shown = displayName(target, name);
            reply(sender, plugin.msg("admin-opened", "%player%", shown, "%page%", String.valueOf(page)));
            plugin.runOnPlayer(player, () -> {
                if (player.isOnline()) plugin.openVaultChecked(player, page, owner);
            });
        }, plugin.asyncExecutor());
    }

    private void cmdShares(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return;
        }
        final String name = args[1];
        CompletableFuture.runAsync(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            if (!target.hasPlayedBefore()) {
                reply(sender, plugin.msg("player-offline"));
                return;
            }
            UUID owner = target.getUniqueId();
            String shown = displayName(target, name);
            List<UUID> guests = plugin.db().guestsOf(owner);
            List<UUID> sharers = plugin.db().ownersSharingWith(owner);
            reply(sender, plugin.msg("admin-shares-header", "%player%", shown));
            if (guests.isEmpty() && sharers.isEmpty()) {
                reply(sender, plugin.msg("admin-shares-none"));
                return;
            }
            for (UUID guest : guests) {
                reply(sender, plugin.rawMsg("admin-shares-guests", "%player%", nameOf(guest)));
            }
            for (UUID sharer : sharers) {
                reply(sender, plugin.rawMsg("admin-shares-owners", "%player%", nameOf(sharer)));
            }
        }, plugin.asyncExecutor());
    }

    private void cmdUnshare(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return;
        }
        final String ownerName = args[1];
        final String guestName = args[2];
        CompletableFuture.runAsync(() -> {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerName);
            OfflinePlayer guest = Bukkit.getOfflinePlayer(guestName);
            if (!owner.hasPlayedBefore() || !guest.hasPlayedBefore()) {
                reply(sender, plugin.msg("player-offline"));
                return;
            }
            String oName = displayName(owner, ownerName);
            String gName = displayName(guest, guestName);
            boolean removed = plugin.db().removeShare(owner.getUniqueId(), guest.getUniqueId());
            reply(sender, removed
                    ? plugin.msg("admin-unshared", "%owner%", oName, "%guest%", gName)
                    : plugin.msg("admin-unshare-none", "%owner%", oName, "%guest%", gName));
        }, plugin.asyncExecutor());
    }

    private void cmdClear(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return;
        }
        final String name = args[1];
        final String pageArg = args[2].toLowerCase(Locale.ROOT);
        final boolean confirm = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
        CompletableFuture.runAsync(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            if (!target.hasPlayedBefore()) {
                reply(sender, plugin.msg("player-offline"));
                return;
            }
            UUID owner = target.getUniqueId();
            String shown = displayName(target, name);
            if (pageArg.equals("all")) {
                if (!confirm) {
                    reply(sender, plugin.msg("admin-clear-confirm", "%player%", shown));
                    return;
                }
                int removed = plugin.db().deleteAllPages(owner);
                reply(sender, plugin.msg("admin-cleared",
                        "%player%", shown, "%count%", String.valueOf(removed)));
                return;
            }
            int page = parseInt(pageArg, -1);
            if (page < 1) {
                reply(sender, plugin.msg("admin-usage"));
                return;
            }
            plugin.db().deletePage(owner, page);
            reply(sender, plugin.msg("admin-cleared", "%player%", shown, "%count%", "1"));
        }, plugin.asyncExecutor());
    }

    private void cmdReload(CommandSender sender) {
        plugin.runOnMain(() -> {
            try {
                plugin.reloadConfig();
                plugin.saveDefaultConfig();
                plugin.getConfig().options().copyDefaults(true);
                ConfigMigrator.migrate(plugin);
                sender.sendMessage(plugin.msg("admin-reloaded"));
            } catch (Throwable t) {
                plugin.getLogger().warning("Config reload failed: " + t.getMessage());
                sender.sendMessage(plugin.msg("admin-reload-failed"));
            }
        });
    }

    private void cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("admin-usage"));
            return;
        }
        final String name = args[1];
        CompletableFuture.runAsync(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            if (!target.hasPlayedBefore()) {
                reply(sender, plugin.msg("player-offline"));
                return;
            }
            UUID owner = target.getUniqueId();
            String shown = displayName(target, name);
            int pages = plugin.db().pageCount(owner);
            long kb = (plugin.db().totalBytes(owner) + 1023L) / 1024L;
            int guests = plugin.db().guestsOf(owner).size();
            int sharers = plugin.db().ownersSharingWith(owner).size();
            reply(sender, plugin.msg("admin-info-header", "%player%", shown));
            reply(sender, plugin.rawMsg("admin-info-pages", "%pages%", String.valueOf(pages)));
            reply(sender, plugin.rawMsg("admin-info-storage", "%kb%", String.valueOf(kb)));
            reply(sender, plugin.rawMsg("admin-info-shares",
                    "%guests%", String.valueOf(guests), "%owners%", String.valueOf(sharers)));
        }, plugin.asyncExecutor());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void reply(CommandSender sender, Component message) {
        if (sender instanceof Player player) plugin.runOnPlayer(player, () -> sender.sendMessage(message));
        else plugin.runOnMain(() -> sender.sendMessage(message));
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static String displayName(OfflinePlayer p, String fallback) {
        String name = p.getName();
        return name != null ? name : fallback;
    }

    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    private static List<String> onlinePlayers(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers())
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        return out;
    }

    // ─── Tab completion ──────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("open") || sub.equals("shares") || sub.equals("unshare")
                    || sub.equals("clear") || sub.equals("info")) {
                return onlinePlayers(args[1]);
            }
            return List.of();
        }
        if (args.length == 3) {
            if (sub.equals("unshare")) return onlinePlayers(args[2]);
            if (sub.equals("clear")) {
                List<String> options = new ArrayList<>();
                options.add("all");
                int total = Math.max(1, plugin.getConfig().getInt("vault-settings.total-pages", 2));
                for (int i = 1; i <= total; i++) options.add(String.valueOf(i));
                return filter(options, args[2]);
            }
            return List.of();
        }
        if (args.length == 4 && sub.equals("clear") && args[2].equalsIgnoreCase("all")) {
            return filter(List.of("confirm"), args[3]);
        }
        return List.of();
    }
}
