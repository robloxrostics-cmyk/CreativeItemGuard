package ru.alphagrief.creativeitemguard;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class CreativeItemGuard extends JavaPlugin implements Listener {
    private final Set<Material> configuredItems = new HashSet<>();
    private String mode;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CreativeItemGuard enabled.");
    }

    private void loadSettings() {
        configuredItems.clear();
        mode = getConfig().getString("mode", "BLACKLIST").toUpperCase(Locale.ROOT);

        for (String name : getConfig().getStringList("items")) {
            Material material = Material.matchMaterial(name.trim());
            if (material == null) {
                getLogger().warning("Неизвестный предмет в config.yml: " + name);
            } else {
                configuredItems.add(material);
            }
        }

        if (!mode.equals("BLACKLIST") && !mode.equals("WHITELIST")) {
            getLogger().warning("Неверный mode. Использую BLACKLIST.");
            mode = "BLACKLIST";
        }
    }

    private boolean isRestricted(Player player, ItemStack stack) {
        if (player.hasPermission("creativeitemguard.bypass")) return false;
        if (stack == null || stack.getType().isAir()) return false;
        if (getConfig().getBoolean("creative-only", true)
                && player.getGameMode() != GameMode.CREATIVE) return false;

        boolean listed = configuredItems.contains(stack.getType());
        return mode.equals("BLACKLIST") ? listed : !listed;
    }

    private void notifyBlocked(Player player) {
        String message = getConfig().getString("messages.blocked",
                "&cЭтот предмет запрещён в креативе!");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    // Не даёт взять запрещённый предмет из креативного инвентаря.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryClick(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isRestricted(player, clicked) || isRestricted(player, cursor)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    // Не даёт подобрать запрещённый предмет с земли.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;

        ItemStack stack = event.getItem().getItemStack();
        if (isRestricted(player, stack)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    // Команда перезагрузки настроек.
    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cig")) return false;

        if (!sender.hasPermission("creativeitemguard.admin")) {
            sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.reloaded",
                            "&aКонфигурация перезагружена.")));
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Использование: /cig reload");
        return true;
    }
}
