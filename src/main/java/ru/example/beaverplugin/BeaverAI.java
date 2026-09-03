package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Rabbit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Раз в секунду (20 тиков) проверяет состояние одного бобра и продвигает
 * его по конечному автомату: IDLE -> MOVING -> CHOPPING -> EATING -> IDLE(cooldown)
 */
public class BeaverAI extends BukkitRunnable {

    private enum State { IDLE, MOVING, CHOPPING }

    private final BeaverPlugin plugin;
    private final BeaverManager manager;
    private final Rabbit beaver;

    private State state = State.IDLE;
    private long nextActionAtTick = 0;   // тик сервера, когда можно снова искать дерево
    private long chopStartedAtTick = 0;
    private Location targetLogLocation;
    private int stuckTicks = 0;

    private static final int CHOP_DURATION_TICKS = 60; // 3 секунды на "рубку"

    public BeaverAI(BeaverPlugin plugin, BeaverManager manager, Rabbit beaver) {
        this.plugin = plugin;
        this.manager = manager;
        this.beaver = beaver;
        this.nextActionAtTick = currentTick() + randomCooldown();
    }

    private long currentTick() {
        return beaver.getWorld().getFullTime();
    }

    private long randomCooldown() {
        return ThreadLocalRandom.current().nextLong(
                BeaverPlugin.MIN_COOLDOWN_TICKS,
                BeaverPlugin.MAX_COOLDOWN_TICKS
        );
    }

    @Override
    public void run() {
        if (!beaver.isValid() || beaver.isDead()) {
            cancel();
            return;
        }

        long now = currentTick();

        switch (state) {
            case IDLE -> {
                if (now >= nextActionAtTick) {
                    Location tree = TreeUtils.findNearestTree(beaver.getLocation(), BeaverPlugin.TREE_SEARCH_RADIUS);
                    if (tree != null) {
                        targetLogLocation = tree;
                        beaver.getPathfinder().moveTo(tree, 1.0);
                        state = State.MOVING;
                        stuckTicks = 0;
                    } else {
                        // дерева рядом нет - подождём немного и попробуем снова
                        nextActionAtTick = now + 20 * 30;
                    }
                }
            }

            case MOVING -> {
                if (targetLogLocation == null) {
                    state = State.IDLE;
                    return;
                }
                double distSq = beaver.getLocation().distanceSquared(targetLogLocation);
                if (distSq <= 2.5 * 2.5) {
                    state = State.CHOPPING;
                    chopStartedAtTick = now;
                    beaver.getWorld().playSound(beaver.getLocation(), Sound.ENTITY_FOX_BITE, 1f, 0.6f);
                } else if (beaver.getPathfinder().getCurrentPath() == null) {
                    // путь потерян/не найден
                    stuckTicks++;
                    if (stuckTicks > 5) {
                        state = State.IDLE;
                        nextActionAtTick = now + 20 * 15;
                    } else {
                        beaver.getPathfinder().moveTo(targetLogLocation, 1.0);
                    }
                }
            }

            case CHOPPING -> {
                // визуальный эффект "рубки" во время ожидания
                beaver.getWorld().spawnParticle(Particle.BLOCK, beaver.getLocation().add(0, 0.5, 0),
                        6, 0.2, 0.2, 0.2, targetLogLocation != null
                                ? targetLogLocation.getBlock().getBlockData()
                                : org.bukkit.Material.OAK_LOG.createBlockData());

                if (now - chopStartedAtTick >= CHOP_DURATION_TICKS) {
                    finishChopping();
                }
            }
        }
    }

    private void finishChopping() {
        if (targetLogLocation != null) {
            Block base = targetLogLocation.getBlock();
            int felled = TreeUtils.chopTree(base);

            if (felled > 0) {
                // "поедание" последнего бревна - эффект и звук
                beaver.getWorld().playSound(beaver.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
                beaver.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, beaver.getLocation().add(0, 0.6, 0), 8);
            }
        }

        targetLogLocation = null;
        state = State.IDLE;
        nextActionAtTick = currentTick() + randomCooldown();
    }
}
