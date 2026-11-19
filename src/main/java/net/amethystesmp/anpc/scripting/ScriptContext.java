package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ScriptContext {
    private static final class LoopFrame { boolean stopped; boolean skipped; }
    private ManagedNPC npc; private Player player; private final Map<String,Object> locals=new HashMap<>();
    private final java.util.Set<String> transientLocals=new java.util.HashSet<>();
    private final Deque<LoopFrame> loops = new ArrayDeque<>();
    private boolean returned;
    private Object returnValue;
    private int callDepth;
    private String eventData="";
    private ManagedNPC eventNpc;
    private int pendingWaits;
    private boolean finished;
    private long lastChangeAt=System.currentTimeMillis();
    private long lastWarningAt;
    private String scriptId="";
    private long scriptGeneration;
    public ScriptContext(ManagedNPC npc,Player player){this.npc=npc;this.player=player;}
    public ManagedNPC npc(){return npc;} public void setNpc(ManagedNPC npc){this.npc=npc;} public Player player(){return player;} public void setPlayer(Player player){this.player=player;} public Map<String,Object> locals(){return locals;}
    public void markTransientLocal(String name){transientLocals.add(name.toLowerCase(java.util.Locale.ROOT));}
    public boolean isTransientLocal(String name){return transientLocals.contains(name.toLowerCase(java.util.Locale.ROOT));}
    public boolean returned() { return returned; }
    public Object returnValue() { return returnValue; }
    public void setReturnValue(Object value) { returned = true; returnValue = value; }
    public void setEventData(String data) { eventData = data == null ? "" : data; }
    public void setEventNpc(ManagedNPC npc) { eventNpc = npc; }
    public ManagedNPC eventNpc() { return eventNpc; }
    public String eventData() { return eventData; }
    public void beginWait() { pendingWaits++; }
    public void endWait() { pendingWaits = Math.max(0, pendingWaits - 1); }
    public boolean hasPendingWaits() { return pendingWaits > 0; }
    public boolean finished() { return finished; }
    public void finish() { finished = true; }
    public void markChanged() { lastChangeAt = System.currentTimeMillis(); }
    public boolean shouldWarnNoChange() {
        long now = System.currentTimeMillis();
        if (now - lastChangeAt < 5_000L || now - lastWarningAt < 5_000L) return false;
        lastWarningAt = now;
        return true;
    }
    public void setScriptExecution(String id,long generation) { scriptId=id==null?"":id; scriptGeneration=generation; }
    public String scriptId() { return scriptId; }
    public long scriptGeneration() { return scriptGeneration; }
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
    public Object value(String key){
        String k=key.trim().toLowerCase(java.util.Locale.ROOT);
        if(k.equals("event entity")||k.equals("player"))return player;
        if(k.equals("event message")||k.equals("event command"))return eventData;
        if(k.equals("event npc"))return eventNpc;
        if(k.equals("name of npc"))return npc.id();
        if(k.equals("state of npc")||k.equals("primary state of npc"))return npc.primaryState();
        if(k.equals("secondary states of npc"))return npc.secondaryStates();
        if(k.equals("location of npc"))return npc.location();
        if(k.equals("target of npc"))return npc.combatTarget()==null?null:Bukkit.getEntity(npc.combatTarget());
        if(k.equals("x of npc"))return npc.location().getX();
        if(k.equals("y of npc"))return npc.location().getY();
        if(k.equals("z of npc"))return npc.location().getZ();
        if(k.equals("yaw of npc"))return npc.location().getYaw();
        if(k.equals("pitch of npc"))return npc.location().getPitch();
        if(k.equals("world of npc"))return npc.location().getWorld().getName();
        if(k.equals("health of npc"))return npc.health();
        if(k.equals("max health of npc"))return npc.maxHealth();
        if(k.equals("speed of npc"))return npc.speed();
        if(k.equals("attack damage of npc"))return npc.attackDamage();
        if(k.equals("hostile of npc")||k.equals("npc is hostile"))return npc.hostile();
        if(k.equals("animation of npc"))return npc.animation();
        if(player!=null){
            if(k.equals("name of player"))return player.getName();
            if(k.equals("location of player"))return player.getLocation();
            if(k.equals("world of player"))return player.getWorld().getName();
            if(k.equals("x of player"))return player.getLocation().getX();
            if(k.equals("y of player"))return player.getLocation().getY();
            if(k.equals("z of player"))return player.getLocation().getZ();
            if(k.equals("yaw of player"))return player.getLocation().getYaw();
            if(k.equals("pitch of player"))return player.getLocation().getPitch();
            if(k.equals("is online")||k.equals("player is online"))return player.isOnline();
            if(k.equals("is op")||k.equals("player is op"))return player.isOp();
            if(k.equals("is sprinting")||k.equals("player is sprinting"))return player.isSprinting();
            if(k.equals("is flying")||k.equals("player is flying"))return player.isFlying();
            if(k.equals("game mode of player"))return player.getGameMode().name();
            if(k.equals("max health of player"))return player.getAttribute(Attribute.MAX_HEALTH)==null?20.0:player.getAttribute(Attribute.MAX_HEALTH).getValue();
            if(k.equals("movement speed of player"))return player.getAttribute(Attribute.MOVEMENT_SPEED)==null?0.1:player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
            if(k.equals("health of player"))return player.getHealth();
            if(k.equals("food level of player"))return player.getFoodLevel();
            if(k.equals("level of player"))return player.getLevel();
            if(k.equals("total experience of player")||k.equals("experience of player"))return player.getTotalExperience();
            if(k.equals("exp progress of player"))return player.getExp();
            if(k.equals("event item")||k.equals("held item of player")||k.equals("item in hand of player"))return player.getInventory().getItemInMainHand();
            if(k.equals("helmet of player"))return player.getInventory().getHelmet();
            if(k.equals("chestplate of player"))return player.getInventory().getChestplate();
            if(k.equals("leggings of player"))return player.getInventory().getLeggings();
            if(k.equals("boots of player"))return player.getInventory().getBoots();
            if(k.equals("equipment of player"))return player.getInventory();
        }
        if(locals.containsKey(key.trim()))return locals.get(key.trim());
        if(locals.containsKey(k))return locals.get(k);
        return key;
    }
}
