package pl.prace;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.scheduler.BukkitTask;

public final class PracePlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private File dataFile;
    private YamlConfiguration data;
    private final Random random = new Random();
    private final Set<UUID> treeProcessing = new HashSet<>();
    private final Map<UUID, BukkitTask> actionBarTasks = new HashMap<>();
    private static final String MENU_TITLE = "ᴘʀᴀᴄᴇ";
    private static final long SWITCH_COOLDOWN_MS = 72L * 60L * 60L * 1000L;

    private enum Job {
        MINER("ɢᴏʀɴɪᴋ", Material.IRON_PICKAXE, "Gornik"),
        ASSASSIN("ᴢᴀʙᴏᴊᴄᴀ", Material.IRON_SWORD, "Zabojca"),
        LUMBERJACK("ᴅʀᴡᴀʟ", Material.IRON_AXE, "Drwal");
        final String display;
        final Material icon;
        final String plain;
        Job(String display, Material icon, String plain) {
            this.display = display; this.icon = icon; this.plain = plain;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Nie znaleziono Vault lub pluginu ekonomii. Plugin zostaje wylaczony.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("praca")).setExecutor(this);

        getLogger().info("Plugin Prace zostal wlaczony.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override public void onDisable() { saveData(); }

    private String base(Player p, Job j) { return p.getUniqueId() + "." + j.name().toLowerCase(Locale.ROOT); }
    private int getLevel(Player p, Job j) { return data.getInt(base(p,j) + ".level", 0); }
    private double getProgress(Player p, Job j) { return data.getDouble(base(p,j) + ".progress", 0.0); }
    private Job getActiveJob(Player p) {
        String raw = data.getString(p.getUniqueId() + ".active-job", "");
        if (raw == null || raw.isBlank()) return null;
        try { return Job.valueOf(raw); } catch (IllegalArgumentException ex) { return null; }
    }

    private void addProgress(Player p, Job j, double amount) {
        if (getActiveJob(p) != j || amount <= 0) return;
        String path = base(p,j);
        double progress = data.getDouble(path + ".progress", 0.0) + amount;
        int level = data.getInt(path + ".level", 0);
        while (progress >= 100.0) {
            progress -= 100.0;
            level++;
            int milestone = Math.max(1, getConfig().getInt("rewards.milestone-every", 10));
            double reward = (level == 1)
                    ? getConfig().getDouble("rewards.normal-level", 50.0)
                    : (level % milestone == 0
                        ? getConfig().getDouble("rewards.milestone-level", 500.0)
                        : getConfig().getDouble("rewards.normal-level", 50.0));
            economy.depositPlayer(p, reward);
            p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &aUkończono poziom " + level + " pracy " + j.plain + "! Otrzymujesz &e" + String.format(Locale.US, "%.0f", reward) + "$"));
            if (j == Job.ASSASSIN && level == getConfig().getInt("assassin.unlock-level", 20))
                p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &dOdblokowano umiejętność podwojnych obrażeń!"));
            if (j == Job.LUMBERJACK && level == getConfig().getInt("lumberjack.tree-felling-unlock-level", 10))
                p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &aOdblokowano ścinanie całych drzew!"));
        }
        data.set(path + ".level", level);
        data.set(path + ".progress", progress);
        saveData();
        showActionBar(p, j);
    }

    private void showActionBar(Player p, Job job) {
        UUID id = p.getUniqueId();
        BukkitTask old = actionBarTasks.remove(id);
        if (old != null) old.cancel();
        p.sendActionBar(net.kyori.adventure.text.Component.text(progressBar(p, job) + "  Poziom: " + getLevel(p, job)));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) p.sendActionBar(net.kyori.adventure.text.Component.text(""));
            actionBarTasks.remove(id);
        }, 60L);
        actionBarTasks.put(id, task);
    }

    private String progressBar(Player p, Job j) {
        int percent = Math.max(0, Math.min(100, (int)Math.floor(getProgress(p,j))));
        int green = Math.min(10, percent / 10);
        return color("&f" + percent + "% &a" + "|".repeat(green) + "&c" + "|".repeat(10-green));
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void saveData() {
        if (data == null || dataFile == null) return;
        try { data.save(dataFile); }
        catch (IOException e) { getLogger().severe("Nie mozna zapisac players.yml: " + e.getMessage()); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Tej komendy moze uzyc tylko gracz.");
            return true;
        }
        openMenu(p);
        return true;
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);
        inv.setItem(10, jobItem(p, Job.MINER));
        inv.setItem(13, jobItem(p, Job.ASSASSIN));
        inv.setItem(16, jobItem(p, Job.LUMBERJACK));
        Job active = getActiveJob(p);
        long remaining = Math.max(0L, data.getLong(p.getUniqueId() + ".switch-until", 0L) - System.currentTimeMillis());
        inv.setItem(22, makeItem(Material.BOOK, "&eTwoja praca",
                "&7Aktywna: " + (active == null ? "&cBrak" : "&a" + active.plain),
                active == null ? "&7Wybierz jedną z prac." : "&7Zmiana pracy za: &e" + formatTime(remaining),
                "&7Poziomy każdej pracy zapisują się osobno."));
        p.openInventory(inv);
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000L;
        long days = seconds / 86400L; seconds %= 86400L;
        long hours = seconds / 3600L; seconds %= 3600L;
        long minutes = seconds / 60L;
        return days + "d " + hours + "h " + minutes + "min";
    }

    private ItemStack jobItem(Player p, Job j) {
        String status = getActiveJob(p) == j ? "&a&lAKTYWNA" : "&7Kliknij, aby wybrać";
        return makeItem(j.icon, "&e" + j.display,
                "&7Poziom: &f" + getLevel(p,j),
                "&7Postęp: &f" + (int)getProgress(p,j) + "%",
                "&7" + progressBar(p,j),
                status,
                j == Job.MINER ? "&715% szansy na podwojny drop" :
                j == Job.ASSASSIN ? "&7Podwojne obrażenia od poziomu 20" :
                "&7Ścinanie całych drzew od poziomu 10");
    }

    private ItemStack makeItem(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> lines = new ArrayList<>();
        for (String line : lore) lines.add(color(line));
        meta.setLore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!MENU_TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        Job selected = switch (e.getRawSlot()) {
            case 10 -> Job.MINER;
            case 13 -> Job.ASSASSIN;
            case 16 -> Job.LUMBERJACK;
            default -> null;
        };
        if (selected == null) return;
        Job current = getActiveJob(p);
        if (current == selected) {
            p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &aTa praca jest już aktywna."));
            p.closeInventory();
            return;
        }
        long until = data.getLong(p.getUniqueId() + ".switch-until", 0L);
        long remaining = until - System.currentTimeMillis();
        if (current != null && remaining > 0L) {
            p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &cNie możesz jeszcze zmienić pracy. Pozostało: &e" + formatTime(remaining)));
            p.closeInventory();
            return;
        }
        data.set(p.getUniqueId() + ".active-job", selected.name());
        data.set(p.getUniqueId() + ".switch-until", System.currentTimeMillis() + SWITCH_COOLDOWN_MS);
        saveData();
        p.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &aWybrano pracę: &e" + selected.plain + "&a. Zmiana będzie możliwa za 3 dni."));
        p.closeInventory();
    }

    @EventHandler public void onMenuDrag(InventoryDragEvent e) {
        if (MENU_TITLE.equals(e.getView().getTitle())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Job active = getActiveJob(p);
        Material type = e.getBlock().getType();

        if (active == Job.MINER && getConfig().getStringList("miner.materials").contains(type.name())) {
            addProgress(p, Job.MINER, getConfig().getDouble("miner.progress-per-block", 2.0));
            double chance = getConfig().getDouble("miner.double-drop-chance", 15.0);
            if (random.nextDouble() * 100.0 < chance) {
                Collection<ItemStack> drops = e.getBlock().getDrops(p.getInventory().getItemInMainHand(), p);
                e.setDropItems(false);
                Location dropAt = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
                for (ItemStack drop : drops) {
                    ItemStack doubled = drop.clone();
                    doubled.setAmount(drop.getAmount() * 2);
                    e.getBlock().getWorld().dropItemNaturally(dropAt, doubled);
                }
            }
        }

        if (active == Job.LUMBERJACK && isLog(type)) {
            if (getLevel(p, Job.LUMBERJACK) >= getConfig().getInt("lumberjack.tree-felling-unlock-level", 10)
                    && !treeProcessing.contains(p.getUniqueId())) {
                fellConnectedLogs(p, e.getBlock());
            } else {
                addProgress(p, Job.LUMBERJACK, getConfig().getDouble("lumberjack.progress-per-log", 2.0));
            }
        }
    }

    private boolean isLog(Material m) {
        return m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") ||
               m.name().equals("CRIMSON_STEM") || m.name().equals("WARPED_STEM") ||
               m.name().equals("CRIMSON_HYPHAE") || m.name().equals("WARPED_HYPHAE");
    }

    private void fellConnectedLogs(Player p, Block first) {
        treeProcessing.add(p.getUniqueId());
        try {
            int max = Math.max(1, getConfig().getInt("lumberjack.max-logs-per-tree", 128));
            Queue<Block> queue = new ArrayDeque<>();
            Set<Location> visited = new HashSet<>();
            List<Block> logs = new ArrayList<>();
            queue.add(first);
            while (!queue.isEmpty() && logs.size() < max) {
                Block b = queue.poll();
                if (!visited.add(b.getLocation()) || !isLog(b.getType())) continue;
                logs.add(b);
                for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
                    if (dx==0 && dy==0 && dz==0) continue;
                    Block next = b.getRelative(dx,dy,dz);
                    if (isLog(next.getType()) && !visited.contains(next.getLocation())) queue.add(next);
                }
            }
            // Zwykły event zniszczy pierwszy blok; pozostałe ścinamy z uwzględnieniem narzędzia.
            for (Block b : logs) {
                if (b.equals(first)) continue;
                Collection<ItemStack> drops = b.getDrops(p.getInventory().getItemInMainHand(), p);
                Location loc = b.getLocation().add(0.5,0.5,0.5);
                for (ItemStack drop : drops) b.getWorld().dropItemNaturally(loc, drop);
                b.setType(Material.AIR, false);
                addProgress(p, Job.LUMBERJACK, getConfig().getDouble("lumberjack.progress-per-log", 2.0));
            }
            addProgress(p, Job.LUMBERJACK, getConfig().getDouble("lumberjack.progress-per-log", 2.0));
        } finally {
            treeProcessing.remove(p.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobKill(EntityDeathEvent e) {
        if (!(e.getEntity().getKiller() instanceof Player killer)) return;
        if (e.getEntity() instanceof Player) return;
        addProgress(killer, Job.ASSASSIN, getConfig().getDouble("assassin.mob-progress", 2.0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKill(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        addProgress(killer, Job.ASSASSIN, getConfig().getDouble("assassin.player-progress", 10.0));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAssassinDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof Player victim)) return;
        if (getActiveJob(attacker) != Job.ASSASSIN) return;
        if (getLevel(attacker, Job.ASSASSIN) < getConfig().getInt("assassin.unlock-level", 20)) return;
        if (victim.getHealth() <= 4.0) return;
        double chance = getConfig().getDouble("assassin.double-damage-chance", 10.0);
        if (random.nextDouble() * 100.0 < chance) {
            e.setDamage(e.getDamage() * 2.0);
            attacker.sendMessage(color("&fᴘʀᴀᴄᴇ &7» &dAktywowałeś podwojne obrażenia!"));
        }
    }
}
