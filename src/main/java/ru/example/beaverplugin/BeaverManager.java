package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
 * "Тело" бобра - ДВЕ отдельные ItemDisplay-сущности (не пассажиры!):
 *  - body  - туловище, лапы, хвост (models/item/beaver_body.json)
 *  - head  - голова, уши, глаза, нос, зубы (models/item/beaver_head.json)
 * Раздельные сущности нужны, чтобы голову можно было отдельно покачивать/
 * кивать при "рубке" дерева, не трогая туловище.
 *
 * ПОЧЕМУ НЕ PASSENGER: посадка пассажиром привязывает модель к фиксированной
 * точке "в седле" на спине моба (для свиньи - это высоко над землёй), из-за
 * чего модель визуально "парит" в воздухе. Вместо этого BeaverAI каждый тик
 * вручную телепортирует обе ItemDisplay-сущности на позицию "мозга" и сам
 * считает анимацию (покачивание при ходьбе/плавании, кивок при кусании).
 *
 * Ресурспак обязателен на клиенте, иначе игрок увидит стандартный предмет
 * (бумагу) вместо бобра - сама механика поведения при этом всё равно работает.
 */
public class BeaverManager {

    /** Хранит ссылки на визуальные сущности одного бобра. */
    public record BeaverVisual(ItemDisplay body, ItemDisplay head) {
        public void remove() {
            if (body.isValid()) body.remove();
            if (head.isValid()) head.remove();
        }
    }

    private final BeaverPlugin plugin;
    private final NamespacedKey isBeaverKey;
    private final Map<UUID, BeaverVisual> visuals = new ConcurrentHashMap<>();

    public static final NamespacedKey BODY_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_body");
    public static final NamespacedKey HEAD_MODEL_KEY = new NamespacedKey("beaverdam", "beaver_head");

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

    /** Убирает обе визуальные сущности бобра и забывает про него. */
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
            // иначе покачивание будет дёрганым.
            d.setInterpolationDuration(3);
            d.setTeleportDuration(3);
            d.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Базовая трансформация: центрируем модель (координаты 0-16) и чуть увеличиваем. */
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

        // 2. Видимые части - создаются отдельно, НЕ как пассажиры (см. комментарий класса)
        ItemDisplay body = spawnPart(location, BODY_MODEL_KEY);
        ItemDisplay head = spawnPart(location, HEAD_MODEL_KEY);
        visuals.put(brain.getUniqueId(), new BeaverVisual(body, head));

        // Запускаем персональный ИИ-цикл этого бобра (управляет "мозгом" и визуалом)
        new BeaverAI(plugin, this, brain).runTaskTimer(plugin, 1L, 1L);

        return brain;
    }
}
