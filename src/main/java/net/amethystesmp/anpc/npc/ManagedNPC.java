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

public final class ManagedNPC {
    private NPCDefinition definition;
    private final DummyTracker tracker;
    private final Set<String> viewers = new HashSet<>();
    private Location current;
    private final Interaction hitbox;
    private final Entity backingEntity;
    private final Runnable backingCleanup;
    private String animation = "idle";
    private boolean closed;

    public ManagedNPC(NPCDefinition definition, ModelRenderer renderer, Location location) {
        this(definition, renderer, location, null);
    }

    public ManagedNPC(NPCDefinition definition, ModelRenderer renderer, Location location,
                      ModelProfile.Uncompleted profile) {
        this.definition = definition;
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
            if (createdTracker != null && !createdTracker.isClosed()) {
                try {
                    createdTracker.close();
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
        this.tracker = createdTracker;
        this.hitbox = createdHitbox;
    }

    public ManagedNPC(NPCDefinition definition, Entity backingEntity) {
        this(definition, backingEntity, () -> { });
    }

    public ManagedNPC(NPCDefinition definition, Entity backingEntity, Runnable backingCleanup) {
        this.definition = definition;
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
        if (backingEntity != null) return backingEntity.isValid();
        return tracker != null && !tracker.isClosed();
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
        if (tracker == null || closed || tracker.isClosed() || !player.getWorld().equals(current.getWorld())) return;
        if (viewers.add(player.getUniqueId().toString())) tracker.spawn(BukkitAdapter.adapt(player));
    }

    public void hide(Player player) {
        if (tracker != null && viewers.remove(player.getUniqueId().toString()) && !tracker.isClosed()) {
            tracker.remove(BukkitAdapter.adapt(player));
        }
    }

    public void resync(Player player) {
        if (tracker == null || closed || tracker.isClosed()) return;
        viewers.remove(player.getUniqueId().toString());
        tracker.remove(BukkitAdapter.adapt(player));
        if (player.getWorld().equals(current.getWorld())) {
            tracker.spawn(BukkitAdapter.adapt(player));
            viewers.add(player.getUniqueId().toString());
        }
    }

    public void move(Location target) {
        if (closed || target == null || target.getWorld() == null) return;
        if (current.getWorld() != null && !current.getWorld().equals(target.getWorld())) return;
        if (backingEntity != null) {
            if (backingEntity.isValid() && backingEntity.teleport(target)) current = backingEntity.getLocation();
            return;
        }
        if (tracker == null || tracker.isClosed()) return;
        current = target.clone();
        tracker.location(BukkitAdapter.adapt(current));
        if (hitbox != null && hitbox.isValid()) hitbox.teleport(current);
    }

    public boolean animate(String name) {
        if (closed || tracker == null || tracker.isClosed()) return false;
        boolean ok = tracker.animate(name, AnimationModifier.DEFAULT);
        if (ok) animation = name;
        return ok;
    }

    public String animation() { return animation; }
    public Entity hitbox() { return backingEntity != null ? backingEntity : hitbox; }

    public void close() {
        if (closed) return;
        closed = true;
        if (tracker != null && !tracker.isClosed()) tracker.close();
        if (hitbox != null && hitbox.isValid()) hitbox.remove();
        if (backingEntity != null) {
            try {
                backingCleanup.run();
            } catch (RuntimeException ignored) {
            }
            if (backingEntity.isValid()) backingEntity.remove();
        }
        viewers.clear();
    }
}
