package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.example.beaverplugin.listeners.BeaverLootListener;
import ru.example.beaverplugin.listeners.DamGenerator;

public final class BeaverPlugin extends JavaPlugin {

    private BeaverManager beaverManager;
    private DamGenerator damGenerator;

    // ==== Настройки (потом можно вынести в config.yml) ====
    public static final int TREE_SEARCH_RADIUS = 12;      // радиус поиска дерева бобром
    public static final int MIN_COOLDOWN_TICKS = 20 * 60 * 10; // 10 минут
    public static final int MAX_COOLDOWN_TICKS = 20 * 60 * 20; // 20 минут
    public static final double DAM_CHANCE_PER_CHUNK = 0.02;    // 2% шанс плотины на новый чанк-реку

    @Override
    public void onEnable() {
        this.beaverManager = new BeaverManager(this);
        this.damGenerator = new DamGenerator(this, beaverManager);

        getServer().getPluginManager().registerEvents(damGenerator, this);
        getServer().getPluginManager().registerEvents(new BeaverLootListener(beaverManager), this);

        getLogger().info("BeaverPlugin включен. Бобры вышли на охоту за деревьями.");
    }

    @Override
    public void onDisable() {
        getLogger().info("BeaverPlugin выключен.");
    }

    public BeaverManager getBeaverManager() {
        return beaverManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна только игроку.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("Использование: /beaver spawn | /beaver dam");
            return true;
        }

        Location loc = player.getLocation();
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                beaverManager.spawnBeaver(loc);
                player.sendMessage("§aБобёр заспавнен рядом с вами.");
            }
            case "dam" -> {
                boolean built = damGenerator.buildDamAt(loc.getBlock());
                player.sendMessage(built ? "§aПлотина построена." : "§cЗдесь не получилось построить плотину (нужна вода рядом).");
            }
            default -> player.sendMessage("Неизвестная подкоманда. Используйте spawn | dam");
        }
        return true;
    }
}
