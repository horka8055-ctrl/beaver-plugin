package ru.example.beaverplugin.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.StructureType;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import ru.example.beaverplugin.BeaverManager;
import ru.example.beaverplugin.BeaverPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Слушает подгрузку новых (впервые сгенерированных) чанков.
 * Если биом - река, с небольшим шансом вставляет НАСТОЯЩУЮ структуру
 * (Bukkit Structure API, тот же формат, что и структурные блоки/датапаки)
 * с плотиной, и спавнит рядом бобра.
 *
 * Структура грузится ОДИН раз при старте плагина двумя способами:
 *  1) Из собственных ресурсов плагина (src/main/resources/structures/dam.nbt) -
 *     работает "из коробки", без датапака.
 *  2) Если рядом установлен датапак BeaverDamDatapack (data/beaverdam/structures/dam.nbt),
 *     её тоже можно достать через StructureManager#loadStructure(NamespacedKey) -
 *     используется как приоритетный вариант, если найдётся.
 *
 * Если по каким-то причинам структуру загрузить не удалось (повреждён jar,
 * старая версия сервера и т.п.) - используется простой запасной вариант
 * (настил из брёвен), чтобы плагин не переставал работать вообще.
 */
public class DamGenerator implements Listener {

    private static final NamespacedKey DAM_STRUCTURE_KEY = new NamespacedKey("beaverdam", "dam");

    private final BeaverPlugin plugin;
    private final BeaverManager beaverManager;
    private Structure damStructure; // может быть null, если не удалось загрузить

    public DamGenerator(BeaverPlugin plugin, BeaverManager beaverManager) {
        this.plugin = plugin;
        this.beaverManager = beaverManager;
        this.damStructure = loadDamStructure();
    }

    private Structure loadDamStructure() {
        StructureManager structureManager = Bukkit.getStructureManager();

        // Приоритет 1: структура из установленного датапака (data/beaverdam/structures/dam.nbt)
        try {
            Structure fromDatapack = structureManager.getStructure(DAM_STRUCTURE_KEY);
            if (fromDatapack != null) {
                plugin.getLogger().info("Структура плотины загружена из датапака beaverdam.");
                return fromDatapack;
            }
        } catch (IllegalArgumentException ignored) {
            // датапак не установлен - это нормально, пойдём во вариант 2
        }

        // Приоритет 2: структура, зашитая внутрь самого плагина (не требует датапака)
        try (InputStream in = plugin.getResource("structures/dam.nbt")) {
            if (in != null) {
                Structure bundled = structureManager.loadStructure(in);
                plugin.getLogger().info("Структура плотины загружена из ресурсов плагина.");
                return bundled;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось загрузить встроенную структуру плотины: " + e.getMessage());
        }

        plugin.getLogger().warning("Структура плотины не найдена - будет использован упрощённый запасной вариант.");
        return null;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return; // строим плотины только там, где мир генерируется впервые

        Chunk chunk = event.getChunk();
        int centerX = chunk.getX() * 16 + 8;
        int centerZ = chunk.getZ() * 16 + 8;

        Block surfaceBlock = chunk.getWorld().getHighestBlockAt(centerX, centerZ);
        if (surfaceBlock.getBiome() != Biome.RIVER) return;

        if (ThreadLocalRandom.current().nextDouble() > BeaverPlugin.DAM_CHANCE_PER_CHUNK) return;

        tryBuildDamAt(surfaceBlock, beaverManager, this);
    }

    /**
     * Пытается построить плотину (использует настоящую .nbt структуру, если она
     * загружена для этого экземпляра плагина). Для использования из команд.
     */
    public boolean buildDamAt(Block anchor) {
        return tryBuildDamAt(anchor, beaverManager, this);
    }

    /**
     * Статический запасной вариант без доступа к загруженной структуре -
     * всегда использует упрощённый настил из брёвен.
     */
    public static boolean tryBuildDamAt(Block anchor, BeaverManager beaverManager) {
        return tryBuildDamAt(anchor, beaverManager, null);
    }

    private static boolean tryBuildDamAt(Block anchor, BeaverManager beaverManager, DamGenerator instanceOrNull) {
        Block waterBlock = findNearbyWater(anchor);
        if (waterBlock == null) return false;

        boolean placedRealStructure = false;

        if (instanceOrNull != null && instanceOrNull.damStructure != null) {
            // Ставим настоящую NBT-структуру. Разворот выбираем случайно для разнообразия.
            StructureRotation rotation = StructureRotation.values()[
                    ThreadLocalRandom.current().nextInt(StructureRotation.values().length)];

            Location origin = waterBlock.getLocation().clone().add(-3, 0, -1); // см. геометрию в dam.nbt
            instanceOrNull.damStructure.place(
                    origin,
                    false,                 // includeEntities
                    rotation,
                    org.bukkit.block.structure.Mirror.NONE,
                    0,                      // без "дырявости" (palette integrity 100%)
                    1.0f,
                    new Random()
            );
            placedRealStructure = true;
        }

        if (!placedRealStructure) {
            buildFallbackDam(waterBlock);
        }

        // Спавним бобра рядом с плотиной
        Location spawnLoc = waterBlock.getLocation().add(0, 1, 0);
        beaverManager.spawnBeaver(spawnLoc);

        return true;
    }

    /** Простой запасной вариант, если .nbt структуру загрузить не удалось. */
    private static void buildFallbackDam(Block waterBlock) {
        int baseY = waterBlock.getY();
        int bx = waterBlock.getX();
        int bz = waterBlock.getZ();

        for (int i = -3; i <= 3; i++) {
            Block deckBlock = waterBlock.getWorld().getBlockAt(bx + i, baseY, bz);
            Material below = deckBlock.getRelative(0, -1, 0).getType();

            if (deckBlock.getType() == Material.WATER || below == Material.WATER) {
                deckBlock.setType(Material.OAK_LOG);
            } else {
                deckBlock.setType(Material.DIRT);
            }

            if (i == -3 || i == 3) {
                deckBlock.getRelative(0, -1, 0).setType(Material.OAK_LOG);
            }
        }
    }

    private static Block findNearbyWater(Block anchor) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block b = anchor.getRelative(dx, dy, dz);
                    if (b.getType() == Material.WATER) {
                        return b;
                    }
                }
            }
        }
        return null;
    }
}
