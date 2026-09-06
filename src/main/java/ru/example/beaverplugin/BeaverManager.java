package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управляет "бобрами".
 *
 * АРХИТЕКТУРА ВИЗУАЛА:
 * "Мозг" бобра - невидимый Pig (ИИ, Pathfinder, коллизии, физика).
 *
 * "Тело" бобра - СЕМЬ отдельных ItemDisplay-сущностей (не пассажиры!),
 * по числу частей модели, разрезанной из файла пользователя (bobr.json):
 * голова, туловище, хвост и 4 лапы (перед-лево/право, зад-лево/право).
 * Раздельные сущности - единственный способ получить реальное движение
 * "костей" в ванильном Minecraft без модов: сервер каждый тик сам
 * пересчитывает положение и поворот каждой части (BeaverAI).
 *
 * Координаты каждой модели части (models/item/beaver_<part>.json) уже
 * пересчитаны так, чтобы локальный ноль (0,0,0) совпадал с точкой крепления
 * этой части к телу (бедро для лап, шея для головы, основание для хвоста) -
 * это позволяет поворачивать часть вокруг ЕЁ РЕАЛЬНОГО сустава, а не вокруг
 * общего центра модели.
 *
 * ПОЧЕМУ НЕ PASSENGER: посадка пассажиром привязывает модель к фиксированной
 * точке "в седле" на спине моба, из-за чего модель визуально "парит" в
 * воздухе. Вместо этого BeaverAI каждый тик вручную телепортирует все семь
 * ItemDisplay-сущностей на позицию "мозга" (с индивидуальным смещением под
 * точку крепления каждой части).
 */
public class BeaverManager {

    /** Хранит ссылки на все семь визуальных частей одного бобра. */
    public record BeaverVisual(
            ItemDisplay head, ItemDisplay body, ItemDisplay tail,
            ItemDisplay legFL, ItemDisplay legFR, ItemDisplay legBL, ItemDisplay legBR
    ) {
        public void remove() {
            for (ItemDisplay d : new ItemDisplay[]{head, body, tail, legFL, legFR, legBL, legBR}) {
                if (d.isValid()) d.remove();
            }
        }
    }

    private final BeaverPlugin plugin;
    private final NamespacedKey isBeaverKey;
    private final Map<UUID, BeaverVisual> visuals = new ConcurrentHashMap<>();

    public static final NamespacedKey HEAD_MODEL_KEY   = new NamespacedKey("beaverdam", "beaver_head");
    public static final NamespacedKey BODY_MODEL_KEY   = new NamespacedKey("beaverdam", "beaver_body");
    public static final NamespacedKey TAIL_MODEL_KEY   = new NamespacedKey("beaverdam", "beaver_tail");
    public static final NamespacedKey LEG_FL_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_leg_fl");
    public static final NamespacedKey LEG_FR_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_leg_fr");
    public static final NamespacedKey LEG_BL_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_leg_bl");
    public static final NamespacedKey LEG_BR_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_leg_br");

    /** Насколько медленнее свиньи-бобра ходят (обычная свинья - 0.25). */
    private static final double BEAVER_MOVEMENT_SPEED = 0.16;

    /** Во сколько раз уменьшаем хитбокс относительно стандартной свиньи. */
    private static final double BEAVER_HITBOX_SCALE = 0.8;

    public BeaverManager(BeaverPlugin plugin) {
        this.plugin = plugin;
        this.isBeaverKey = new NamespacedKey(plugin, "is_beaver");
    }

    public NamespacedKey getIsBeaverKey() {
        return isBeaverKey;
    }

    public boolean isBeaver(Mob mob) {
        return mob.getPersistentDataContainer().has(isBeaverKey, PersistentDataType.BYTE);
    }

    public BeaverVisual getVisual(UUID brainId) {
        return visuals.get(brainId);
    }

    /** Убирает все семь визуальных частей бобра и забывает про него. */
    public void removeVisual(UUID brainId) {
        BeaverVisual visual = visuals.remove(brainId);
        if (visual != null) {
            visual.remove();
        }
    }

    private ItemStack buildModelItem(NamespacedKey modelKey) {
        // PAPER - нейтральный предмет без спец-рендеринга (не броня/не голова),
        // модель полностью его переопределяет.
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(modelKey);
        item.setItemMeta(meta);
        return item;
    }

    private ItemDisplay spawnPart(Location location, NamespacedKey modelKey) {
        return location.getWorld().spawn(location, ItemDisplay.class, d -> {
            d.setItemStack(buildModelItem(modelKey));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(identityTransform());
            // Плавная интерполяция между кадрами телепорта/трансформации,
            // иначе анимация будет дёрганой.
            d.setInterpolationDuration(3);
            d.setTeleportDuration(3);
            d.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public static Transformation identityTransform() {
        return new Transformation(
                new Vector3f(-0.5f, 0f, -0.5f),
                new AxisAngle4f(0f, 0f, 1f, 0f),
                new Vector3f(1.15f, 1.15f, 1.15f),
                new AxisAngle4f(0f, 0f, 1f, 0f)
        );
    }

    public Pig spawnBeaver(Location location) {
        // 1. Невидимый "мозг" - используется для ИИ, пути и физики
        Pig brain = location.getWorld().spawn(location, Pig.class, p -> {
            p.setAdult();
            p.setInvisible(true);
            p.setSilent(true);
            p.setCustomName("§6Бобёр");
            p.setCustomNameVisible(false);
            p.setRemoveWhenFarAway(false);
            p.setAI(true);

            var speedAttr = p.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.setBaseValue(BEAVER_MOVEMENT_SPEED);
            }
            var scaleAttr = p.getAttribute(Attribute.SCALE);
            if (scaleAttr != null) {
                scaleAttr.setBaseValue(BEAVER_HITBOX_SCALE);
            }

            p.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });

        // 2. Семь видимых частей - создаются отдельно, НЕ как пассажиры
        BeaverVisual visual = new BeaverVisual(
                spawnPart(location, HEAD_MODEL_KEY),
                spawnPart(location, BODY_MODEL_KEY),
                spawnPart(location, TAIL_MODEL_KEY),
                spawnPart(location, LEG_FL_MODEL_KEY),
                spawnPart(location, LEG_FR_MODEL_KEY),
                spawnPart(location, LEG_BL_MODEL_KEY),
                spawnPart(location, LEG_BR_MODEL_KEY)
        );
        visuals.put(brain.getUniqueId(), visual);

        // Запускаем персональный ИИ-цикл этого бобра (управляет "мозгом" и визуалом)
        new BeaverAI(plugin, this, brain).runTaskTimer(plugin, 1L, 1L);

        return brain;
    }
}
