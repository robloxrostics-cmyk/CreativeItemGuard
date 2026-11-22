package ru.alphagrief.creativeitemguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;


import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class CreativeItemGuard extends JavaPlugin implements Listener {
    private final Set<Material> configuredItems = new HashSet<>();
    private final Set<UUID> disabledPlayers = new HashSet<>();
    private final Map<UUID, Long> lastViolation = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> warningCounts = new HashMap<>();
    private final Set<UUID> privilegeRemoved = new HashSet<>();
    private File dataFile;
    private YamlConfiguration data;
    private String mode;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        if (getServer().getPluginManager().isPluginEnabled("WorldEdit")
                && getConfig().getBoolean("worldedit.enabled", true)) {
            com.sk89q.worldedit.WorldEdit.getInstance().getEventBus().register(new WorldEditListener());
            getLogger().info("WorldEdit protection enabled.");
        } else {
            getLogger().info("WorldEdit not found or protection disabled; WorldEdit protection is inactive.");
        }

        getLogger().info("CreativeItemGuard enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String id : data.getStringList("disabled-players")) {
            try { disabledPlayers.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        }
        for (String id : data.getConfigurationSection("warnings") == null
                ? Collections.<String>emptySet() : data.getConfigurationSection("warnings").getKeys(false)) {
            try { warningCounts.put(UUID.fromString(id), data.getInt("warnings." + id)); }
            catch (IllegalArgumentException ignored) {}
        }
        for (String id : data.getStringList("privilege-removed")) {
            try { privilegeRemoved.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveData() {
        if (data == null) return;
        data.set("disabled-players", disabledPlayers.stream().map(UUID::toString).toList());
        data.set("warnings", null);
        for (Map.Entry<UUID, Integer> entry : warningCounts.entrySet())
            data.set("warnings." + entry.getKey(), entry.getValue());
        data.set("privilege-removed", privilegeRemoved.stream().map(UUID::toString).toList());
        try { data.save(dataFile); }
        catch (IOException ex) { getLogger().severe("Не удалось сохранить data.yml: " + ex.getMessage()); }
    }

    private void loadSettings() {
        configuredItems.clear();
        mode = getConfig().getString("mode", "BLACKLIST").toUpperCase(Locale.ROOT);
        for (String name : getConfig().getStringList("items")) {
            Material material = Material.matchMaterial(name.trim());
            if (material == null) getLogger().warning("Неизвестный материал в config.yml: " + name);
            else configuredItems.add(material);
        }
        if (!mode.equals("BLACKLIST") && !mode.equals("WHITELIST")) mode = "BLACKLIST";
    }

    private boolean isCreativeScope(Player player) {
        return !getConfig().getBoolean("creative-only", true) || player.getGameMode() == GameMode.CREATIVE;
    }

    private boolean isDisabled(Player player) {
        return disabledPlayers.contains(player.getUniqueId());
    }

    private boolean isRestricted(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        if (player.hasPermission("creativeitemguard.bypass")) return false;
        if (!isCreativeScope(player)) return false;
        boolean listed = configuredItems.contains(stack.getType());
        return mode.equals("BLACKLIST") ? listed : !listed;
    }

    private boolean isProtectedContainer(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST || type == Material.BARREL;
    }

    private boolean isShulker(Material type) {
        return type.name().endsWith("SHULKER_BOX");
    }

    private void msg(CommandSender sender, String path, String fallback) {
        sender.sendMessage(color(getConfig().getString(path, fallback)));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private void violation(Player player, String reason) {
        if (player.hasPermission("creativeitemguard.bypass")) return;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, getConfig().getLong("punishments.violation-cooldown-seconds", 3)) * 1000L;
        Long previous = lastViolation.put(player.getUniqueId(), now);
        if (previous != null && now - previous < cooldown) return;

        getLogger().info("[Violation] " + player.getName() + ": " + reason);
        if (getConfig().getBoolean("punishments.notify-admins", true)) {
            String notice = color(getConfig().getString("messages.admin-violation",
                    "&8[&6CIG&8] &e%player% &7: &f%reason%")
                    .replace("%player%", player.getName()).replace("%reason%", reason));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("creativeitemguard.notify")) online.sendMessage(notice);
            }
        }

        int count = warningCounts.getOrDefault(player.getUniqueId(), 0) + 1;
        warningCounts.put(player.getUniqueId(), count);
        saveData();
        player.sendMessage(color(getConfig().getString("messages.warning",
                "&cПредупреждение за нарушение ограничений! &7(%count%/%limit%)")
                .replace("%count%", String.valueOf(count))
                .replace("%limit%", String.valueOf(getConfig().getInt("punishments.warning-limit", 10)))));

        if (getConfig().getBoolean("litebans.enabled", false)) {
            String command = getConfig().getString("litebans.warn-command",
                    "warn %player% Нарушение CreativeItemGuard: %reason%");
            command = command.replace("%player%", player.getName()).replace("%reason%", reason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }

        int limit = Math.max(1, getConfig().getInt("punishments.warning-limit", 10));
        if (count >= limit && !privilegeRemoved.contains(player.getUniqueId())) {
            if (getConfig().getBoolean("luckperms.enabled", false)) {
                removeHighestDonorGroup(player);
            } else {
                getLogger().warning("Достигнут лимит варнов у " + player.getName()
                        + ", но LuckPerms-интеграция выключена.");
            }
        }
    }

    private void removeHighestDonorGroup(Player player) {
        net.luckperms.api.LuckPerms luckPerms = getServer().getServicesManager()
                .load(net.luckperms.api.LuckPerms.class);
        if (luckPerms == null) {
            getLogger().warning("LuckPerms API не найден. Не удалось определить группу " + player.getName());
            return;
        }
        Set<String> excluded = new HashSet<>();
        for (String name : getConfig().getStringList("luckperms.excluded-groups"))
            excluded.add(name.toLowerCase(Locale.ROOT));

        UUID uuid = player.getUniqueId();
        luckPerms.getUserManager().loadUser(uuid).thenAccept(user -> {
            String selected = null;
            int highestWeight = Integer.MIN_VALUE;
            for (net.luckperms.api.node.Node node : user.getNodes()) {
                if (!(node instanceof net.luckperms.api.node.types.InheritanceNode inheritance)) continue;
                String groupName = inheritance.getGroupName();
                if (excluded.contains(groupName.toLowerCase(Locale.ROOT))) continue;
                net.luckperms.api.model.group.Group group = luckPerms.getGroupManager().getGroup(groupName);
                if (group == null) continue;
                int weight = group.getWeight().orElse(0);
                if (weight > highestWeight) {
                    highestWeight = weight;
                    selected = groupName;
                }
            }
            String groupToRemove = selected;
            Bukkit.getScheduler().runTask(this, () -> {
                if (groupToRemove == null) {
                    getLogger().warning("У " + player.getName() + " не найдена донатная группа для снятия.");
                    return;
                }
                String cmd = "lp user " + player.getName() + " parent remove " + groupToRemove;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                privilegeRemoved.add(uuid);
                saveData();
                player.sendMessage(color(getConfig().getString("messages.privilege-removed",
                        "&cЗа достижение лимита предупреждений ваша высшая донатная привилегия снята.")));
                getLogger().info("Снята группа " + groupToRemove + " у " + player.getName()
                        + " (10+ предупреждений CreativeItemGuard).");
            });
        }).exceptionally(error -> {
            getLogger().warning("Не удалось загрузить данные LuckPerms для " + player.getName()
                    + ": " + error.getMessage());
            return null;
        });
    }

    private boolean isRestrictedBlock(Player player, Material material) {
        if (material.isAir()) return false;
        if (player.hasPermission("creativeitemguard.bypass")) return false;
        if (!isCreativeScope(player)) return false;
        if (getConfig().getBoolean("containers.block-shulkers", true)
                && isShulker(material)) return true;

        boolean listed = configuredItems.contains(material);
        return mode.equals("BLACKLIST") ? listed : !listed;
    }

    private Material worldEditMaterial(com.sk89q.worldedit.world.block.BlockType blockType) {
        if (blockType == null) return Material.AIR;
        String id = blockType.getId();
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        Material material = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
        return material == null ? Material.AIR : material;
    }

    /**
     * Separate class keeps WorldEdit optional: this class is only loaded when WorldEdit is installed.
     */
    private final class WorldEditListener {
        @com.sk89q.worldedit.util.eventbus.Subscribe
        public void onWorldEditSession(com.sk89q.worldedit.event.extent.EditSessionEvent event) {
            if (!getConfig().getBoolean("worldedit.enabled", true)) return;
            if (event.getStage() != com.sk89q.worldedit.EditSession.Stage.BEFORE_CHANGE
                    && event.getStage() != com.sk89q.worldedit.EditSession.Stage.BEFORE_HISTORY) return;

            if (!(event.getActor() instanceof com.sk89q.worldedit.entity.Player worldEditPlayer)) return;
            Player player = Bukkit.getPlayer(worldEditPlayer.getUniqueId());
            if (player == null || player.hasPermission("creativeitemguard.bypass")) return;
            if (!isCreativeScope(player)) return;

            event.setExtent(new com.sk89q.worldedit.extent.AbstractDelegateExtent(event.getExtent()) {
                @Override
                public boolean setBlock(com.sk89q.worldedit.math.BlockVector3 position,
                                        com.sk89q.worldedit.world.block.BlockStateHolder<?> block)
                        throws com.sk89q.worldedit.WorldEditException {
                    Material material = worldEditMaterial(block.getBlockType());

                    if (isDisabled(player)) {
                        violation(player, "Попытка использовать WorldEdit при отключённом креативе");
                        return false;
                    }

                    if (isRestrictedBlock(player, material)) {
                        violation(player, "Попытка установить запрещённый блок через WorldEdit: "
                                + material.name());
                        return false;
                    }

                    return super.setBlock(position, block);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        if (isDisabled(player)) {
            event.setCancelled(true);
            violation(player, "Попытка использовать творческий инвентарь при отключённом креативе");
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (isRestricted(player, current) || isRestricted(player, cursor)
                || (current != null && isShulker(current.getType()))
                || (cursor != null && isShulker(cursor.getType()))) {
            event.setCancelled(true);
            violation(player, "Попытка получить запрещённый предмет");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        if (isDisabled(player)) {
            event.setCancelled(true);
            violation(player, "Попытка перемещения предметов при отключённом креативе");
            return;
        }
        if (isRestricted(player, event.getCurrentItem()) || isRestricted(player, event.getCursor())
                || (event.getCurrentItem() != null && isShulker(event.getCurrentItem().getType()))
                || (event.getCursor() != null && isShulker(event.getCursor().getType()))) {
            event.setCancelled(true);
            violation(player, "Попытка переместить запрещённый предмет");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        if (isDisabled(player) || isRestricted(player, event.getItem().getItemStack())
                || isShulker(event.getItem().getItemStack().getType())) {
            event.setCancelled(true);
            violation(player, "Попытка подобрать запрещённый предмет");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        if (isDisabled(player) || isRestricted(player, event.getItemDrop().getItemStack())
                || isShulker(event.getItemDrop().getItemStack().getType())) {
            event.setCancelled(true);
            violation(player, "Попытка выбросить запрещённый предмет");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        Material type = event.getClickedBlock().getType();
        boolean blocked = isProtectedContainer(type) || type == Material.CRAFTING_TABLE;
        if (isDisabled(player) || blocked) {
            event.setCancelled(true);
            if (blocked) violation(player, type == Material.CRAFTING_TABLE
                    ? "Попытка открыть верстак" : "Попытка открыть контейнер");
            else violation(player, "Взаимодействие при отключённом креативе");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        Block block = event.getBlock();
        if (isDisabled(player) || isProtectedContainer(block.getType())) {
            event.setCancelled(true);
            if (!isDisabled(player)) violation(player, "Попытка сломать контейнер");
            else violation(player, "Попытка ломать блок при отключённом креативе");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isCreativeScope(player) || player.hasPermission("creativeitemguard.bypass")) return;
        if (isDisabled(player)) {
            event.setCancelled(true);
            violation(player, "Попытка поставить блок при отключённом креативе");
            return;
        }
        if (isShulker(event.getItemInHand().getType())) {
            event.setCancelled(true);
            violation(player, "Попытка поставить шалкер");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cig")) return false;
        if (!sender.hasPermission("creativeitemguard.admin")) {
            sender.sendMessage(color("&cНедостаточно прав."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            msg(sender, "messages.reloaded", "&aКонфигурация перезагружена.");
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("disable")
                || args[0].equalsIgnoreCase("enable") || args[0].equalsIgnoreCase("status"))) {
            String name = args[1];
            Player online = Bukkit.getPlayerExact(name);
            if (online == null) {
                sender.sendMessage(color("&cИгрок должен быть онлайн: " + name));
                return true;
            }
            UUID id = online.getUniqueId();
            if (args[0].equalsIgnoreCase("disable")) {
                disabledPlayers.add(id);
                saveData();
                sender.sendMessage(color("&aОграничения креатива включены для &e" + name
                        + "&a. Полёт в Creative остаётся доступен."));
                online.sendMessage(color(getConfig().getString("messages.creative-disabled",
                        "&cВаши возможности креатива ограничены администрацией. Полёт доступен.")));
                return true;
            }
            if (args[0].equalsIgnoreCase("enable")) {
                disabledPlayers.remove(id);
                saveData();
                sender.sendMessage(color("&aОбычный креатив возвращён игроку &e" + name + "&a."));
                online.sendMessage(color(getConfig().getString("messages.creative-enabled",
                        "&aОграничения креатива сняты.")));
                return true;
            }
            sender.sendMessage(color("&e" + name + ": "
                    + (isDisabled(online) ? "&cограниченный креатив" : "&aобычный креатив")
                    + "&7, предупреждений: &f" + warningCounts.getOrDefault(id, 0)));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("disabled")) {
            if (disabledPlayers.isEmpty()) sender.sendMessage(color("&7Нет игроков с ограниченным креативом."));
            else sender.sendMessage(color("&eИгроков с ограниченным креативом: &f" + disabledPlayers.size()));
            return true;
        }
        sender.sendMessage(color("&eКоманды: /cig reload, /cig disable <ник>, /cig enable <ник>, /cig status <ник>, /cig disabled"));
        return true;
    }
}
