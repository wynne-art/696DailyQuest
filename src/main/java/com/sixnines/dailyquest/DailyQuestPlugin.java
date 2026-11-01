package com.sixnines.dailyquest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

public final class DailyQuestPlugin extends JavaPlugin implements Listener {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private File dataFile;
    private YamlConfiguration store;
    private BukkitTask ticker;
    private NamespacedKey menuKey;

    private static final class PlayerData {
        String date;
        long activeSeconds;
        double walkedBlocks;
        Set<String> claimed = new HashSet<>();
        PlayerData(String date) { this.date = date; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuKey = new NamespacedKey(this, "quest_id");
        loadStore();
        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("daily")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Command ini hanya bisa dipakai pemain.");
                return true;
            }
            openMenu(player);
            return true;
        });
        Objects.requireNonNull(getCommand("dailyquestadmin")).setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("dailyquest.admin")) {
                send(sender, msg("no-permission"));
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                send(sender, msg("reloaded"));
            } else {
                sender.sendMessage("Gunakan: /dailyquestadmin reload");
            }
            return true;
        });

        long period = Math.max(10, getConfig().getLong("settings.activity-check-seconds", 30));
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tickPlayers, 20L, period * 20L);
        long savePeriod = Math.max(20, getConfig().getLong("settings.save-interval-seconds", 60));
        Bukkit.getScheduler().runTaskTimer(this, this::saveStore, savePeriod * 20L, savePeriod * 20L);
        getLogger().info("696DailyQuest enabled.");
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        saveStore();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        ensureFresh(p.getUniqueId());
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        // Teleportation is deliberately not counted as walking.
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        if (event.getTo() == null) return;
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) return;

        double distanceSquared = event.getFrom().distanceSquared(event.getTo());
        if (distanceSquared < 0.0001) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        lastActivity.put(id, System.currentTimeMillis());

        // Count genuine ground movement only: no flying, gliding, vehicles, or swimming.
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding() ||
                player.isSwimming() || !player.isOnGround()) return;

        double maxStep = getConfig().getDouble("settings.max-walk-event-distance", 1.5);
        double distance = Math.sqrt(distanceSquared);
        // Ignore abnormal movement spikes (plugin teleports / server corrections).
        if (distance > maxStep) return;

        ensureFresh(id);
        data.get(id).walkedBlocks += distance;
    }

    private void tickPlayers() {
        long now = System.currentTimeMillis();
        long interval = Math.max(10, getConfig().getLong("settings.activity-check-seconds", 30));
        long afkTimeout = Math.max(30, getConfig().getLong("settings.afk-timeout-seconds", 120));
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            ensureFresh(id);
            long last = lastActivity.getOrDefault(id, now);
            if (now - last <= afkTimeout * 1000L) {
                data.get(id).activeSeconds += interval;
            }
        }
    }

    private void ensureFresh(UUID id) {
        String today = LocalDate.now(ZoneId.of(getConfig().getString("settings.reset-timezone", "Asia/Jakarta"))).toString();
        PlayerData pd = data.get(id);
        if (pd == null) {
            String path = "players." + id;
            String savedDate = store.getString(path + ".date", today);
            pd = new PlayerData(savedDate);
            pd.activeSeconds = store.getLong(path + ".active-seconds", 0);
            pd.walkedBlocks = store.getDouble(path + ".walked-blocks", 0);
            pd.claimed.addAll(store.getStringList(path + ".claimed"));
            data.put(id, pd);
        }
        if (!pd.date.equals(today)) {
            pd.date = today;
            pd.activeSeconds = 0;
            pd.walkedBlocks = 0;
            pd.claimed.clear();
        }
    }

    private void openMenu(Player player) {
        ensureFresh(player.getUniqueId());
        ConfigurationSection quests = getConfig().getConfigurationSection("quests");
        if (quests == null || quests.getKeys(false).isEmpty()) {
            player.sendMessage(color("<red>Belum ada quest di config.yml."));
            return;
        }
        int size = Math.min(54, Math.max(9, ((quests.getKeys(false).size() + 8) / 9) * 9));
        var inv = Bukkit.createInventory(null, size, color("<#3498DB><bold>696 DAILY QUESTS</bold>"));
        PlayerData pd = data.get(player.getUniqueId());
        int slot = 0;
        for (String id : quests.getKeys(false)) {
            ConfigurationSection q = quests.getConfigurationSection(id);
            if (q == null || slot >= size) continue;

            String type = q.getString("type", "PLAYTIME").toUpperCase(Locale.ROOT);
            double target = type.equals("WALK") ? q.getDouble("target-blocks", 0) : q.getDouble("target-minutes", 0);
            double current = type.equals("WALK") ? pd.walkedBlocks : pd.activeSeconds / 60.0;
            boolean complete = current >= target;
            boolean claimed = pd.claimed.contains(id);

            Material icon = claimed ? Material.LIME_STAINED_GLASS_PANE :
                    complete ? Material.ENCHANTED_BOOK : (type.equals("WALK") ? Material.LEATHER_BOOTS : Material.CLOCK);
            ItemStack item = new ItemStack(icon);
            var meta = item.getItemMeta();
            meta.displayName(color(q.getString("name", id)));
            List<Component> lore = new ArrayList<>();
            lore.add(color(q.getString("description", "")));
            String unit = type.equals("WALK") ? "blok" : "menit";
            String progress = type.equals("WALK")
                    ? String.format(Locale.US, "%.0f", Math.min(current, target))
                    : String.format(Locale.US, "%.0f", Math.min(current, target));
            lore.add(color("<gray>Progress: <white>" + progress + "<gray>/<white>" + String.format(Locale.US, "%.0f", target) + " " + unit));
            lore.add(color(claimed ? "<green>Sudah diklaim" : complete ? "<yellow>Klik untuk klaim reward" : "<red>Belum selesai"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().title().equals(color("<#3498DB><bold>696 DAILY QUESTS</bold>"))) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String id = clicked.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        if (id == null) return;
        claim(player, id);
    }

    private void claim(Player player, String id) {
        ensureFresh(player.getUniqueId());
        ConfigurationSection q = getConfig().getConfigurationSection("quests." + id);
        if (q == null) return;
        PlayerData pd = data.get(player.getUniqueId());
        if (pd.claimed.contains(id)) {
            send(player, msg("already-claimed"));
            return;
        }

        String type = q.getString("type", "PLAYTIME").toUpperCase(Locale.ROOT);
        double current = type.equals("WALK") ? pd.walkedBlocks : pd.activeSeconds / 60.0;
        double target = type.equals("WALK") ? q.getDouble("target-blocks", Double.MAX_VALUE) :
                q.getDouble("target-minutes", Double.MAX_VALUE);
        if (current < target) {
            send(player, msg("not-complete"));
            return;
        }

        List<ItemStack> rewards = new ArrayList<>();
        for (String entry : q.getStringList("reward")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null || !material.isItem()) {
                getLogger().warning("Material reward tidak valid di quest " + id + ": " + entry);
                continue;
            }
            int amount;
            try { amount = Integer.parseInt(parts[1].trim()); }
            catch (NumberFormatException ex) { continue; }
            if (amount <= 0) continue;
            while (amount > 0) {
                int stack = Math.min(amount, material.getMaxStackSize());
                rewards.add(new ItemStack(material, stack));
                amount -= stack;
            }
        }
        if (rewards.isEmpty()) return;
        if (countEmptySlots(player) < rewards.size()) {
            send(player, msg("inventory-full"));
            return;
        }

        pd.claimed.add(id);
        for (ItemStack reward : rewards) player.getInventory().addItem(reward);
        send(player, msg("claimed"));
        saveStore();
        openMenu(player);
    }

    private int countEmptySlots(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) count++;
        }
        return count;
    }

    private Component msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }
    private Component color(String input) { return mm.deserialize(input == null ? "" : input); }
    private void send(org.bukkit.command.CommandSender sender, Component message) { sender.sendMessage(message); }

    private void loadStore() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        store = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveStore() {
        if (store == null) return;
        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerData pd = entry.getValue();
            store.set(path + ".date", pd.date);
            store.set(path + ".active-seconds", pd.activeSeconds);
            store.set(path + ".walked-blocks", pd.walkedBlocks);
            store.set(path + ".claimed", new ArrayList<>(pd.claimed));
        }
        try { store.save(dataFile); }
        catch (IOException e) { getLogger().severe("Gagal menyimpan data.yml: " + e.getMessage()); }
    }
}
