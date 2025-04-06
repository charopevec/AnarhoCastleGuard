package com.anarhoplay;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

public class CastleGuard extends JavaPlugin {
    private BossBar bossBar;
    private boolean eventActive = false;
    private List<Player> lootEligible = new ArrayList<>();
    private Location eventLocation;

    @Override
    public void onEnable() {
        getLogger().info("AnarhoCastleGuard включен!");
        bossBar = Bukkit.createBossBar("Защитники замка | Ожидание", BarColor.YELLOW, BarStyle.SEGMENTED_20);
    }

    @Override
    public void onDisable() {
        getLogger().info("AnarhoCastleGuard выключен!");
        bossBar.removeAll();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только игроки могут использовать команды!");
            return true;
        }
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("castle")) {
            if (args.length == 0 || !args[0].equalsIgnoreCase("start")) {
                player.sendMessage("Используй: /castle start");
                return true;
            }
            if (!player.hasPermission("castleguard.use")) {
                player.sendMessage("У тебя нет прав!");
                return true;
            }
            if (eventActive) {
                player.sendMessage("Ивент уже идёт!");
                return true;
            }
            startEvent(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("claimloot")) {
            if (!lootEligible.contains(player)) {
                player.sendMessage("Ты не участвовал в ивенте или награда недоступна!");
                return true;
            }
            giveLoot(player);
            lootEligible.remove(player);
            return true;
        }
        return false;
    }

    private void startEvent(Player player) {
        eventActive = true;
        eventLocation = player.getLocation();
        World world = eventLocation.getWorld();

        // Создание региона WorldGuard
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(world);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(
            "castle_event_" + System.currentTimeMillis(),
            eventLocation.clone().add(-50, -50, -50).toVector().toBlockVector(),
            eventLocation.clone().add(50, 50, 50).toVector().toBlockVector()
        );
        region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        region.setFlag(Flags.DAMAGE_ANIMALS, StateFlag.State.ALLOW);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        regions.addRegion(region);

        // Сообщения и босс-бар
        Bukkit.broadcastMessage("§fИвент: §6Нападение на защитников замка");
        Bukkit.broadcastMessage("§fУчаствуй, чтобы получить лут!");
        bossBar.setTitle("Защитники замка | Нападение началось");
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }

        // Завершение через 30 секунд (для теста)
        new BukkitRunnable() {
            @Override
            public void run() {
                endEvent();
            }
        }.runTaskLater(this, 30 * 20L); // 30 секунд
    }

    private void endEvent() {
        eventActive = false;
        Bukkit.broadcastMessage("§fЗащитники пали!");
        Bukkit.broadcastMessage("§cНапиши /claimloot для получения награды!");
        bossBar.setTitle("Защитники замка ПАЛИ! | /claimloot");
        bossBar.setProgress(0.0);

        // Добавляем всех игроков в радиусе 50 блоков в список наград
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getLocation().distance(eventLocation) <= 50) {
                lootEligible.add(p);
            }
        }

        // Удаление босс-бара через 15 секунд
        new BukkitRunnable() {
            @Override
            public void run() {
                bossBar.removeAll();
                lootEligible.clear();
            }
        }.runTaskLater(this, 15 * 20L);
    }

    private void giveLoot(Player player) {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        player.sendMessage("§aТы получил награду за участие!");
    }
}
