package ac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AntiCheatPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private File bansFile;
    private YamlConfiguration bans;
    private final Set<UUID> checked = new HashSet<>();
    private static final long DAY = 86400000L;

    static class BanHolder implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bansFile = new File(getDataFolder(), "bans.yml");
        bans = YamlConfiguration.loadConfiguration(bansFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("banlist").setExecutor(this);
    }

    // ---- Tarama ----
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("ac.bypass")) return;
        // client brand paketi giriş sonrası gelir, biraz bekle
        Bukkit.getScheduler().runTaskLater(this, () -> scan(p), 40L);
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("ac.bypass")) return;
        check(p, e.getChannel());
    }

    private void scan(Player p) {
        if (!p.isOnline()) return;
        String brand = p.getClientBrandName();
        if (brand != null) check(p, brand);
        for (String ch : p.getListeningPluginChannels()) check(p, ch);
    }

    private void check(Player p, String text) {
        String low = text.toLowerCase(Locale.ROOT);
        for (String kw : getConfig().getStringList("blocked")) {
            if (low.contains(kw.toLowerCase(Locale.ROOT))) {
                if (checked.add(p.getUniqueId())) banPlayer(p, kw);
                return;
            }
        }
    }

    // ---- Ban ----
    private void banPlayer(Player p, String found) {
        int days = getConfig().getInt("ban-days", 60);
        long now = System.currentTimeMillis();
        String ip = p.getAddress().getAddress().getHostAddress();
        String path = p.getUniqueId().toString();
        bans.set(path + ".name", p.getName());
        bans.set(path + ".ip", ip);
        bans.set(path + ".reason", found + " modu");
        bans.set(path + ".days", days);
        bans.set(path + ".until", now + days * DAY);
        saveBans();
        p.kickPlayer(ChatColor.RED + "Bu " + found + " Sunucuda Kullanmak YASAK!\n"
                + ChatColor.GRAY + days + " gün IP ban");
        sendDiscord(p.getName(), found + " modu", days);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String ip = e.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        for (String key : bans.getKeys(false)) {
            long until = bans.getLong(key + ".until");
            if (until < now) continue;
            if (ip.equals(bans.getString(key + ".ip")) || key.equals(e.getUniqueId().toString())) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        ChatColor.RED + "Sunucudan banlandın: " + bans.getString(key + ".reason")
                                + "\nKalan: " + ((until - now) / DAY + 1) + " gün");
                return;
            }
        }
    }

    private void saveBans() {
        try { bans.save(bansFile); } catch (IOException ex) { ex.printStackTrace(); }
    }

    // ---- Discord ----
    private void sendDiscord(String name, String reason, int days) {
        String url = getConfig().getString("discord-webhook", "");
        if (url.isEmpty() || url.contains("BURAYA")) return;
        String msg = "**" + name + "** banlandı\\nSebep: " + reason + "\\nSüre: " + days + " gün (IP ban)";
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", "AntiCheat");
                c.setDoOutput(true);
                c.getOutputStream().write(("{\"content\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ex) { ex.printStackTrace(); }
        });
    }

    // ---- /banlist ----
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        Inventory inv = Bukkit.createInventory(new BanHolder(), 54, "Ban Listesi");
        long now = System.currentTimeMillis();
        for (String key : bans.getKeys(false)) {
            long until = bans.getLong(key + ".until");
            if (until < now || inv.firstEmpty() == -1) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(key)));
            m.setDisplayName(ChatColor.RED + bans.getString(key + ".name"));
            m.setLore(Arrays.asList(
                    ChatColor.GRAY + "Neden: " + ChatColor.WHITE + bans.getString(key + ".reason"),
                    ChatColor.GRAY + "Süre: " + ChatColor.WHITE + bans.getInt(key + ".days") + " gün",
                    ChatColor.GRAY + "Kalan: " + ChatColor.WHITE + ((until - now) / DAY + 1) + " gün"));
            head.setItemMeta(m);
            inv.addItem(head);
        }
        p.openInventory(inv);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof BanHolder) e.setCancelled(true);
    }
}
