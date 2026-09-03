package ru.example.beaverplugin;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Rabbit;
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
 * "Мозг" бобра - невидимый Rabbit (используем его готовый ИИ, Pathfinder,
 * коллизии и физику - сам он не виден).
 * "Тело" бобра - ItemDisplay, посаженный на кролика пассажиром, показывающий
 * предмет с кастомной моделью через ItemMeta#setItemModel (современный способ
 * с 1.21.2+, без числового custom_model_data). Модель - полноценная 3D-
 * геометрия (не плоский спрайт item/generated): пухлое округлое тело ("chonk"),
 * компактная голова, маленькие уши, глаза, нос, фирменные передние зубы,
 * короткие лапы и широкий плоский хвост - см.
 * assets/beaverdam/models/item/beaver.json ресурспака BeaverDamResourcePack.
 * Стилистически модель ориентируется на низкополигональные voxel/Blockbench
 * модели бобра для Minecraft (не является копией конкретного файла).
 *
 * Ресурспак обязателен на клиенте, иначе игрок увидит стандартный предмет
 * (кожаную попону) вместо бобра - сама механика поведения при этом всё равно работает.
 */
public class BeaverManager {

    private final BeaverPlugin plugin;
    private final NamespacedKey isBeaverKey;

    /** Пространство имён и путь модели в ресурспаке BeaverDamResourcePack. */
    public static final NamespacedKey BEAVER_MODEL_KEY = new NamespacedKey("beaverdam", "beaver");

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
        // Базовый предмет не важен визуально (модель полностью его переопределяет).
        ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(BEAVER_MODEL_KEY);
        item.setItemMeta(meta);
        return item;
    }

    public Rabbit spawnBeaver(Location location) {
        // 1. Невидимый "мозг" - используется для ИИ, пути и физики
        Rabbit brain = location.getWorld().spawn(location, Rabbit.class, r -> {
            r.setRabbitType(Rabbit.Type.BROWN);
            r.setAdult();
            r.setInvisible(true);
            r.setSilent(true);
            r.setCustomName("§6Бобёр");
            r.setCustomNameVisible(false);
            r.setRemoveWhenFarAway(false);
            r.getPersistentDataContainer().set(isBeaverKey, PersistentDataType.BYTE, (byte) 1);
        });

        // 2. Видимая "модель" - ItemDisplay с кастомной геометрией, катается на кролике
        ItemDisplay model = location.getWorld().spawn(location, ItemDisplay.class, d -> {
            d.setItemStack(buildBeaverModelItem());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED);

            // Масштаб и позиция модели относительно "мозга"-кролика.
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
