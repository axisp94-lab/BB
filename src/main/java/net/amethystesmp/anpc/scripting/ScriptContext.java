package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ScriptContext {
    private static final class LoopFrame { boolean stopped; boolean skipped; }
    private final ManagedNPC npc; private final Player player; private final Map<String,Object> locals=new HashMap<>();
    private final Deque<LoopFrame> loops = new ArrayDeque<>();
    private boolean returned;
    private Object returnValue;
    private int callDepth;
    public ScriptContext(ManagedNPC npc,Player player){this.npc=npc;this.player=player;}
    public ManagedNPC npc(){return npc;} public Player player(){return player;} public Map<String,Object> locals(){return locals;}
    public boolean returned() { return returned; }
    public Object returnValue() { return returnValue; }
    public void setReturnValue(Object value) { returned = true; returnValue = value; }
    public int callDepth() { return callDepth; }
    public void setCallDepth(int depth) { callDepth = depth; }
    public void pushLoop() { loops.push(new LoopFrame()); }
    public void popLoop() { if (!loops.isEmpty()) loops.pop(); }
    public void stopLoop() { if (!loops.isEmpty()) loops.peek().stopped = true; }
    public void skipLoop() { if (!loops.isEmpty()) loops.peek().skipped = true; }
    public boolean loopStopped() { return !loops.isEmpty() && loops.peek().stopped; }
    public boolean loopSkipped() { return !loops.isEmpty() && loops.peek().skipped; }
    public boolean loopControlRequested() { return loopStopped() || loopSkipped(); }
    public void clearLoopSkip() { if (!loops.isEmpty()) loops.peek().skipped = false; }
    public Object value(String key){String k=key.trim(); if(k.equalsIgnoreCase("event entity")||k.equalsIgnoreCase("player"))return player; if(k.equalsIgnoreCase("name of npc"))return npc.id(); if(k.equalsIgnoreCase("state of npc")||k.equalsIgnoreCase("primary state of npc"))return npc.primaryState(); if(k.equalsIgnoreCase("secondary states of npc"))return npc.secondaryStates(); if(k.equalsIgnoreCase("location of npc"))return npc.location(); if(k.equalsIgnoreCase("x of npc"))return npc.location().getX(); if(k.equalsIgnoreCase("y of npc"))return npc.location().getY(); if(k.equalsIgnoreCase("z of npc"))return npc.location().getZ(); if(k.equalsIgnoreCase("name of player")&&player!=null)return player.getName(); if(k.equalsIgnoreCase("event item")&&player!=null)return player.getInventory().getItemInMainHand(); if(locals.containsKey(k))return locals.get(k); return k; }
}
