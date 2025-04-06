package com.anarhoplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class CastleGuard extends JavaPlugin implements Listener {
    private BossBar bossBar;
    private boolean eventActive = false;
    private List<Player> lootEligible = new ArrayList<>();
    private Location eventLocation;
    private Map<Player, Double> damageDealt = new HashMap<>();
    private List<Entity> defenders = new ArrayList<>();
    private Entity boss = null;
    private int defendersKilled = 0;
    private Clipboard castleClipboard;
    private com.sk89q.worldedit.world.World worldEditWorld;

    @Override
    public void onEnable() {
        getLogger().info("AnarhoCastleGuard включен!");
        bossBar = Bukkit.createBossBar("Защитники замка | Ожидание", BarColor.YELLOW, BarStyle.SEGMENTED_20);
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        loadSchematic();

        // НОВОЕ: Запускаем таймер для автоматического ивента (каждый час)
        startEventTimer();
    }

    @Override
    public void onDisable() {
        getLogger().info("AnarhoCastleGuard выключен!");
        bossBar.removeAll();
        cleanupEvent();
    }

    // НОВОЕ: Метод для запуска таймера, который будет запускать ивент каждые 60 минут
    private void startEventTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive) {
                    // Оповещаем игроков за 5 минут до начала
                    Bukkit.broadcastMessage("§eИвент 'Нападение на защитников замка' начнётся через 5 минут!");
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!eventActive) {
                                Bukkit.broadcastMessage("§eИвент 'Нападение на защитников замка' начинается!");
                                startEvent(null); // Запускаем ивент автоматически
                            }
                        }
                    }.runTaskLater(CastleGuard.this, 5 * 60 * 20L); // 5 минут = 5 * 60 секунд * 20 тиков
                }
            }
        }.runTaskTimer(this, 0L, 3600 * 20L); // 3600 секунд = 1 час, 20 тиков в секунде
    }

    private void loadSchematic() {
        try {
            Path schematicPath = Paths.get(getDataFolder().toPath().toString(), "castle.schem");
            if (!Files.exists(schematicPath)) {
                saveResource("castle.schem", false);
            }
            InputStream inputStream = Files.newInputStream(schematicPath);
            ClipboardFormat format = ClipboardFormats.findByFile(schematicPath.toFile());
            if (format != null) {
                try (ClipboardReader reader = format.getReader(inputStream)) {
                    castleClipboard = reader.read();
                }
            }
        } catch (Exception e) {
            getLogger().severe("Не удалось загрузить схематику замка: " + e.getMessage());
        }
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

    // ИЗМЕНЕНИЕ: Делаем параметр player nullable, чтобы ивент мог запускаться автоматически
    private void startEvent(Player player) {
        eventActive = true;
        defendersKilled = 0;
        defenders.clear();
        damageDealt.clear();
        lootEligible.clear();
        boss = null;

        // НОВОЕ: Выбираем мир и координаты
        org.bukkit.World world;
        if (player != null) {
            world = player.getWorld();
        } else {
            // Если ивент запускается автоматически, берём первый доступный мир
            world = Bukkit.getWorlds().get(0);
        }

        // Генерируем случайные координаты
        Random random = new Random();
        int x = random.nextInt(1000) - 500; // От -500 до 500
        int z = random.nextInt(1000) - 500;
        int y = world.getHighestBlockYAt(x, z) + 1; // Находим высоту поверхности
        eventLocation = new Location(world, x, y, z);

        // Загружаем замок из схематики
        worldEditWorld = BukkitAdapter.adapt(world);
        try (com.sk89q.worldedit.EditSession editSession = com.sk89q.worldedit.WorldEdit.getInstance().newEditSession(worldEditWorld)) {
            Operation operation = new ClipboardHolder(castleClipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(x, y, z))
                    .build();
            Operations.complete(operation);
        } catch (Exception e) {
            getLogger().severe("Не удалось загрузить замок: " + e.getMessage());
            eventActive = false;
            return;
        }

        // Создаём защищённый регион
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(worldEditWorld);
        BlockVector3 pos1 = BlockVector3.at(x - 50, y - 50, z - 50);
        BlockVector3 pos2 = BlockVector3.at(x + 50, y + 50, z + 50);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(
                "castle_event_" + System.currentTimeMillis(),
                pos1,
                pos2
        );
        region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        region.setFlag(Flags.DAMAGE_ANIMALS, StateFlag.State.ALLOW);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        regions.addRegion(region);

        // Спавним защитников
        for (int i = 0; i < 20; i++) {
            double offsetX = random.nextDouble() * 20 - 10; // От -10 до 10
            double offsetZ = random.nextDouble() * 20 - 10;
            Location spawnLocation = eventLocation.clone().add(offsetX, 1, offsetZ);
            Zombie defender = (Zombie) world.spawnEntity(spawnLocation, EntityType.ZOMBIE);
            defender.setCustomName("Защитник замка");
            defender.setCustomNameVisible(true);
            defender.setMaxHealth(140.0);
            defender.setHealth(140.0);
            defenders.add(defender);
        }

        // Оповещаем игроков
        Bukkit.broadcastMessage("§fИвент: §6Нападение на защитников замка");
        Bukkit.broadcastMessage("§fЗамок появился на координатах: §e" + x + ", " + y + ", " + z);
        Bukkit.broadcastMessage("§fУбей 20 защитников, чтобы вызвать босса!");
        bossBar.setTitle("Защитники замка: " + defenders.size() + "/20");
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!eventActive) return;
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        if (player.getLocation().distance(eventLocation) > 50) return;

        double damage = event.getFinalDamage();
        damageDealt.put(player, damageDealt.getOrDefault(player, 0.0) + damage);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!eventActive) return;
        Entity entity = event.getEntity();

        // Проверяем, является ли убитый сущностью защитником
        if (defenders.contains(entity)) {
            defenders.remove(entity);
            defendersKilled++;
            bossBar.setTitle("Защитники замка: " + (20 - defendersKilled) + "/20");
            bossBar.setProgress((20.0 - defendersKilled) / 20.0);

            // Если все защитники убиты, спавним босса
            if (defendersKilled >= 20) {
                spawnBoss();
            }
        }

        // Проверяем, является ли убитый сущностью боссом
        if (entity.equals(boss)) {
            endEvent();
        }
    }

    private void spawnBoss() {
        Location bossLocation = eventLocation.clone().add(0, 2, 0);
        boss = eventLocation.getWorld().spawnEntity(bossLocation, EntityType.WITHER);
        boss.setCustomName("Король замка");
        boss.setCustomNameVisible(true);
        boss.setMaxHealth(2000.0);
        boss.setHealth(2000.0);

        Bukkit.broadcastMessage("§cКороль замка появился! Убей его, чтобы получить награду!");
        bossBar.setTitle("Король замка: " + (int) boss.getHealth() + "/2000");
        bossBar.setProgress(1.0);

        // Обновляем босс-бар в зависимости от здоровья босса
        new BukkitRunnable() {
            @Override
            public void run() {
                if (boss == null || boss.isDead()) {
                    cancel();
                    return;
                }
                bossBar.setTitle("Король замка: " + (int) boss.getHealth() + "/2000");
                bossBar.setProgress(boss.getHealth() / 2000.0);
            }
        }.runTaskTimer(this, 0L, 20L); // Обновляем каждую секунду
    }

    private void endEvent() {
        eventActive = false;
        Bukkit.broadcastMessage("§fКороль замка повержен!");
        Bukkit.broadcastMessage("§cНапиши /claimloot для получения награды!");
        bossBar.setTitle("Король замка ПАЛ! | /claimloot");
        bossBar.setProgress(0.0);

        // Определяем игроков, которые получат награду
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getLocation().distance(eventLocation) <= 50 && damageDealt.getOrDefault(p, 0.0) >= 50.0) {
                lootEligible.add(p);
            }
        }

        // Очищаем мобов и босса
        defenders.forEach(Entity::remove);
        if (boss != null) boss.remove();
        defenders.clear();
        boss = null;

        // Удаляем замок
        cleanupEvent();

        // Удаляем босс-бар через 15 секунд
        new BukkitRunnable() {
            @Override
            public void run() {
                bossBar.removeAll();
                lootEligible.clear();
            }
        }.runTaskLater(this, 15 * 20L);
    }

    private void cleanupEvent() {
        if (eventLocation == null || castleClipboard == null || worldEditWorld == null) return;

        try (com.sk89q.worldedit.EditSession editSession = com.sk89q.worldedit.WorldEdit.getInstance().newEditSession(worldEditWorld)) {
            editSession.undo(editSession); // Откатываем изменения (удаляем замок)
        } catch (Exception e) {
            getLogger().severe("Не удалось удалить замок: " + e.getMessage());
        }
    }

    private void giveLoot(Player player) {
        List<?> rewards = getConfig().getList("rewards");
        if (rewards == null || rewards.isEmpty()) {
            player.sendMessage("§cНаграды не настроены!");
            return;
        }

        double playerDamage = damageDealt.getOrDefault(player, 0.0);
        int bonusAmount = (int) (playerDamage / 100); // +1 предмет за каждые 100 урона

        double totalChance = 0.0;
        for (Object rewardObj : rewards) {
            Map<?, ?> reward = (Map<?, ?>) rewardObj;
            totalChance += ((Number) reward.get("chance")).doubleValue();
        }

        double random = Math.random() * totalChance;
        double current = 0.0;
        for (Object rewardObj : rewards) {
            Map<?, ?> reward = (Map<?, ?>) rewardObj;
            current += ((Number) reward.get("chance")).doubleValue();
            if (random <= current) {
                Material material = Material.valueOf((String) reward.get("material"));
                int amount = ((Number) reward.get("amount")).intValue() + bonusAmount;
                String rarity = (String) reward.get("rarity");
                player.getInventory().addItem(new ItemStack(material, amount));
                player.sendMessage("§aТы получил " + amount + " " + material.name() + " (" + rarity + ") за участие!");
                return;
            }
        }
    }
}
