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

/**
 * Управляет "бобрами".
 *
 * АРХИТЕКТУРА ВИЗУАЛА:
 * "Мозг" бобра - невидимый Pig (не Rabbit!). Кролик в ваниле физически не
 * умеет ходить - прыжки зашиты в его движение на уровне ванильного ИИ и не
 * отключаются публичным Bukkit API. Свинья ходит обычной походкой без
 * прыжков, и её скорость можно занизить через Attribute.MOVEMENT_SPEED -
 * получаем медленную "вперевалку" походку, похожую на бобра.
 *
 * "Тело" бобра - ItemDisplay, посаженный на свинью пассажиром, показывающий
 * предмет с кастомной моделью через ItemMeta#setItemModel (способ с 1.21.2+,
 * без числового custom_model_data). Базовый предмет - PAPER (а не броня),
 * чтобы не пересекаться со спец-рендерером цветной кожаной брони, у которого
 * есть свои слои текстур - это иногда даёт "текстура не найдена" даже при
 * корректной модели.
 *
 * Ресурспак обязателен на клиенте, иначе игрок увидит стандартный предмет
 * (бумагу) вместо бобра - сама механика поведения при этом всё равно работает.
 */
public class BeaverManager {

    private final BeaverPlugin plugin;
    private final NamespacedKey isBeaverKey;

    /** Пространство имён и путь модели в ресурспаке BeaverDamResourcePack. */
    public static final NamespacedKey BEAVER_MODEL_KEY = new NamespacedKey("beaverdam", "beaver");

    /** Насколько медленнее свиньи-бобра ходят (обычная свинья - 0.25). */
    private static final double BEAVER_MOVEMENT_SPEED = 0.16;

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

    /**
     * Собирает ItemStack, который через ItemMeta#setItemModel указывает
     * на кастомную модель бобра из ресурспака.
     */
    private ItemStack buildBeaverModelItem() {
        // PAPER - нейтральный предмет без спец-рендеринга (не броня/не голова),
        // модель полностью его переопределяет.
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(BEAVER_MODEL_KEY);
        item.setItemMeta(meta);
        return item;
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
            p.setAI(true); // обычный ИИ ходьбы, без специфики кролика

            var speedAttr = p.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.setBaseValue(BEAVER_MOVEMENT_SPEED);
            }

            p.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });

        // 2. Видимая "модель" - ItemDisplay с кастомной геометрией, катается на свинье
        ItemDisplay model = location.getWorld().spawn(location, ItemDisplay.class, d -> {
            d.setItemStack(buildBeaverModelItem());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED);

            // Масштаб и позиция модели относительно "мозга"-свиньи.
            // Модель спроектирована в единицах 0-16 (как блок) с центром
            // приблизительно в (8,0,8) по X/Z, поэтому сдвигаем её на -0.5 по X/Z.
            Transformation transformation = new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f),      // смещение к центру
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(1.15f, 1.15f, 1.15f),    // масштаб
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            );
            d.setTransformation(transformation);
            d.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });

        brain.addPassenger(model);

        // Запускаем персональный ИИ-цикл этого бобра (управляет "мозгом")
        new BeaverAI(plugin, this, brain).runTaskTimer(plugin, 20L, 20L);

        return brain;
    }
}
