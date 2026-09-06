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
 *  1) синхронизирует позицию всех семи частей тела с невидимым "мозгом" и
 *     считает процедурную анимацию (лапы качаются на своих реальных
 *     суставах - см. пивоты ниже, посчитанные из геометрии модели
 *     пользователя): ходьба/бег - переставляющийся шаг по диагонали,
 *     плавание - гребки + покачивание хвостом, покой - лёгкое "дыхание";
 *  2) раз в 20 тиков (1 секунду) продвигает конечный автомат поведения:
 *     IDLE -> MOVING -> CHOPPING -> (снова IDLE с кулдауном).
 *
 * ЧЕСТНОЕ ОГРАНИЧЕНИЕ: это не скелетная анимация (в Minecraft item-модели
 * не поддерживают кости/скиннинг без модов вроде GeckoLib, которые требуют
 * мод-клиент, а не просто ресурспак). Каждая "часть" - это целиком жёсткий
 * кусок геометрии, который мы поворачиваем как единое целое вокруг точки
 * его крепления к телу - это максимум, что достижимо чистым ванильным
 * рендером (Display-сущности) на подключении обычных, немодифицированных
 * клиентов.
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
    private static final double MOVE_EPSILON = 0.0025;  // порог смещения/тик, чтобы считать "идёт"
    private static final double RUN_EPSILON = 0.012;    // порог смещения/тик, чтобы считать "бежит"

    /**
     * Подстройка модели по высоте относительно ног "мозга"-свиньи.
     * Подобрано "на глаз" без возможности визуально протестировать в
     * реальном клиенте - при необходимости поменяйте и пересоберите.
     */
    private static final double VERTICAL_OFFSET = 0.35;

    // Точки крепления каждой части (в единицах модели 0-16, см. build_parts.py) -
    // переведены в блоки (/16) и объединены со сдвигом центрирования (-0.5 по X/Z),
    // см. BeaverManager.identityTransform(). Тело и голова/хвост/лапы делят одну
    // систему координат, поэтому все части автоматически стыкуются друг с другом.
    private static final Vector3f T_HEAD    = new Vector3f(-0.5f + 8f/16f,  5f/16f,  -0.5f + 1f/16f);
    private static final Vector3f T_BODY    = new Vector3f(-0.5f,           0f,       -0.5f);
    private static final Vector3f T_TAIL    = new Vector3f(-0.5f + 8f/16f,  2f/16f,  -0.5f + 8f/16f);
    private static final Vector3f T_LEG_FL  = new Vector3f(-0.5f + 4f/16f,  6f/16f,  -0.5f + 4.5f/16f);
    private static final Vector3f T_LEG_FR  = new Vector3f(-0.5f + 12f/16f, 6f/16f,  -0.5f + 4.5f/16f);
    private static final Vector3f T_LEG_BL  = new Vector3f(-0.5f + 4f/16f,  6f/16f,  -0.5f + 7.5f/16f);
    private static final Vector3f T_LEG_BR  = new Vector3f(-0.5f + 12f/16f, 6f/16f,  -0.5f + 7.5f/16f);

    private static final Vector3f MODEL_SCALE = new Vector3f(1.15f, 1.15f, 1.15f);
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0f, 0f, 1f, 0f);

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

        try {
            updateVisual();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Ошибка обновления визуала бобра " + beaver.getUniqueId() + ": " + e, e);
        }

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
        lastX = loc.getX();
        lastZ = loc.getZ();

        boolean swimming = beaver.isInWater();
        boolean running = horizDist > RUN_EPSILON;
        walking = horizDist > MOVE_EPSILON;

        double bobY = 0;
        float bodyPitch = 0f;
        float legAngleA = 0f; // передняя-левая + задняя-правая (диагональ A)
        float legAngleB = 0f; // передняя-правая + задняя-левая (диагональ B)
        float tailYaw = 0f;
        float headPitch = 0f;

        if (swimming) {
            double phase = animTick * 0.5;
            bobY = Math.sin(phase) * 0.05;
            bodyPitch = 35f;
            legAngleA = (float) (Math.sin(phase) * 35);
            legAngleB = (float) (Math.sin(phase + Math.PI) * 35);
            tailYaw = (float) (Math.sin(phase * 0.8) * 20);
        } else if (running) {
            double phase = animTick * 0.9;
            bobY = Math.abs(Math.sin(phase)) * 0.07;
            legAngleA = (float) (Math.sin(phase) * 32);
            legAngleB = (float) (Math.sin(phase + Math.PI) * 32);
            tailYaw = (float) (Math.sin(phase * 0.5) * 8);
        } else if (walking) {
            double phase = animTick * 0.45;
            bobY = Math.abs(Math.sin(phase)) * 0.045;
            legAngleA = (float) (Math.sin(phase) * 20);
            legAngleB = (float) (Math.sin(phase + Math.PI) * 20);
            tailYaw = (float) (Math.sin(phase * 0.5) * 5);
        } else {
            // Стоит на месте: едва заметное "дыхание", лапы и хвост в покое
            double idlePhase = animTick * 0.08;
            bobY = Math.sin(idlePhase) * 0.015;
        }

        // Кивок головой при "рубке" - перекрывает обычную анимацию, тело/лапы замирают
        if (state == State.CHOPPING) {
            double bitePhase = (currentTick() - chopStartedAtTick) * 0.9;
            headPitch = (float) (18 * Math.max(0, Math.sin(bitePhase)));
            bobY = 0;
            legAngleA = 0;
            legAngleB = 0;
            tailYaw = 0;
        }

        float yaw = loc.getYaw() + 180f; // см. BeaverManager: Display-сущности смотрят на север при yaw=0
        Location displayLoc = new Location(loc.getWorld(), loc.getX(), loc.getY() + VERTICAL_OFFSET, loc.getZ(), yaw, 0f);

        teleportAndTransform(visual.head(), displayLoc, T_HEAD, bobY, pitch(headPitch));
        teleportAndTransform(visual.body(), displayLoc, T_BODY, bobY, pitch(bodyPitch));
        teleportAndTransform(visual.tail(), displayLoc, T_TAIL, bobY, yawRot(tailYaw));
        teleportAndTransform(visual.legFL(), displayLoc, T_LEG_FL, bobY, pitch(legAngleA));
        teleportAndTransform(visual.legBR(), displayLoc, T_LEG_BR, bobY, pitch(legAngleA));
        teleportAndTransform(visual.legFR(), displayLoc, T_LEG_FR, bobY, pitch(legAngleB));
        teleportAndTransform(visual.legBL(), displayLoc, T_LEG_BL, bobY, pitch(legAngleB));
    }

    private static AxisAngle4f pitch(float degrees) {
        if (degrees == 0f) return NO_ROTATION;
        return new AxisAngle4f((float) Math.toRadians(degrees), 1f, 0f, 0f);
    }

    private static AxisAngle4f yawRot(float degrees) {
        if (degrees == 0f) return NO_ROTATION;
        return new AxisAngle4f((float) Math.toRadians(degrees), 0f, 1f, 0f);
    }

    private void teleportAndTransform(ItemDisplay display, Location baseLoc, Vector3f basePos, double bobY, AxisAngle4f rotation) {
        display.teleport(baseLoc);
        Vector3f translation = new Vector3f(basePos.x, basePos.y + (float) bobY, basePos.z);
        display.setTransformation(new Transformation(translation, rotation, MODEL_SCALE, NO_ROTATION));
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
