package net.amethystesmp.anpc.npc;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.profile.ModelProfile;
import net.amethystesmp.anpc.ANPCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NPCManager {
    private final ANPCPlugin plugin;
    private final Map<String, ManagedNPC> npcs = new ConcurrentHashMap<>();
    private BukkitTask visibilityTask;
    private File file;
    private YamlConfiguration yaml;

    public NPCManager(ANPCPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        stopLoadedNpcs();
        file = new File(plugin.getDataFolder(), "npc.yml");
        yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    plugin.getLogger().warning("Entrée NPC ignorée : 'npcs." + id + "' n'est pas une section YAML.");
                    continue;
                }
                try {
                    createFrom(NPCDefinition.read(id, section));
                } catch (Exception e) {
                    plugin.getLogger().warning("NPC '" + id + "' non chargé : " + e.getMessage());
                }
            }
        }

        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVisibility, 10L, 10L);
    }

    private void createFrom(NPCDefinition definition) {
        List<World> targets = new ArrayList<>();
        boolean allWorlds = definition.worlds().stream().anyMatch(w -> w.equalsIgnoreCase("all"));

        if (allWorlds) {
            targets.addAll(Bukkit.getWorlds());
        } else {
            for (String worldName : definition.worlds()) {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().severe("Monde '" + worldName + "' absent/non chargé pour le NPC " + definition.id());
                } else if (!targets.contains(world)) {
                    targets.add(world);
                }
            }
        }

        if (targets.isEmpty()) {
            plugin.getLogger().warning("NPC '" + definition.id() + "' ignoré : aucun monde valide n'est configuré.");
            return;
        }

        for (World world : targets) {
            ModelRenderer renderer = resolveRenderer(definition);
            if (renderer == null) {
                plugin.getLogger().severe("Modèle BetterModel introuvable pour " + definition.id() + " : " + definition.type());
                continue;
            }

            ManagedNPC managed = new ManagedNPC(definition, renderer, definition.location(world), skinProfile(definition));
            String key = instanceKey(definition.id(), world, allWorlds);
            ManagedNPC previous = npcs.put(key, managed);
            if (previous != null) previous.close();
        }
    }

    private String instanceKey(String id, World world, boolean allWorlds) {
        String normalizedId = id.toLowerCase(Locale.ROOT);
        return allWorlds ? normalizedId + "@" + world.getName().toLowerCase(Locale.ROOT) : normalizedId;
    }

    private ModelProfile.Uncompleted skinProfile(NPCDefinition definition) {
        if (!definition.type().equalsIgnoreCase("player")
                && !definition.type().toLowerCase(Locale.ROOT).startsWith("limb:")) {
            return null;
        }
        String skin = definition.skin().trim();
        if (skin.isEmpty()) return null;

        if (skin.regionMatches(true, 0, "file:", 0, 5)) {
            return plugin.localSkins().profileFor(skin.substring(5).trim());
        }
        if (skin.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return plugin.localSkins().profileFor(skin);
        }

        try {
            return ModelProfile.of(UUID.fromString(skin));
        } catch (IllegalArgumentException ignored) {
            Player online = Bukkit.getPlayerExact(skin);
            if (online != null) return ModelProfile.of(BukkitAdapter.adapt(online)).asUncompleted();

            plugin.getLogger().warning("Skin '" + skin + "' du NPC '" + definition.id()
                    + "' introuvable : utilise un UUID ou un fichier PNG dans le dossier npcs/.");
            return null;
        }
    }

    private ModelRenderer resolveRenderer(NPCDefinition definition) {
        String type = definition.type();
        if (type.equalsIgnoreCase("player")) return BetterModel.limbOrNull("steve");
        if (type.toLowerCase(Locale.ROOT).startsWith("limb:")) {
            return BetterModel.limbOrNull(type.substring(5));
        }
        return BetterModel.modelOrNull(type);
    }

    private ModelRenderer resolveRenderer(String type) {
        if (type.equalsIgnoreCase("player")) return BetterModel.limbOrNull("steve");
        if (type.toLowerCase(Locale.ROOT).startsWith("limb:")) {
            return BetterModel.limbOrNull(type.substring(5));
        }
        return BetterModel.modelOrNull(type);
    }

    private void refreshVisibility() {
        double range = Math.max(0, plugin.getConfig().getDouble("tracking-range", 64));
        double rangeSquared = range * range;

        for (ManagedNPC npc : npcs.values()) {
            Location location = npc.location();
            if (location.getWorld() == null) continue;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(location.getWorld())
                        && player.getLocation().distanceSquared(location) <= rangeSquared) {
                    npc.show(player);
                } else {
                    npc.hide(player);
                }
            }
        }
    }

    public ManagedNPC get(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        ManagedNPC exact = npcs.get(key);
        if (exact != null) return exact;

        return npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id))
                .sorted(Comparator.comparing(npc -> {
                    World world = npc.location().getWorld();
                    return world == null ? "" : world.getName().toLowerCase(Locale.ROOT);
                }))
                .findFirst()
                .orElse(null);
    }

    public ManagedNPC get(String id, World world) {
        if (id == null || world == null) return null;
        String normalizedId = id.toLowerCase(Locale.ROOT);
        ManagedNPC exact = npcs.get(normalizedId);
        if (exact != null && exact.location().getWorld().equals(world)) return exact;

        ManagedNPC worldInstance = npcs.get(normalizedId + "@" + world.getName().toLowerCase(Locale.ROOT));
        if (worldInstance != null) return worldInstance;

        return npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id) && npc.location().getWorld().equals(world))
                .findFirst()
                .orElse(null);
    }

    public Collection<ManagedNPC> all() {
        List<ManagedNPC> result = new ArrayList<>(npcs.values());
        result.sort(Comparator.comparing(ManagedNPC::id, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(npc -> {
                    World world = npc.location().getWorld();
                    return world == null ? "" : world.getName();
                }));
        return Collections.unmodifiableList(result);
    }

    public int size() {
        return npcs.values().stream().map(ManagedNPC::id).map(id -> id.toLowerCase(Locale.ROOT)).distinct().toList().size();
    }

    public boolean create(String id, String type, Location location) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+") || type == null || type.isBlank()
                || location == null || location.getWorld() == null || get(id) != null) {
            return false;
        }

        NPCDefinition definition = new NPCDefinition(id, id, type, List.of(location.getWorld().getName()),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), "");
        ModelRenderer renderer = resolveRenderer(type);
        if (renderer == null) return false;

        try {
            ManagedNPC managed = new ManagedNPC(definition, renderer, location, skinProfile(definition));
            npcs.put(id.toLowerCase(Locale.ROOT), managed);
            saveDefinition(definition);
            refreshVisibility();
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Création du NPC '" + id + "' impossible : " + e.getMessage());
            return false;
        }
    }

    public boolean remove(String id) {
        if (id == null) return false;
        List<String> matches = npcs.entrySet().stream()
                .filter(entry -> entry.getValue().id().equalsIgnoreCase(id))
                .map(Map.Entry::getKey)
                .toList();
        if (matches.isEmpty()) return false;

        for (String key : matches) {
            ManagedNPC npc = npcs.remove(key);
            if (npc != null) npc.close();
        }

        String yamlId = findYamlId(id);
        if (yamlId != null && yaml != null) {
            yaml.set("npcs." + yamlId, null);
            save();
        }
        return true;
    }

    public boolean rename(String id, String displayName) {
        if (id == null || displayName == null || displayName.isBlank()) return false;
        List<ManagedNPC> matches = npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id))
                .toList();
        if (matches.isEmpty()) return false;

        for (ManagedNPC npc : matches) {
            NPCDefinition current = npc.definition();
            npc.updateDefinition(new NPCDefinition(current.id(), displayName, current.type(), current.worlds(),
                    current.x(), current.y(), current.z(), current.yaw(), current.pitch(), current.skin()));
        }

        String yamlId = findYamlId(id);
        if (yamlId != null) {
            yaml.set("npcs." + yamlId + ".display-name", displayName);
            save();
        }
        return true;
    }

    public boolean center(String id) {
        if (id == null) return false;
        List<ManagedNPC> matches = npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id))
                .toList();
        if (matches.isEmpty()) return false;

        for (ManagedNPC npc : matches) {
            Location centered = npc.location();
            centered.setX(centered.getBlockX() + 0.5);
            centered.setZ(centered.getBlockZ() + 0.5);
            npc.move(centered);
        }
        persistPosition(matches.get(0), matches.get(0).location());
        return true;
    }

    public void teleport(String id, Location location) {
        ManagedNPC npc = get(id, location == null ? null : location.getWorld());
        if (npc == null || location == null || location.getWorld() == null) return;
        npc.move(location);
        persistPosition(npc, location);
        refreshVisibility();
    }

    private void persistPosition(ManagedNPC npc, Location location) {
        NPCDefinition current = npc.definition();
        NPCDefinition updated = new NPCDefinition(current.id(), current.displayName(), current.type(),
                current.worlds(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), current.skin());
        npc.updateDefinition(updated);
        saveDefinition(updated);
    }

    private String findYamlId(String id) {
        if (yaml == null) return null;
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) return null;
        return root.getKeys(false).stream().filter(key -> key.equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    private void saveDefinition(NPCDefinition definition) {
        if (yaml == null) return;
        String path = "npcs." + definition.id() + ".";
        yaml.set(path + "display-name", definition.displayName());
        yaml.set(path + "type", definition.type());
        yaml.set(path + "world", definition.worlds());
        yaml.set(path + "x", definition.x());
        yaml.set(path + "y", definition.y());
        yaml.set(path + "z", definition.z());
        yaml.set(path + "yaw", definition.yaw());
        yaml.set(path + "pitch", definition.pitch());
        if (definition.skin().isBlank()) yaml.set(path + "skin", null);
        else yaml.set(path + "skin", definition.skin());
        save();
    }

    private void save() {
        if (yaml == null || file == null) return;
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Écriture de npc.yml impossible : " + e.getMessage());
        }
    }

    public void reload() {
        load();
    }

    private void stopLoadedNpcs() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        npcs.values().forEach(ManagedNPC::close);
        npcs.clear();
    }

    public void shutdown() {
        stopLoadedNpcs();
    }
}
