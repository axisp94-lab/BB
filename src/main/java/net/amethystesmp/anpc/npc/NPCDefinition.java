package net.amethystesmp.anpc.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record NPCDefinition(String id, String displayName, String type, List<String> worlds,
                            double x, double y, double z, float yaw, float pitch, String skin) {

    public NPCDefinition {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Identifiant NPC invalide : seuls les lettres, chiffres, '_' et '-' sont autorisés.");
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        type = type == null ? "" : type.trim();
        if (type.isBlank()) {
            throw new IllegalArgumentException("Type de modèle manquant pour le NPC '" + id + "'.");
        }
        if (worlds == null) {
            worlds = List.of("world");
        } else {
            Set<String> seenWorlds = new HashSet<>();
            worlds = worlds.stream()
                    .filter(world -> world != null && !world.isBlank())
                    .map(String::trim)
                    .filter(world -> seenWorlds.add(world.toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException("Aucun monde valide configuré pour le NPC '" + id + "'.");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Coordonnées ou orientation non finies pour le NPC '" + id + "'.");
        }
        skin = skin == null ? "" : skin.trim();
    }

    public static NPCDefinition read(String id, ConfigurationSection section) {
        List<String> worlds = new ArrayList<>();
        Object rawWorld = section.get("world");
        if (rawWorld == null) {
            worlds.add("world");
        } else if (rawWorld instanceof String world) {
            worlds.add(world);
        } else if (rawWorld instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof String world)) {
                    throw new IllegalArgumentException("La liste 'world' doit contenir uniquement des noms de mondes.");
                }
                worlds.add(world);
            }
        } else {
            throw new IllegalArgumentException("Le champ 'world' doit être un nom ou une liste de noms.");
        }

        return new NPCDefinition(
                id,
                section.getString("display-name", id),
                section.getString("type", "villager"),
                worlds,
                requiredNumber(section, "x"),
                requiredNumber(section, "y"),
                requiredNumber(section, "z"),
                (float) optionalNumber(section, "yaw", 0.0),
                (float) optionalNumber(section, "pitch", 0.0),
                section.getString("skin", "")
        );
    }

    private static double requiredNumber(ConfigurationSection section, String key) {
        Object raw = section.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Le champ '" + key + "' est absent ou n'est pas numérique.");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Le champ '" + key + "' doit être un nombre fini.");
        }
        return value;
    }

    private static double optionalNumber(ConfigurationSection section, String key, double fallback) {
        if (!section.contains(key)) return fallback;
        return requiredNumber(section, key);
    }

    public boolean isModel() {
        return !type.toLowerCase(Locale.ROOT).startsWith("mythicmob:");
    }

    public Location location(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public boolean appliesTo(World world) {
        return world != null && worlds.stream()
                .anyMatch(name -> name.equalsIgnoreCase("all") || name.equalsIgnoreCase(world.getName()));
    }
}
