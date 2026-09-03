package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public final class TreeUtils {

    private TreeUtils() {}

    private static final int MAX_LOGS_PER_TREE = 14;

    public static boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") && !name.startsWith("STRIPPED");
    }

    public static boolean isLeaves(Material material) {
        return material.name().endsWith("_LEAVES");
    }

    /**
     * Ищет ближайший "ствол" дерева (нижний блок бревна, стоящий на земле)
     * в кубе radius x radius вокруг центра.
     */
    public static Location findNearestTree(Location center, int radius) {
        Block bestBlock = null;
        double bestDistSq = Double.MAX_VALUE;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    Block block = center.getWorld().getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!isLog(block.getType())) continue;

                    Block below = block.getRelative(0, -1, 0);
                    boolean standsOnGround = below.getType().isSolid() && !isLog(below.getType());
                    if (!standsOnGround) continue;

                    double distSq = block.getLocation().distanceSquared(center);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestBlock = block;
                    }
                }
            }
        }

        return bestBlock == null ? null : bestBlock.getLocation();
    }

    /**
     * Валит дерево, начиная с базового блока бревна (flood-fill по соседним логам).
     * Один блок бревна не выпадает предметом - его "съедает" бобёр.
     * Возвращает количество фактически срубленных блоков бревна.
     */
    public static int chopTree(Block baseLog) {
        if (!isLog(baseLog.getType())) return 0;

        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        List<Block> logBlocks = new ArrayList<>();

        queue.add(baseLog);
        visited.add(baseLog);

        int[][] offsets = {
                {0, 1, 0}, {0, -1, 0},
                {1, 0, 0}, {-1, 0, 0},
                {0, 0, 1}, {0, 0, -1},
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}
        };

        while (!queue.isEmpty() && logBlocks.size() < MAX_LOGS_PER_TREE) {
            Block current = queue.poll();
            if (isLog(current.getType())) {
                logBlocks.add(current);
            }
            for (int[] off : offsets) {
                Block next = current.getRelative(off[0], off[1], off[2]);
                if (!visited.contains(next) && isLog(next.getType())) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        // Ломаем все блоки: последнее бревно не выпадает (его "съедает" бобёр)
        for (int i = 0; i < logBlocks.size(); i++) {
            Block log = logBlocks.get(i);
            boolean lastOne = (i == logBlocks.size() - 1);
            if (lastOne) {
                log.setType(Material.AIR); // без дропа - это бревно "съедает" бобёр
            } else {
                log.breakNaturally();
            }
        }

        return logBlocks.size();
    }
}
