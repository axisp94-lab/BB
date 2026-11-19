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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptEngine implements Listener {
    private record Instruction(int indent,String text,int line){}
    private record FunctionDefinition(List<String> parameters,List<Instruction> code){}
    private final ANPCPlugin plugin; private final NPCManager npcs; private final VariableStore variables;
    private final Map<String,Map<String,List<Instruction>>> scripts=new ConcurrentHashMap<>();
    private final Map<String,FunctionDefinition> functions=new ConcurrentHashMap<>();
    private final Set<BukkitTask> tasks=ConcurrentHashMap.newKeySet();
    private final Map<String,Set<UUID>> nearbyPlayers=new ConcurrentHashMap<>();
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll(){
        scripts.clear(); functions.clear();
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
            if(indent==0&&text.toLowerCase(Locale.ROOT).startsWith("on ")){
                if(!text.endsWith(":")){
                    plugin.getLogger().warning("Déclaration d'événement invalide ligne "+(i+1)+" : "+text);
                    event=null;
                    continue;
                }
                String eventName=text.substring(3,text.length()-1).trim().toLowerCase(Locale.ROOT);
                if(result.containsKey(eventName)){
                    plugin.getLogger().warning("Événement dupliqué '"+eventName+"' ligne "+(i+1)+" : bloc ignoré.");
                    event=null;
                }else{
                    event=eventName;
                    result.put(event,new ArrayList<>());
                }
            }else if(event!=null)result.get(event).add(new Instruction(indent,text,i+1));
        }
        return result;
    }
    private Map<String,FunctionDefinition> parseFunctions(List<String> lines){
        Map<String,FunctionDefinition> result=new LinkedHashMap<>();
        for(int i=0;i<lines.size();i++){
            String raw=lines.get(i);
            if(raw.isBlank()||raw.stripLeading().startsWith("#")||!raw.equals(raw.stripLeading()))continue;
            String header=raw.trim();
            if(!header.toLowerCase(Locale.ROOT).startsWith("function ")||!header.endsWith(":"))continue;
            int open=header.indexOf('('),close=header.lastIndexOf(')');
            if(open<10||close<open){plugin.getLogger().warning("Déclaration de fonction invalide ligne "+(i+1)+" : "+header);continue;}
            String name=header.substring(9,open).trim().toLowerCase(Locale.ROOT);
            if(name.isBlank())continue;
            List<String> parameters=new ArrayList<>();
            String rawParameters=header.substring(open+1,close).trim();
            if(!rawParameters.isBlank())for(String parameter:splitArguments(rawParameters))parameters.add(parameter.trim().toLowerCase(Locale.ROOT));
            List<Instruction> body=new ArrayList<>();
            int j=i+1;
            for(;j<lines.size();j++){
                String line=lines.get(j);
                if(line.isBlank()||line.stripLeading().startsWith("#"))continue;
                int indent=line.length()-line.stripLeading().length();
                if(indent==0)break;
                body.add(new Instruction(indent,line.trim(),j+1));
            }
            if(result.putIfAbsent(name,new FunctionDefinition(List.copyOf(parameters),List.copyOf(body)))!=null)
                plugin.getLogger().warning("Fonction dupliquée '"+name+"' dans le même fichier, déclaration ignorée (ligne "+(i+1)+").");
            i=j-1;
        }
        return result;
    }
    private void scheduleIntervals(){for(var entry:scripts.entrySet()){List<Instruction> code=entry.getValue().get("interval");if(code==null)continue;long period=plugin.getConfig().getLong("script-default-interval",20);BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{for(ManagedNPC npc:npcs.instances(entry.getKey()))if(npc.scriptsEnabled())execute(npc,null,code,0);},period,period);tasks.add(task);}}
    private void scheduleProximity(){BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{double radius=plugin.getConfig().getDouble("script-approach-radius",3.0);double r2=radius*radius;for(ManagedNPC npc:npcs.all()){String id=npc.id().toLowerCase(Locale.ROOT)+"@"+npc.location().getWorld().getName().toLowerCase(Locale.ROOT);Set<UUID> current=new HashSet<>();for(Player p:npc.location().getWorld().getPlayers())if(p.getLocation().distanceSquared(npc.location())<=r2){current.add(p.getUniqueId());if(nearbyPlayers.computeIfAbsent(id,k->ConcurrentHashMap.newKeySet()).add(p.getUniqueId()))fire(npc.id(),"entity approach",p);}Set<UUID> previous=nearbyPlayers.computeIfAbsent(id,k->ConcurrentHashMap.newKeySet());for(UUID uuid:new HashSet<>(previous))if(!current.contains(uuid)){Player p=Bukkit.getPlayer(uuid);previous.remove(uuid);if(p!=null)fire(npc.id(),"entity leave",p);}}},10L,10L);tasks.add(task);}
    public void fire(String npc,String event,Player player){
        Map<String,List<Instruction>> map=scripts.get(npc.toLowerCase(Locale.ROOT));
        if(map==null)return;
        List<Instruction> code=map.get(event.toLowerCase(Locale.ROOT));
        if(code==null)return;
        Collection<ManagedNPC> targets=player==null?npcs.instances(npc):Optional.ofNullable(npcs.get(npc,player.getWorld())).map(List::of).orElse(List.of());
        for(ManagedNPC managed:targets)if(managed.scriptsEnabled())execute(managed,player,code,0);
    }
    private void execute(ManagedNPC npc,Player player,List<Instruction> code,int baseIndent){ScriptContext ctx=new ScriptContext(npc,player);int indent=code.isEmpty()?baseIndent:code.get(0).indent();runBlock(ctx,code,0,indent);}
    private int runBlock(ScriptContext c,List<Instruction> code,int index,int indent){if(!c.npc().scriptsEnabled())return code.size();while(index<code.size()){if(!c.npc().scriptsEnabled()||c.returned())return code.size();if(c.loopControlRequested())return code.size();Instruction in=code.get(index);if(in.indent()<indent)break;if(in.indent()>indent){index++;continue;}String s=in.text();int line=in.line();
        if(s.equals("return")){c.setReturnValue(null);return code.size();}
        if(s.startsWith("return ")){c.setReturnValue(resolve(c,s.substring(7).trim()));return code.size();}
        if(s.startsWith("call ")){callFunction(c,s.substring(5).trim());index++;continue;}
        if(s.startsWith("loop ")&&s.endsWith(":")){int end=findEnd(code,index+1,in.indent());int child=index+1<code.size()?code.get(index+1).indent():in.indent()+4;executeLoop(c,code,index+1,child,s.substring(5,s.length()-1).trim());index=end;continue;}
        if(s.equals("stop loop")){c.stopLoop();return code.size();}
        if(s.equals("skip loop")){c.skipLoop();return code.size();}
        if(s.startsWith("if ")&&s.endsWith(":")){int end=findEnd(code,index+1,in.indent());if(test(c,s.substring(3,s.length()-1))){int child=index+1<code.size()?code.get(index+1).indent():in.indent()+4;runBlock(c,code,index+1,child);}index=end;continue;}
        if(s.startsWith("set ")){String body=s.substring(4).trim();int q=body.indexOf('"',1);if(body.startsWith("\"")&&q>0){String name=body.substring(1,q);String val=body.substring(q+1).trim();if(val.startsWith("(" )&&val.endsWith(")"))val=val.substring(1,val.length()-1);Object value=resolve(c,val);c.locals().put(name,value);if(!c.isTransientLocal(name))storeVariable(c,name,value);}index++;continue;}
        if(s.startsWith("create variable ")){createVariable(c,s.substring(15).trim());index++;continue;}
        if(s.startsWith("say local ")||s.startsWith("say global ")){boolean global=s.startsWith("say global ");Object message=resolve(c,s.substring(global?11:10).trim());String rendered=ChatColor.translateAlternateColorCodes('&',c.npc().definition().displayName()+" » "+String.valueOf(message));if(global)Bukkit.broadcastMessage(rendered);else if(c.player()!=null)c.player().sendMessage(rendered);else for(Player p:c.npc().location().getWorld().getPlayers())if(p.getLocation().distanceSquared(c.npc().location())<32*32)p.sendMessage(rendered);index++;continue;}
        if(s.startsWith("send to ")){int pos=s.indexOf(' ',8);if(pos>0){String target=s.substring(8,pos);Object targetValue=resolve(c,target);String msg=String.valueOf(resolve(c,s.substring(pos+1).trim()));Player p=targetValue instanceof Player selected?selected:(target.equals("@p")?nearest(c.npc().location()):Bukkit.getPlayerExact(target));if(p!=null)p.sendMessage(ChatColor.translateAlternateColorCodes('&',msg));}index++;continue;}
        if(s.startsWith("play anime ")){c.npc().animate(unquote(s.substring(11)));index++;continue;}
        if(s.startsWith("play sound ")){String name=unquote(s.substring(11));try{Sound sound=Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.','_'));c.npc().location().getWorld().playSound(c.npc().location(),sound,1f,1f);}catch(IllegalArgumentException ignored){}index++;continue;}
        if(s.startsWith("move to ")){Location target=parseLocation(c,s.substring(8).trim());if(target!=null)move(c.npc(),target);index++;continue;}
        if(s.startsWith("tp ")){Location target=parseLocation(c,s.substring(3).trim());if(target!=null)move(c.npc(),target);index++;continue;}
        if(s.startsWith("flag on ")||s.startsWith("flag off ")){boolean on=s.startsWith("flag on ");String name=s.substring(on?8:9).replace("(","").replace(")","").trim();variables.setLocal(c.npc().id(),c.npc().location().getWorld().getName(),"flag:"+name,on);index++;continue;}
        if(s.startsWith("wait ")){long ticks=parseDurationTicks(s.substring(5));List<Instruction> remaining=new ArrayList<>(code.subList(index+1,code.size()));BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->runBlock(c,remaining,0,indent),ticks);tasks.add(task);return code.size();}
        if(s.startsWith("run command ")){String cmd=unquote(s.substring(12));Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd.replace("%player%",c.player()==null?"":c.player().getName()));index++;continue;}
        if(s.startsWith("look at ")&&c.player()!=null){Location at=c.player().getLocation();Location from=c.npc().location();from.setDirection(at.toVector().subtract(from.toVector()));c.npc().move(from);index++;continue;}
        if(s.equals("stop follow")||s.equals("stop attack")){index++;continue;}
        if(s.startsWith("set state ")){c.npc().setPrimaryState(unquote(s.substring(10)));index++;continue;}
        if(s.startsWith("add state ")){c.npc().addSecondaryState(unquote(s.substring(10)));index++;continue;}
        if(s.startsWith("remove state ")){c.npc().removeSecondaryState(unquote(s.substring(13)));index++;continue;}
        if(s.equals("clear states")){c.npc().setPrimaryState("idle");c.npc().clearSecondaryStates();index++;continue;}
        index++;
    }return index;}
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
    private boolean test(ScriptContext c,String expression){String e=expression.trim();if(e.startsWith("flag (")&&e.contains(") is ")){String name=e.substring(6,e.indexOf(')'));boolean v=Boolean.parseBoolean(variables.getLocal(c.npc().id(),c.npc().location().getWorld().getName(),"flag:"+name,"false"));return e.endsWith("on")?v:!v;}String[] ops={" >= "," <= "," != "," == "," > "," < "," contains "};for(String op:ops){int p=e.indexOf(op);if(p>0){Object a=resolve(c,e.substring(0,p)),b=resolve(c,e.substring(p+op.length()));if(op.equals(" contains "))return String.valueOf(a).contains(String.valueOf(b));double x=num(a),y=num(b);return switch(op.trim()){case ">"->x>y;case "<"->x<y;case ">="->x>=y;case "<="->x<=y;case "!="->!Objects.equals(String.valueOf(a),String.valueOf(b));default->Objects.equals(String.valueOf(a),String.valueOf(b))||x==y;};}}if(e.equalsIgnoreCase("player is sneaking"))return c.player()!=null&&c.player().isSneaking();if(e.toLowerCase(Locale.ROOT).startsWith("player has permission "))return c.player()!=null&&c.player().hasPermission(unquote(e.substring(22).trim()));if(e.toLowerCase(Locale.ROOT).startsWith("player has item ")){if(c.player()==null)return false;try{return c.player().getInventory().contains(org.bukkit.Material.valueOf(e.substring(16).trim().toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException ex){return false;}}if(e.equalsIgnoreCase("npc is under attack"))return false;
        if(e.toLowerCase(Locale.ROOT).startsWith("npc state is "))return c.npc().primaryState().equalsIgnoreCase(unquote(e.substring(13).trim()));
        if(e.toLowerCase(Locale.ROOT).startsWith("state of npc is "))return c.npc().primaryState().equalsIgnoreCase(unquote(e.substring(16).trim()));
        if(e.toLowerCase(Locale.ROOT).startsWith("npc has state "))return c.npc().secondaryStates().contains(unquote(e.substring(14).trim()).toLowerCase(Locale.ROOT));return false;}
    private Object resolve(ScriptContext c,String raw){
        String value=raw.trim();
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

    private int findTopLevelOperator(String expression,char wanted){
        boolean quoted=false;int depth=0;int found=-1;
        for(int i=0;i<expression.length();i++){
            char ch=expression.charAt(i);
            if(ch=='"'&&(i==0||expression.charAt(i-1)!='\\\\'))quoted=!quoted;
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
    private Location parseLocation(ScriptContext c,String s){if(s.equals("@p")){Player p=nearest(c.npc().location());return p==null?null:p.getLocation();}if(s.equalsIgnoreCase("location of npc"))return c.npc().location();if(s.startsWith("(")&&s.endsWith(")")){String[] a=s.substring(1,s.length()-1).split(",");if(a.length==3)try{return new Location(c.npc().location().getWorld(),Double.parseDouble(a[0].trim()),Double.parseDouble(a[1].trim()),Double.parseDouble(a[2].trim()));}catch(NumberFormatException ignored){}}return null;}
    private void move(ManagedNPC npc,Location target){Location start=npc.location();if(start.getWorld()!=target.getWorld())return;double distance=start.distance(target);double speed=plugin.getConfig().getDouble("movement.default-speed",4.0);long duration=Math.max(1,Math.round(distance/Math.max(.1,speed)*20));final long[] tick={0};BukkitTask[] holder=new BukkitTask[1];holder[0]=Bukkit.getScheduler().runTaskTimer(plugin,()->{tick[0]++;double t=Math.min(1,(double)tick[0]/duration);Location next=start.clone().add((target.getX()-start.getX())*t,(target.getY()-start.getY())*t,(target.getZ()-start.getZ())*t);next.setYaw((float)(start.getYaw()+(target.getYaw()-start.getYaw())*t));next.setPitch((float)(start.getPitch()+(target.getPitch()-start.getPitch())*t));npc.move(next);if(t>=1){holder[0].cancel();tasks.remove(holder[0]);npc.animate("idle");}},1L,Math.max(1,plugin.getConfig().getLong("movement.update-ticks",1)));tasks.add(holder[0]);npc.animate("walk");}
    private long parseDurationTicks(String s){String[] a=s.trim().split("\\s+");try{long n=Long.parseLong(a[0]);return a.length>1&&a[1].startsWith("second")?n*20:n;}catch(Exception e){return 1;}}
    @EventHandler public void onNpcRightClick(PlayerInteractAtEntityEvent e){
        String id=e.getRightClicked().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);fire(id,e.getPlayer().isSneaking()?"right click while sneaking":"right click",e.getPlayer());if(!e.getPlayer().getInventory().getItemInMainHand().getType().isAir())fire(id,"item click",e.getPlayer());}
    }
    @EventHandler public void onNpcLeftClick(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player p))return;
        String id=e.getEntity().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);fire(id,p.isSneaking()?"left click while sneaking":"left click",p);fire(id,"damage",p);}
    }
    @EventHandler public void onJoin(PlayerJoinEvent e){for(ManagedNPC npc:npcs.all())if(e.getPlayer().getWorld().equals(npc.location().getWorld())&&e.getPlayer().getLocation().distanceSquared(npc.location())<64*64)npc.show(e.getPlayer());}
    @EventHandler public void onQuit(PlayerQuitEvent e){for(ManagedNPC npc:npcs.all())npc.hide(e.getPlayer());}
    public void shutdown(){tasks.forEach(BukkitTask::cancel);tasks.clear();scripts.clear();functions.clear();nearbyPlayers.clear();}
}
