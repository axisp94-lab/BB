package net.amethystesmp.anpc.npc;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Locale;

/**
 * Creates native Minecraft mobs as managed NPCs without requiring BetterModel
 * or MythicMobs definitions for their visual representation.
 */
public final class VanillaMobAdapter {
    private VanillaMobAdapter() {
    }

    public static EntityType resolve(String input) {
        if (input == null || input.isBlank()) return null;

        String value = input.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key;
        if (value.indexOf(':') >= 0) {
            key = NamespacedKey.fromString(value);
            if (key == null || !key.getNamespace().equals("minecraft")) return null;
        } else {
            key = NamespacedKey.minecraft(value);
        }

        EntityType type = Registry.ENTITY_TYPE.get(key);
        if (type == null || !type.isAlive()) return null;

        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) return null;
        return type;
    }

    public static boolean isVanillaMob(String input) {
        return resolve(input) != null;
    }

    public static Entity spawn(Location location, String typeName, String displayName) {
        if (location == null || location.getWorld() == null) return null;
        EntityType type = resolve(typeName);
        if (type == null) return null;

        Entity entity = location.getWorld().spawnEntity(
                location,
                type,
                CreatureSpawnEvent.SpawnReason.CUSTOM
        );

        try {
            if (!(entity instanceof LivingEntity living) || !(entity instanceof Mob mob)) {
                entity.remove();
                return null;
            }

            entity.setCustomName(ChatColor.translateAlternateColorCodes('&', displayName));
            entity.setCustomNameVisible(true);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setPersistent(false);
            living.setRemoveWhenFarAway(false);

            // A vanilla NPC is a display/interaction character, not a hostile
            // gameplay mob. Disable autonomous behaviour and item pickup.
            mob.setAI(false);
            mob.setAware(false);
            mob.setCanPickupItems(false);
            return entity;
        } catch (RuntimeException | LinkageError exception) {
            try {
                if (entity.isValid()) entity.remove();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }
}
