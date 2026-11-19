package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.ANPCPlugin;
import net.amethystesmp.anpc.npc.ManagedNPC;
import net.amethystesmp.anpc.npc.NPCManager;
import net.amethystesmp.anpc.storage.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptEngine implements Listener {
    private record Instruction(int indent,String text,int line){}
    private record FunctionDefinition(List<String> parameters,List<Instruction> code){}
    private record PathNode(int x,int y,int z,double cost,double score,PathNode parent){}
    private final ANPCPlugin plugin; private final NPCManager npcs; private final VariableStore variables;
    private final Map<String,Map<String,List<Instruction>>> scripts=new ConcurrentHashMap<>();
    private final Map<String,FunctionDefinition> functions=new ConcurrentHashMap<>();
    private final Set<BukkitTask> tasks=ConcurrentHashMap.newKeySet();
    private final Map<ManagedNPC,BukkitTask> movementTasks=new ConcurrentHashMap<>();
    private final Map<ManagedNPC,BukkitTask> followTasks=new ConcurrentHashMap<>();
    private final Map<ManagedNPC,BukkitTask> combatTasks=new ConcurrentHashMap<>();
    private final Map<ManagedNPC,Long> lastAttackTimes=new ConcurrentHashMap<>();
    private final Map<String,BukkitTask> intervalTasks=new ConcurrentHashMap<>();
    private final Map<ScriptContext,BukkitTask> watchdogTasks=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> nearbyPlayers=new ConcurrentHashMap<>();
    private final Map<String,Set<String>> nearbyNpcs=new ConcurrentHashMap<>();
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll(){
        scripts.clear(); functions.clear(); intervalTasks.clear();
        File dir=new File(plugin.getDataFolder(),"scripts");
        File[] fs=dir.listFiles((d,n)->n.endsWith(".aa"));
        if(fs==null)return;
        for(File file:fs){
            String id=file.getName().substring(0,file.getName().length()-3).toLowerCase(Locale.ROOT);
            try{
                List<String> lines=Files.readAllLines(file.toPath());
                Map<String,FunctionDefinition> declared=parseFunctions(lines);
                for(var function:declared.entrySet()){
                    if(functions.putIfAbsent(function.getKey(),function.getValue())!=null)
                        plugin.getLogger().warning("Fonction globale dupliquée '"+function.getKey()+"' dans "+file.getName()+" (déclaration ignorée).");
                }
                scripts.put(id,parse(lines));
            }catch(Exception exception){plugin.getLogger().warning("Erreur script "+file.getName()+" : "+exception.getMessage());}
        }
        scheduleIntervals(); scheduleProximity();
        for(ManagedNPC npc:npcs.all()){
            Map<String,List<Instruction>> map=scripts.get(npc.id().toLowerCase(Locale.ROOT));
            if(map!=null&&map.get("spawn")!=null&&npc.scriptsEnabled())execute(npc,null,map.get("spawn"),0);
        }
    }
    private Map<String,List<Instruction>> parse(List<String> lines){
        Map<String,List<Instruction>> result=new LinkedHashMap<>(); String event=null;
        for(int i=0;i<lines.size();i++){
            String raw=lines.get(i);
            if(raw.isBlank()||raw.stripLeading().startsWith("#"))continue;
            int indent=raw.length()-raw.stripLeading().length(); String text=raw.trim();
            if(indent==0&&text.toLowerCase(Locale.ROOT).startsWith("function ")){event=null;continue;}
            if(event!=null&&indent>0&&indent%4!=0){
                plugin.getLogger().warning("Indentation invalide ligne "+(i+1)+" : utilise des blocs de 4 espaces. L'événement '"+event+"' est ignoré.");
                result.remove(event);event=null;continue;
            }
            if(indent==0&&text.toLowerCase(Locale.ROOT).startsWith("on ")){
                if(!text.endsWith(":")){
                    plugin.getLogger().warning("Déclaration d'événement invalide ligne "+(i+1)+" : "+text);
                    event=null;
                    continue;
                }
                String eventName=text.substring(3,text.length()-1).trim().toLowerCase(Locale.ROOT);
                if(!isKnownEvent(eventName)){
                    plugin.getLogger().warning("Événement .aa inconnu ligne "+(i+1)+" : '"+eventName+"'. Bloc ignoré.");
                    event=null;continue;
                }
                if(result.containsKey(eventName)){
                    plugin.getLogger().warning("Événement dupliqué '"+eventName+"' ligne "+(i+1)+" : bloc ignoré.");
                    event=null;
                }else{
                    event=eventName;
                    result.put(event,new ArrayList<>());
                }
            }else if(event!=null){
                if(!isKnownInstruction(text)){
                    warnUnknownInstruction(i+1,text);
                    plugin.getLogger().warning("Bloc de l'événement '"+event+"' ignoré à cause de cette instruction.");
                    result.remove(event);event=null;continue;
                }
                result.get(event).add(new Instruction(indent,text,i+1));
            }
        }
        return result;
    }
    private Map<String,FunctionDefinition> parseFunctions(List<String> lines){
        Map<String,FunctionDefinition> result=new LinkedHashMap<>();
        for(int i=0;i<lines.size();i++){
            String raw=lines.get(i);
            if(raw.isBlank()||raw.stripLeading().startsWith("#")||!raw.equals(raw.stripLeading()))continue;
            String header=raw.trim();
            if(!header.toLowerCase(Locale.ROOT).startsWith("function "))continue;
            if(!header.endsWith(":")){
                plugin.getLogger().warning("Déclaration de fonction invalide ligne "+(i+1)+" : ':' manquant.");
                continue;
            }
            int open=header.indexOf('('),close=header.lastIndexOf(')');
            if(open<10||close<open){plugin.getLogger().warning("Déclaration de fonction invalide ligne "+(i+1)+" : "+header);continue;}
            String name=header.substring(9,open).trim().toLowerCase(Locale.ROOT);
            if(name.isBlank())continue;
            List<String> parameters=new ArrayList<>();
            String rawParameters=header.substring(open+1,close).trim();
            if(!rawParameters.isBlank())for(String parameter:splitArguments(rawParameters))parameters.add(parameter.trim().toLowerCase(Locale.ROOT));
            List<Instruction> body=new ArrayList<>();
            boolean invalidIndent=false;
            int j=i+1;
            for(;j<lines.size();j++){
                String line=lines.get(j);
                if(line.isBlank()||line.stripLeading().startsWith("#"))continue;
                int indent=line.length()-line.stripLeading().length();
                if(indent==0)break;
                if(indent%4!=0){plugin.getLogger().warning("Indentation invalide dans la fonction '"+name+"' ligne "+(j+1)+" : fonction ignorée.");invalidIndent=true;}
                body.add(new Instruction(indent,line.trim(),j+1));
            }
            boolean invalidBody=invalidIndent;
            for(Instruction instruction:body)if(!isKnownInstruction(instruction.text())){
                warnUnknownInstruction(instruction.line(),instruction.text());
                invalidBody=true;break;
            }
            if(!invalidBody&&result.putIfAbsent(name,new FunctionDefinition(List.copyOf(parameters),List.copyOf(body)))!=null)
                plugin.getLogger().warning("Fonction dupliquée '"+name+"' dans le même fichier, déclaration ignorée (ligne "+(i+1)+").");
            i=j-1;
        }
        return result;
    }
    private void scheduleIntervals(){
        for(var entry:scripts.entrySet()){
            List<Instruction> code=entry.getValue().get("interval");
            if(code!=null)scheduleInterval(entry.getKey(),code);
        }
    }

    private void scheduleInterval(String id,List<Instruction> code){
        long period=Math.max(1,plugin.getConfig().getLong("script-default-interval",20));
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(ManagedNPC npc:npcs.instances(id))if(npc.scriptsEnabled())execute(npc,null,code,0);
        },period,period);
        intervalTasks.put(id,task);tasks.add(task);
    }

    public List<String> scriptIds(){
        File directory=new File(plugin.getDataFolder(),"scripts");
        File[] files=directory.listFiles((folder,name)->name.toLowerCase(Locale.ROOT).endsWith(".aa"));
        if(files==null)return List.of();
        return Arrays.stream(files).map(file->file.getName().substring(0,file.getName().length()-3))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public boolean reloadScript(String requestedId){
        String id=requestedId.toLowerCase(Locale.ROOT).endsWith(".aa")
                ?requestedId.substring(0,requestedId.length()-3).toLowerCase(Locale.ROOT)
                :requestedId.toLowerCase(Locale.ROOT);
        if(!id.matches("[a-z0-9_-]+"))return false;
        File directory=new File(plugin.getDataFolder(),"scripts");
        File[] files=directory.listFiles((folder,name)->name.toLowerCase(Locale.ROOT).endsWith(".aa"));
        if(files==null)return false;
        File selected=Arrays.stream(files).filter(file->file.getName().equalsIgnoreCase(id+".aa")).findFirst().orElse(null);
        if(selected==null)return false;
        try{
            Map<String,Map<String,List<Instruction>>> parsedScripts=new HashMap<>();
            Map<String,FunctionDefinition> parsedFunctions=new LinkedHashMap<>();
            for(File file:files){
                List<String> lines=Files.readAllLines(file.toPath());
                String fileId=file.getName().substring(0,file.getName().length()-3).toLowerCase(Locale.ROOT);
                parsedScripts.put(fileId,parse(lines));
                for(var function:parseFunctions(lines).entrySet()){
                    if(parsedFunctions.putIfAbsent(function.getKey(),function.getValue())!=null)
                        plugin.getLogger().warning("Fonction globale dupliquée '"+function.getKey()+"' dans "+file.getName()+" (déclaration ignorée).");
                }
            }
            scripts.put(id,parsedScripts.getOrDefault(id,Map.of()));
            functions.clear();functions.putAll(parsedFunctions);
            BukkitTask old=intervalTasks.remove(id);
            if(old!=null){old.cancel();tasks.remove(old);}
            List<Instruction> interval=scripts.get(id).get("interval");
            if(interval!=null)scheduleInterval(id,interval);
            List<Instruction> spawn=scripts.get(id).get("spawn");
            if(spawn!=null)for(ManagedNPC npc:npcs.instances(id))if(npc.scriptsEnabled())execute(npc,null,spawn,0);
            return true;
        }catch(Exception exception){
            plugin.getLogger().warning("Rechargement du script '"+id+"' impossible : "+exception.getMessage());
            return false;
        }
    }
    private void scheduleProximity(){
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            double radius=plugin.getConfig().getDouble("script-approach-radius",3.0),r2=radius*radius;
            List<ManagedNPC> all=new ArrayList<>(npcs.all());
            for(ManagedNPC npc:all){
                String sourceKey=npc.id().toLowerCase(Locale.ROOT)+"@"+npc.location().getWorld().getName().toLowerCase(Locale.ROOT);
                Set<UUID> currentPlayers=new HashSet<>();
                for(Player player:npc.location().getWorld().getPlayers())if(player.getLocation().distanceSquared(npc.location())<=r2){
                    currentPlayers.add(player.getUniqueId());
                    if(nearbyPlayers.computeIfAbsent(sourceKey,k->ConcurrentHashMap.newKeySet()).add(player.getUniqueId()))
                        fire(npc.id(),"entity approach",player);
                }
                Set<UUID> previousPlayers=nearbyPlayers.computeIfAbsent(sourceKey,k->ConcurrentHashMap.newKeySet());
                for(UUID uuid:new HashSet<>(previousPlayers))if(!currentPlayers.contains(uuid)){
                    Player player=Bukkit.getPlayer(uuid);previousPlayers.remove(uuid);
                    if(player!=null)fire(npc.id(),"entity leave",player);
                }

                Set<String> currentNpcs=new HashSet<>();
                for(ManagedNPC other:all){
                    if(other==npc||!other.location().getWorld().equals(npc.location().getWorld()))continue;
                    if(other.location().distanceSquared(npc.location())<=r2){
                        String targetKey=other.id().toLowerCase(Locale.ROOT)+"@"+other.location().getWorld().getName().toLowerCase(Locale.ROOT);
                        currentNpcs.add(targetKey);
                        if(nearbyNpcs.computeIfAbsent(sourceKey,k->ConcurrentHashMap.newKeySet()).add(targetKey))
                            fireOn(npc,"npc approach",other);
                    }
                }
                Set<String> previousNpcs=nearbyNpcs.computeIfAbsent(sourceKey,k->ConcurrentHashMap.newKeySet());
                for(String targetKey:new HashSet<>(previousNpcs))if(!currentNpcs.contains(targetKey)){
                    previousNpcs.remove(targetKey);
                    ManagedNPC other=all.stream().filter(candidate->(candidate.id().toLowerCase(Locale.ROOT)+"@"+candidate.location().getWorld().getName().toLowerCase(Locale.ROOT)).equals(targetKey)).findFirst().orElse(null);
                    if(other!=null)fireOn(npc,"npc leave",other);
                }
            }
        },10L,10L);
        tasks.add(task);
    }
    public void fire(String npc,String event,Player player){fire(npc,event,player,"");}
    private void fire(String npc,String event,Player player,String eventData){
        Map<String,List<Instruction>> map=scripts.get(npc.toLowerCase(Locale.ROOT));
        if(map==null)return;
        List<Instruction> code=map.get(event.toLowerCase(Locale.ROOT));
        if(code==null)return;
        Collection<ManagedNPC> targets=player==null?npcs.instances(npc):Optional.ofNullable(npcs.get(npc,player.getWorld())).map(List::of).orElse(List.of());
        for(ManagedNPC managed:targets)if(managed.scriptsEnabled())execute(managed,player,code,0,eventData,null);
    }
    private void fireOn(ManagedNPC source,String event,ManagedNPC target){
        Map<String,List<Instruction>> map=scripts.get(source.id().toLowerCase(Locale.ROOT));
        if(map==null)return;
        List<Instruction> code=map.get(event.toLowerCase(Locale.ROOT));
        if(code!=null&&source.scriptsEnabled())execute(source,null,code,0,"",target);
    }
    private void execute(ManagedNPC npc,Player player,List<Instruction> code,int baseIndent){execute(npc,player,code,baseIndent,"",null);}
    private void execute(ManagedNPC npc,Player player,List<Instruction> code,int baseIndent,String eventData){execute(npc,player,code,baseIndent,eventData,null);}
    private void execute(ManagedNPC npc,Player player,List<Instruction> code,int baseIndent,String eventData,ManagedNPC eventNpc){
        ScriptContext ctx=new ScriptContext(npc,player);
        ctx.setEventData(eventData);
        ctx.setEventNpc(eventNpc);
        int indent=code.isEmpty()?baseIndent:code.get(0).indent();
        runBlock(ctx,code,0,indent);
    }
    private int runBlock(ScriptContext c,List<Instruction> code,int index,int indent){if(!c.npc().scriptsEnabled()||c.npc().tracker().isClosed())return code.size();while(index<code.size()){if(!c.npc().scriptsEnabled()||c.npc().tracker().isClosed()||c.returned())return code.size();if(c.loopControlRequested())return code.size();Instruction in=code.get(index);if(in.indent()<indent)break;if(in.indent()>indent){index++;continue;}String s=in.text();int line=in.line();
        if(s.equals("return")){c.setReturnValue(null);return code.size();}
        if(s.startsWith("return ")){c.setReturnValue(resolve(c,s.substring(7).trim()));return code.size();}
        if(s.startsWith("call ")){callFunction(c,s.substring(5).trim());index++;continue;}
        if(s.startsWith("loop ")&&s.endsWith(":")){int end=findEnd(code,index+1,in.indent());int child=index+1<code.size()?code.get(index+1).indent():in.indent()+4;executeLoop(c,code,index+1,child,s.substring(5,s.length()-1).trim());index=end;continue;}
        if(s.equals("stop loop")){c.stopLoop();return code.size();}
        if(s.equals("skip loop")){c.skipLoop();return code.size();}
        if(s.startsWith("if ")&&s.endsWith(":")){int end=findEnd(code,index+1,in.indent());if(test(c,s.substring(3,s.length()-1))){int child=index+1<code.size()?code.get(index+1).indent():in.indent()+4;runBlock(c,code,index+1,child);}index=end;continue;}
        if(s.startsWith("set hostile ")){setHostile(c.npc(),s.substring(12).trim().equalsIgnoreCase("on"));index++;continue;}
        if(s.startsWith("set health of npc to ")){c.npc().setHealth(num(resolve(c,s.substring(21).trim())));c.markChanged();index++;continue;}
        if(s.startsWith("set max health of npc to ")){c.npc().setMaxHealth(num(resolve(c,s.substring(25).trim())));c.markChanged();index++;continue;}
        if(s.startsWith("set speed of npc to ")){c.npc().setSpeed(num(resolve(c,s.substring(20).trim())));c.markChanged();index++;continue;}
        if(s.startsWith("set target of npc to ")){setTargetSelector(c.npc(),s.substring(21).trim());index++;continue;}
        if(s.startsWith("set cooldown ")){
            String body=s.substring(13).trim();int forIndex=body.toLowerCase(Locale.ROOT).lastIndexOf(" for ");
            if(forIndex>0){String name=unquote(body.substring(0,forIndex).trim());long duration=parseDurationMillis(body.substring(forIndex+5).trim());c.npc().setCooldown(name,duration);c.markChanged();}
            else plugin.getLogger().warning("Syntaxe attendue : set cooldown \"nom\" for 5 seconds.");
            index++;continue;
        }
        if(s.startsWith("set ")){String body=s.substring(4).trim();int q=body.indexOf('"',1);if(body.startsWith("\"")&&q>0){String name=body.substring(1,q);String val=body.substring(q+1).trim();if(val.startsWith("(" )&&val.endsWith(")"))val=val.substring(1,val.length()-1);Object value=resolve(c,val);c.locals().put(name,value);if(!c.isTransientLocal(name))storeVariable(c,name,value);c.markChanged();}index++;continue;}
        if(s.startsWith("create variable ")){createVariable(c,s.substring(15).trim());c.markChanged();index++;continue;}
        if(s.startsWith("say local ")||s.startsWith("say global ")){boolean global=s.startsWith("say global ");Object message=resolve(c,s.substring(global?11:10).trim());String rendered=ChatColor.translateAlternateColorCodes('&',c.npc().definition().displayName()+" » "+String.valueOf(message));if(global)Bukkit.broadcastMessage(rendered);else if(c.player()!=null)c.player().sendMessage(rendered);else for(Player p:c.npc().location().getWorld().getPlayers())if(p.getLocation().distanceSquared(c.npc().location())<32*32)p.sendMessage(rendered);index++;continue;}
        if(s.startsWith("send to ")){int pos=s.indexOf(' ',8);if(pos>0){String target=s.substring(8,pos);Object targetValue=resolve(c,target);String msg=String.valueOf(resolve(c,s.substring(pos+1).trim()));Player p=targetValue instanceof Player selected?selected:(target.equals("@p")?nearest(c.npc().location()):Bukkit.getPlayerExact(target));if(p!=null)p.sendMessage(ChatColor.translateAlternateColorCodes('&',msg));}index++;continue;}
        if(s.startsWith("simulate break block at ")){
            Location at=parseLocation(c,s.substring(23).trim());
            if(at!=null)simulateBreakBlock(at);
            index++;continue;
        }
        if(s.startsWith("simulate place ")){
            int atIndex=s.toLowerCase(Locale.ROOT).indexOf(" at ");
            if(atIndex>15){
                Material material=Material.matchMaterial(s.substring(15,atIndex).trim());
                Location at=parseLocation(c,s.substring(atIndex+4).trim());
                if(material!=null&&at!=null)simulatePlaceBlock(at,material);
            }
            index++;continue;
        }
        if(s.startsWith("simulate interact with block at ")){
            Location at=parseLocation(c,s.substring(32).trim());
            if(at!=null){at.getWorld().spawnParticle(Particle.CRIT,at.clone().add(0.5,0.5,0.5),8,0.2,0.2,0.2,0.02);at.getWorld().playSound(at,Sound.UI_BUTTON_CLICK,0.8f,1.0f);}
            index++;continue;
        }
        if(s.startsWith("set block ")){c.markChanged();
            int atIndex=s.toLowerCase(Locale.ROOT).indexOf(" at ");
            if(atIndex>10){
                Material material=Material.matchMaterial(s.substring(10,atIndex).trim());
                Location at=parseLocation(c,s.substring(atIndex+4).trim());
                if(material!=null&&at!=null)at.getBlock().setType(material,false);
            }
            index++;continue;
        }
        if(s.startsWith("break block at ")){c.markChanged();
            Location at=parseLocation(c,s.substring(15).trim());
            if(at!=null)at.getBlock().breakNaturally();
            index++;continue;
        }
        if(s.startsWith("play anime ")){c.npc().animate(unquote(s.substring(11)));c.markChanged();index++;continue;}
        if(s.startsWith("play sound ")){playScriptSound(c,s.substring(11).trim());index++;continue;}
        if(s.startsWith("reset cooldown ")){c.npc().resetCooldown(unquote(s.substring(15).trim()));c.markChanged();index++;continue;}
        if(s.startsWith("set checkpoint ")){c.npc().setCheckpoint(unquote(s.substring(15).trim()),c.npc().location());c.markChanged();index++;continue;}
        if(s.startsWith("remove checkpoint ")){c.npc().removeCheckpoint(unquote(s.substring(18).trim()));c.markChanged();index++;continue;}
        if(s.startsWith("move to checkpoint ")){stopFollow(c.npc());Location checkpoint=c.npc().checkpoint(unquote(s.substring(19).trim()));if(checkpoint!=null)move(c.npc(),checkpoint);else plugin.getLogger().warning("Checkpoint introuvable pour "+c.npc().id()+" : "+s.substring(19));c.markChanged();index++;continue;}
        if(s.startsWith("move to ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(8).trim());if(target!=null)move(c.npc(),target);c.markChanged();index++;continue;}
        if(s.startsWith("fly to ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(7).trim());if(target!=null)moveFlying(c.npc(),target);c.markChanged();index++;continue;}
        if(s.startsWith("tp ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(3).trim());if(target!=null)move(c.npc(),target);index++;continue;}
        if(s.startsWith("flag on ")||s.startsWith("flag off ")){boolean on=s.startsWith("flag on ");String name=s.substring(on?8:9).replace("(","").replace(")","").trim();variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),"flag:"+name,on);c.markChanged();index++;continue;}
        if(s.startsWith("wait ")){
            long ticks=parseDurationTicks(s.substring(5));
            List<Instruction> remaining=new ArrayList<>(code.subList(index+1,code.size()));
            c.beginWait();startWatchdog(c);
            BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->{
                c.endWait();
                runBlock(c,remaining,0,indent);
                if(!c.hasPendingWaits()){c.finish();stopWatchdog(c);}
            },ticks);
            tasks.add(task);return code.size();
        }
        if(s.startsWith("run command ")){String cmd=unquote(s.substring(12));Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("%player%",c.player()==null?"":c.player().getName()));index++;continue;}
        if(s.startsWith("look at ")&&(c.player()!=null||c.eventNpc()!=null)){Location at=c.player()!=null?c.player().getLocation():c.eventNpc().location();Location from=c.npc().location();from.setDirection(at.toVector().subtract(from.toVector()));c.npc().move(from);c.markChanged();index++;continue;}
        if(s.startsWith("follow ")){startFollow(c.npc(),s.substring(7).trim());c.markChanged();index++;continue;}
        if(s.startsWith("keep distance ")){startKeepDistance(c.npc(),s.substring(14).trim());c.markChanged();index++;continue;}
        if(s.equals("stop follow")){stopFollow(c.npc());c.markChanged();index++;continue;}
        if(s.startsWith("target ")){setTargetSelector(c.npc(),s.substring(7).trim());index++;continue;}
        if(s.startsWith("attack ")){setTargetSelector(c.npc(),s.substring(7).trim());setHostile(c.npc(),true);index++;continue;}
        if(s.equals("stop attack")){setHostile(c.npc(),false);index++;continue;}
        if(s.startsWith("set state ")){c.npc().setPrimaryState(unquote(s.substring(10)));c.markChanged();index++;continue;}
        if(s.startsWith("add state ")){c.npc().addSecondaryState(unquote(s.substring(10)));c.markChanged();index++;continue;}
        if(s.startsWith("remove state ")){c.npc().removeSecondaryState(unquote(s.substring(13)));c.markChanged();index++;continue;}
        if(s.equals("clear states")){c.npc().setPrimaryState("idle");c.npc().clearSecondaryStates();c.markChanged();index++;continue;}
        warnUnknownInstruction(line,s);
        index++;
    }return index;}
    private void startWatchdog(ScriptContext context){
        if(watchdogTasks.containsKey(context))return;
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if(context.finished()||!context.hasPendingWaits()||!context.npc().scriptsEnabled()||context.npc().tracker().isClosed()){context.finish();stopWatchdog(context);return;}
            if(movementTasks.containsKey(context.npc())||followTasks.containsKey(context.npc())||combatTasks.containsKey(context.npc())){
                context.markChanged();return;
            }
            if(context.shouldWarnNoChange()){
                String permission=plugin.getConfig().getString("script-warning-permission","anpc.script.warning");
                for(Player player:Bukkit.getOnlinePlayers())if(player.hasPermission(permission))
                    player.sendMessage(ChatColor.YELLOW+"[AmethyysNPCs] Le script du NPC "+context.npc().id()+" n'a rien modifié depuis 100 ticks.");
            }
        },100L,100L);
        watchdogTasks.put(context,task);tasks.add(task);
    }

    private void stopWatchdog(ScriptContext context){
        BukkitTask task=watchdogTasks.remove(context);
        if(task!=null){task.cancel();tasks.remove(task);}
    }

    private boolean isKnownEvent(String event){
        return Set.of("spawn","right click","left click","left click while sneaking","damage","entity approach","entity leave",
                "npc approach","npc leave","interval","player join","player connect","player quit","player disconnect",
                "player chat","player command","walk").contains(event);
    }

    private boolean isKnownInstruction(String instruction){
        String text=instruction.trim().toLowerCase(Locale.ROOT);
        if(text.startsWith("if ")&&text.endsWith(":"))return true;
        if(text.startsWith("loop ")&&text.endsWith(":"))return true;
        if(text.startsWith("set state ")||text.startsWith("set hostile ")
                ||text.startsWith("set health of npc to ")||text.startsWith("set max health of npc to ")
                ||text.startsWith("set speed of npc to ")||text.startsWith("set target of npc to ")
                ||text.startsWith("set checkpoint ")||text.startsWith("set cooldown ")||text.startsWith("set block "))return true;
        if(text.startsWith("set "))return text.matches("^set\\s+\"[^\"]+\"\\s+.+$");
        return text.startsWith("say local ")||text.startsWith("say global ")||text.startsWith("send to ")
                ||text.startsWith("create variable ")||text.startsWith("add state ")
                ||text.startsWith("remove state ")||text.equals("clear states")||text.startsWith("flag on ")
                ||text.startsWith("flag off ")||text.startsWith("play sound ")||text.startsWith("play anime ")
                ||text.startsWith("move to ")||text.startsWith("fly to ")||text.startsWith("follow ")
                ||text.startsWith("keep distance ")||text.equals("stop follow")||text.startsWith("target ")
                ||text.startsWith("attack ")||text.equals("stop attack")||text.startsWith("look at ")
                ||text.startsWith("set checkpoint ")||text.startsWith("remove checkpoint ")
                ||text.startsWith("move to checkpoint ")||text.startsWith("reset cooldown ")||text.startsWith("simulate break block at ")
                ||text.startsWith("simulate place ")||text.startsWith("simulate interact with block at ")
                ||text.startsWith("set block ")||text.startsWith("break block at ")||text.startsWith("wait ")
                ||text.startsWith("run command ")||text.equals("stop loop")||text.equals("skip loop")
                ||text.equals("return")||text.startsWith("return ")||text.startsWith("call ");
    }

    private void warnUnknownInstruction(int line,String text){
        List<String> keywords=List.of("say local","say global","send to","set","create variable","flag on","flag off",
                "play sound","play anime","move to","fly to","follow","keep distance","stop follow","target","attack",
                "stop attack","set state","add state","remove state","clear states","simulate break block","simulate place",
                "simulate interact","set block","break block","wait","run command","look at","if","loop","return","call");
        String first=text.trim().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        String suggestion=keywords.stream().min(Comparator.comparingInt(keyword->editDistance(first,keyword.split(" ",2)[0]))).orElse("");
        int distance=suggestion.isBlank()?99:editDistance(first,suggestion.split(" ",2)[0]);
        String suffix=distance<=2?" Suggestion : '"+suggestion+"'.":"";
        plugin.getLogger().warning("Instruction .aa inconnue ligne "+line+" : '"+text+"'."+suffix);
    }

    private int editDistance(String left,String right){
        int[][] dp=new int[left.length()+1][right.length()+1];
        for(int i=0;i<=left.length();i++)dp[i][0]=i;
        for(int j=0;j<=right.length();j++)dp[0][j]=j;
        for(int i=1;i<=left.length();i++)for(int j=1;j<=right.length();j++)
            dp[i][j]=Math.min(Math.min(dp[i-1][j]+1,dp[i][j-1]+1),dp[i-1][j-1]+(left.charAt(i-1)==right.charAt(j-1)?0:1));
        return dp[left.length()][right.length()];
    }

    private void executeLoop(ScriptContext context,List<Instruction> code,int bodyStart,int bodyIndent,String expression){
        List<Object> values=new ArrayList<>();
        String loop=expression.trim();
        if(loop.equalsIgnoreCase("all players")){
            values.addAll(Bukkit.getOnlinePlayers());
        }else if(loop.equalsIgnoreCase("all npcs")){
            values.addAll(npcs.all());
        }else if(loop.toLowerCase(Locale.ROOT).startsWith("all (")&&loop.endsWith(")")){
            Object source=resolve(context,loop.substring(5,loop.length()-1).trim());
            if(source instanceof Iterable<?> iterable)for(Object value:iterable)values.add(value);
            else if(source instanceof Object[] array)values.addAll(Arrays.asList(array));
            else if(source instanceof String text&&!text.isBlank())values.addAll(Arrays.asList(text.split(",")));
        }else if(loop.toLowerCase(Locale.ROOT).endsWith(" times")){
            try{
                int count=Integer.parseInt(loop.substring(0,loop.length()-6).trim());
                for(int i=0;i<Math.max(0,Math.min(count,10000));i++)values.add(i+1);
            }catch(NumberFormatException exception){
                plugin.getLogger().warning("Boucle invalide : '"+expression+"'. Nombre attendu avant 'times'.");
            }
        }else{
            plugin.getLogger().warning("Type de boucle inconnu : '"+expression+"'.");
            return;
        }
        ManagedNPC previousNpc=context.npc();
        Player previousPlayer=context.player();
        context.pushLoop();
        try{
            int number=0;
            for(Object value:values){
                number++;
                context.locals().put("loop-value",value);
                context.locals().put("loop-number",number);
                context.setNpc(value instanceof ManagedNPC targetNpc?targetNpc:previousNpc);
                context.setPlayer(value instanceof Player targetPlayer?targetPlayer:previousPlayer);
                if(value instanceof Player player)context.locals().put("loop-player",player);
                if(value instanceof ManagedNPC npc)context.locals().put("loop-npc",npc);
                context.clearLoopSkip();
                runBlock(context,code,bodyStart,bodyIndent);
                if(context.loopStopped())break;
                if(context.loopSkipped())context.clearLoopSkip();
            }
        }finally{
            context.popLoop();
            context.setNpc(previousNpc);
            context.setPlayer(previousPlayer);
        }
    }
    private int findEnd(List<Instruction> c,int from,int parent){int i=from;while(i<c.size()&&c.get(i).indent()>parent)i++;return i;}
    private boolean test(ScriptContext c,String expression){String e=expression.trim();if(e.startsWith("flag (")&&e.contains(") is ")){String name=e.substring(6,e.indexOf(')'));boolean v=Boolean.parseBoolean(variables.getLocal(c.npc().id(),c.npc().location().getWorld().getName(),"flag:"+name,"false"));return e.endsWith("on")?v:!v;}String[] ops={" >= "," <= "," != "," == "," > "," < "," contains "};for(String op:ops){int p=e.indexOf(op);if(p>0){Object a=resolve(c,e.substring(0,p)),b=resolve(c,e.substring(p+op.length()));if(op.equals(" contains "))return String.valueOf(a).contains(String.valueOf(b));double x=num(a),y=num(b);return switch(op.trim()){case ">"->x>y;case "<"->x<y;case ">="->x>=y;case "<="->x<=y;case "!="->!Objects.equals(String.valueOf(a),String.valueOf(b));default->Objects.equals(String.valueOf(a),String.valueOf(b))||x==y;};}}if(e.equalsIgnoreCase("player is sneaking"))return c.player()!=null&&c.player().isSneaking();if(e.toLowerCase(Locale.ROOT).startsWith("player has permission "))return c.player()!=null&&c.player().hasPermission(unquote(e.substring(22).trim()));if(e.toLowerCase(Locale.ROOT).startsWith("player has item ")){if(c.player()==null)return false;try{return c.player().getInventory().contains(org.bukkit.Material.valueOf(e.substring(16).trim().toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException ex){return false;}}String lower=e.toLowerCase(Locale.ROOT);
        if(lower.startsWith("cooldown ")&&lower.endsWith(" is ready")){
            String name=unquote(e.substring(9,e.length()-9).trim());return !c.npc().hasCooldown(name);
        }
        if(lower.startsWith("cooldown ")&&lower.endsWith(" is active")){
            String name=unquote(e.substring(9,e.length()-10).trim());return c.npc().hasCooldown(name);
        }
        if(e.toLowerCase(Locale.ROOT).startsWith("block below is ")){
            Material expected=Material.matchMaterial(e.substring(15).trim());
            Location location=c.npc().location();
            return expected!=null&&location.getWorld().getBlockAt(location.getBlockX(),location.getBlockY()-1,location.getBlockZ()).getType()==expected;
        }
        if(e.equalsIgnoreCase("npc is under attack"))return c.npc().lastAttacker()!=null;
        if(e.equalsIgnoreCase("npc is hostile"))return c.npc().hostile();
        if(e.toLowerCase(Locale.ROOT).startsWith("npc state is "))return c.npc().primaryState().equalsIgnoreCase(unquote(e.substring(13).trim()));
        if(e.toLowerCase(Locale.ROOT).startsWith("state of npc is "))return c.npc().primaryState().equalsIgnoreCase(unquote(e.substring(16).trim()));
        if(e.toLowerCase(Locale.ROOT).startsWith("npc has state "))return c.npc().secondaryStates().contains(unquote(e.substring(14).trim()).toLowerCase(Locale.ROOT));return false;}
    private Object resolve(ScriptContext c,String raw){
        String value=raw.trim();
        if(value.length()>1&&value.startsWith("(")&&value.endsWith(")")&&balancedOuterParentheses(value))value=value.substring(1,value.length()-1).trim();
        if(c.locals().containsKey(value))return c.locals().get(value);
        if(value.toLowerCase(Locale.ROOT).startsWith("call "))return callFunction(c,value.substring(5).trim());
        if(value.startsWith("\"")&&value.endsWith("\""))return unquote(value);
        if(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("false"))return Boolean.parseBoolean(value);
        try{return Double.parseDouble(value);}catch(NumberFormatException ignored){}
        int operator=findTopLevelOperator(value,'+');
        if(operator>0){Object left=resolve(c,value.substring(0,operator)),right=resolve(c,value.substring(operator+1));if(left instanceof String||right instanceof String)return String.valueOf(left)+right;return num(left)+num(right);}
        operator=findTopLevelOperator(value,'-');
        if(operator>0)return num(resolve(c,value.substring(0,operator)))-num(resolve(c,value.substring(operator+1)));
        operator=findTopLevelOperator(value,'*');
        if(operator>0)return num(resolve(c,value.substring(0,operator)))*num(resolve(c,value.substring(operator+1)));
        operator=findTopLevelOperator(value,'/');
        if(operator>0){double divisor=num(resolve(c,value.substring(operator+1)));return divisor==0?0:num(resolve(c,value.substring(0,operator)))/divisor;}
        String meta=variables.get("meta",c.npc().id(),value,null);
        if(meta==null)meta=variables.get("meta","",value,null);
        if(meta!=null){
            String[] parts=meta.split("\\|",2);
            String scope=parts[0].toLowerCase(Locale.ROOT);
            String owner=parts.length>1?parts[1]:"";
            if(scope.equals("global")){String stored=variables.getGlobal(value,null);return stored==null?c.value(value):parseStored(stored);}
            if(scope.equals("shared")){
                if(!isSharedMember(owner,c.npc().id())){
                    plugin.getLogger().warning("Accès refusé : le NPC "+c.npc().id()+" ne peut pas lire la variable shared '"+value+"'.");
                    return null;
                }
                String stored=variables.get("shared",owner,value,null);return stored==null?c.value(value):parseStored(stored);
            }
            if(scope.equals("local")){String stored=variables.getLocal(c.npc().id(),c.npc().location().getWorld().getName(),value,null);return stored==null?c.value(value):parseStored(stored);}
        }
        return c.value(value);
    }
    private boolean isSharedMember(String members,String npcId){
        return Arrays.stream(members.split(",")).map(String::trim).anyMatch(member->member.equalsIgnoreCase(npcId));
    }
    private void createVariable(ScriptContext c,String declaration){
        String d=declaration.replace("(","").replace(")","").trim();
        String[] a=d.split("\\s+");
        if(a.length<2)return;
        String name=a[0],value=a[1];
        String scope=a.length>2?a[2].toLowerCase(Locale.ROOT):"local";
        if(scope.equals("global")){
            variables.set("meta","",name,"global|@");
            variables.setGlobal(name,value);
        }else if(scope.equals("shared")){
            if(a.length<4){
                plugin.getLogger().warning("Déclaration shared invalide pour '"+name+"' : liste de NPC autorisés manquante.");
                return;
            }
            String members=a[3].replace("[","").replace("]","").trim();
            if(!isSharedMember(members,c.npc().id())){
                plugin.getLogger().warning("Le NPC "+c.npc().id()+" doit faire partie des membres autorisés pour créer la variable shared '"+name+"'.");
                return;
            }
            variables.set("meta","",name,"shared|"+members);
            variables.set("meta",c.npc().id(),name,"shared|"+members);
            for(String member:members.split(","))variables.set("meta",member.trim(),name,"shared|"+members);
            variables.set("shared",members,name,value);
        }else{
            variables.set("meta",c.npc().id(),name,"local|"+c.npc().id());
            variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),name,value);
        }
        c.locals().put(name,parseStored(value));
    }
    private void storeVariable(ScriptContext c,String name,Object value){
        String meta=variables.get("meta",c.npc().id(),name,null);
        if(meta==null)meta=variables.get("meta","",name,"local|"+c.npc().id());
        String[] parts=meta.split("\\|",2);
        String scope=parts[0].toLowerCase(Locale.ROOT);
        String owner=parts.length>1?parts[1]:"";
        if(scope.equals("global")){
            variables.setGlobal(name,value);
        }else if(scope.equals("shared")){
            if(!isSharedMember(owner,c.npc().id())){
                plugin.getLogger().warning("Écriture refusée : le NPC "+c.npc().id()+" n'est pas autorisé à modifier la variable shared '"+name+"'.");
                return;
            }
            variables.set("shared",owner,name,value);
        }else{
            variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),name,value);
        }
    }
    private Object callFunction(ScriptContext caller,String invocation){
        int open=invocation.indexOf('('),close=invocation.lastIndexOf(')');
        if(open<=0||close<open){plugin.getLogger().warning("Appel de fonction invalide : "+invocation);return null;}
        String name=invocation.substring(0,open).trim().toLowerCase(Locale.ROOT);
        FunctionDefinition function=functions.get(name);
        if(function==null){plugin.getLogger().warning("Fonction inconnue : "+name);return null;}
        if(caller.callDepth()>=32){plugin.getLogger().warning("Profondeur maximale d'appel atteinte dans la fonction "+name);return null;}
        List<String> arguments=splitArguments(invocation.substring(open+1,close));
        if(arguments.size()!=function.parameters().size()){
            plugin.getLogger().warning("La fonction "+name+" attend "+function.parameters().size()+" paramètre(s), reçu "+arguments.size()+".");
            return null;
        }
        ScriptContext child=new ScriptContext(caller.npc(),caller.player());
        child.setCallDepth(caller.callDepth()+1);
        for(int i=0;i<arguments.size();i++){String parameter=function.parameters().get(i);child.locals().put(parameter,resolve(caller,arguments.get(i)));child.markTransientLocal(parameter);}
        if(!function.code().isEmpty())runBlock(child,function.code(),0,function.code().get(0).indent());
        return child.returnValue();
    }

    private List<String> splitArguments(String input){
        List<String> result=new ArrayList<>();
        if(input==null||input.isBlank())return result;
        StringBuilder current=new StringBuilder();
        boolean quoted=false; int depth=0;
        for(int i=0;i<input.length();i++){
            char ch=input.charAt(i);
            if(ch=='"'&&(i==0||input.charAt(i-1)!='\\'))quoted=!quoted;
            if(!quoted){
                if(ch=='(')depth++;
                else if(ch==')')depth--;
                else if(ch==','&&depth==0){result.add(current.toString().trim());current.setLength(0);continue;}
            }
            current.append(ch);
        }
        if(!current.isEmpty())result.add(current.toString().trim());
        return result;
    }

    private boolean balancedOuterParentheses(String expression){
        int depth=0;boolean quoted=false;
        for(int i=0;i<expression.length();i++){
            char ch=expression.charAt(i);
            if(ch=='"'&&(i==0||expression.charAt(i-1)!='\\'))quoted=!quoted;
            if(quoted)continue;
            if(ch=='(')depth++;
            else if(ch==')'){
                depth--;
                if(depth==0&&i<expression.length()-1)return false;
            }
        }
        return depth==0&&!quoted;
    }

    private int findTopLevelOperator(String expression,char wanted){
        boolean quoted=false;int depth=0;int found=-1;
        for(int i=0;i<expression.length();i++){
            char ch=expression.charAt(i);
            if(ch=='"'&&(i==0||expression.charAt(i-1)!='\\'))quoted=!quoted;
            if(quoted)continue;
            if(ch=='('){depth++;continue;}
            if(ch==')'){depth--;continue;}
            if(depth==0&&ch==wanted&&i>0)found=i;
        }
        return found;
    }

    private Object parseStored(String value){
        if(value==null)return null;
        if(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("false"))return Boolean.parseBoolean(value);
        try{return Double.parseDouble(value);}catch(NumberFormatException ignored){return value;}
    }
    private double num(Object o){try{return Double.parseDouble(String.valueOf(o));}catch(Exception e){return 0;}}
    private String unquote(String s){s=s.trim();return s.length()>=2&&s.startsWith("\"")&&s.endsWith("\"")?s.substring(1,s.length()-1):s;}
    private Player nearest(Location l){return l.getWorld().getPlayers().stream().min(Comparator.comparingDouble(p->p.getLocation().distanceSquared(l))).orElse(null);}
    private Location parseLocation(ScriptContext c,String s){if(s.equalsIgnoreCase("event npc")||s.equalsIgnoreCase("location of event npc"))return c.eventNpc()==null?null:c.eventNpc().location();if(s.equals("@p")){Player p=nearest(c.npc().location());return p==null?null:p.getLocation();}if(s.equalsIgnoreCase("location of npc"))return c.npc().location();if(s.startsWith("(")&&s.endsWith(")")){String[] a=s.substring(1,s.length()-1).split(",");if(a.length==3)try{return new Location(c.npc().location().getWorld(),Double.parseDouble(a[0].trim()),Double.parseDouble(a[1].trim()),Double.parseDouble(a[2].trim()));}catch(NumberFormatException ignored){}}return null;}
    private boolean move(ManagedNPC npc,Location target){
        Location start=npc.location();
        if(start.getWorld()==null||target.getWorld()==null||start.getWorld()!=target.getWorld())return false;
        List<Location> path=findPath(start,target);
        return animatePath(npc,target,path,"walk");
    }

    private boolean moveFlying(ManagedNPC npc,Location target){
        Location start=npc.location();
        if(start.getWorld()==null||target.getWorld()==null||start.getWorld()!=target.getWorld())return false;
        List<Location> path=findFlyingPath(start,target);
        return animatePath(npc,target,path,"fly");
    }

    private boolean animatePath(ManagedNPC npc,Location target,List<Location> path,String animation){
        if(path==null||path.isEmpty()){
            plugin.getLogger().warning("Aucun chemin trouvé pour le NPC "+npc.id()+" (limite d'essais : "+plugin.getConfig().getInt("movement.max-path-attempts",100)+").");
            return false;
        }
        BukkitTask previous=movementTasks.remove(npc);
        if(previous!=null){previous.cancel();tasks.remove(previous);}
        List<Location> points=new ArrayList<>(path);
        if(points.isEmpty()||points.get(points.size()-1).distanceSquared(target)>0.05)points.add(target.clone());
        if(points.size()==1)points.add(target.clone());
        int period=Math.max(1,plugin.getConfig().getInt("movement.update-ticks",1));
        double speed=npc.speed();
        int[] segment={0};
        int[] lastBlock={npc.location().getBlockX(),npc.location().getBlockY(),npc.location().getBlockZ()};
        BukkitTask[] holder=new BukkitTask[1];
        npc.animate(animation);
        holder[0]=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if(npc.tracker().isClosed()||!npc.location().getWorld().equals(target.getWorld())){
                holder[0].cancel();tasks.remove(holder[0]);movementTasks.remove(npc,holder[0]);return;
            }
            Location destination=points.get(Math.min(segment[0]+1,points.size()-1));
            Location current=npc.location();
            double distance=current.distance(destination);
            double step=speed*period/20.0;
            if(distance<=step||distance<0.001){
                npc.move(destination);
                fireWalkIfBlockChanged(npc,lastBlock);
                segment[0]++;
                if(segment[0]>=points.size()-1){
                    holder[0].cancel();tasks.remove(holder[0]);movementTasks.remove(npc,holder[0]);npc.animate("idle");
                }
            }else{
                double fraction=step/distance;
                Location next=current.clone().add((destination.getX()-current.getX())*fraction,
                        (destination.getY()-current.getY())*fraction,(destination.getZ()-current.getZ())*fraction);
                next.setYaw(destination.getYaw());next.setPitch(destination.getPitch());
                npc.move(next);
                fireWalkIfBlockChanged(npc,lastBlock);
            }
        },0L,period);
        movementTasks.put(npc,holder[0]);tasks.add(holder[0]);
        return true;
    }

    private void fireWalkIfBlockChanged(ManagedNPC npc,int[] lastBlock){
        Location location=npc.location();
        int x=location.getBlockX(),y=location.getBlockY(),z=location.getBlockZ();
        if(x!=lastBlock[0]||y!=lastBlock[1]||z!=lastBlock[2]){
            lastBlock[0]=x;lastBlock[1]=y;lastBlock[2]=z;
            fireOn(npc,"walk",null);
        }
    }

    private List<Location> findFlyingPath(Location start,Location target){
        org.bukkit.World world=start.getWorld();
        int sx=start.getBlockX(),sy=start.getBlockY(),sz=start.getBlockZ();
        int tx=target.getBlockX(),ty=target.getBlockY(),tz=target.getBlockZ();
        if(!canFly(world,tx,ty,tz)){
            boolean adjusted=false;
            for(int radius=1;radius<=2&&!adjusted;radius++){
                for(int dx=-radius;dx<=radius&&!adjusted;dx++)for(int dy=-radius;dy<=radius&&!adjusted;dy++)for(int dz=-radius;dz<=radius;dz++){
                    if(canFly(world,tx+dx,ty+dy,tz+dz)){tx+=dx;ty+=dy;tz+=dz;adjusted=true;break;}
                }
            }
        }
        int maxAttempts=Math.max(1,plugin.getConfig().getInt("movement.max-path-attempts",100));
        PriorityQueue<PathNode> open=new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<String,Double> best=new HashMap<>();
        open.add(new PathNode(sx,sy,sz,0,heuristic(sx,sy,sz,tx,ty,tz),null));
        best.put(pathKey(sx,sy,sz),0.0);
        PathNode found=null;int attempts=0;
        while(!open.isEmpty()&&attempts<maxAttempts){
            PathNode current=open.poll();attempts++;
            if(current.x()==tx&&current.y()==ty&&current.z()==tz){found=current;break;}
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                if(dx==0&&dy==0&&dz==0)continue;
                int x=current.x()+dx,y=current.y()+dy,z=current.z()+dz;
                if(!canFly(world,x,y,z))continue;
                double cost=current.cost()+Math.sqrt(dx*dx+dy*dy+dz*dz);
                String key=pathKey(x,y,z);
                if(cost>=best.getOrDefault(key,Double.POSITIVE_INFINITY))continue;
                best.put(key,cost);
                open.add(new PathNode(x,y,z,cost,cost+heuristic(x,y,z,tx,ty,tz),current));
            }
        }
        if(found==null)return null;
        LinkedList<Location> result=new LinkedList<>();
        for(PathNode node=found;node!=null;node=node.parent())result.addFirst(new Location(world,node.x()+0.5,node.y()+0.5,node.z()+0.5));
        result.add(target.clone());
        return result;
    }

    private void playScriptSound(ScriptContext context,String raw){
        String soundName=unquote(raw).trim();
        String lower=soundName.toLowerCase(Locale.ROOT);
        if(lower.startsWith("itemsadder:")||lower.contains(":")){
            String id=lower.startsWith("itemsadder:")?soundName.substring(11):soundName;
            if(Bukkit.getPluginManager().getPlugin("ItemsAdder")==null){
                plugin.getLogger().warning("ItemsAdder n'est pas installé : son personnalisé '"+id+"' ignoré.");
                return;
            }
            try{
                Class<?> customSound=Class.forName("dev.lone.itemsadder.api.CustomSound");
                boolean invoked=false;
                for(Method method:customSound.getMethods()){
                    if(!method.getName().equals("playSound")||!Modifier.isStatic(method.getModifiers()))continue;
                    Class<?>[] parameters=method.getParameterTypes();
                    if(parameters.length==4&&parameters[1]==String.class&&parameters[2]==float.class&&parameters[3]==float.class){
                        if(parameters[0].isAssignableFrom(Location.class)){
                            method.invoke(null,context.npc().location(),id,1.0f,1.0f);
                            invoked=true;break;
                        }
                        if(parameters[0].isAssignableFrom(Player.class)){
                            for(Player player:context.npc().location().getWorld().getPlayers())
                                if(player.getLocation().distanceSquared(context.npc().location())<=32*32)
                                    method.invoke(null,player,id,1.0f,1.0f);
                            invoked=true;break;
                        }
                    }
                }
                if(!invoked)plugin.getLogger().warning("API de son ItemsAdder non reconnue pour '"+id+"'.");
            }catch(Exception exception){
                plugin.getLogger().warning("Lecture du son ItemsAdder '"+id+"' impossible : "+exception.getMessage());
            }
            return;
        }
        try{
            Sound sound=Sound.valueOf(soundName.toUpperCase(Locale.ROOT).replace('.','_'));
            context.npc().location().getWorld().playSound(context.npc().location(),sound,1.0f,1.0f);
        }catch(IllegalArgumentException exception){
            plugin.getLogger().warning("Son inconnu : "+soundName);
        }
    }

    private void simulateBreakBlock(Location location){
        Block block=location.getBlock();
        Location center=block.getLocation().add(0.5,0.5,0.5);
        block.getWorld().spawnParticle(Particle.BLOCK,center,24,0.25,0.25,0.25,block.getBlockData());
        block.getWorld().playSound(center,Sound.BLOCK_STONE_BREAK,0.8f,1.0f);
    }

    private void simulatePlaceBlock(Location location,Material material){
        Location center=location.getBlock().getLocation().add(0.5,0.5,0.5);
        location.getWorld().spawnParticle(Particle.BLOCK,center,18,0.2,0.2,0.2,material.createBlockData());
        location.getWorld().playSound(center,Sound.BLOCK_STONE_PLACE,0.8f,1.0f);
    }

    private boolean canFly(org.bukkit.World world,int x,int y,int z){
        if(y<world.getMinHeight()||y+1>=world.getMaxHeight())return false;
        return world.getBlockAt(x,y,z).isPassable()&&world.getBlockAt(x,y+1,z).isPassable();
    }

    private void startFollow(ManagedNPC npc,String expression){
        String targetSelector=expression;double min=2.0,max=5.0;
        int at=expression.toLowerCase(Locale.ROOT).lastIndexOf(" at ");
        int minIndex=expression.toLowerCase(Locale.ROOT).indexOf(" min ");
        if(at>0){targetSelector=expression.substring(0,at).trim();double distance=parseDistance(expression.substring(at+4).trim(),2.0);min=Math.max(0.5,distance-0.2);max=distance+0.2;}
        else if(minIndex>0){
            int maxIndex=expression.toLowerCase(Locale.ROOT).indexOf(" max ",minIndex+5);
            if(maxIndex<0){plugin.getLogger().warning("Syntaxe follow invalide : utilise follow <joueur> min <distance> max <distance>.");return;}
            targetSelector=expression.substring(0,minIndex).trim();
            min=parseDistance(expression.substring(minIndex+5,maxIndex).trim(),2.0);
            max=parseDistance(expression.substring(maxIndex+5).trim(),5.0);
        }
        Player target=resolveFollowTarget(npc,targetSelector);
        if(target==null||!target.getWorld().equals(npc.location().getWorld())){
            plugin.getLogger().warning("Cible de follow introuvable ou dans un autre monde pour "+npc.id()+".");return;
        }
        startFollow(npc,target.getUniqueId().toString(),min,max);
    }

    private void startKeepDistance(ManagedNPC npc,String expression){
        int from=expression.toLowerCase(Locale.ROOT).indexOf(" from ");
        if(from<1){plugin.getLogger().warning("Syntaxe attendue : keep distance <distance> from <joueur>.");return;}
        double distance=parseDistance(expression.substring(0,from).trim(),4.0);
        startFollow(npc,expression.substring(from+6).trim(),Math.max(0.5,distance-0.2),distance+0.2);
    }

    private double parseDistance(String raw,double fallback){try{return Math.max(0.5,Double.parseDouble(raw));}catch(NumberFormatException exception){return fallback;}}

    private Player resolveFollowTarget(ManagedNPC npc,String selector){
        if(selector.equalsIgnoreCase("@p"))return nearest(npc.location());
        try{return Bukkit.getPlayer(UUID.fromString(selector));}catch(IllegalArgumentException ignored){return Bukkit.getPlayerExact(selector);}
    }

    private void startFollow(ManagedNPC npc,String targetId,double min,double max){
        stopFollowTask(npc);
        npc.setFollowState(targetId,min,max);
        UUID targetUuid;
        try{targetUuid=UUID.fromString(targetId);}catch(IllegalArgumentException exception){return;}
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            Player target=Bukkit.getPlayer(targetUuid);
            if(target==null||!target.getWorld().equals(npc.location().getWorld())){stopFollow(npc);return;}
            double distance=target.getLocation().distance(npc.location());
            if(distance>=npc.followMin()&&distance<=npc.followMax())return;
            if(movementTasks.containsKey(npc))return;
            double desired=distance>npc.followMax()?npc.followMax():npc.followMin();
            org.bukkit.util.Vector away=npc.location().toVector().subtract(target.getLocation().toVector());
            if(away.lengthSquared()<0.001)away=new org.bukkit.util.Vector(1,0,0);
            Location destination=target.getLocation().clone().add(away.normalize().multiply(desired));
            destination.setY(target.getLocation().getY());
            if(!move(npc,destination))stopFollow(npc);
        },0L,10L);
        followTasks.put(npc,task);tasks.add(task);
    }

    private void setHostile(ManagedNPC npc,boolean hostile){
        npc.setHostile(hostile);
        if(hostile)startCombat(npc);else stopCombat(npc);
    }

    private void setTargetSelector(ManagedNPC npc,String selector){
        npc.setTargetSelector(selector);
        if(npc.hostile()){stopCombatTask(npc);startCombat(npc);}
    }

    private void startCombat(ManagedNPC npc){
        stopCombatTask(npc);
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if(!npc.hostile()||npc.tracker().isClosed()){stopCombat(npc);return;}
            LivingEntity target=chooseCombatTarget(npc);
            if(target==null){npc.setCombatTarget(null);return;}
            npc.setCombatTarget(target.getUniqueId());
            double distance=target.getLocation().distance(npc.location());
            double range=plugin.getConfig().getDouble("combat.attack-range",2.5);
            if(distance>range){
                if(!movementTasks.containsKey(npc))move(npc,target.getLocation());
                return;
            }
            long now=System.currentTimeMillis();
            long cooldown=plugin.getConfig().getLong("combat.cooldown-ticks",20)*50L;
            if(now-lastAttackTimes.getOrDefault(npc,0L)<cooldown)return;
            lastAttackTimes.put(npc,now);
            npc.animate("attack");
            npc.location().getWorld().playSound(npc.location(),Sound.ENTITY_PLAYER_ATTACK_STRONG,0.8f,1.0f);
            target.damage(plugin.getConfig().getDouble("combat.damage",2.0),npc.hitbox());
        },0L,10L);
        combatTasks.put(npc,task);tasks.add(task);
    }

    private LivingEntity chooseCombatTarget(ManagedNPC npc){
        double maxRange=plugin.getConfig().getDouble("combat.target-range",24.0);
        UUID attacker=npc.lastAttacker();
        if(attacker!=null){
            org.bukkit.entity.Entity entity=Bukkit.getEntity(attacker);
            if(entity instanceof LivingEntity living&&living.getWorld().equals(npc.location().getWorld())&&isEligibleTarget(npc,living)
                    &&living.getLocation().distanceSquared(npc.location())<=maxRange*maxRange)return living;
        }
        return npc.location().getWorld().getLivingEntities().stream()
                .filter(entity->entity!=npc.hitbox()&&entity.isValid()&&!entity.isDead())
                .filter(entity->entity.getLocation().distanceSquared(npc.location())<=maxRange*maxRange)
                .filter(entity->isEligibleTarget(npc,entity))
                .min(Comparator.comparingDouble(entity->entity.getLocation().distanceSquared(npc.location())))
                .orElse(null);
    }

    private boolean isEligibleTarget(ManagedNPC npc,LivingEntity entity){
        if(entity.getUniqueId().equals(npc.hitbox().getUniqueId()))return false;
        for(String raw:npc.targetSelector().split(",")){
            String selector=raw.trim().toLowerCase(Locale.ROOT);
            if(selector.equals("all players")||selector.equals("players")||selector.equals("@p")){if(entity instanceof Player)return true;}
            else if(selector.startsWith("player:")||selector.startsWith("player ")){
                String name=raw.substring(7).trim();if(entity instanceof Player player&&player.getName().equalsIgnoreCase(name))return true;
            }else if(selector.equals("all mobs")||selector.equals("mobs")){
                if(!(entity instanceof Player))return true;
            }else if(selector.equals("all entities")||selector.equals("entities")){
                return true;
            }else if(selector.equals("monsters")||selector.equals("category:monsters")){
                if(entity instanceof Monster)return true;
            }else if(selector.equals("animals")||selector.equals("category:animals")){
                if(entity instanceof Animals)return true;
            }else if(selector.startsWith("mob:")||selector.startsWith("mob ")){
                String type=raw.substring(4).trim().toUpperCase(Locale.ROOT);
                if(entity.getType().name().equals(type))return true;
            }else if(selector.startsWith("entity:")){
                String type=raw.substring(7).trim().toUpperCase(Locale.ROOT);
                if(entity.getType().name().equals(type))return true;
            }else if(selector.startsWith("mythic:")){
                if(isMythicMob(entity,raw.substring(7).trim()))return true;
            }else if(entity instanceof Player player&&!selector.contains(" ")&&!selector.contains(":")&&player.getName().equalsIgnoreCase(raw.trim())){return true;
            }else if(selector.startsWith("category:")){
                String category=selector.substring(9);
                if(category.equals("players")&&entity instanceof Player)return true;
                if(category.equals("mobs")&&!(entity instanceof Player))return true;
                if(category.equals("monsters")&&entity instanceof Monster)return true;
                if(category.equals("animals")&&entity instanceof Animals)return true;
            }
        }
        return false;
    }

    private boolean isMythicMob(LivingEntity entity,String expectedId){
        try{
            Class<?> apiClass=Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythic=apiClass.getMethod("inst").invoke(null);
            Object helper=apiClass.getMethod("getAPIHelper").invoke(mythic);
            Object instance=helper.getClass().getMethod("getMythicMobInstance",org.bukkit.entity.Entity.class).invoke(helper,entity);
            if(instance==null)return false;
            Object type=instance.getClass().getMethod("getType").invoke(instance);
            Object internal=type.getClass().getMethod("getInternalName").invoke(type);
            return expectedId.equalsIgnoreCase(String.valueOf(internal));
        }catch(Exception ignored){return false;}
    }

    private void stopCombatTask(ManagedNPC npc){
        BukkitTask task=combatTasks.remove(npc);
        if(task!=null){task.cancel();tasks.remove(task);}
    }

    private void stopCombat(ManagedNPC npc){
        stopCombatTask(npc);
        npc.setCombatTarget(null);
        if(npc.followTarget().isBlank())stopMovement(npc);
    }

    private void stopFollowTask(ManagedNPC npc){
        BukkitTask task=followTasks.remove(npc);
        if(task!=null){task.cancel();tasks.remove(task);}
    }

    private void stopMovement(ManagedNPC npc){
        BukkitTask task=movementTasks.remove(npc);
        if(task!=null){task.cancel();tasks.remove(task);}
        if(!npc.tracker().isClosed())npc.animate("idle");
    }

    private void stopFollow(ManagedNPC npc){stopFollowTask(npc);stopMovement(npc);npc.clearFollowState();}

    public void pauseNpc(String id){
        for(ManagedNPC npc:npcs.instances(id)){stopFollowTask(npc);stopCombatTask(npc);stopMovement(npc);}
    }

    public void resumeNpc(String id){
        for(ManagedNPC npc:npcs.instances(id)){
            if(!npc.followTarget().isBlank())startFollow(npc,npc.followTarget(),npc.followMin(),npc.followMax());
            if(npc.hostile())startCombat(npc);
        }
    }

    private List<Location> findPath(Location start,Location target){
        org.bukkit.World world=start.getWorld();
        int sx=start.getBlockX(),sy=start.getBlockY(),sz=start.getBlockZ();
        int tx=target.getBlockX(),ty=target.getBlockY(),tz=target.getBlockZ();
        if(!canStand(world,tx,ty,tz)){
            int[] offsets={1,-1,2,-2};
            for(int offset:offsets)if(canStand(world,tx,ty+offset,tz)){ty+=offset;break;}
        }
        int maxAttempts=Math.max(1,plugin.getConfig().getInt("movement.max-path-attempts",100));
        PriorityQueue<PathNode> open=new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<String,Double> best=new HashMap<>();
        PathNode first=new PathNode(sx,sy,sz,0,heuristic(sx,sy,sz,tx,ty,tz),null);
        open.add(first);best.put(pathKey(sx,sy,sz),0.0);
        PathNode found=null;int attempts=0;
        while(!open.isEmpty()&&attempts<maxAttempts){
            PathNode current=open.poll();attempts++;
            if(current.x()==tx&&current.y()==ty&&current.z()==tz){found=current;break;}
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0&&dz==0)continue;
                int x=current.x()+dx,y=current.y()+dy,z=current.z()+dz;
                if(!canStand(world,x,y,z))continue;
                if(dx!=0&&dz!=0&&(!canStand(world,current.x()+dx,current.y(),current.z())
                        ||!canStand(world,current.x(),current.y(),current.z()+dz)))continue;
                double step=Math.sqrt(dx*dx+dz*dz)+Math.abs(dy)*1.2;
                double cost=current.cost()+step;
                String key=pathKey(x,y,z);
                if(cost>=best.getOrDefault(key,Double.POSITIVE_INFINITY))continue;
                best.put(key,cost);
                open.add(new PathNode(x,y,z,cost,cost+heuristic(x,y,z,tx,ty,tz),current));
            }
        }
        if(found==null)return null;
        LinkedList<Location> result=new LinkedList<>();
        for(PathNode node=found;node!=null;node=node.parent())
            result.addFirst(new Location(world,node.x()+0.5,node.y(),node.z()+0.5));
        result.add(target.clone());
        return result;
    }

    private boolean canStand(org.bukkit.World world,int x,int y,int z){
        if(y<world.getMinHeight()||y+1>=world.getMaxHeight())return false;
        return world.getBlockAt(x,y,z).isPassable()
                &&world.getBlockAt(x,y+1,z).isPassable()
                &&world.getBlockAt(x,y-1,z).getType().isSolid();
    }

    private double heuristic(int x,int y,int z,int tx,int ty,int tz){
        return Math.sqrt((x-tx)*(x-tx)+(z-tz)*(z-tz))+Math.abs(y-ty)*1.2;
    }

    private String pathKey(int x,int y,int z){return x+":"+y+":"+z;}

    private long parseDurationMillis(String raw){
        String value=raw.trim().toLowerCase(Locale.ROOT);
        try{
            if(value.endsWith("ticks"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-5).trim())*50L);
            if(value.endsWith("tick"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-4).trim())*50L);
            if(value.endsWith("ms"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-2).trim()));
            if(value.endsWith("minutes"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-7).trim())*60_000L);
            if(value.endsWith("minute"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-6).trim())*60_000L);
            if(value.endsWith("m"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-1).trim())*60_000L);
            if(value.endsWith("seconds"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-7).trim())*1_000L);
            if(value.endsWith("second"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-6).trim())*1_000L);
            if(value.endsWith("s"))return Math.max(0L,Long.parseLong(value.substring(0,value.length()-1).trim())*1_000L);
            return Math.max(0L,Long.parseLong(value)*50L);
        }catch(NumberFormatException exception){return 0L;}
    }

    private long parseDurationTicks(String s){String[] a=s.trim().split("\\s+");try{long n=Long.parseLong(a[0]);return a.length>1&&a[1].startsWith("second")?n*20:n;}catch(Exception e){return 1;}}
    @EventHandler public void onNpcRightClick(PlayerInteractAtEntityEvent e){
        String id=e.getRightClicked().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);fire(id,e.getPlayer().isSneaking()?"right click while sneaking":"right click",e.getPlayer());if(!e.getPlayer().getInventory().getItemInMainHand().getType().isAir())fire(id,"item click",e.getPlayer());}
    }
    @EventHandler public void onNpcLeftClick(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player p))return;
        String id=e.getEntity().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);ManagedNPC npc=npcs.get(id,p.getWorld());if(npc!=null)npc.setLastAttacker(p.getUniqueId());fire(id,p.isSneaking()?"left click while sneaking":"left click",p);fire(id,"damage",p);}
    }
    @EventHandler public void onPlayerCommand(PlayerCommandPreprocessEvent event){
        Player player=event.getPlayer();
        String commandText=event.getMessage();
        for(ManagedNPC npc:npcs.all())if(npc.location().getWorld().equals(player.getWorld()))
            fire(npc.id(),"player command",player,commandText);
    }
    @EventHandler public void onPlayerChat(AsyncChatEvent event){
        Player player=event.getPlayer();
        String message=PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin,()->{
            for(ManagedNPC npc:npcs.all())if(npc.location().getWorld().equals(player.getWorld()))
                fire(npc.id(),"player chat",player,message);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e){
        for(ManagedNPC npc:npcs.all()){
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())&&e.getPlayer().getLocation().distanceSquared(npc.location())<64*64)npc.show(e.getPlayer());
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())){
                fire(npc.id(),"player join",e.getPlayer());
                fire(npc.id(),"player connect",e.getPlayer());
            }
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){
        for(ManagedNPC npc:npcs.all()){
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())){
                fire(npc.id(),"player quit",e.getPlayer());
                fire(npc.id(),"player disconnect",e.getPlayer());
            }
            npc.hide(e.getPlayer());
        }
    }
    public void shutdown(){tasks.forEach(BukkitTask::cancel);tasks.clear();movementTasks.clear();followTasks.clear();combatTasks.clear();lastAttackTimes.clear();intervalTasks.clear();watchdogTasks.clear();scripts.clear();functions.clear();nearbyPlayers.clear();nearbyNpcs.clear();}
}
