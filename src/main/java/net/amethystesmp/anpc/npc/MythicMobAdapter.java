package net.amethystesmp.anpc.npc;

import net.amethystesmp.anpc.ANPCPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

public final class MythicMobAdapter {
    private MythicMobAdapter() { }

    private static void keepAlive(ANPCPlugin plugin, Object activeMob) {
        try {
            Method setter = Arrays.stream(activeMob.getClass().getMethods())
                    .filter(method -> method.getName().equals("setDespawnMode")
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isEnum())
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("ActiveMob.setDespawnMode"));
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object never = Enum.valueOf((Class<? extends Enum>) setter.getParameterTypes()[0], "NEVER");
            setter.invoke(activeMob, never);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("Impossible de désactiver le despawn automatique du NPC MythicMobs : "
                    + exception.getMessage());
        }
    }

    public static void despawn(ANPCPlugin plugin, Entity entity) {
        if (entity == null) return;
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Method lookup = Arrays.stream(mobManager.getClass().getMethods())
                    .filter(method -> method.getName().equals("getActiveMob")
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == java.util.UUID.class)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("MobManager.getActiveMob(UUID)"));
            Object result = lookup.invoke(mobManager, entity.getUniqueId());
            Object activeMob = result instanceof Optional<?> optional ? optional.orElse(null) : result;
            if (activeMob != null) activeMob.getClass().getMethod("despawn").invoke(activeMob);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Nettoyage MythicMobs incomplet pour le NPC : " + exception.getMessage());
        }
    }

    public static Entity spawn(ANPCPlugin plugin, String mobId, Location location, String displayName) {
        if (plugin.getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            plugin.getLogger().severe("Le type '" + mobId + "' nécessite MythicMobs, qui n'est pas installé.");
            return null;
        }

        Object activeMob = null;
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Object optionalMob = mobManager.getClass().getMethod("getMythicMob", String.class).invoke(mobManager, mobId);

            Object mythicMob = optionalMob instanceof Optional<?> optional ? optional.orElse(null) : optionalMob;
            if (mythicMob == null) {
                plugin.getLogger().severe("MythicMob introuvable : " + mobId);
                return null;
            }

            Class<?> adapterClass = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
            Method adaptLocation = Arrays.stream(adapterClass.getMethods())
                    .filter(method -> method.getName().equals("adapt")
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isAssignableFrom(Location.class))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("BukkitAdapter.adapt(Location)"));
            Object abstractLocation = adaptLocation.invoke(null, location);

            Method spawn = Arrays.stream(mythicMob.getClass().getMethods())
                    .filter(method -> method.getName().equals("spawn")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0].isInstance(abstractLocation)
                            && (method.getParameterTypes()[1] == double.class
                                || method.getParameterTypes()[1] == Double.class))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("MythicMob.spawn(AbstractLocation, double)"));

            activeMob = spawn.invoke(mythicMob, abstractLocation, 1.0d);
            if (activeMob == null) {
                plugin.getLogger().severe("MythicMobs n'a pas pu créer le mob : " + mobId);
                return null;
            }

            Object abstractEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
            Object bukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity").invoke(abstractEntity);
            if (!(bukkitEntity instanceof Entity entity)) {
                cleanupActiveMob(plugin, activeMob);
                plugin.getLogger().severe("MythicMobs a renvoyé une entité Bukkit invalide pour " + mobId);
                return null;
            }

            keepAlive(plugin, activeMob);
            if (entity instanceof LivingEntity living) {
                living.setAI(false);
                living.setRemoveWhenFarAway(false);
            }
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setCustomName(ChatColor.translateAlternateColorCodes('&', displayName));
            entity.setCustomNameVisible(true);
            return entity;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (activeMob != null) cleanupActiveMob(plugin, activeMob);
            plugin.getLogger().severe("Échec de l'adaptateur MythicMobs pour '" + mobId + "' : " + exception.getMessage());
            return null;
        }
    }

    private static void cleanupActiveMob(ANPCPlugin plugin, Object activeMob) {
        try {
            activeMob.getClass().getMethod("despawn").invoke(activeMob);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError cleanupFailure) {
            plugin.getLogger().warning("Impossible de nettoyer un MythicMob créé partiellement : "
                    + cleanupFailure.getMessage());
        }
    }
}
