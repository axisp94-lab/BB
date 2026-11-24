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
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NPCManager implements Listener {
    private final ANPCPlugin plugin;
    private final Map<String, ManagedNPC> npcs = new ConcurrentHashMap<>();
    private final Map<String, NPCDefinition> definitions = new ConcurrentHashMap<>();
    private BukkitTask visibilityTask;
    private File file;
    private YamlConfiguration yaml;
    private YamlConfiguration pendingReloadYaml;
    private BukkitTask modelRetryTask;
    private int modelRetryAttempts;
    private final Set<String> warnedMissingModels = ConcurrentHashMap.newKeySet();
    private static final int MAX_MODEL_RETRY_ATTEMPTS = 60;

    public NPCManager(ANPCPlugin plugin) {
        this.plugin = plugin;
    }

    public void validateReload() {
        file = new File(plugin.getDataFolder(), "npc.yml");
        YamlConfiguration candidate = readYaml();
        ConfigurationSection root = candidate.getConfigurationSection("npcs");
        if (root != null) {
            Set<String> validatedIds = new HashSet<>();
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    throw new IllegalStateException("npcs." + id + " doit être une section YAML.");
                }
                String normalizedId = id.toLowerCase(Locale.ROOT);
                if (!validatedIds.add(normalizedId)) {
                    throw new IllegalStateException("Identifiant NPC dupliqué (la casse est ignorée) : " + id);
                }
                try {
                    NPCDefinition.read(id, section);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Définition NPC invalide '"
                            + id + "' : " + exception.getMessage(), exception);
                }
            }
        }
        pendingReloadYaml = candidate;
    }

    private YamlConfiguration readYaml() {
        YamlConfiguration loadedYaml = new YamlConfiguration();
        if (file.isFile()) {
            try {
                loadedYaml.load(file);
            } catch (IOException | InvalidConfigurationException exception) {
                plugin.getLogger().severe("Rechargement annulé : npc.yml est illisible ou invalide. "
                        + "Les NPC actuellement chargés sont conservés.");
                throw new IllegalStateException("Impossible de lire npc.yml.", exception);
            }
        }
        return loadedYaml;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "npc.yml");
        YamlConfiguration loadedYaml = pendingReloadYaml != null ? pendingReloadYaml : readYaml();
        pendingReloadYaml = null;

        // Parse the complete YAML before touching live NPCs. A malformed file
        // must never erase the currently working NPC set during /anpc reload.
        stopLoadedNpcs();
        yaml = loadedYaml;

        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    plugin.getLogger().warning("Entrée NPC ignorée : 'npcs." + id + "' n'est pas une section YAML.");
                    continue;
                }
                try {
                    String normalizedId = id.toLowerCase(Locale.ROOT);
                    if (definitions.containsKey(normalizedId)) {
                        plugin.getLogger().warning("Définition NPC '" + id
                                + "' ignorée : un identifiant équivalent existe déjà (la casse est ignorée).");
                        continue;
                    }
                    NPCDefinition definition = NPCDefinition.read(id, section);
                    definitions.put(normalizedId, definition);
                    createFrom(definition);
                } catch (Exception e) {
                    plugin.getLogger().warning("NPC '" + id + "' non chargé : " + e.getMessage());
                }
            }
        }

        int loadedInstances = npcs.size();
        plugin.getLogger().info("Définitions NPC lues : " + definitions.size()
                + " ; instances chargées : " + loadedInstances + ".");
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVisibility, 10L, 10L);
    }

    private void createFrom(NPCDefinition definition) {
        boolean allWorlds = definition.worlds().stream().anyMatch(world -> world.equalsIgnoreCase("all"));
        List<World> targets = new ArrayList<>();

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
        for (World world : targets) createInWorld(definition, world);
    }

    private void createInWorld(NPCDefinition definition, World world) {
        if (world == null) return;
        boolean configured = definition.worlds().stream()
                .anyMatch(name -> name.equalsIgnoreCase("all") || name.equalsIgnoreCase(world.getName()));
        if (!configured) return;

        String key = instanceKey(definition.id(), world);
        ManagedNPC existing = npcs.get(key);
        if (existing != null) {
            if (existing.isActive()) return;
            if (npcs.remove(key, existing)) {
                closeManagedNPC(existing);
            }
        }

        ManagedNPC managed = null;
        try {
            if (isMythicMob(definition.type())) {
                String mythicId = definition.type().substring("mythicmob:".length()).trim();
                Entity entity = MythicMobAdapter.spawn(plugin, mythicId, definition.location(world), definition.displayName());
                if (entity == null) return;
                managed = new ManagedNPC(definition, entity, () -> MythicMobAdapter.despawn(plugin, entity), plugin.getLogger());
            } else {
                ModelRenderer renderer = resolveRenderer(definition);
                if (renderer == null) {
                    if (warnedMissingModels.add(key)) {
                        plugin.getLogger().warning("Modèle BetterModel pas encore disponible pour " + definition.id()
                                + " : " + definition.type() + ". Nouvel essai automatique pendant le démarrage.");
                    }
                    scheduleModelRetry();
                    return;
                }
                managed = new ManagedNPC(definition, renderer, definition.location(world), skinProfile(definition), plugin.getLogger());
                warnedMissingModels.remove(key);
            }

            ManagedNPC previous = npcs.put(key, managed);
            if (previous != null) {
                closeManagedNPC(previous);
            }
            notifyNpcLoaded(managed);
        } catch (RuntimeException | LinkageError exception) {
            if (managed != null && npcs.remove(key, managed)) {
                if (plugin.scripts() != null) {
                    try {
                        plugin.scripts().onNpcUnloaded(managed);
                    } catch (RuntimeException | LinkageError cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    managed.close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            plugin.getLogger().warning("Impossible de créer le NPC '" + definition.id() + "' dans " + world.getName()
                    + " : " + exception.getMessage());
        }
    }

    private void scheduleModelRetry() {
        if (modelRetryTask != null) return;
        modelRetryAttempts = 0;
        modelRetryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::retryPendingModels, 20L, 20L);
    }

    private void retryPendingModels() {
        for (NPCDefinition definition : definitions.values()) createFrom(definition);

        boolean pending = false;
        for (NPCDefinition definition : definitions.values()) {
            if (isMythicMob(definition.type())) continue;
            for (World world : Bukkit.getWorlds()) {
                boolean configured = definition.worlds().stream()
                        .anyMatch(name -> name.equalsIgnoreCase("all") || name.equalsIgnoreCase(world.getName()));
                if (configured && !npcs.containsKey(instanceKey(definition.id(), world))) {
                    pending = true;
                    break;
                }
            }
            if (pending) break;
        }

        if (!pending) {
            cancelModelRetry();
            return;
        }

        modelRetryAttempts++;
        if (modelRetryAttempts >= MAX_MODEL_RETRY_ATTEMPTS) {
            for (NPCDefinition definition : definitions.values()) {
                if (isMythicMob(definition.type())) continue;
                for (World world : Bukkit.getWorlds()) {
                    boolean configured = definition.worlds().stream()
                            .anyMatch(name -> name.equalsIgnoreCase("all") || name.equalsIgnoreCase(world.getName()));
                    if (!configured || npcs.containsKey(instanceKey(definition.id(), world))) continue;
                    String available = definition.type().equalsIgnoreCase("player")
                            || definition.type().toLowerCase(Locale.ROOT).startsWith("limb:")
                            ? BetterModel.limbKeys().toString() : BetterModel.modelKeys().toString();
                    plugin.getLogger().severe("Modèle BetterModel toujours indisponible pour " + definition.id()
                            + " après 60 secondes. Modèles enregistrés : " + available);
                }
            }
            cancelModelRetry();
        }
    }

    private void cancelModelRetry() {
        if (modelRetryTask != null) {
            modelRetryTask.cancel();
            modelRetryTask = null;
        }
        modelRetryAttempts = 0;
    }

    private double configDouble(String path, double fallback, double minimum, double maximum) {
        double value = plugin.getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value)) value = fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String instanceKey(String id, World world) {
        return id.toLowerCase(Locale.ROOT) + "@" + world.getName().toLowerCase(Locale.ROOT);
    }

    private void notifyNpcLoaded(ManagedNPC npc) {
        if (npc == null || plugin.scripts() == null) return;
        try {
            plugin.scripts().onNpcLoaded(npc);
        } catch (RuntimeException | LinkageError exception) {
            // A broken script or scheduler must not make a successfully created
            // visual NPC disappear or roll back its persisted definition.
            plugin.getLogger().warning("Démarrage des scripts impossible pour le NPC '" + npc.id()
                    + "' : " + exception.getMessage());
        }
    }

    private void closeManagedNPC(ManagedNPC npc) {
        if (npc == null) return;
        if (plugin.scripts() != null) {
            try {
                plugin.scripts().onNpcUnloaded(npc);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning("Nettoyage des tâches du NPC '" + npc.id()
                        + "' impossible : " + exception.getMessage());
            }
        }
        try {
            npc.close();
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("Fermeture du NPC '" + npc.id()
                    + "' incomplète : " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            double range = configDouble("tracking-range", 64.0, 0.0, 512.0);
            double rangeSquared = range * range;
            for (ManagedNPC npc : npcs.values()) {
                Location location = npc.location();
                if (location.getWorld() != null && player.getWorld().equals(location.getWorld())
                        && player.getLocation().distanceSquared(location) <= rangeSquared) {
                    npc.resync(player);
                }
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (ManagedNPC npc : npcs.values()) npc.hide(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        var chunk = event.getChunk();
        List<String> keys = npcs.entrySet().stream()
                .filter(entry -> {
                    Location location = entry.getValue().location();
                    return location.getWorld() != null
                            && location.getWorld().equals(chunk.getWorld())
                            && (location.getBlockX() >> 4) == chunk.getX()
                            && (location.getBlockZ() >> 4) == chunk.getZ();
                })
                .map(Map.Entry::getKey)
                .toList();

        for (String key : keys) {
            ManagedNPC npc = npcs.remove(key);
            if (npc == null) continue;
            closeManagedNPC(npc);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!chunk.isLoaded()) return;
            for (NPCDefinition definition : definitions.values()) {
                if (!definition.appliesTo(chunk.getWorld())) continue;
                Location configuredLocation = definition.location(chunk.getWorld());
                if ((configuredLocation.getBlockX() >> 4) == chunk.getX()
                        && (configuredLocation.getBlockZ() >> 4) == chunk.getZ()) {
                    createInWorld(definition, chunk.getWorld());
                }
            }
        });
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        for (NPCDefinition definition : definitions.values()) createInWorld(definition, event.getWorld());
        refreshVisibility();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World unloading = event.getWorld();
        List<String> keys = npcs.entrySet().stream()
                .filter(entry -> unloading.equals(entry.getValue().location().getWorld()))
                .map(Map.Entry::getKey)
                .toList();
        for (String key : keys) {
            ManagedNPC npc = npcs.remove(key);
            if (npc != null) {
                closeManagedNPC(npc);
            }
        }
    }

    private boolean isMythicMob(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("mythicmob:");
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
        double range = configDouble("tracking-range", 64.0, 0.0, 512.0);
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

    public Collection<NPCDefinition> definitions() {
        List<NPCDefinition> result = new ArrayList<>(definitions.values());
        result.sort(Comparator.comparing(NPCDefinition::id, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }

    public int size() {
        return npcs.values().stream().map(ManagedNPC::id).map(id -> id.toLowerCase(Locale.ROOT)).distinct().toList().size();
    }

    public boolean create(String id, String type, Location location) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+") || type == null || type.isBlank()
                || type.trim().equalsIgnoreCase("mythicmob:") || type.trim().equalsIgnoreCase("limb:")
                || location == null || location.getWorld() == null
                || definitions.containsKey(id.toLowerCase(Locale.ROOT)) || get(id) != null) {
            return false;
        }

        NPCDefinition definition = new NPCDefinition(id, id, type, List.of(location.getWorld().getName()),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), "");

        ManagedNPC managed = null;
        try {
            if (isMythicMob(type)) {
                String mythicId = type.substring("mythicmob:".length()).trim();
                Entity entity = MythicMobAdapter.spawn(plugin, mythicId, location, definition.displayName());
                if (entity == null) return false;
                managed = new ManagedNPC(definition, entity, () -> MythicMobAdapter.despawn(plugin, entity), plugin.getLogger());
            } else {
                ModelRenderer renderer = resolveRenderer(type);
                if (renderer == null) return false;
                managed = new ManagedNPC(definition, renderer, location, skinProfile(definition), plugin.getLogger());
            }

            npcs.put(instanceKey(id, location.getWorld()), managed);
            definitions.put(id.toLowerCase(Locale.ROOT), definition);
            if (!saveDefinition(definition)) {
                npcs.remove(instanceKey(id, location.getWorld()), managed);
                closeManagedNPC(managed);
                definitions.remove(id.toLowerCase(Locale.ROOT));
                plugin.getLogger().severe("NPC '" + id + "' créé en mémoire mais retiré : sa sauvegarde a échoué.");
                return false;
            }
            notifyNpcLoaded(managed);
            try {
                refreshVisibility();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning("Actualisation de visibilité impossible après création du NPC '"
                        + id + "' : " + exception.getMessage());
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            if (managed != null) {
                npcs.remove(instanceKey(id, location.getWorld()), managed);
                closeManagedNPC(managed);
            }
            definitions.remove(id.toLowerCase(Locale.ROOT));
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
        String normalizedId = id.toLowerCase(Locale.ROOT);
        List<String> yamlIds = findYamlIds(id);
        boolean knownDefinition = definitions.containsKey(normalizedId) || !yamlIds.isEmpty();
        if (matches.isEmpty() && !knownDefinition) return false;

        if (!yamlIds.isEmpty() && yaml != null) {
            YamlConfiguration updatedYaml = copyYaml();
            yamlIds.forEach(yamlId -> updatedYaml.set("npcs." + yamlId, null));
            if (!commitYaml(updatedYaml)) return false;
        }

        for (String key : matches) {
            ManagedNPC npc = npcs.remove(key);
            if (npc != null) {
                closeManagedNPC(npc);
            }
        }
        definitions.remove(normalizedId);
        return true;
    }

    public boolean rename(String id, String displayName) {
        if (id == null || displayName == null || displayName.isBlank()) return false;
        List<ManagedNPC> matches = npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id))
                .toList();
        String normalizedId = id.toLowerCase(Locale.ROOT);
        NPCDefinition currentDefinition = definitions.get(normalizedId);
        if (matches.isEmpty() && currentDefinition == null) return false;

        NPCDefinition base = currentDefinition != null ? currentDefinition : matches.get(0).definition();
        NPCDefinition updated = new NPCDefinition(base.id(), displayName, base.type(), base.worlds(),
                base.x(), base.y(), base.z(), base.yaw(), base.pitch(), base.skin());
        if (!saveDefinition(updated)) return false;
        matches.forEach(npc -> npc.updateDefinition(updated));
        return true;
    }

    public boolean center(String id) {
        if (id == null) return false;
        List<ManagedNPC> matches = npcs.values().stream()
                .filter(npc -> npc.id().equalsIgnoreCase(id))
                .toList();
        if (matches.isEmpty()) {
            NPCDefinition definition = definitions.get(id.toLowerCase(Locale.ROOT));
            if (definition == null) return false;
            NPCDefinition centered = new NPCDefinition(definition.id(), definition.displayName(), definition.type(),
                    definition.worlds(), Math.floor(definition.x()) + 0.5, definition.y(),
                    Math.floor(definition.z()) + 0.5, definition.yaw(), definition.pitch(), definition.skin());
            return saveDefinition(centered);
        }

        List<Location> previousLocations = matches.stream().map(ManagedNPC::location).toList();
        for (ManagedNPC npc : matches) {
            Location centered = npc.location();
            centered.setX(centered.getBlockX() + 0.5);
            centered.setZ(centered.getBlockZ() + 0.5);
            npc.move(centered);
        }
        if (persistPosition(matches.get(0), matches.get(0).location())) return true;
        for (int index = 0; index < matches.size(); index++) {
            matches.get(index).move(previousLocations.get(index));
        }
        return false;
    }

    public void teleport(String id, Location location) {
        ManagedNPC npc = get(id, location == null ? null : location.getWorld());
        if (npc == null || location == null || location.getWorld() == null) return;
        Location previousLocation = npc.location();
        npc.move(location);
        if (!persistPosition(npc, location)) npc.move(previousLocation);
        refreshVisibility();
    }

    private boolean persistPosition(ManagedNPC npc, Location location) {
        NPCDefinition current = npc.definition();
        NPCDefinition updated = new NPCDefinition(current.id(), current.displayName(), current.type(),
                current.worlds(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), current.skin());
        if (!saveDefinition(updated)) return false;
        npcs.values().stream()
                .filter(instance -> instance.id().equalsIgnoreCase(updated.id()))
                .forEach(instance -> instance.updateDefinition(updated));
        return true;
    }

    private List<String> findYamlIds(String id) {
        if (yaml == null) return List.of();
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) return List.of();
        return root.getKeys(false).stream().filter(key -> key.equalsIgnoreCase(id)).toList();
    }

    private boolean saveDefinition(NPCDefinition definition) {
        if (yaml == null) return false;
        YamlConfiguration updatedYaml = copyYaml();
        String path = "npcs." + definition.id() + ".";
        updatedYaml.set(path + "display-name", definition.displayName());
        updatedYaml.set(path + "type", definition.type());
        updatedYaml.set(path + "world", definition.worlds());
        updatedYaml.set(path + "x", definition.x());
        updatedYaml.set(path + "y", definition.y());
        updatedYaml.set(path + "z", definition.z());
        updatedYaml.set(path + "yaw", definition.yaw());
        updatedYaml.set(path + "pitch", definition.pitch());
        if (definition.skin().isBlank()) updatedYaml.set(path + "skin", null);
        else updatedYaml.set(path + "skin", definition.skin());
        if (!commitYaml(updatedYaml)) return false;
        definitions.put(definition.id().toLowerCase(Locale.ROOT), definition);
        return true;
    }

    private YamlConfiguration copyYaml() {
        YamlConfiguration copy = new YamlConfiguration();
        try {
            copy.loadFromString(yaml.saveToString());
        } catch (InvalidConfigurationException exception) {
            throw new IllegalStateException("Impossible de copier npc.yml avant modification.", exception);
        }
        return copy;
    }

    private boolean commitYaml(YamlConfiguration updatedYaml) {
        YamlConfiguration previousYaml = yaml;
        yaml = updatedYaml;
        if (save()) return true;
        yaml = previousYaml;
        return false;
    }

    private boolean save() {
        if (yaml == null || file == null) {
            plugin.getLogger().severe("Sauvegarde NPC impossible : npc.yml n'est pas initialisé.");
            return false;
        }
        Path target = file.toPath();
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Écriture de npc.yml impossible : " + e.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                plugin.getLogger().warning("Impossible de supprimer le fichier temporaire npc.yml : "
                        + cleanupFailure.getMessage());
            }
            return false;
        }
    }

    public void reload() {
        load();
    }

    private void stopLoadedNpcs() {
        cancelModelRetry();
        warnedMissingModels.clear();
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
        npcs.values().forEach(npc -> {
            closeManagedNPC(npc);
        });
        npcs.clear();
        definitions.clear();
    }

    public void shutdown() {
        // Les définitions sont déjà sauvegardées à chaque modification ; cette
        // dernière écriture sécurise également l'arrêt normal du serveur.
        save();
        stopLoadedNpcs();
    }
}
