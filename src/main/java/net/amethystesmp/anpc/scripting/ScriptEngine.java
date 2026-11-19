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
import org.bukkit.inventory.ItemStack;
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
    private final Map<String,Long> scriptGenerations=new ConcurrentHashMap<>();
    private final Map<String,Long> scriptLastRun=new ConcurrentHashMap<>();
    private final Map<String,Long> scriptLastChange=new ConcurrentHashMap<>();
    private final Map<String,Long> scriptLastWarning=new ConcurrentHashMap<>();
    private BukkitTask scriptWatchdogTask;
    private BukkitTask proximityTask;
    private final Map<String,Set<UUID>> nearbyEntities=new ConcurrentHashMap<>();
    private final Map<String,Set<String>> nearbyNpcs=new ConcurrentHashMap<>();
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll(){
        scriptGenerations.replaceAll((id,generation)->generation+1L);
        for(BukkitTask interval:intervalTasks.values()){interval.cancel();tasks.remove(interval);}
        intervalTasks.clear();
        scripts.clear(); functions.clear(); scriptLastRun.clear(); scriptLastChange.clear(); scriptLastWarning.clear();
        File dir=new File(plugin.getDataFolder(),"scripts");
        File[] fs=dir.listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".aa"));
        if(fs==null)return;
        for(File file:fs){
            String id=file.getName().substring(0,file.getName().length()-3).toLowerCase(Locale.ROOT);
            scriptGenerations.merge(id,1L,Long::sum);
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
        scheduleIntervals(); scheduleProximity(); startScriptWatchdog();
        for(ManagedNPC npc:npcs.all()){
            Map<String,List<Instruction>> map=scripts.get(npc.id().toLowerCase(Locale.ROOT));
            if(map!=null&&map.get("spawn")!=null&&npc.scriptsEnabled())execute(npc,null,map.get("spawn"),0);
            if(map!=null&&map.get("reload")!=null&&npc.scriptsEnabled())execute(npc,null,map.get("reload"),0);
        }
    }
    private Map<String,List<Instruction>> parse(List<String> lines){
        Map<String,List<Instruction>> result=new LinkedHashMap<>(); String event=null;
        for(int i=0;i<lines.size();i++){
            String raw=lines.get(i);
            if(raw.isBlank()||raw.stripLeading().startsWith("#"))continue;
            int indent=raw.length()-raw.stripLeading().length(); String text=raw.trim();
            if(indent==0&&(text.toLowerCase(Locale.ROOT).startsWith("function ")||text.toLowerCase(Locale.ROOT).startsWith("def "))){event=null;continue;}
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
        for(String eventKey:new ArrayList<>(result.keySet())){
            if(!validateBlockStructure(result.get(eventKey),"événement '"+eventKey+"'"))result.remove(eventKey);
        }
        return result;
    }
    private Map<String,FunctionDefinition> parseFunctions(List<String> lines){
        Map<String,FunctionDefinition> result=new LinkedHashMap<>();
        for(int i=0;i<lines.size();i++){
            String raw=lines.get(i);
            if(raw.isBlank()||raw.stripLeading().startsWith("#")||!raw.equals(raw.stripLeading()))continue;
            String header=raw.trim();
            boolean functionKeyword=header.toLowerCase(Locale.ROOT).startsWith("function ");
            boolean defKeyword=header.toLowerCase(Locale.ROOT).startsWith("def ");
            if(!functionKeyword&&!defKeyword)continue;
            if(!header.endsWith(":")){
                plugin.getLogger().warning("Déclaration de fonction invalide ligne "+(i+1)+" : ':' manquant.");
                continue;
            }
            int open=header.indexOf('('),close=header.lastIndexOf(')');
            if(open<(functionKeyword?10:5)||close<open||close!=header.length()-2){plugin.getLogger().warning("Déclaration de fonction invalide ligne "+(i+1)+" : "+header);continue;}
            String name=header.substring(functionKeyword?9:4,open).trim().toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z_][a-z0-9_-]*")){
                plugin.getLogger().warning("Nom de fonction invalide ligne "+(i+1)+" : '"+name+"'.");
                continue;
            }
            List<String> parameters=new ArrayList<>();
            Set<String> parameterNames=new HashSet<>();
            String rawParameters=header.substring(open+1,close).trim();
            if(!rawParameters.isBlank()){
                boolean invalidParameters=false;
                for(String rawParameter:splitArguments(rawParameters)){
                    String parameter=rawParameter.trim().toLowerCase(Locale.ROOT);
                    if(!parameter.matches("[a-z_][a-z0-9_-]*")||!parameterNames.add(parameter)){
                        plugin.getLogger().warning("Paramètre invalide ou dupliqué '"+rawParameter+"' dans la fonction '"+name+"' (ligne "+(i+1)+").");
                        invalidParameters=true;break;
                    }
                    parameters.add(parameter);
                }
                if(invalidParameters)continue;
            }
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
            boolean invalidBody=invalidIndent||!validateBlockStructure(body,"fonction '"+name+"'");
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
    private boolean validateBlockStructure(List<Instruction> code,String owner){
        if(code.isEmpty())return true;
        boolean valid=true;
        if(code.get(0).indent()!=4){
            plugin.getLogger().warning("Indentation invalide dans "+owner+" ligne "+code.get(0).line()+" : le premier niveau doit utiliser 4 espaces.");
            valid=false;
        }
        for(int i=0;i<code.size();i++){
            Instruction current=code.get(i);
            if(current.indent()<4||current.indent()%4!=0){
                plugin.getLogger().warning("Indentation invalide dans "+owner+" ligne "+current.line()+".");
                valid=false;
            }
            if(i==0)continue;
            Instruction previous=code.get(i-1);
            if(isBlockHeader(previous.text())&&current.indent()<=previous.indent()){
                plugin.getLogger().warning("Bloc vide ou indentation manquante après la ligne "+previous.line()+" dans "+owner+".");
                valid=false;
            }
            if(current.indent()>previous.indent()){
                if(!isBlockHeader(previous.text())||current.indent()!=previous.indent()+4){
                    plugin.getLogger().warning("Niveau d'indentation inattendu ligne "+current.line()+" dans "+owner+".");
                    valid=false;
                }
            }
            String currentText=current.text().trim().toLowerCase(Locale.ROOT);
            if(currentText.equals("else:")||currentText.startsWith("else if ")){
                int previousAtLevel=i-1;
                while(previousAtLevel>=0&&code.get(previousAtLevel).indent()>current.indent())previousAtLevel--;
                if(previousAtLevel<0||code.get(previousAtLevel).indent()!=current.indent()
                        ||!(code.get(previousAtLevel).text().startsWith("if ")||code.get(previousAtLevel).text().startsWith("else if "))){
                    plugin.getLogger().warning("Branche else sans if correspondant ligne "+current.line()+" dans "+owner+".");
                    valid=false;
                }
            }
        }
        Instruction last=code.get(code.size()-1);
        if(isBlockHeader(last.text())){
            plugin.getLogger().warning("Bloc sans contenu après la ligne "+last.line()+" dans "+owner+".");
            valid=false;
        }
        return valid;
    }

    private boolean isBlockHeader(String instruction){
        String text=instruction.trim().toLowerCase(Locale.ROOT);
        return (text.startsWith("if ")||text.startsWith("else if ")||text.startsWith("loop "))&&text.endsWith(":")||text.equals("else:");
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

    public void clearNpcTracking(String requestedId){
        String prefix=requestedId.toLowerCase(Locale.ROOT)+"@";
        nearbyEntities.keySet().removeIf(key->key.startsWith(prefix));
        nearbyNpcs.keySet().removeIf(key->key.startsWith(prefix));
        scriptLastRun.keySet().removeIf(key->key.startsWith(prefix));
        scriptLastChange.keySet().removeIf(key->key.startsWith(prefix));
        scriptLastWarning.keySet().removeIf(key->key.startsWith(prefix));
    }

    public void unloadScript(String requestedId){
        String id=requestedId.toLowerCase(Locale.ROOT).replaceFirst("\\.aa$","");
        if(!id.matches("[a-z0-9_-]+"))return;
        clearNpcTracking(id);
        scriptGenerations.merge(id,1L,Long::sum);
        scripts.remove(id);
        BukkitTask interval=intervalTasks.remove(id);
        if(interval!=null){interval.cancel();tasks.remove(interval);}
        scriptLastRun.keySet().removeIf(key->key.startsWith(id+"@"));
        scriptLastChange.keySet().removeIf(key->key.startsWith(id+"@"));
        scriptLastWarning.keySet().removeIf(key->key.startsWith(id+"@"));
        File directory=new File(plugin.getDataFolder(),"scripts");
        File[] files=directory.listFiles((folder,name)->name.toLowerCase(Locale.ROOT).endsWith(".aa"));
        functions.clear();
        if(files==null)return;
        Arrays.sort(files,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        for(File file:files){
            try{
                List<String> lines=Files.readAllLines(file.toPath());
                for(var function:parseFunctions(lines).entrySet()){
                    if(functions.putIfAbsent(function.getKey(),function.getValue())!=null)
                        plugin.getLogger().warning("Fonction globale dupliquée '"+function.getKey()+"' dans "+file.getName()+" (déclaration ignorée).");
                }
            }catch(Exception exception){
                plugin.getLogger().warning("Lecture des fonctions de "+file.getName()+" impossible : "+exception.getMessage());
            }
        }
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
            scriptGenerations.merge(id,1L,Long::sum);
            functions.clear();functions.putAll(parsedFunctions);
            BukkitTask old=intervalTasks.remove(id);
            if(old!=null){old.cancel();tasks.remove(old);}
            List<Instruction> interval=scripts.get(id).get("interval");
            if(interval!=null)scheduleInterval(id,interval);
            List<Instruction> spawn=scripts.get(id).get("spawn");
            List<Instruction> reload=scripts.get(id).get("reload");
            for(ManagedNPC npc:npcs.instances(id))if(npc.scriptsEnabled()){
                if(spawn!=null)execute(npc,null,spawn,0);
                if(reload!=null)execute(npc,null,reload,0);
            }
            return true;
        }catch(Exception exception){
            plugin.getLogger().warning("Rechargement du script '"+id+"' impossible : "+exception.getMessage());
            return false;
        }
    }
    private void scheduleProximity(){
        if(proximityTask!=null){proximityTask.cancel();tasks.remove(proximityTask);}
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            double radius=plugin.getConfig().getDouble("script-approach-radius",3.0),r2=radius*radius;
            List<ManagedNPC> all=new ArrayList<>(npcs.all());
            for(ManagedNPC npc:all){
                String sourceKey=npc.id().toLowerCase(Locale.ROOT)+"@"+npc.location().getWorld().getName().toLowerCase(Locale.ROOT);
                Set<UUID> currentEntities=new HashSet<>();
                for(Entity entity:npc.location().getWorld().getEntities()){
                    if(!(entity instanceof LivingEntity)||!entity.isValid()||entity==npc.hitbox()||entity.getScoreboardTags().stream().anyMatch(tag->tag.startsWith("anpc:")))continue;
                    if(entity.getLocation().distanceSquared(npc.location())>r2)continue;
                    currentEntities.add(entity.getUniqueId());
                    if(nearbyEntities.computeIfAbsent(sourceKey,key->ConcurrentHashMap.newKeySet()).add(entity.getUniqueId()))
                        fireEntity(npc,"entity approach",entity);
                }
                Set<UUID> previous=nearbyEntities.computeIfAbsent(sourceKey,key->ConcurrentHashMap.newKeySet());
                for(UUID uuid:new HashSet<>(previous))if(!currentEntities.contains(uuid)){
                    previous.remove(uuid);
                    Entity entity=Bukkit.getEntity(uuid);
                    if(entity!=null)fireEntity(npc,"entity leave",entity);
                }

                Set<String> currentNpcs=new HashSet<>();
                for(ManagedNPC other:all){
                    if(other==npc||!other.location().getWorld().equals(npc.location().getWorld()))continue;
                    if(other.location().distanceSquared(npc.location())<=r2){
                        String targetKey=other.id().toLowerCase(Locale.ROOT)+"@"+other.location().getWorld().getName().toLowerCase(Locale.ROOT);
                        currentNpcs.add(targetKey);
                        if(nearbyNpcs.computeIfAbsent(sourceKey,key->ConcurrentHashMap.newKeySet()).add(targetKey))
                            fireOn(npc,"npc approach",other);
                    }
                }
                Set<String> previousNpcs=nearbyNpcs.computeIfAbsent(sourceKey,key->ConcurrentHashMap.newKeySet());
                for(String targetKey:new HashSet<>(previousNpcs))if(!currentNpcs.contains(targetKey)){
                    previousNpcs.remove(targetKey);
                    ManagedNPC other=all.stream().filter(candidate->(candidate.id().toLowerCase(Locale.ROOT)+"@"+candidate.location().getWorld().getName().toLowerCase(Locale.ROOT)).equals(targetKey)).findFirst().orElse(null);
                    if(other!=null)fireOn(npc,"npc leave",other);
                }
            }
        },10L,10L);
        proximityTask=task;
        tasks.add(task);
    }

    private void fireEntity(ManagedNPC npc,String event,Entity entity){
        Map<String,List<Instruction>> map=scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if(map==null)return;
        List<Instruction> code=map.get(event);
        if(code==null||!npc.scriptsEnabled())return;
        Player player=entity instanceof Player selected?selected:null;
        ItemStack item=player==null?null:player.getInventory().getItemInMainHand();
        execute(npc,player,code,0,"",null,entity,item,null);
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
        execute(npc,player,code,baseIndent,eventData,eventNpc,player,player==null?null:player.getInventory().getItemInMainHand(),null);
    }

    private void execute(ManagedNPC npc,Player player,List<Instruction> code,int baseIndent,String eventData,ManagedNPC eventNpc,Entity eventEntity,ItemStack eventItem,Location eventPlace){
        String key=npcInstanceKey(npc);
        long now=System.currentTimeMillis();
        Long previousRun=scriptLastRun.put(key,now);
        if(previousRun==null||now-previousRun>60_000L)scriptLastChange.put(key,now);
        ScriptContext ctx=new ScriptContext(npc,player);
        ctx.setEventData(eventData);
        ctx.setEventNpc(eventNpc);
        ctx.setEventEntity(eventEntity!=null?eventEntity:(eventNpc==null?null:eventNpc.hitbox()));
        ctx.setEventItem(eventItem);
        ctx.setEventPlace(eventPlace);
        ctx.setScriptExecution(npc.id().toLowerCase(Locale.ROOT),scriptGenerations.getOrDefault(npc.id().toLowerCase(Locale.ROOT),0L));
        int indent=code.isEmpty()?baseIndent:code.get(0).indent();
        runBlock(ctx,code,0,indent);
    }

    private String npcInstanceKey(ManagedNPC npc){
        return npc.id().toLowerCase(Locale.ROOT)+"@"+npc.location().getWorld().getName().toLowerCase(Locale.ROOT);
    }

    private void markChanged(ScriptContext context){
        context.markChanged();
        scriptLastChange.put(npcInstanceKey(context.npc()),System.currentTimeMillis());
    }

    private void startScriptWatchdog(){
        if(scriptWatchdogTask!=null){scriptWatchdogTask.cancel();tasks.remove(scriptWatchdogTask);}
        scriptWatchdogTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            for(String key:new HashSet<>(scriptLastRun.keySet())){
                long lastRun=scriptLastRun.getOrDefault(key,0L);
                if(now-lastRun>60_000L){scriptLastRun.remove(key);scriptLastChange.remove(key);scriptLastWarning.remove(key);continue;}
                long lastChange=scriptLastChange.getOrDefault(key,lastRun);
                if(now-lastRun>5_000L||now-lastChange<5_000L)continue;
                ManagedNPC npc=npcs.all().stream().filter(candidate->npcInstanceKey(candidate).equals(key)).findFirst().orElse(null);
                if(npc==null||!npc.scriptsEnabled()){
                    scriptLastRun.remove(key);scriptLastChange.remove(key);scriptLastWarning.remove(key);continue;
                }
                if(movementTasks.containsKey(npc)||followTasks.containsKey(npc)){
                    scriptLastChange.put(key,now);continue;
                }
                long lastWarning=scriptLastWarning.getOrDefault(key,0L);
                if(now-lastWarning<5_000L)continue;
                String permission=plugin.getConfig().getString("script-warning-permission","anpc.script.warning");
                for(Player online:Bukkit.getOnlinePlayers())if(online.hasPermission(permission))
                    online.sendMessage(ChatColor.YELLOW+"[AmethyysNPCs] Le script du NPC "+npc.id()+" n'a rien modifié depuis 100 ticks.");
                scriptLastWarning.put(key,now);
            }
        },100L,100L);
        tasks.add(scriptWatchdogTask);
    }
    private int runBlock(ScriptContext c,List<Instruction> code,int index,int indent){if(!c.npc().scriptsEnabled()||c.npc().tracker().isClosed())return code.size();while(index<code.size()){if(!c.npc().scriptsEnabled()||c.npc().tracker().isClosed()||c.returned())return code.size();if(c.loopControlRequested())return code.size();Instruction in=code.get(index);if(in.indent()<indent)break;if(in.indent()>indent){index++;continue;}String s=in.text();int line=in.line();
        if(s.equals("return")){c.setReturnValue(null);return code.size();}
        if(s.startsWith("return ")){c.setReturnValue(resolve(c,s.substring(7).trim()));return code.size();}
        if(s.startsWith("call ")){callFunction(c,s.substring(5).trim(),true);index++;if(c.suspended()){int resumeAt=index;c.addContinuation(()->runBlock(c,code,resumeAt,indent));return code.size();}continue;}
         if(isFunctionInvocation(s)){callFunction(c,s,true);index++;if(c.suspended()){int resumeAt=index;c.addContinuation(()->runBlock(c,code,resumeAt,indent));return code.size();}continue;}
        if(s.startsWith("loop ")&&s.endsWith(":")){int end=findEnd(code,index+1,in.indent());int child=index+1<code.size()?code.get(index+1).indent():in.indent()+4;executeLoop(c,code,index+1,child,s.substring(5,s.length()-1).trim());if(c.suspended()){int resumeAt=end;c.addContinuation(()->runBlock(c,code,resumeAt,indent));return code.size();}index=end;continue;}
        if(s.equals("stop loop")){c.stopLoop();return code.size();}
        if(s.equals("skip loop")){c.skipLoop();return code.size();}
        if((s.startsWith("if ")||s.startsWith("else if "))&&s.endsWith(":")||s.equals("else:")){
             int next=executeConditional(c,code,index,indent);
             if(c.suspended()){int resumeAt=next;c.addContinuation(()->runBlock(c,code,resumeAt,indent));return code.size();}
             index=next;continue;
         }
        if(s.startsWith("set hostile ")){setHostile(c.npc(),s.substring(12).trim().equalsIgnoreCase("on"));markChanged(c);index++;continue;}
        if(s.startsWith("set health of npc to ")){c.npc().setHealth(num(resolve(c,s.substring(21).trim())));markChanged(c);index++;continue;}
        if(s.startsWith("set max health of npc to ")){c.npc().setMaxHealth(num(resolve(c,s.substring(25).trim())));markChanged(c);index++;continue;}
        if(s.startsWith("set speed of npc to ")){c.npc().setSpeed(num(resolve(c,s.substring(20).trim())));markChanged(c);index++;continue;}
        if(s.startsWith("set attack damage of npc to ")){c.npc().setAttackDamage(num(resolve(c,s.substring(28).trim())));markChanged(c);index++;continue;}
        if(s.startsWith("set target of npc to ")){setTargetSelector(c.npc(),s.substring(21).trim());markChanged(c);index++;continue;}
        if(s.startsWith("set cooldown ")){
            String body=s.substring(13).trim();int forIndex=body.toLowerCase(Locale.ROOT).lastIndexOf(" for ");
            if(forIndex>0){String name=unquote(body.substring(0,forIndex).trim());long duration=parseDurationMillis(body.substring(forIndex+5).trim());if(duration<0)plugin.getLogger().warning("Durée de cooldown invalide : "+body.substring(forIndex+5).trim());else{c.npc().setCooldown(name,duration);markChanged(c);}}
            else plugin.getLogger().warning("Syntaxe attendue : set cooldown \"nom\" for 5 seconds.");
            index++;continue;
        }
        if(s.startsWith("set ")){String body=s.substring(4).trim();int q=body.indexOf('"',1);if(body.startsWith("\"")&&q>0){String name=body.substring(1,q);String val=body.substring(q+1).trim();if(val.startsWith("(" )&&val.endsWith(")"))val=val.substring(1,val.length()-1);Object value=resolve(c,val);c.locals().put(name.toLowerCase(Locale.ROOT),value);if(!c.isTransientLocal(name))storeVariable(c,name,value);markChanged(c);}index++;continue;}
        if(s.startsWith("create variable ")){createVariable(c,s.substring(15).trim());markChanged(c);index++;continue;}
        if(s.startsWith("say local ")||s.startsWith("say global ")){boolean global=s.startsWith("say global ");Object message=resolve(c,s.substring(global?11:10).trim());String rendered=ChatColor.translateAlternateColorCodes('&',c.npc().definition().displayName()+" » "+String.valueOf(message));if(global)Bukkit.broadcastMessage(rendered);else if(c.player()!=null)c.player().sendMessage(rendered);else for(Player p:c.npc().location().getWorld().getPlayers())if(p.getLocation().distanceSquared(c.npc().location())<32*32)p.sendMessage(rendered);index++;continue;}
        if(s.startsWith("send to ")){int pos=s.indexOf(' ',8);if(pos>0){String target=s.substring(8,pos);Object targetValue=resolve(c,target);String msg=String.valueOf(resolve(c,s.substring(pos+1).trim()));sendToTarget(c,target,targetValue,msg);}index++;continue;}
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
        if(s.startsWith("setblock ")){
            String body=s.substring(9).trim();
            int close=body.indexOf(')');
            if(body.startsWith("(")&&close>0){
                Location at=parseLocation(c,body.substring(0,close+1));
                String materialText=body.substring(close+1).trim();
                if(materialText.startsWith("(")&&materialText.endsWith(")"))materialText=materialText.substring(1,materialText.length()-1).trim();
                Material material=Material.matchMaterial(materialText);
                if(at!=null&&material!=null)at.getBlock().setType(material,false);
                else plugin.getLogger().warning("Syntaxe setblock invalide ligne "+line+" : setblock (x, y, z) stone.");
            }else plugin.getLogger().warning("Syntaxe setblock invalide ligne "+line+" : utilise setblock (position) (bloc).");
            markChanged(c);index++;continue;
        }
        if(s.startsWith("set block ")){markChanged(c);
            int atIndex=s.toLowerCase(Locale.ROOT).indexOf(" at ");
            if(atIndex>10){
                Material material=Material.matchMaterial(s.substring(10,atIndex).trim());
                Location at=parseLocation(c,s.substring(atIndex+4).trim());
                if(material!=null&&at!=null)at.getBlock().setType(material,false);
            }
            index++;continue;
        }
        if(s.startsWith("break block at ")){markChanged(c);
            Location at=parseLocation(c,s.substring(15).trim());
            if(at!=null)at.getBlock().breakNaturally();
            index++;continue;
        }
        if(s.startsWith("play anime ")||s.startsWith("emote ")){
            int offset=s.startsWith("emote ")?6:11;
            c.npc().animate(unquote(s.substring(offset)));markChanged(c);index++;continue;
        }
        if(s.equals("stop animation")){c.npc().animate("idle");markChanged(c);index++;continue;}
        if(s.equals("sneak")||s.equals("sprint")||s.equals("jump")){c.npc().animate(s);markChanged(c);index++;continue;}
        if(s.startsWith("play sound ")){playScriptSound(c,s.substring(11).trim());index++;continue;}
        if(s.startsWith("reset cooldown ")){c.npc().resetCooldown(unquote(s.substring(15).trim()));markChanged(c);index++;continue;}
        if(s.startsWith("set checkpoint ")){c.npc().setCheckpoint(unquote(s.substring(15).trim()),c.npc().location());markChanged(c);index++;continue;}
        if(s.startsWith("remove checkpoint ")){c.npc().removeCheckpoint(unquote(s.substring(18).trim()));markChanged(c);index++;continue;}
        if(s.startsWith("move to checkpoint ")){stopFollow(c.npc());Location checkpoint=c.npc().checkpoint(unquote(s.substring(19).trim()));if(checkpoint!=null)move(c.npc(),checkpoint);else plugin.getLogger().warning("Checkpoint introuvable pour "+c.npc().id()+" : "+s.substring(19));markChanged(c);index++;continue;}
        if(s.startsWith("move to ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(8).trim());if(target!=null)move(c.npc(),target);markChanged(c);index++;continue;}
        if(s.startsWith("fly to ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(7).trim());if(target!=null)moveFlying(c.npc(),target);markChanged(c);index++;continue;}
        if(s.startsWith("tp ")){stopFollow(c.npc());Location target=parseLocation(c,s.substring(3).trim());if(target!=null)move(c.npc(),target);index++;continue;}
        if(s.startsWith("create flag ")){createFlag(c,s.substring(12).trim());markChanged(c);index++;continue;}
        if(s.startsWith("flag send ")){sendFlag(c,s.substring(10).trim());markChanged(c);index++;continue;}
        if(s.startsWith("flag on ")||s.startsWith("flag off ")){boolean on=s.startsWith("flag on ");String name=s.substring(on?8:9).replace("(","").replace(")","").trim();variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),"flag:"+name,on);markChanged(c);index++;continue;}
        if(s.startsWith("wait ")){
            long ticks=parseDurationTicks(s.substring(5));
            if(ticks<1){plugin.getLogger().warning("Durée wait invalide ligne "+line+" : '"+s.substring(5)+"'. Utilise par exemple 5 ticks, 2 seconds ou 500 ms.");index++;continue;}
            List<Instruction> remaining=new ArrayList<>(code.subList(index+1,code.size()));
            c.beginWait();startWatchdog(c);
            c.suspend();
            final long expectedGeneration=c.scriptGeneration();
            BukkitTask[] scheduled=new BukkitTask[1];
            scheduled[0]=Bukkit.getScheduler().runTaskLater(plugin,()->{
                tasks.remove(scheduled[0]);
                c.endWait();
                boolean sameScript=scriptGenerations.getOrDefault(c.scriptId(),0L)==expectedGeneration;
                if(c.finished()||!sameScript||!c.npc().scriptsEnabled()||c.npc().tracker().isClosed()){
                    c.finish();
                    stopWatchdog(c);
                    return;
                }
                c.resume();
                runBlock(c,remaining,0,indent);
                drainContinuations(c);
                if(!c.hasPendingWaits()&&!c.suspended()){c.finish();stopWatchdog(c);}
            },ticks);
            tasks.add(scheduled[0]);return code.size();
        }
        if(s.startsWith("run command ")){String cmd=unquote(s.substring(12));Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("%player%",c.player()==null?"":c.player().getName()));index++;continue;}
        if(s.startsWith("look at ")){Location at=parseLocation(c,s.substring(8).trim());if(at!=null){Location from=c.npc().location();from.setDirection(at.toVector().subtract(from.toVector()));c.npc().move(from);markChanged(c);}index++;continue;}
        if(s.equals("look away")){Location facing=c.npc().location();facing.setYaw(c.npc().definition().yaw());facing.setPitch(c.npc().definition().pitch());c.npc().move(facing);markChanged(c);index++;continue;}
        if(s.startsWith("follow ")){startFollow(c.npc(),s.substring(7).trim());markChanged(c);index++;continue;}
        if(s.startsWith("keep distance ")){startKeepDistance(c.npc(),s.substring(14).trim());markChanged(c);index++;continue;}
        if(s.equals("stop follow")||s.equals("stop movement")){stopFollow(c.npc());markChanged(c);index++;continue;}
        if(s.startsWith("target ")){setTargetSelector(c.npc(),s.substring(7).trim());markChanged(c);index++;continue;}
        if(s.startsWith("attack ")){setTargetSelector(c.npc(),s.substring(7).trim());setHostile(c.npc(),true);markChanged(c);index++;continue;}
        if(s.equals("stop attack")){setHostile(c.npc(),false);markChanged(c);index++;continue;}
        if(s.startsWith("set state ")){c.npc().setPrimaryState(unquote(s.substring(10)));markChanged(c);index++;continue;}
        if(s.startsWith("add state ")){c.npc().addSecondaryState(unquote(s.substring(10)));markChanged(c);index++;continue;}
        if(s.startsWith("remove state ")){c.npc().removeSecondaryState(unquote(s.substring(13)));markChanged(c);index++;continue;}
        if(s.equals("clear states")){c.npc().setPrimaryState("idle");c.npc().clearSecondaryStates();markChanged(c);index++;continue;}
        warnUnknownInstruction(line,s);
        index++;
    }return index;}
    private void drainContinuations(ScriptContext context){
        while(!context.suspended()){
            Runnable continuation=context.nextContinuation();
            if(continuation==null)return;
            continuation.run();
        }
        if(context.returned())context.clearContinuations();
    }

    private void startWatchdog(ScriptContext context){
        if(watchdogTasks.containsKey(context))return;
        scriptLastRun.remove(npcInstanceKey(context.npc()));
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if(context.finished()||!context.hasPendingWaits()||!context.npc().scriptsEnabled()||context.npc().tracker().isClosed()){context.finish();stopWatchdog(context);return;}
            if(movementTasks.containsKey(context.npc())||followTasks.containsKey(context.npc())||combatTasks.containsKey(context.npc())){
                markChanged(context);return;
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
        return Set.of("spawn","right click","right click while sneaking","left click","left click while sneaking","item click","damage","entity approach","entity leave",
                "npc approach","npc leave","interval","player join","player connect","player quit","player disconnect",
                "player chat","player command","chat","command","join","quit","connect","disconnect","click","interact","walk","reload").contains(event)
                ||isFlagReceiveEvent(event);
    }

    private boolean isKnownInstruction(String instruction){
        String text=instruction.trim().toLowerCase(Locale.ROOT);
        if(text.matches("[a-z_][a-z0-9_-]*\\s*\\(.*\\)"))return true;
        if((text.startsWith("if ")||text.startsWith("else if "))&&text.endsWith(":"))return true;
        if(text.equals("else:"))return true;
        if(text.startsWith("loop ")&&text.endsWith(":"))return true;
        if(text.startsWith("set state ")||text.startsWith("set hostile ")
                ||text.startsWith("set health of npc to ")||text.startsWith("set max health of npc to ")
                ||text.startsWith("set speed of npc to ")||text.startsWith("set attack damage of npc to ")||text.startsWith("set target of npc to ")
                ||text.startsWith("set checkpoint ")||text.startsWith("set cooldown ")||text.startsWith("set block "))return true;
        if(text.startsWith("set "))return text.matches("^set\\s+\"[^\"]+\"\\s+.+$");
        return text.startsWith("create flag ")||text.startsWith("flag send ")
                ||text.startsWith("say local ")||text.startsWith("say global ")||text.startsWith("send to ")
                ||text.startsWith("create variable ")||text.startsWith("add state ")
                ||text.startsWith("remove state ")||text.equals("clear states")||text.startsWith("flag on ")
                ||text.startsWith("flag off ")||text.startsWith("play sound ")||text.startsWith("play anime ")||text.startsWith("emote ")||text.equals("stop animation")
                 ||text.equals("sneak")||text.equals("sprint")||text.equals("jump")
                ||text.startsWith("move to ")||text.startsWith("fly to ")||text.startsWith("tp ")||text.startsWith("follow ")
                ||text.startsWith("keep distance ")||text.equals("stop follow")||text.equals("stop movement")||text.startsWith("target ")
                ||text.startsWith("attack ")||text.equals("stop attack")||text.startsWith("look at ")||text.equals("look away")
                ||text.startsWith("set checkpoint ")||text.startsWith("remove checkpoint ")
                ||text.startsWith("move to checkpoint ")||text.startsWith("reset cooldown ")||text.startsWith("simulate break block at ")
                ||text.startsWith("simulate place ")||text.startsWith("simulate interact with block at ")
                ||text.startsWith("set block ")||text.startsWith("setblock ")||text.startsWith("break block at ")||text.startsWith("wait ")
                ||text.startsWith("run command ")||text.equals("stop loop")||text.equals("skip loop")
                ||text.equals("return")||text.startsWith("return ")||text.startsWith("call ");
    }

    private void warnUnknownInstruction(int line,String text){
        List<String> keywords=List.of("say local","say global","send to","set","create variable","flag on","flag off",
                "play sound","play anime","move to","fly to","follow","keep distance","stop follow","target","attack",
                "stop attack","stop movement","tp","set state","add state","remove state","clear states","simulate break block","simulate place",
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
            else if(source instanceof String text&&!text.isBlank())for(String value:text.split(","))if(!value.isBlank())values.add(value.trim());
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
        Map<String,Object> previousLoopLocals=new HashMap<>();
        Set<String> existingLoopLocals=new HashSet<>();
        for(String key:List.of("loop-value","loop-number","loop-player","loop-npc")){
            if(context.locals().containsKey(key)){existingLoopLocals.add(key);previousLoopLocals.put(key,context.locals().get(key));}
        }
        context.pushLoop();
        runLoopIterations(context,values,code,bodyStart,bodyIndent,0,previousNpc,previousPlayer,previousLoopLocals,existingLoopLocals);
    }

    private void runLoopIterations(ScriptContext context,List<Object> values,List<Instruction> code,int bodyStart,int bodyIndent,
                                  int completed,ManagedNPC previousNpc,Player previousPlayer,
                                  Map<String,Object> previousLoopLocals,Set<String> existingLoopLocals){
        int number=completed;
        while(number<values.size()&&!context.returned()&&!context.loopStopped()){
            Object value=values.get(number++);
            context.locals().remove("loop-player");
            context.locals().remove("loop-npc");
            context.locals().put("loop-value",value);
            context.locals().put("loop-number",number);
            context.setNpc(value instanceof ManagedNPC targetNpc?targetNpc:previousNpc);
            context.setPlayer(value instanceof Player targetPlayer?targetPlayer:previousPlayer);
            if(value instanceof Player player)context.locals().put("loop-player",player);
            if(value instanceof ManagedNPC npc)context.locals().put("loop-npc",npc);
            context.clearLoopSkip();
            runBlock(context,code,bodyStart,bodyIndent);
            if(context.suspended()){
                int resumeAt=number;
                context.addContinuation(()->runLoopIterations(context,values,code,bodyStart,bodyIndent,resumeAt,
                        previousNpc,previousPlayer,previousLoopLocals,existingLoopLocals));
                return;
            }
            if(context.returned()||context.loopStopped())break;
            if(context.loopSkipped())context.clearLoopSkip();
        }
        context.popLoop();
        context.setNpc(previousNpc);
        context.setPlayer(previousPlayer);
        for(String key:List.of("loop-value","loop-number","loop-player","loop-npc")){
            if(existingLoopLocals.contains(key))context.locals().put(key,previousLoopLocals.get(key));
            else context.locals().remove(key);
        }
    }
    private int executeConditional(ScriptContext context,List<Instruction> code,int index,int indent){
        Instruction header=code.get(index);
        String text=header.text().trim();
        boolean matches=text.equals("else:")||test(context,text.startsWith("else if ")?text.substring(8,text.length()-1):text.substring(3,text.length()-1));
        int branchEnd=findEnd(code,index+1,indent);
        if(matches){
            int child=index+1<code.size()?code.get(index+1).indent():indent+4;
            runBlock(context,code,index+1,child);
        }
        int next=branchEnd;
        if(next<code.size()&&code.get(next).indent()==indent){
            String nextText=code.get(next).text().trim();
            if(nextText.equals("else:")||nextText.startsWith("else if ")){
                if(matches)return skipConditionalChain(code,next,indent);
                return executeConditional(context,code,next,indent);
            }
        }
        return next;
    }

    private int skipConditionalChain(List<Instruction> code,int index,int indent){
        int next=index;
        while(next<code.size()&&code.get(next).indent()==indent){
            String text=code.get(next).text().trim();
            if(!text.equals("else:")&&!text.startsWith("else if "))break;
            next=findEnd(code,next+1,indent);
        }
        return next;
    }

    private int findEnd(List<Instruction> c,int from,int parent){int i=from;while(i<c.size()&&c.get(i).indent()>parent)i++;return i;}
    private boolean test(ScriptContext context,String expression){
        String value=stripOuterParentheses(expression.trim());
        int split=findTopLevelKeyword(value,"or");
        if(split>=0)return test(context,value.substring(0,split))||test(context,value.substring(split+2));
        split=findTopLevelKeyword(value,"and");
        if(split>=0)return test(context,value.substring(0,split))&&test(context,value.substring(split+3));
        if(value.toLowerCase(Locale.ROOT).startsWith("not "))return !test(context,value.substring(4));
        String lower=value.toLowerCase(Locale.ROOT);

        if(lower.startsWith("flag (")&&lower.contains(") is ")){
            String name=value.substring(6,value.indexOf(')')).trim();
            boolean enabled=Boolean.parseBoolean(variables.getLocal(context.npc().id(),context.npc().location().getWorld().getName(),"flag:"+name,"false"));
            return lower.endsWith(" on")?enabled:lower.endsWith(" off")&&!enabled;
        }
        if(lower.startsWith("block below is ")){
            String expected=value.substring(15).trim();
            Material actual=context.npc().location().getBlock().getRelative(0,-1,0).getType();
            if(expected.toLowerCase(Locale.ROOT).startsWith("not "))
                return !matchesMaterial(actual,expected.substring(4).trim());
            if(expected.toLowerCase(Locale.ROOT).startsWith("one of ")){
                String list=expected.substring(7).trim();
                if(list.startsWith("(")&&list.endsWith(")"))list=list.substring(1,list.length()-1);
                for(String item:splitArguments(list))if(matchesMaterial(actual,item.trim()))return true;
                return false;
            }
            return matchesMaterial(actual,expected);
        }
        if(lower.startsWith("ray = ")||lower.startsWith("sphere = ")){
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^(ray|sphere)\\s*=\\s*(\\d+)\\s+contains\\s+([a-z0-9_:-]+)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
            if(matcher.matches()){
                Material material=Material.matchMaterial(matcher.group(3));
                int radius=parseSafeRadius(matcher.group(2));
                return material!=null&&radius>=0&&countBlocks(context,material,radius,matcher.group(1).equalsIgnoreCase("sphere"))>0;
            }
        }
        if(lower.startsWith("player is sneaking"))return context.player()!=null&&context.player().isSneaking();
        if(lower.startsWith("player has permission "))return context.player()!=null&&context.player().hasPermission(unquote(value.substring(22).trim()));
        if(lower.startsWith("player has item ")){
            if(context.player()==null)return false;
            Material material=Material.matchMaterial(value.substring(16).trim());
            return material!=null&&context.player().getInventory().contains(material);
        }
        if(lower.startsWith("cooldown ")&&lower.endsWith(" is ready")){
            String name=unquote(value.substring(9,value.length()-9).trim());return !context.npc().hasCooldown(name);
        }
        if(lower.startsWith("cooldown ")&&lower.endsWith(" is active")){
            String name=unquote(value.substring(9,value.length()-10).trim());return context.npc().hasCooldown(name);
        }
        if(lower.equals("npc is under attack"))return context.npc().lastAttacker()!=null;
        if(lower.equals("npc is hostile"))return context.npc().hostile();
        if(lower.startsWith("npc state is "))return context.npc().primaryState().equalsIgnoreCase(unquote(value.substring(13).trim()));
        if(lower.startsWith("state of npc is ")||lower.startsWith("state is ")){
            int offset=lower.startsWith("state is ")?9:16;
            return context.npc().primaryState().equalsIgnoreCase(unquote(value.substring(offset).trim()));
        }
        if(lower.startsWith("npc has state "))return context.npc().secondaryStates().contains(unquote(value.substring(14).trim()).toLowerCase(Locale.ROOT));

        int contains=findTopLevelKeyword(value,"contains");
        if(contains>=0){
            Object source=resolve(context,value.substring(0,contains).trim());
            String tail=value.substring(contains+8).trim();
            java.util.regex.Matcher countMatcher=java.util.regex.Pattern.compile("^(.*?)\\s+(more than|less than)\\s+(\\d+)\\s+times$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tail);
            if(countMatcher.matches()){
                Object needle=resolve(context,countMatcher.group(1).trim());
                int expected=Integer.parseInt(countMatcher.group(3));
                int actual=countOccurrences(source,needle);
                return countMatcher.group(2).equalsIgnoreCase("more than")?actual>expected:actual<expected;
            }
            return countOccurrences(source,resolve(context,tail))>0;
        }

        String[] operators={"==","!=",">=","<=",">","<"};
        for(String operator:operators){
            int position=findTopLevelToken(value,operator);
            if(position<0)continue;
            Object left=resolve(context,value.substring(0,position).trim());
            Object right=resolve(context,value.substring(position+operator.length()).trim());
            return compareValues(left,right,operator);
        }
        Object resolved=resolve(context,value);
        return resolved instanceof Boolean bool&&bool;
    }

    private String stripOuterParentheses(String value){
        String result=value;
        while(result.startsWith("(")&&result.endsWith(")")&&balancedOuterParentheses(result))
            result=result.substring(1,result.length()-1).trim();
        return result;
    }

    private int findTopLevelKeyword(String expression,String keyword){
        boolean quoted=false;int depth=0;
        for(int i=0;i<=expression.length()-keyword.length();i++){
            char ch=expression.charAt(i);
            if(ch=='"'&&(i==0||expression.charAt(i-1)!='\\'))quoted=!quoted;
            if(quoted)continue;
            if(ch=='('){depth++;continue;}
            if(ch==')'){depth--;continue;}
            if(depth!=0||!expression.regionMatches(true,i,keyword,0,keyword.length()))continue;
            boolean left=i==0||Character.isWhitespace(expression.charAt(i-1));
            int after=i+keyword.length();
            boolean right=after==expression.length()||Character.isWhitespace(expression.charAt(after));
            if(left&&right)return i;
        }
        return -1;
    }

    private int findTopLevelToken(String expression,String token){
        boolean quoted=false;int depth=0;
        for(int i=0;i<=expression.length()-token.length();i++){
            char ch=expression.charAt(i);
            if(ch=='"'&&(i==0||expression.charAt(i-1)!='\\'))quoted=!quoted;
            if(quoted)continue;
            if(ch=='('){depth++;continue;}
            if(ch==')'){depth--;continue;}
            if(depth==0&&expression.startsWith(token,i))return i;
        }
        return -1;
    }

    private boolean compareValues(Object left,Object right,String operator){
        if(operator.equals("==")||operator.equals("!=")){
            boolean equal;
            if(left==null||right==null)equal=left==right;
            else if(isNumericValue(left)&&isNumericValue(right))equal=Double.compare(Double.parseDouble(String.valueOf(left)),Double.parseDouble(String.valueOf(right)))==0;
            else equal=String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
            return operator.equals("==")?equal:!equal;
        }
        if(isNumericValue(left)&&isNumericValue(right)){
            double a=Double.parseDouble(String.valueOf(left)),b=Double.parseDouble(String.valueOf(right));
            return switch(operator){case ">"->a>b;case "<"->a<b;case ">="->a>=b;case "<="->a<=b;default->false;};
        }
        int comparison=String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
        return switch(operator){case ">"->comparison>0;case "<"->comparison<0;case ">="->comparison>=0;case "<="->comparison<=0;default->false;};
    }

    private boolean isNumericValue(Object value){
        if(value instanceof Number)return true;
        if(value==null)return false;
        try{Double.parseDouble(String.valueOf(value));return true;}
        catch(NumberFormatException exception){return false;}
    }

    private int countOccurrences(Object source,Object needle){
        if(source==null||needle==null)return 0;
        if(source instanceof Iterable<?> iterable){
            int count=0;for(Object item:iterable)if(Objects.equals(item,needle)||String.valueOf(item).equalsIgnoreCase(String.valueOf(needle)))count++;
            return count;
        }
        if(source.getClass().isArray()){
            int count=0;int length=java.lang.reflect.Array.getLength(source);
            for(int i=0;i<length;i++){Object item=java.lang.reflect.Array.get(source,i);if(Objects.equals(item,needle)||String.valueOf(item).equalsIgnoreCase(String.valueOf(needle)))count++;}
            return count;
        }
        String text=String.valueOf(source),target=String.valueOf(needle);
        if(target.isEmpty())return 0;
        int count=0,from=0;
        while((from=text.indexOf(target,from))>=0){count++;from+=target.length();}
        if(count==0&&text.contains(",")){
            for(String item:text.split(","))if(item.trim().equalsIgnoreCase(target.trim()))count++;
        }
        return count;
    }

    private boolean matchesMaterial(Material actual,String raw){
        Material expected=Material.matchMaterial(unquote(raw).trim());
        return expected!=null&&actual==expected;
    }

    private int parseSafeRadius(String raw){
        try{int radius=Integer.parseInt(raw);return radius>=0&&radius<=32?radius:-1;}
        catch(NumberFormatException exception){return -1;}
    }

    private int countBlocks(ScriptContext context,Material material,int radius,boolean sphere){
        Location center=context.npc().location();
        int cx=center.getBlockX(),cy=center.getBlockY(),cz=center.getBlockZ(),count=0;
        int radiusSquared=radius*radius;
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(dx*dx+dz*dz>radiusSquared)continue;
            int minY=sphere?-radius:0,maxY=sphere?radius:0;
            for(int dy=minY;dy<=maxY;dy++){
                if(sphere&&dx*dx+dy*dy+dz*dz>radiusSquared)continue;
                if(center.getWorld().getBlockAt(cx+dx,cy+dy,cz+dz).getType()==material)count++;
            }
        }
        return count;
    }

    private Object resolve(ScriptContext c,String raw){
        String value=raw.trim();
        if(value.length()>1&&value.startsWith("(")&&value.endsWith(")")&&balancedOuterParentheses(value))value=value.substring(1,value.length()-1).trim();
        if(c.locals().containsKey(value))return c.locals().get(value);
        String lowerValue=value.toLowerCase(Locale.ROOT);
        if(lowerValue.startsWith("npc:"))return npcs.get(value.substring(4).trim(),c.npc().location().getWorld());
        if(lowerValue.startsWith("player:"))return Bukkit.getPlayerExact(value.substring(7).trim());
        Object namedNpcValue=resolveNamedNpcProperty(c,value);
        if(namedNpcValue!=UNRESOLVED)return namedNpcValue;
        Object namedPlayerValue=resolveNamedPlayerProperty(value);
        if(namedPlayerValue!=UNRESOLVED)return namedPlayerValue;
        if(value.equalsIgnoreCase("@p"))return nearest(c.npc().location());
        if(value.equalsIgnoreCase("@a"))return new ArrayList<>(c.npc().location().getWorld().getPlayers());
        if(value.equalsIgnoreCase("@n"))return nearestEntity(c.npc().location(),null);
        java.util.regex.Matcher selector=java.util.regex.Pattern.compile("^@(e|n)\\s*\\[\\s*type\\s*=\\s*([a-z0-9_:-]+)\\s*]$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if(selector.matches()){
            EntityType type;
            try{type=EntityType.valueOf(selector.group(2).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException exception){return null;}
            if(selector.group(1).equalsIgnoreCase("n"))return nearestEntity(c.npc().location(),type);
            return c.npc().location().getWorld().getEntities().stream().filter(entity->entity.getType()==type&&entity!=c.npc().hitbox()&&entity.getScoreboardTags().stream().noneMatch(tag->tag.startsWith("anpc:"))).toList();
        }
        if(value.equalsIgnoreCase("@e"))return c.npc().location().getWorld().getEntities().stream().filter(entity->entity!=c.npc().hitbox()&&entity.getScoreboardTags().stream().noneMatch(tag->tag.startsWith("anpc:"))).toList();
        if(value.toLowerCase(Locale.ROOT).startsWith("call "))return callFunction(c,value.substring(5).trim(),false);
        java.util.regex.Matcher blockCount=java.util.regex.Pattern.compile("^number of ([a-z0-9_:-]+) in (range|sphere)\\s+(\\d+)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if(blockCount.matches()){
            Material material=Material.matchMaterial(blockCount.group(1));
            int radius=parseSafeRadius(blockCount.group(3));
            return material==null||radius<0?0:countBlocks(c,material,radius,blockCount.group(2).equalsIgnoreCase("sphere"));
        }
         if(isFunctionInvocation(value))return callFunction(c,value,false);
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
    private static final Object UNRESOLVED=new Object();

    private Object resolveNamedNpcProperty(ScriptContext context,String expression){
        String lower=expression.toLowerCase(Locale.ROOT);
        int marker=lower.indexOf(" of npc:");
        if(marker<0)return UNRESOLVED;
        String property=lower.substring(0,marker).trim();
        String id=expression.substring(marker+8).trim();
        if(id.isBlank())return UNRESOLVED;
        ManagedNPC target=npcs.get(id,context.npc().location().getWorld());
        if(target==null)return null;
        return switch(property){
            case "name"->target.definition().displayName();
            case "id"->target.id();
            case "type"->target.definition().type();
            case "skin"->target.definition().skin();
            case "world"->target.location().getWorld().getName();
            case "location","position"->target.location();
            case "x"->target.location().getX();
            case "y"->target.location().getY();
            case "z"->target.location().getZ();
            case "yaw"->target.location().getYaw();
            case "pitch"->target.location().getPitch();
            case "health"->target.health();
            case "max health"->target.maxHealth();
            case "speed"->target.speed();
            case "state","primary state"->target.primaryState();
            default->UNRESOLVED;
        };
    }

    private Object resolveNamedPlayerProperty(String expression){
        String lower=expression.toLowerCase(Locale.ROOT);
        int marker=lower.indexOf(" of player:");
        if(marker<0)return UNRESOLVED;
        String property=lower.substring(0,marker).trim();
        String name=expression.substring(marker+11).trim();
        if(name.isBlank())return UNRESOLVED;
        Player target=Bukkit.getPlayerExact(name);
        if(target==null)return property.equals("name")?name:null;
        return switch(property){
            case "name"->target.getName();
            case "uuid"->target.getUniqueId().toString();
            case "location","position"->target.getLocation();
            case "world"->target.getWorld().getName();
            case "x"->target.getLocation().getX();
            case "y"->target.getLocation().getY();
            case "z"->target.getLocation().getZ();
            case "yaw"->target.getLocation().getYaw();
            case "pitch"->target.getLocation().getPitch();
            case "health"->target.getHealth();
            case "food level"->target.getFoodLevel();
            case "level"->target.getLevel();
            case "game mode"->target.getGameMode().name();
            case "is online"->target.isOnline();
            default->UNRESOLVED;
        };
    }

    private Entity nearestEntity(Location location,EntityType requiredType){
        return location.getWorld().getEntities().stream()
                .filter(entity->entity.isValid()&&entity.getScoreboardTags().stream().noneMatch(tag->tag.startsWith("anpc:")))
                .filter(entity->requiredType==null||entity.getType()==requiredType)
                .min(Comparator.comparingDouble(entity->entity.getLocation().distanceSquared(location)))
                .orElse(null);
    }

    private boolean isFlagReceiveEvent(String event){
        return event.matches("flag receive \\([a-z0-9_-]+\\)");
    }

    private void createFlag(ScriptContext context,String declaration){
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^\\(?([a-zA-Z_][a-zA-Z0-9_-]*)\\)?\\s+(toggle|input)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(declaration);
        if(!matcher.matches()){
            plugin.getLogger().warning("Syntaxe attendue : create flag (nom) toggle ou create flag (nom) input.");
            return;
        }
        String name=matcher.group(1).toLowerCase(Locale.ROOT),type=matcher.group(2).toLowerCase(Locale.ROOT);
        String existing=variables.get("npc-flags",context.npc().id(),name,null);
        if(existing!=null&&!existing.equalsIgnoreCase(type)){
            plugin.getLogger().warning("Le flag '"+name+"' est déjà déclaré avec le type '"+existing+"' pour "+context.npc().id()+".");
            return;
        }
        variables.set("npc-flags",context.npc().id(),name,type);
    }

    private void sendFlag(ScriptContext context,String declaration){
        int toIndex=declaration.toLowerCase(Locale.ROOT).lastIndexOf(" to ");
        if(toIndex<0){
            plugin.getLogger().warning("Syntaxe attendue : flag send (nom) to npc, npc2 ou all.");
            return;
        }
        String name=declaration.substring(0,toIndex).replace("(","").replace(")","").trim().toLowerCase(Locale.ROOT);
        String targets=declaration.substring(toIndex+4).trim();
        String type=variables.get("npc-flags",context.npc().id(),name,null);
        if(!"input".equalsIgnoreCase(type)){
            plugin.getLogger().warning("Le flag '"+name+"' doit être déclaré avec le type input avant son envoi.");
            return;
        }
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        if(targets.equalsIgnoreCase("all"))npcs.allIds().forEach(ids::add);
        else{
            String list=targets;
            if(list.startsWith("[")&&list.endsWith("]"))list=list.substring(1,list.length()-1);
            for(String target:splitArguments(list)){
                String id=target.trim();
                if(id.toLowerCase(Locale.ROOT).startsWith("npc:"))id=id.substring(4).trim();
                if(!id.isBlank())ids.add(id);
            }
        }
        for(String id:ids)for(ManagedNPC receiver:npcs.instances(id)){
            Map<String,List<Instruction>> map=scripts.get(receiver.id().toLowerCase(Locale.ROOT));
            if(map==null||!receiver.scriptsEnabled())continue;
            List<Instruction> code=map.get("flag receive ("+name+")");
            if(code!=null)execute(receiver,null,code,0,name,context.npc());
        }
    }

    private boolean isSharedMember(String members,String npcId){
        return Arrays.stream(members.split(",")).map(String::trim).anyMatch(member->member.equalsIgnoreCase(npcId));
    }
    private void createVariable(ScriptContext c,String declaration){
        String input=declaration.trim();
        String name,remainder;
        if(input.startsWith("(")){
            int close=input.indexOf(')');
            if(close<2){
                plugin.getLogger().warning("Nom de variable invalide dans la déclaration : "+declaration);
                return;
            }
            name=input.substring(1,close).trim();
            remainder=input.substring(close+1).trim();
        }else{
            int separator=input.indexOf(' ');
            if(separator<=0){
                plugin.getLogger().warning("Déclaration de variable invalide : "+declaration);
                return;
            }
            name=input.substring(0,separator).trim();
            remainder=input.substring(separator+1).trim();
        }
        if(!name.matches("[A-Za-z_][A-Za-z0-9_-]*")){
            plugin.getLogger().warning("Nom de variable invalide : "+name);
            return;
        }
        String rawValue;
        if(remainder.startsWith("(")){
            int depth=0;boolean quoted=false;int closing=-1;
            for(int i=0;i<remainder.length();i++){
                char ch=remainder.charAt(i);
                if(ch=='"'&&(i==0||remainder.charAt(i-1)!='\\'))quoted=!quoted;
                if(quoted)continue;
                if(ch=='(')depth++;
                else if(ch==')'&&--depth==0){closing=i;break;}
            }
            if(closing<0){
                plugin.getLogger().warning("Valeur entre parenthèses non terminée pour la variable '"+name+"'.");
                return;
            }
            rawValue=remainder.substring(1,closing).trim();
            remainder=remainder.substring(closing+1).trim();
        }else{
            int next=remainder.indexOf(' ');
            rawValue=next<0?remainder:remainder.substring(0,next);
            remainder=next<0?"":remainder.substring(next+1).trim();
        }
        if(rawValue.isBlank()){
            plugin.getLogger().warning("Valeur manquante pour la variable '"+name+"'.");
            return;
        }
        String scope="local";
        if(!remainder.isBlank()){
            int next=remainder.indexOf(' ');
            scope=(next<0?remainder:remainder.substring(0,next)).toLowerCase(Locale.ROOT);
            remainder=next<0?"":remainder.substring(next+1).trim();
        }
        Object value=resolve(c,rawValue);
        String existingGlobalMeta=variables.get("meta","",name,null);
        if(scope.equals("global")){
            if(existingGlobalMeta!=null&&existingGlobalMeta.toLowerCase(Locale.ROOT).startsWith("shared|")){
                plugin.getLogger().warning("La variable '"+name+"' est déjà déclarée shared et ne peut pas devenir global.");
                return;
            }
            variables.set("meta","",name,"global|@");
            variables.setGlobal(name,value);
        }else if(scope.equals("shared")){
            if(existingGlobalMeta!=null&&!existingGlobalMeta.toLowerCase(Locale.ROOT).startsWith("shared|")){
                plugin.getLogger().warning("La variable '"+name+"' possède déjà une portée globale différente de shared.");
                return;
            }
            if(!remainder.startsWith("[")||!remainder.endsWith("]")){
                plugin.getLogger().warning("Déclaration shared invalide pour '"+name+"' : utilise shared [npc1, npc2].");
                return;
            }
            String members=Arrays.stream(remainder.substring(1,remainder.length()-1).split(","))
                    .map(String::trim).filter(member->!member.isBlank()).distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            if(existingGlobalMeta!=null){
                String existingMembers=existingGlobalMeta.substring(existingGlobalMeta.indexOf('|')+1);
                Set<String> oldSet=Arrays.stream(existingMembers.split(",")).map(v->v.trim().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                Set<String> newSet=Arrays.stream(members.split(",")).map(v->v.trim().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
                if(!oldSet.equals(newSet)){
                    plugin.getLogger().warning("La liste des membres de la variable shared '"+name+"' ne peut pas être modifiée après sa création.");
                    return;
                }
            }
            if(members.isBlank()||!isSharedMember(members,c.npc().id())){
                plugin.getLogger().warning("Le NPC "+c.npc().id()+" doit figurer dans la liste des membres autorisés de la variable shared '"+name+"'.");
                return;
            }
            variables.set("meta","",name,"shared|"+members);
            for(String member:members.split(","))variables.set("meta",member.trim(),name,"shared|"+members);
            variables.set("shared",members,name,value);
        }else if(scope.equals("local")){
            variables.set("meta",c.npc().id(),name,"local|"+c.npc().id());
            variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),name,value);
        }else{
            plugin.getLogger().warning("Portée inconnue '"+scope+"' pour la variable '"+name+"'. Utilise local, global ou shared.");
            return;
        }
        c.locals().put(name.toLowerCase(Locale.ROOT),value);
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
    private boolean isFunctionInvocation(String expression){
        int open=expression.indexOf('('),close=expression.lastIndexOf(')');
        if(open<=0||close!=expression.length()-1)return false;
        String name=expression.substring(0,open).trim().toLowerCase(Locale.ROOT);
        return name.matches("[a-z_][a-z0-9_-]*")&&functions.containsKey(name);
    }

    private Object callFunction(ScriptContext caller,String invocation,boolean allowWait){
        int open=invocation.indexOf('('),close=invocation.lastIndexOf(')');
        if(open<=0||close<open||close!=invocation.length()-1){plugin.getLogger().warning("Appel de fonction invalide : "+invocation);return null;}
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
        child.setEventData(caller.eventData());
        child.setEventNpc(caller.eventNpc());
        child.setEventEntity(caller.eventEntity());
        child.setEventItem(caller.eventItem());
        child.setEventPlace(caller.eventPlace());
        child.setScriptExecution(caller.scriptId(),caller.scriptGeneration());
        child.setCallDepth(caller.callDepth()+1);
        for(int i=0;i<arguments.size();i++){String parameter=function.parameters().get(i);child.locals().put(parameter,resolve(caller,arguments.get(i)));child.markTransientLocal(parameter);}
        if(!function.code().isEmpty())runBlock(child,function.code(),0,function.code().get(0).indent());
        if(child.suspended()){
            if(!allowWait){plugin.getLogger().warning("Un appel de fonction contenant wait ne peut pas être utilisé dans une expression : "+name);child.finish();return null;}
            caller.suspend();
            child.setCompletionCallback(()->{
                caller.resume();
                drainContinuations(caller);
                if(!caller.hasPendingWaits()&&!caller.suspended()){caller.finish();stopWatchdog(caller);}
            });
            return null;
        }
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
    private Location parseLocation(ScriptContext context,String raw){
        String expression=raw.trim();
        int offset=findTopLevelToken(expression,"+");
        if(offset<0)offset=findTopLevelToken(expression,"-");
        if(offset>0){
            Location base=parseLocation(context,expression.substring(0,offset).trim());
            if(base==null)return null;
            String vector=expression.substring(offset+1).trim();
            double sign=expression.charAt(offset)=='-'?-1.0:1.0;
            if(vector.startsWith("(")&&vector.endsWith(")")){
                String[] parts=vector.substring(1,vector.length()-1).split(",");
                if(parts.length!=3)return null;
                base.add(sign*num(resolve(context,parts[0])),sign*num(resolve(context,parts[1])),sign*num(resolve(context,parts[2])));
            }else{
                base.add(sign*num(resolve(context,vector)),0,0);
            }
            return base;
        }
        if(expression.startsWith("(")&&expression.endsWith(")")){
            String[] parts=expression.substring(1,expression.length()-1).split(",");
            if(parts.length==3){
                try{return new Location(context.npc().location().getWorld(),num(resolve(context,parts[0])),num(resolve(context,parts[1])),num(resolve(context,parts[2])));}
                catch(NumberFormatException ignored){return null;}
            }
        }
        if(expression.equalsIgnoreCase("event npc")||expression.equalsIgnoreCase("location of event npc"))
            return context.eventNpc()==null?null:context.eventNpc().location();
        if(expression.equalsIgnoreCase("event place"))return context.eventPlace();
        if(expression.equalsIgnoreCase("event entity")){Entity entity=context.eventEntity();return entity==null?null:entity.getLocation();}
        if(expression.equalsIgnoreCase("location of npc"))return context.npc().location();
        if(expression.toLowerCase(Locale.ROOT).startsWith("nearest block ")){
            Material material=Material.matchMaterial(expression.substring(14).trim());
            return material==null?null:findNearestBlock(context.npc().location(),material);
        }
        Object target=resolve(context,expression);
        if(target instanceof Location location)return location.clone();
        if(target instanceof ManagedNPC npc)return npc.location();
        if(target instanceof Entity entity)return entity.getLocation();
        if(target instanceof Iterable<?> iterable){
            Location origin=context.npc().location();
            return java.util.stream.StreamSupport.stream(iterable.spliterator(),false)
                    .filter(Entity.class::isInstance).map(Entity.class::cast)
                    .min(Comparator.comparingDouble(entity->entity.getLocation().distanceSquared(origin)))
                    .map(Entity::getLocation).orElse(null);
        }
        return null;
    }

    private void sendToTarget(ScriptContext context,String selector,Object resolved,String message){
        String rendered=ChatColor.translateAlternateColorCodes('&',message);
        if(resolved instanceof Player player){player.sendMessage(rendered);return;}
        if(resolved instanceof Iterable<?> iterable){
            for(Object value:iterable)if(value instanceof Player player)player.sendMessage(rendered);
            return;
        }
        if(resolved instanceof Entity entity){
            if(entity instanceof Player player)player.sendMessage(rendered);
            return;
        }
        Player player=selector.equalsIgnoreCase("@p")?nearest(context.npc().location()):Bukkit.getPlayerExact(selector);
        if(player!=null)player.sendMessage(rendered);
    }
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

    private Location findNearestBlock(Location origin,Material material){
        int radius=Math.max(1,Math.min(32,plugin.getConfig().getInt("movement.block-search-radius",16)));
        int cx=origin.getBlockX(),cy=origin.getBlockY(),cz=origin.getBlockZ();
        Location nearest=null;double best=Double.POSITIVE_INFINITY;
        for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++)for(int dz=-radius;dz<=radius;dz++){
            double distance=dx*dx+dy*dy+dz*dz;
            if(distance>radius*radius||distance>=best)continue;
            int x=cx+dx,y=cy+dy,z=cz+dz;
            if(!origin.getWorld().isChunkLoaded(x>>4,z>>4))continue;
            if(origin.getWorld().getBlockAt(x,y,z).getType()!=material)continue;
            best=distance;
            nearest=new Location(origin.getWorld(),x+0.5,y,z+0.5);
        }
        return nearest;
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
            target.damage(npc.attackDamage(),npc.hitbox());
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
            if(!npc.scriptsEnabled())continue;
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
        String value=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        try{
            if(value.endsWith("ticks"))return scaledDuration(value.substring(0,value.length()-5),50L);
            if(value.endsWith("tick"))return scaledDuration(value.substring(0,value.length()-4),50L);
            if(value.endsWith("ms"))return scaledDuration(value.substring(0,value.length()-2),1L);
            if(value.endsWith("minutes"))return scaledDuration(value.substring(0,value.length()-7),60_000L);
            if(value.endsWith("minute"))return scaledDuration(value.substring(0,value.length()-6),60_000L);
            if(value.endsWith("m"))return scaledDuration(value.substring(0,value.length()-1),60_000L);
            if(value.endsWith("seconds"))return scaledDuration(value.substring(0,value.length()-7),1_000L);
            if(value.endsWith("second"))return scaledDuration(value.substring(0,value.length()-6),1_000L);
            if(value.endsWith("s"))return scaledDuration(value.substring(0,value.length()-1),1_000L);
            return scaledDuration(value,50L);
        }catch(NumberFormatException|ArithmeticException exception){return -1L;}
    }

    private long scaledDuration(String raw,long multiplier){
        long amount=Long.parseLong(raw.trim());
        if(amount<0)return -1L;
        return Math.multiplyExact(amount,multiplier);
    }

    private long parseDurationTicks(String raw){
        String value=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        if(!value.matches("[0-9]+\\s*(ticks?|ms|minutes?|m|seconds?|s)?"))return -1L;
        long millis=parseDurationMillis(value);
        return millis<0?-1L:Math.max(1L,millis/50L+(millis%50L==0?0:1));
    }
    @EventHandler public void onNpcRightClick(PlayerInteractAtEntityEvent e){
        String id=e.getRightClicked().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);fire(id,e.getPlayer().isSneaking()?"right click while sneaking":"right click",e.getPlayer());fire(id,"click",e.getPlayer());fire(id,"interact",e.getPlayer());if(!e.getPlayer().getInventory().getItemInMainHand().getType().isAir())fire(id,"item click",e.getPlayer());}
    }
    @EventHandler public void onNpcLeftClick(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player p))return;
        String id=e.getEntity().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);ManagedNPC npc=npcs.get(id,p.getWorld());if(npc!=null)npc.setLastAttacker(p.getUniqueId());fire(id,p.isSneaking()?"left click while sneaking":"left click",p);fire(id,"click",p);fire(id,"damage",p);}
    }
    @EventHandler public void onPlayerCommand(PlayerCommandPreprocessEvent event){
        Player player=event.getPlayer();
        String commandText=event.getMessage();
        for(ManagedNPC npc:npcs.all())if(npc.location().getWorld().equals(player.getWorld())){
            fire(npc.id(),"player command",player,commandText);
            fire(npc.id(),"command",player,commandText);
        }
    }
    @EventHandler public void onPlayerChat(AsyncChatEvent event){
        Player player=event.getPlayer();
        String message=PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin,()->{
            for(ManagedNPC npc:npcs.all())if(npc.location().getWorld().equals(player.getWorld())){
                fire(npc.id(),"player chat",player,message);
                fire(npc.id(),"chat",player,message);
            }
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e){
        for(ManagedNPC npc:npcs.all()){
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())&&e.getPlayer().getLocation().distanceSquared(npc.location())<64*64)npc.show(e.getPlayer());
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())){
                fire(npc.id(),"player join",e.getPlayer());
                fire(npc.id(),"player connect",e.getPlayer());
                fire(npc.id(),"join",e.getPlayer());
                fire(npc.id(),"connect",e.getPlayer());
            }
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){
        for(ManagedNPC npc:npcs.all()){
            if(e.getPlayer().getWorld().equals(npc.location().getWorld())){
                fire(npc.id(),"player quit",e.getPlayer());
                fire(npc.id(),"player disconnect",e.getPlayer());
                fire(npc.id(),"quit",e.getPlayer());
                fire(npc.id(),"disconnect",e.getPlayer());
            }
            npc.hide(e.getPlayer());
        }
    }
    public void shutdown(){tasks.forEach(BukkitTask::cancel);tasks.clear();movementTasks.clear();followTasks.clear();combatTasks.clear();lastAttackTimes.clear();intervalTasks.clear();watchdogTasks.clear();scriptLastRun.clear();scriptLastChange.clear();scriptLastWarning.clear();scriptWatchdogTask=null;proximityTask=null;scripts.clear();functions.clear();nearbyEntities.clear();nearbyNpcs.clear();}
}
