package ru.example.beaverplugin.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import ru.example.beaverplugin.BeaverManager;

import java.util.concurrent.ThreadLocalRandom;

/**
 * При смерти бобра убирает стандартный дроп базового моба (сырую свинину и
 * т.п.) и кладёт вместо него бревно дуба - как будто бобёр "сделан" из
 * запасов дерева, которые он копил.
 */
public class BeaverLootListener implements Listener {

    private final BeaverManager beaverManager;

    public BeaverLootListener(BeaverManager beaverManager) {
        this.beaverManager = beaverManager;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!beaverManager.isBeaver(mob)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        int amount = ThreadLocalRandom.current().nextInt(1, 3); // 1-2 бревна
        event.getDrops().add(new ItemStack(Material.OAK_LOG, amount));

        // Убираем ItemDisplay-"тело", иначе оно останется висеть в воздухе
        mob.getPassengers().forEach(org.bukkit.entity.Entity::remove);
    }
}
