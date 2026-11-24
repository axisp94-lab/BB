package net.amethystesmp.anpc.npc;

import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.profile.ModelProfile;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public final class ManagedNPC {
    private NPCDefinition definition;
    private final DummyTracker tracker;
    private final Set<String> viewers = new HashSet<>();
    private final Set<String> warnedViewerFailures = new HashSet<>();
    private final Set<String> warnedTrackerFailures = new HashSet<>();
    private Location current;
    private final Interaction hitbox;
    private final Entity backingEntity;
    private final Runnable backingCleanup;
    private final Logger logger;
    private String animation = "idle";
    private boolean closed;

    public ManagedNPC(NPCDefinition definition, ModelRenderer renderer, Location location) {
        this(definition, renderer, location, null, Logger.getLogger("AmethyysNPCs"));
    }

    public ManagedNPC(NPCDefinition definition, ModelRenderer renderer, Location location,
                      ModelProfile.Uncompleted profile) {
        this(definition, renderer, location, profile, Logger.getLogger("AmethyysNPCs"));
    }

    public ManagedNPC(NPCDefinition definition, ModelRenderer renderer, Location location,
                      ModelProfile.Uncompleted profile, Logger logger) {
        this.definition = definition;
        this.logger = logger == null ? Logger.getLogger("AmethyysNPCs") : logger;
        this.current = location.clone();
        this.backingEntity = null;
        this.backingCleanup = () -> { };

        DummyTracker createdTracker = null;
        Interaction createdHitbox;
        try {
            createdTracker = profile == null
                    ? renderer.create(BukkitAdapter.adapt(location))
                    : renderer.create(BukkitAdapter.adapt(location), profile);
            if (createdTracker == null) {
                throw new IllegalStateException("BetterModel n'a pas créé de tracker pour le NPC " + definition.id());
            }
            createdHitbox = location.getWorld().spawn(location, Interaction.class, entity -> {
                entity.setInteractionWidth(0.8f);
                entity.setInteractionHeight(1.8f);
                entity.setResponsive(true);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.addScoreboardTag("anpc:" + definition.id().toLowerCase(java.util.Locale.ROOT));
            });
        } catch (RuntimeException | Error exception) {
            if (createdTracker != null) {
                try {
                    if (!createdTracker.isClosed()) createdTracker.close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
        this.tracker = createdTracker;
        this.hitbox = createdHitbox;
    }

    public ManagedNPC(NPCDefinition definition, Entity backingEntity) {
        this(definition, backingEntity, () -> { }, Logger.getLogger("AmethyysNPCs"));
    }

    public ManagedNPC(NPCDefinition definition, Entity backingEntity, Runnable backingCleanup) {
        this(definition, backingEntity, backingCleanup, Logger.getLogger("AmethyysNPCs"));
    }

    public ManagedNPC(NPCDefinition definition, Entity backingEntity, Runnable backingCleanup, Logger logger) {
        this.definition = definition;
        this.logger = logger == null ? Logger.getLogger("AmethyysNPCs") : logger;
        this.backingEntity = backingEntity;
        this.backingCleanup = backingCleanup == null ? () -> { } : backingCleanup;
        this.current = backingEntity.getLocation();
        this.tracker = null;
        this.hitbox = null;
        backingEntity.addScoreboardTag("anpc:" + definition.id().toLowerCase(java.util.Locale.ROOT));
    }

    public String id() { return definition.id(); }
    public NPCDefinition definition() { return definition; }
    public Location location() {
        if (backingEntity != null && backingEntity.isValid()) return backingEntity.getLocation();
        return current.clone();
    }
    public DummyTracker tracker() { return tracker; }
    public boolean isActive() {
        if (closed) return false;
        try {
            if (backingEntity != null) return backingEntity.isValid();
            return tracker != null && !tracker.isClosed();
        } catch (RuntimeException | LinkageError exception) {
            warnTrackerFailure("is-active", exception);
            return false;
        }
    }
    public void updateDefinition(NPCDefinition next) {
        if (next == null) return;
        definition = next;
        if (backingEntity != null && backingEntity.isValid()) {
            backingEntity.setCustomName(ChatColor.translateAlternateColorCodes('&', next.displayName()));
            backingEntity.setCustomNameVisible(true);
        }
    }

    public void show(Player player) {
        if (player == null || tracker == null || closed) return;
        String viewerId = player.getUniqueId().toString();
        try {
            if (tracker.isClosed() || !player.getWorld().equals(current.getWorld()) || viewers.contains(viewerId)) return;
            // Register only after BetterModel confirms that the spawn call returned.
            // A failed spawn must remain eligible for the next visibility refresh.
            tracker.spawn(BukkitAdapter.adapt(player));
            viewers.add(viewerId);
            warnedViewerFailures.remove("spawn:" + viewerId);
        } catch (RuntimeException | LinkageError exception) {
            viewers.remove(viewerId);
            warnViewerFailure("spawn", player, exception);
        }
    }

    public void hide(Player player) {
        if (player == null || tracker == null) return;
        String viewerId = player.getUniqueId().toString();
        if (!viewers.remove(viewerId)) return;
        try {
            if (!tracker.isClosed()) tracker.remove(BukkitAdapter.adapt(player));
            warnedViewerFailures.remove("remove:" + viewerId);
        } catch (RuntimeException | LinkageError exception) {
            warnViewerFailure("remove", player, exception);
        }
    }

    public void resync(Player player) {
        if (player == null || tracker == null || closed) return;
        String viewerId = player.getUniqueId().toString();
        viewers.remove(viewerId);
        try {
            if (!tracker.isClosed()) tracker.remove(BukkitAdapter.adapt(player));
        } catch (RuntimeException | LinkageError exception) {
            warnViewerFailure("resync-remove", player, exception);
        }
        try {
            if (!tracker.isClosed() && player.getWorld().equals(current.getWorld())) {
                tracker.spawn(BukkitAdapter.adapt(player));
                viewers.add(viewerId);
                warnedViewerFailures.remove("spawn:" + viewerId);
            }
        } catch (RuntimeException | LinkageError exception) {
            viewers.remove(viewerId);
            warnViewerFailure("resync-spawn", player, exception);
        }
    }

    private void warnViewerFailure(String operation, Player player, Throwable exception) {
        String key = operation + ":" + player.getUniqueId();
        if (warnedViewerFailures.add(key)) {
            logger.warning("Opération BetterModel '" + operation + "' impossible pour le NPC '" + id()
                    + "' au joueur " + player.getName() + " : " + exception.getMessage());
        }
    }

    public void move(Location target) {
        if (closed || target == null || target.getWorld() == null) return;
        if (current.getWorld() != null && !current.getWorld().equals(target.getWorld())) return;
        try {
            if (backingEntity != null) {
                if (backingEntity.isValid() && backingEntity.teleport(target)) current = backingEntity.getLocation();
                return;
            }
            if (tracker == null || tracker.isClosed()) return;
            Location next = target.clone();
            tracker.location(BukkitAdapter.adapt(next));
            if (hitbox != null && hitbox.isValid()) hitbox.teleport(next);
            current = next;
            warnedTrackerFailures.remove("move");
        } catch (RuntimeException | LinkageError exception) {
            warnTrackerFailure("move", exception);
        }
    }

    public boolean animate(String name) {
        if (closed || tracker == null) return false;
        try {
            if (tracker.isClosed()) return false;
            boolean ok = tracker.animate(name, AnimationModifier.DEFAULT);
            if (ok) {
                animation = name;
                warnedTrackerFailures.remove("animate");
            }
            return ok;
        } catch (RuntimeException | LinkageError exception) {
            warnTrackerFailure("animate", exception);
            return false;
        }
    }

    private void warnTrackerFailure(String operation, Throwable exception) {
        if (warnedTrackerFailures.add(operation)) {
            logger.warning("Opération BetterModel '" + operation + "' impossible pour le NPC '"
                    + id() + "' : " + exception.getMessage());
        }
    }

    public String animation() { return animation; }
    public Entity hitbox() { return backingEntity != null ? backingEntity : hitbox; }

    public void close() {
        if (closed) return;
        closed = true;

        if (tracker != null) {
            try {
                if (!tracker.isClosed()) tracker.close();
            } catch (RuntimeException | LinkageError exception) {
                logger.warning("Fermeture du tracker BetterModel impossible pour le NPC '" + id()
                        + "' : " + exception.getMessage());
                // Continue : la hitbox et l'entité de support doivent être nettoyées
                // même si BetterModel échoue pendant la fermeture du tracker.
            }
        }
        if (hitbox != null && hitbox.isValid()) {
            try {
                hitbox.remove();
            } catch (RuntimeException exception) {
                // La fermeture des autres ressources reste prioritaire.
            }
        }
        if (backingEntity != null) {
            try {
                backingCleanup.run();
            } catch (RuntimeException exception) {
                // L'entité Bukkit est également retirée juste après.
            }
            try {
                if (backingEntity.isValid()) backingEntity.remove();
            } catch (RuntimeException exception) {
                // Le serveur termine la fermeture du plugin même si l'entité est déjà invalide.
            }
        }
        viewers.clear();
        warnedViewerFailures.clear();
        warnedTrackerFailures.clear();
    }
}
