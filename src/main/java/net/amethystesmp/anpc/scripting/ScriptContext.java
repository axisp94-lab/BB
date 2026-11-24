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

    public boolean recognizesValue(String key) {
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "event entity", "player", "event npc", "other npc",
                    "name of event entity", "location of event entity", "type of event entity",
                    "name of event npc", "location of event npc",
                    "name of npc", "location of npc", "x of npc", "y of npc", "z of npc",
                    "name of player", "location of player",
                    "loop-number", "loop-value", "loop-player", "loop-npc",
                    "name of loop-player", "location of loop-player",
                    "name of loop-npc", "location of loop-npc",
                    "event item", "event offhand item",
                    "type of event item", "type of event offhand item" -> true;
            default -> false;
        };
    }

    public Object value(String key) {
        String normalized = key.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "event entity": return eventEntity;
            case "player": return player;
            case "event npc":
            case "other npc": return eventNpc;
            case "name of event npc": return eventNpc == null ? null : eventNpc.id();
            case "location of event npc": return eventNpc == null ? null : eventNpc.location();
            case "name of npc": return npc.id();
            case "location of npc": return npc.location();
            case "x of npc": return npc.location().getX();
            case "y of npc": return npc.location().getY();
            case "z of npc": return npc.location().getZ();
            case "name of event entity":
                if (eventEntity == null) return null;
                if (eventEntity instanceof Player selectedPlayer) return selectedPlayer.getName();
                String customName = eventEntity.getCustomName();
                return customName == null || customName.isBlank()
                        ? eventEntity.getType().name().toLowerCase(Locale.ROOT) : customName;
            case "location of event entity": return eventEntity == null ? null : eventEntity.getLocation();
            case "type of event entity": return eventEntity == null ? null : eventEntity.getType().name();
            case "name of player": return player == null ? null : player.getName();
            case "location of player": return player == null ? null : player.getLocation();
            case "event item": return player == null ? null : player.getInventory().getItemInMainHand();
            case "event offhand item": return player == null ? null : player.getInventory().getItemInOffHand();
            case "type of event item":
                return player == null ? null : player.getInventory().getItemInMainHand().getType().name();
            case "type of event offhand item":
                return player == null ? null : player.getInventory().getItemInOffHand().getType().name();
            case "loop-number":
            case "loop-value":
            case "loop-player":
            case "loop-npc": return locals.get(lower);
            case "name of loop-npc": {
                Object loopNpc = locals.get("loop-npc");
                return loopNpc instanceof ManagedNPC managed ? managed.id() : null;
            }
            case "location of loop-npc": {
                Object loopNpc = locals.get("loop-npc");
                return loopNpc instanceof ManagedNPC managed ? managed.location() : null;
            }
            case "name of loop-player": {
                Object loopPlayer = locals.get("loop-player");
                return loopPlayer instanceof Player selectedPlayer ? selectedPlayer.getName() : null;
            }
            case "location of loop-player": {
                Object loopPlayer = locals.get("loop-player");
                return loopPlayer instanceof Player selectedPlayer ? selectedPlayer.getLocation() : null;
            }
            default:
                for (Map.Entry<String, Object> entry : locals.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(normalized)) return entry.getValue();
                }
                return normalized;
        }
    }}
