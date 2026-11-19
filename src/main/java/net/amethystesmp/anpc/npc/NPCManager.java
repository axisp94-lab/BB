package net.amethystesmp.anpc.npc;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.profile.ModelProfileInfo;
import kr.toxicity.model.api.profile.ModelProfileSkin;
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
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NPCManager {
    private final ANPCPlugin plugin;
    private final Map<String, ManagedNPC> npcs = new ConcurrentHashMap<>();
    private BukkitTask visibilityTask;
    private File file;
    private YamlConfiguration yaml;
    private File deletedFile;
    private YamlConfiguration deletedYaml;

    public NPCManager(ANPCPlugin plugin) { this.plugin = plugin; }

    public void load() {
        file = new File(plugin.getDataFolder(), "npc.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        deletedFile = new File(plugin.getDataFolder(), "deleted-npcs.yml");
        deletedYaml = YamlConfiguration.loadConfiguration(deletedFile);
        purgeExpiredDeletedNPCs();
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                if (isRemoved(id)) continue;
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
            managed.setSpeed(plugin.getConfig().getDouble("movement.default-speed", 4.0));
            boolean scriptsEnabled = yaml.getBoolean("npcs." + def.id() + ".scripts-enabled", true);
            managed.setScriptsEnabled(scriptsEnabled);
            if (!scriptsEnabled) restoreRuntimeState(managed);
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
        if (def.skin().toLowerCase(Locale.ROOT).startsWith("file:")) {
            String relativePath = def.skin().substring(5).trim();
            try {
                File dataDirectory = plugin.getDataFolder().getCanonicalFile();
                File skinFile = new File(dataDirectory, relativePath).getCanonicalFile();
                if (!skinFile.toPath().startsWith(dataDirectory.toPath()) || !skinFile.isFile()) {
                    plugin.getLogger().warning("Fichier de skin introuvable ou hors du dossier du plugin pour le NPC " + def.id() + ".");
                    return null;
                }
                BufferedImage image = ImageIO.read(skinFile);
                if (image == null || !((image.getWidth() == 64 && image.getHeight() == 64)
                        || (image.getWidth() == 64 && image.getHeight() == 32))) {
                    plugin.getLogger().warning("Skin local invalide pour " + def.id() + " : PNG 64x64 ou 64x32 attendu.");
                    return null;
                }
                UUID profileId = UUID.nameUUIDFromBytes(("AmethyysNPCs:" + def.id()).getBytes(StandardCharsets.UTF_8));
                ModelProfileInfo info = new ModelProfileInfo(profileId, def.id());
                ModelProfileSkin skin = new ModelProfileSkin(skinFile.toURI(), null, false, "");
                return ModelProfile.of(info, skin).asUncompleted();
            } catch (Exception exception) {
                plugin.getLogger().warning("Chargement du skin local de " + def.id() + " impossible : " + exception.getMessage());
                return null;
            }
        }
        try { return ModelProfile.of(UUID.fromString(def.skin())); }
        catch (IllegalArgumentException ignored) {
            Player online = Bukkit.getPlayerExact(def.skin());
            if (online != null) return ModelProfile.of(BukkitAdapter.adapt(online)).asUncompleted();
            return ModelProfile.of(Bukkit.getOfflinePlayer(def.skin()).getUniqueId());
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

    public List<String> allIds() {
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ConfigurationSection root = yaml == null ? null : yaml.getConfigurationSection("npcs");
        if (root != null) ids.addAll(root.getKeys(false));
        ConfigurationSection deleted = deletedYaml == null ? null : deletedYaml.getConfigurationSection("npcs");
        if (deleted != null) ids.addAll(deleted.getKeys(false));
        return List.copyOf(ids);
    }

    public Collection<ManagedNPC> all() { return Collections.unmodifiableCollection(npcs.values()); }
    public int size() { return npcs.size(); }
    public boolean exists(String id) { return yaml != null && yaml.isConfigurationSection("npcs." + id); }

    private boolean isRemoved(String id) {
        String stored = plugin.variables().get("npc-lifecycle", id, "removed", null);
        return stored == null ? yaml.getBoolean("npcs." + id + ".removed", false) : Boolean.parseBoolean(stored);
    }

    public boolean create(String id, String type, Location location) {
        if (exists(id) || location.getWorld() == null) return false;
        NPCDefinition definition = new NPCDefinition(id, id, type, List.of(location.getWorld().getName()),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), "");
        ModelRenderer renderer = resolveRenderer(type);
        if (renderer == null) return false;
        ManagedNPC managed = new ManagedNPC(definition, renderer, location, skinProfile(definition));
        managed.setSpeed(plugin.getConfig().getDouble("movement.default-speed", 4.0));
        managed.setScriptsEnabled(true);
        npcs.put(key(id, location.getWorld().getName()), managed);
        saveDefinition(definition);
        plugin.variables().set("npc-lifecycle", id, "removed", false);
        yaml.set("npcs." + id + ".removed", null);
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
        plugin.variables().set("npc-lifecycle", id, "removed", true);
        return true;
    }

    /** Recreates a soft-removed NPC from its unchanged definition. */
    public boolean restore(String id) {
        if (exists(id)) {
            plugin.variables().set("npc-lifecycle", id, "removed", false);
            yaml.set("npcs." + id + ".removed", null);
            save();
            try {
                NPCDefinition definition = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
                createFrom(definition);
                refreshVisibility();
                return true;
            } catch (Exception exception) {
                plugin.getLogger().warning("Restauration du NPC '" + id + "' impossible : " + exception.getMessage());
                return false;
            }
        }

        ConfigurationSection backup = deletedYaml == null ? null : deletedYaml.getConfigurationSection("npcs." + id);
        if (backup == null) return false;
        long deletedAt = backup.getLong("deleted-at", 0L);
        if (deletedAt <= 0L || System.currentTimeMillis() - deletedAt > 24L * 60L * 60L * 1000L) {
            deletedYaml.set("npcs." + id, null);
            saveDeleted();
            return false;
        }
        try {
            NPCDefinition definition = NPCDefinition.read(id, backup);
            saveDefinition(definition);
            plugin.variables().set("npc-lifecycle", id, "removed", false);
            yaml.set("npcs." + id + ".scripts-enabled", true);
            List<String> recoveryWorlds = backup.getStringList("recovery-worlds");
            if (recoveryWorlds.isEmpty()) recoveryWorlds = definition.worlds().stream().anyMatch(name -> name.equalsIgnoreCase("all"))
                    ? Bukkit.getWorlds().stream().map(World::getName).toList() : definition.worlds();
            for (String worldName : recoveryWorlds) plugin.variables().restoreLocalVariables(id, worldName);
            deletedYaml.set("npcs." + id, null);
            saveDeleted();
            save();
            createFrom(definition);
            refreshVisibility();
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("Récupération du NPC supprimé '" + id + "' impossible : " + exception.getMessage());
            return false;
        }
    }

    /** Permanent deletion, with a 24-hour backup of local variables for each world. */
    public boolean delete(String id) {
        if (!exists(id)) return false;
        NPCDefinition definition = NPCDefinition.read(id, yaml.getConfigurationSection("npcs." + id));
        List<String> worldNames = definition.worlds().stream().anyMatch(name -> name.equalsIgnoreCase("all"))
                ? Bukkit.getWorlds().stream().map(World::getName).toList()
                : definition.worlds();
        for (String worldName : worldNames) {
            plugin.variables().archiveLocalVariables(id, worldName);
            String owner = net.amethystesmp.anpc.storage.VariableStore.localOwner(id, worldName);
            plugin.variables().delete("npc-runtime", owner, "primary-state");
            plugin.variables().delete("npc-runtime", owner, "secondary-states");
            plugin.variables().delete("npc-runtime", owner, "follow-target");
            plugin.variables().delete("npc-runtime", owner, "follow-min");
            plugin.variables().delete("npc-runtime", owner, "follow-max");
            plugin.variables().delete("npc-runtime", owner, "hostile");
            plugin.variables().delete("npc-runtime", owner, "target-selector");
            plugin.variables().delete("npc-runtime", owner, "last-attacker");
            plugin.variables().delete("npc-runtime", owner, "health");
            plugin.variables().delete("npc-runtime", owner, "max-health");
            plugin.variables().delete("npc-runtime", owner, "speed");
        }
        backupDeletedNPC(definition, worldNames);
        for (String mapKey : matchingKeys(id)) {
            ManagedNPC npc = npcs.remove(mapKey);
            if (npc != null) npc.close();
        }
        yaml.set("npcs." + id, null);
        plugin.variables().delete("npc-lifecycle", id, "removed");
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
        if (!exists(id) || isRemoved(id)) return false;
        for (ManagedNPC npc : instances(id)) {
            if (!enabled) saveRuntimeState(npc);
            else restoreRuntimeState(npc);
            npc.setScriptsEnabled(enabled);
            if (enabled) clearRuntimeState(npc);
        }
        yaml.set("npcs." + id + ".scripts-enabled", enabled);
        save();
        return true;
    }

    private void saveRuntimeState(ManagedNPC npc) {
        String owner = net.amethystesmp.anpc.storage.VariableStore.localOwner(
                npc.id(), npc.location().getWorld().getName());
        plugin.variables().set("npc-runtime", owner, "primary-state", npc.primaryState());
        plugin.variables().set("npc-runtime", owner, "secondary-states",
                String.join(",", npc.secondaryStates()));
        plugin.variables().set("npc-runtime", owner, "follow-target", npc.followTarget());
        plugin.variables().set("npc-runtime", owner, "follow-min", npc.followMin());
        plugin.variables().set("npc-runtime", owner, "follow-max", npc.followMax());
        plugin.variables().set("npc-runtime", owner, "hostile", npc.hostile());
        plugin.variables().set("npc-runtime", owner, "target-selector", npc.targetSelector());
        plugin.variables().set("npc-runtime", owner, "last-attacker", npc.lastAttacker() == null ? "" : npc.lastAttacker().toString());
        plugin.variables().set("npc-runtime", owner, "health", npc.health());
        plugin.variables().set("npc-runtime", owner, "max-health", npc.maxHealth());
        plugin.variables().set("npc-runtime", owner, "speed", npc.speed());
    }

    private void restoreRuntimeState(ManagedNPC npc) {
        String owner = net.amethystesmp.anpc.storage.VariableStore.localOwner(
                npc.id(), npc.location().getWorld().getName());
        String primary = plugin.variables().get("npc-runtime", owner, "primary-state", "idle");
        String secondary = plugin.variables().get("npc-runtime", owner, "secondary-states", "");
        npc.setPrimaryState(primary);
        npc.clearSecondaryStates();
        if (!secondary.isBlank()) for (String state : secondary.split(",")) npc.addSecondaryState(state);
        String followTarget = plugin.variables().get("npc-runtime", owner, "follow-target", "");
        double followMin = Double.parseDouble(plugin.variables().get("npc-runtime", owner, "follow-min", "2.0"));
        double followMax = Double.parseDouble(plugin.variables().get("npc-runtime", owner, "follow-max", "5.0"));
        if (!followTarget.isBlank()) npc.setFollowState(followTarget, followMin, followMax);
        npc.setHostile(Boolean.parseBoolean(plugin.variables().get("npc-runtime", owner, "hostile", "false")));
        npc.setTargetSelector(plugin.variables().get("npc-runtime", owner, "target-selector", "all players"));
        String lastAttacker = plugin.variables().get("npc-runtime", owner, "last-attacker", "");
        try { if (!lastAttacker.isBlank()) npc.setLastAttacker(UUID.fromString(lastAttacker)); }
        catch (IllegalArgumentException ignored) { }
        npc.setMaxHealth(Double.parseDouble(plugin.variables().get("npc-runtime", owner, "max-health", "20.0")));
        npc.setHealth(Double.parseDouble(plugin.variables().get("npc-runtime", owner, "health", "20.0")));
        npc.setSpeed(Double.parseDouble(plugin.variables().get("npc-runtime", owner, "speed", String.valueOf(plugin.getConfig().getDouble("movement.default-speed", 4.0)))));
    }

    private void clearRuntimeState(ManagedNPC npc) {
        String owner = net.amethystesmp.anpc.storage.VariableStore.localOwner(
                npc.id(), npc.location().getWorld().getName());
        plugin.variables().delete("npc-runtime", owner, "primary-state");
        plugin.variables().delete("npc-runtime", owner, "secondary-states");
        plugin.variables().delete("npc-runtime", owner, "follow-target");
        plugin.variables().delete("npc-runtime", owner, "follow-min");
        plugin.variables().delete("npc-runtime", owner, "follow-max");
        plugin.variables().delete("npc-runtime", owner, "hostile");
        plugin.variables().delete("npc-runtime", owner, "target-selector");
        plugin.variables().delete("npc-runtime", owner, "last-attacker");
        plugin.variables().delete("npc-runtime", owner, "health");
        plugin.variables().delete("npc-runtime", owner, "max-health");
        plugin.variables().delete("npc-runtime", owner, "speed");
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

    private void backupDeletedNPC(NPCDefinition definition, List<String> recoveryWorlds) {
        String path = "npcs." + definition.id() + ".";
        deletedYaml.set(path + "display-name", definition.displayName());
        deletedYaml.set(path + "type", definition.type());
        deletedYaml.set(path + "world", definition.worlds());
        deletedYaml.set(path + "x", definition.x());
        deletedYaml.set(path + "y", definition.y());
        deletedYaml.set(path + "z", definition.z());
        deletedYaml.set(path + "yaw", definition.yaw());
        deletedYaml.set(path + "pitch", definition.pitch());
        deletedYaml.set(path + "skin", definition.skin());
        deletedYaml.set(path + "recovery-worlds", recoveryWorlds);
        deletedYaml.set(path + "deleted-at", System.currentTimeMillis());
        saveDeleted();
    }

    private void purgeExpiredDeletedNPCs() {
        if (deletedYaml == null) return;
        ConfigurationSection root = deletedYaml.getConfigurationSection("npcs");
        if (root == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (String id : new ArrayList<>(root.getKeys(false))) {
            if (root.getLong(id + ".deleted-at", 0L) < cutoff) deletedYaml.set("npcs." + id, null);
        }
        saveDeleted();
    }

    private void saveDeleted() {
        try { deletedYaml.save(deletedFile); }
        catch (IOException exception) { plugin.getLogger().severe("Ecriture deleted-npcs.yml impossible : " + exception.getMessage()); }
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
