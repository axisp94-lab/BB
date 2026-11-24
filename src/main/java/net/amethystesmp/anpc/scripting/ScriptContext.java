package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ScriptContext {
    private static final int MAX_INSTRUCTIONS = 10_000;

    static final class ExecutionBudget {
        private int remaining = MAX_INSTRUCTIONS;
        private boolean warningEmitted;
        private boolean exhausted;

        boolean consume() {
            if (remaining <= 0) {
                exhausted = true;
                return false;
            }
            remaining--;
            return true;
        }

        boolean exhausted() {
            return exhausted;
        }

        boolean markWarningEmitted() {
            if (warningEmitted) return false;
            warningEmitted = true;
            return true;
        }
    }

    public static final class LoopState {
        private boolean stopped;
        private boolean skipped;

        public boolean stopped() { return stopped; }
        public boolean skipped() { return skipped; }
        public void stop() { stopped = true; }
        public void skip() { skipped = true; }
        public void clearSkip() { skipped = false; }
    }

    private final ManagedNPC npc;
    private final Player player;
    private final ManagedNPC eventNpc;
    private final Entity eventEntity;
    private final Map<String, Object> locals = new HashMap<>();
    private final Set<String> persistentVariables = new HashSet<>();
    private final Set<String> dirtyPersistentVariables = new HashSet<>();
    private final Deque<LoopState> loops = new ArrayDeque<>();
    private Runnable completion = () -> { };
    private boolean finished;
    private final ExecutionBudget executionBudget;

    public ScriptContext(ManagedNPC npc, Player player) {
        this(npc, player, null, player, new ExecutionBudget());
    }

    public ScriptContext(ManagedNPC npc, Player player, ManagedNPC eventNpc) {
        this(npc, player, eventNpc, player, new ExecutionBudget());
    }

    public ScriptContext(ManagedNPC npc, Player player, ManagedNPC eventNpc, Entity eventEntity) {
        this(npc, player, eventNpc, eventEntity, new ExecutionBudget());
    }

    ScriptContext(ManagedNPC npc, Player player, ManagedNPC eventNpc, ExecutionBudget executionBudget) {
        this(npc, player, eventNpc, player, executionBudget);
    }

    ScriptContext(ManagedNPC npc, Player player, ManagedNPC eventNpc, Entity eventEntity,
                  ExecutionBudget executionBudget) {
        this.npc = npc;
        this.player = player;
        this.eventNpc = eventNpc;
        this.eventEntity = eventEntity;
        this.executionBudget = executionBudget;
    }

    boolean consumeInstruction() {
        return executionBudget.consume();
    }

    boolean markExecutionBudgetWarning() {
        return executionBudget.markWarningEmitted();
    }

    boolean executionBudgetExhausted() {
        return executionBudget.exhausted();
    }

    ExecutionBudget executionBudget() {
        return executionBudget;
    }

    public ManagedNPC npc() { return npc; }
    public Player player() { return player; }
    public ManagedNPC eventNpc() { return eventNpc; }
    public Entity eventEntity() { return eventEntity; }
    public Map<String, Object> locals() { return locals; }

    void markPersistentVariable(String name) {
        if (name == null) return;
        String normalized = name.toLowerCase(Locale.ROOT);
        persistentVariables.add(normalized);
        dirtyPersistentVariables.add(normalized);
    }

    Set<String> persistentVariables() {
        return Set.copyOf(persistentVariables);
    }

    Set<String> dirtyPersistentVariables() {
        return Set.copyOf(dirtyPersistentVariables);
    }

    void inheritPersistentVariables(ScriptContext other) {
        persistentVariables.addAll(other.persistentVariables);
    }

    void setCompletion(Runnable completion) {
        this.completion = completion == null ? () -> { } : completion;
    }

    void finish() {
        if (finished) return;
        finished = true;
        completion.run();
    }

    public LoopState pushLoop() {
        if (loops.size() >= 16) return null;
        LoopState state = new LoopState();
        loops.push(state);
        return state;
    }

    public int loopDepth() {
        return loops.size();
    }

    public LoopState currentLoop() {
        return loops.peek();
    }

    public void popLoop() {
        if (!loops.isEmpty()) loops.pop();
    }

    public boolean loopControlRequested() {
        LoopState state = currentLoop();
        return state != null && (state.stopped() || state.skipped());
    }

    public Object value(String key) {
        String normalized = key.trim();
        if (normalized.equalsIgnoreCase("event entity")) return eventEntity;
        if (normalized.equalsIgnoreCase("player")) return player;
        if (normalized.equalsIgnoreCase("event npc") || normalized.equalsIgnoreCase("other npc")) return eventNpc;
        if (normalized.equalsIgnoreCase("name of event entity") && eventEntity != null) {
            if (eventEntity instanceof Player selectedPlayer) return selectedPlayer.getName();
            String customName = eventEntity.getCustomName();
            return customName == null || customName.isBlank()
                    ? eventEntity.getType().name().toLowerCase(Locale.ROOT) : customName;
        }
        if (normalized.equalsIgnoreCase("location of event entity") && eventEntity != null) return eventEntity.getLocation();
        if (normalized.equalsIgnoreCase("type of event entity") && eventEntity != null) {
            return eventEntity.getType().name();
        }
        if (normalized.equalsIgnoreCase("location of player") && player != null) return player.getLocation();
        if (normalized.equalsIgnoreCase("name of event npc") && eventNpc != null) return eventNpc.id();
        if (normalized.equalsIgnoreCase("location of event npc") && eventNpc != null) return eventNpc.location();
        if (normalized.equalsIgnoreCase("name of npc")) return npc.id();
        if (normalized.equalsIgnoreCase("location of npc")) return npc.location();
        if (normalized.equalsIgnoreCase("x of npc")) return npc.location().getX();
        if (normalized.equalsIgnoreCase("y of npc")) return npc.location().getY();
        if (normalized.equalsIgnoreCase("z of npc")) return npc.location().getZ();
        Object loopNpc = locals.get("loop-npc");
        if (loopNpc instanceof ManagedNPC managed) {
            if (normalized.equalsIgnoreCase("name of loop-npc")) return managed.id();
            if (normalized.equalsIgnoreCase("location of loop-npc")) return managed.location();
        }
        Object loopPlayer = locals.get("loop-player");
        if (loopPlayer instanceof Player selectedPlayer) {
            if (normalized.equalsIgnoreCase("name of loop-player")) return selectedPlayer.getName();
            if (normalized.equalsIgnoreCase("location of loop-player")) return selectedPlayer.getLocation();
        }
        if (normalized.equalsIgnoreCase("name of player") && player != null) return player.getName();
        if (normalized.equalsIgnoreCase("event item") && player != null) return player.getInventory().getItemInMainHand();
        if (normalized.equalsIgnoreCase("event offhand item") && player != null) return player.getInventory().getItemInOffHand();
        if (normalized.equalsIgnoreCase("type of event item") && player != null) {
            return player.getInventory().getItemInMainHand().getType().name();
        }
        if (normalized.equalsIgnoreCase("type of event offhand item") && player != null) {
            return player.getInventory().getItemInOffHand().getType().name();
        }
        for (Map.Entry<String, Object> entry : locals.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) return entry.getValue();
        }
        return normalized;
    }
}
