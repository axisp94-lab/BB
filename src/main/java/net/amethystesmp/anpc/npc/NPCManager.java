package net.amethystesmp.anpc.npc;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.profile.ModelProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.amethystesmp.anpc.ANPCPlugin;

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

    public NPCManager(ANPCPlugin plugin) { this.plugin = plugin; }

    public void load() {
        file = new File(plugin.getDataFolder(), "npc.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                if (yaml.getBoolean("npcs." + id + ".removed", false)) continue;
                try {
                    createFrom(NPCDefinition.read(id, root.getConfigurationSection(id)));
                } catch (Exception e) {
                    plugin.getLogger().warning("NPC '" + id + "' non charge : " + e.getMessage());
                }
            }
        }
        if (visibilityTask != null) visibilityTask.cancel();
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVisibility, 10L, 10L);
    }

    private void createFrom(NPCDefinition def) {
        List<World> targets = resolveWorlds(def.worlds());
        for (World world : targets) {
            ModelRenderer renderer = resolveRenderer(def);
            if (renderer == null) {
                plugin.getLogger().severe("Modele BetterModel introuvable pour " + def.id() + " : " + def.type());
                continue;
            }
            ManagedNPC managed = new ManagedNPC(def, renderer, def.location(world), skinProfile(def));
            managed.setScriptsEnabled(yaml.getBoolean("npcs." + def.id() + ".scripts-enabled", true));
            String key = key(def.id(), world.getName());
            ManagedNPC previous = npcs.put(key, managed);
            if (previous != null) previous.close();
        }
    }

    private List<World> resolveWorlds(List<String> names) {
        List<World> targets = new ArrayList<>();
        if (names.stream().anyMatch(name -> name.equalsIgnoreCase("all"))) return new ArrayList<>(Bukkit.getWorlds());
        for (String worldName : names) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) plugin.getLogger().severe("Monde '" + worldName + "' absent/non charge.");
            else targets.add(world);
        }
        return targets;
    }

    private String key(String id, String world) {
        return id.toLowerCase(Locale.ROOT) + "@" + world.toLowerCase(Locale.ROOT);
    }

    private ModelProfile.Uncompleted skinProfile(NPCDefinition def) {
        if (!def.type().equalsIgnoreCase("player") && !def.type().toLowerCase(Locale.ROOT).startsWith("limb:")) return null;
        if (def.skin().isBlank()) return null;
        try { return ModelProfile.of(UUID.fromString(def.skin())); }
        catch (IllegalArgumentException ignored) {
            Player online = Bukkit.getPlayerExact(def.skin());
            return online == null ? null : ModelProfile.of(BukkitAdapter.adapt(online)).asUncompleted();
        }
    }

    private ModelRenderer resolveRenderer(NPCDefinition def) { return resolveRenderer(def.type()); }

    private ModelRenderer resolveRenderer(String type) {
        if (type.equalsIgnoreCase("player")) return BetterModel.limbOrNull("steve");
        if (type.toLowerCase(Locale.ROOT).startsWith("limb:")) return BetterModel.limbOrNull(type.substring(5));
        return BetterModel.modelOrNull(type);
    }

    private void refreshVisibility() {
        double range = plugin.getConfig().getDouble("tracking-range", 64);
        double rangeSquared = range * range;
        for (ManagedNPC npc : npcs.values()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(npc.location().getWorld())
                        && player.getLocation().distanceSquared(npc.location()) <= rangeSquared) npc.show(player);
                else npc.hide(player);
            }
        }
    }

    public ManagedNPC get(String id) {
        String prefix = id.toLowerCase(Locale.ROOT) + "@";
        return npcs.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    public ManagedNPC get(String id, World world) {
        if (world == null) return get(id);
        return npcs.get(key(id, world.getName()));
    }

    public List<ManagedNPC> instances(String id) {
        return npcs.values().stream().filter(npc -> npc.id().equalsIgnoreCase(id)).toList();
    }

    public Collection<ManagedNPC> all() { return Collections.unmodifiableCollection(npcs.values()); }
    public int size() { return npcs.size(); }
    public boolean exists(String id) { return yaml != null && yaml.isConfigurationSection("npcs." + id); }

    public boolean create(String id, String type, Location location) {
        if (exists(id) || location.getWorld() == null) return false;
        NPCDefinition definition = new NPCDefinition(id, id, type, List.of(location.getWorld().getName()),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), "");
        ModelRenderer renderer = resolveRenderer(type);
        if (renderer == null) return false;
        ManagedNPC managed = new ManagedNPC(definition, renderer, location, skinProfile(definition));
        managed.setScriptsEnabled(true);
        npcs.put(key(id, location.getWorld().getName()), managed);
        saveDefinition(definition);
        yaml.set("npcs." + id + ".removed", false);
        yaml.set("npcs." + id + ".scripts-enabled", true);
        save();
        plugin.variables().restoreLocalVariables(id, location.getWorld().getName());
        refreshVisibility();
        return true;
    }

    /** Soft removal: runtime entities disappear, while the YAML definition is retained. */
    public boolean remove(String id) {
        if (!exists(id)) return false;
        for (String mapKey : matchingKeys(id)) {
            ManagedNPC npc = npcs.remove(mapKey);
            if (npc != null) npc.close();
        }
        yaml.set("npcs." + id + ".removed", true);
        save();
        return true;
    }

    /** Recreates a soft-removed NPC from its unchanged definition. */
    public boolean restore(String id) {
        if (!exists(id)) return false;
        yaml.set("npcs." + id + ".removed", false);
        save();
        try {
            NPCDefinition definition = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
            createFrom(definition);
            refreshVisibility();
            return !instances(id).isEmpty();
        } catch (Exception exception) {
            plugin.getLogger().warning("Restauration du NPC '" + id + "' impossible : " + exception.getMessage());
            return false;
        }
    }

    /** Permanent deletion, with a 24-hour backup of local variables for each world. */
    public boolean delete(String id) {
        if (!exists(id)) return false;
        NPCDefinition definition = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
        for (World world : resolveWorlds(definition.worlds())) {
            plugin.variables().archiveLocalVariables(id, world.getName());
        }
        for (String mapKey : matchingKeys(id)) {
            ManagedNPC npc = npcs.remove(mapKey);
            if (npc != null) npc.close();
        }
        yaml.set("npcs." + id, null);
        save();
        return true;
    }

    /** Temporary runtime despawn. The definition remains enabled and returns on reload. */
    public boolean kill(String id) {
        if (!exists(id)) return false;
        for (String mapKey : matchingKeys(id)) {
            ManagedNPC npc = npcs.remove(mapKey);
            if (npc != null) npc.close();
        }
        return true;
    }

    public boolean setScriptsEnabled(String id, boolean enabled) {
        if (!exists(id) || yaml.getBoolean("npcs." + id + ".removed", false)) return false;
        for (ManagedNPC npc : instances(id)) npc.setScriptsEnabled(enabled);
        yaml.set("npcs." + id + ".scripts-enabled", enabled);
        save();
        return true;
    }

    public void rename(String id, String display) {
        if (!exists(id)) return;
        NPCDefinition old = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
        NPCDefinition next = new NPCDefinition(old.id(), display, old.type(), old.worlds(),
                old.x(), old.y(), old.z(), old.yaw(), old.pitch(), old.skin());
        for (ManagedNPC npc : instances(id)) npc.updateDefinition(next);
        saveDefinition(next);
    }

    public void center(String id) {
        for (ManagedNPC npc : instances(id)) {
            Location location = npc.location();
            location.setX(location.getBlockX() + 0.5);
            location.setZ(location.getBlockZ() + 0.5);
            npc.move(location);
        }
        persistSharedPosition(id);
    }

    public void teleport(String id, Location location) {
        ManagedNPC npc = get(id, location.getWorld());
        if (npc == null || location.getWorld() == null) return;
        npc.move(location);
        refreshVisibility();
    }

    private void persistSharedPosition(String id) {
        List<ManagedNPC> instances = instances(id);
        if (instances.isEmpty()) return;
        Location location = instances.get(0).location();
        NPCDefinition old = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
        NPCDefinition next = new NPCDefinition(old.id(), old.displayName(), old.type(), old.worlds(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), old.skin());
        saveDefinition(next);
    }

    private List<String> matchingKeys(String id) {
        return npcs.keySet().stream().filter(k -> k.startsWith(id.toLowerCase(Locale.ROOT) + "@")).toList();
    }

    private void saveDefinition(NPCDefinition definition) {
        String path = "npcs." + definition.id() + ".";
        yaml.set(path + "display-name", definition.displayName());
        yaml.set(path + "type", definition.type());
        yaml.set(path + "world", definition.worlds());
        yaml.set(path + "x", definition.x());
        yaml.set(path + "y", definition.y());
        yaml.set(path + "z", definition.z());
        yaml.set(path + "yaw", definition.yaw());
        yaml.set(path + "pitch", definition.pitch());
        if (!definition.skin().isBlank()) yaml.set(path + "skin", definition.skin());
        save();
    }

    private void save() {
        try { yaml.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Ecriture npc.yml impossible : " + exception.getMessage()); }
    }

    public void reload() {
        shutdownNPCs();
        npcs.clear();
        load();
    }

    private void shutdownNPCs() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        npcs.values().forEach(ManagedNPC::close);
    }

    public void shutdown() {
        shutdownNPCs();
        npcs.clear();
    }
}
