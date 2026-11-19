package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class ScriptContext {
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
    private final Map<String, Object> locals = new HashMap<>();
    private final Deque<LoopState> loops = new ArrayDeque<>();

    public ScriptContext(ManagedNPC npc, Player player) {
        this.npc = npc;
        this.player = player;
    }

    public ManagedNPC npc() { return npc; }
    public Player player() { return player; }
    public Map<String, Object> locals() { return locals; }

    public LoopState pushLoop() {
        LoopState state = new LoopState();
        loops.push(state);
        return state;
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
        if (normalized.equalsIgnoreCase("event entity") || normalized.equalsIgnoreCase("player")) return player;
        if (normalized.equalsIgnoreCase("name of npc")) return npc.id();
        if (normalized.equalsIgnoreCase("location of npc")) return npc.location();
        if (normalized.equalsIgnoreCase("x of npc")) return npc.location().getX();
        if (normalized.equalsIgnoreCase("y of npc")) return npc.location().getY();
        if (normalized.equalsIgnoreCase("z of npc")) return npc.location().getZ();
        if (normalized.equalsIgnoreCase("name of player") && player != null) return player.getName();
        if (normalized.equalsIgnoreCase("event item") && player != null) return player.getInventory().getItemInMainHand();
        for (Map.Entry<String, Object> entry : locals.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) return entry.getValue();
        }
        return normalized;
    }
}
