package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Каждый тик:
 *  1) синхронизирует позицию видимых частей (тело/голова) с невидимым
 *     "мозгом" и считает процедурную анимацию (покачивание при ходьбе,
 *     наклон при плавании, кивок челюстью при "рубке");
 *  2) раз в 20 тиков (1 секунду) продвигает конечный автомат поведения:
 *     IDLE -> MOVING -> CHOPPING -> (снова IDLE с кулдауном).
 */
public class BeaverAI extends BukkitRunnable {

    public enum State { IDLE, MOVING, CHOPPING }

    private final BeaverPlugin plugin;
    private final BeaverManager manager;
    private final Pig beaver;

    private State state = State.IDLE;
    private long nextActionAtTick = 0;
    private long chopStartedAtTick = 0;
    private Location targetLogLocation;
    private int stuckTicks = 0;

    // --- анимация ---
    private int animTick = 0;
    private double lastX;
    private double lastZ;
    private boolean walking = false;

    private static final int CHOP_DURATION_TICKS = 60; // 3 секунды на "рубку"
    private static final double MOVE_EPSILON = 0.003;   // порог горизонтального смещения за тик, чтобы считать "идёт"

    public BeaverAI(BeaverPlugin plugin, BeaverManager manager, Pig beaver) {
        this.plugin = plugin;
        this.manager = manager;
        this.beaver = beaver;
        this.nextActionAtTick = currentTick() + randomCooldown();
        Location loc = beaver.getLocation();
        this.lastX = loc.getX();
        this.lastZ = loc.getZ();
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
            manager.removeVisual(beaver.getUniqueId());
            cancel();
            return;
        }

        updateVisual();

        animTick++;
        if (animTick % 20 == 0) {
            updateStateMachine();
        }
    }

    // ==================== ВИЗУАЛ / АНИМАЦИЯ ====================

    private void updateVisual() {
        BeaverManager.BeaverVisual visual = manager.getVisual(beaver.getUniqueId());
        if (visual == null) return;

        Location loc = beaver.getLocation();

        double dx = loc.getX() - lastX;
        double dz = loc.getZ() - lastZ;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        walking = horizDist > MOVE_EPSILON;
        lastX = loc.getX();
        lastZ = loc.getZ();

        boolean swimming = beaver.isInWater();

        double bobY;
        AxisAngle4f bodyRotation;
        AxisAngle4f headRotation;

        if (swimming) {
            // Плавание: тело слегка наклонено вперёд (плоское положение на воде)
            // + быстрое лёгкое покачивание, как гребки.
            double swimPhase = animTick * 0.35;
            bobY = Math.sin(swimPhase) * 0.05;
            bodyRotation = new AxisAngle4f((float) Math.toRadians(35), 1f, 0f, 0f);
            headRotation = new AxisAngle4f((float) Math.toRadians(15), 1f, 0f, 0f);
        } else if (walking) {
            // Ходьба: медленное покачивание вверх-вниз ("вперевалку")
            double walkPhase = animTick * 0.45;
            bobY = Math.abs(Math.sin(walkPhase)) * 0.05;
            bodyRotation = identityAngle();
            headRotation = identityAngle();
        } else {
            // Стоит на месте: едва заметное "дыхание"
            double idlePhase = animTick * 0.08;
            bobY = Math.sin(idlePhase) * 0.015;
            bodyRotation = identityAngle();
            headRotation = identityAngle();
        }

        // Кивок головой при "рубке" - перекрывает обычную анимацию головы
        if (state == State.CHOPPING) {
            double bitePhase = (currentTick() - chopStartedAtTick) * 0.9;
            float biteAngle = (float) (Math.toRadians(18) * Math.max(0, Math.sin(bitePhase)));
            headRotation = new AxisAngle4f(biteAngle, 1f, 0f, 0f);
            bobY = 0; // тело замирает, кивает только голова
        }

        float yaw = loc.getYaw();
        Location displayLoc = new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw, 0f);

        visual.body().teleport(displayLoc);
        visual.body().setTransformation(new Transformation(
                new Vector3f(-0.5f, (float) bobY, -0.5f),
                bodyRotation,
                new Vector3f(1.15f, 1.15f, 1.15f),
                identityAngle()
        ));

        visual.head().teleport(displayLoc);
        visual.head().setTransformation(new Transformation(
                new Vector3f(-0.5f, (float) bobY, -0.5f),
                headRotation,
                new Vector3f(1.15f, 1.15f, 1.15f),
                identityAngle()
        ));
    }

    private static AxisAngle4f identityAngle() {
        return new AxisAngle4f(0f, 0f, 1f, 0f);
    }

    // ==================== ПОВЕДЕНИЕ (рубка дерева) ====================

    private void updateStateMachine() {
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
                beaver.getWorld().playSound(beaver.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
                beaver.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, beaver.getLocation().add(0, 0.6, 0), 8);
            }
        }

        targetLogLocation = null;
        state = State.IDLE;
        nextActionAtTick = currentTick() + randomCooldown();
    }
}
