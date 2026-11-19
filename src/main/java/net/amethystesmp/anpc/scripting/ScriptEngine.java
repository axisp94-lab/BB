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
    private record Instruction(int indent, String text, int line) {}
    private record LoopPlan(String variable, List<Object> values) {}
    private final ANPCPlugin plugin; private final NPCManager npcs; private final VariableStore variables;
    private final Map<String,Map<String,List<Instruction>>> scripts=new ConcurrentHashMap<>();
    private final Set<BukkitTask> tasks=ConcurrentHashMap.newKeySet();
    private final Map<String,Set<UUID>> nearbyPlayers=new ConcurrentHashMap<>();
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll() {
        cancelTasks();
        scripts.clear();
        nearbyPlayers.clear();

        File directory = new File(plugin.getDataFolder(), "scripts");
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".aa"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 3).toLowerCase(Locale.ROOT);
            try {
                scripts.put(id, parse(Files.readAllLines(file.toPath()), file.getName()));
            } catch (Exception exception) {
                plugin.getLogger().warning("Erreur script " + file.getName() + " : " + exception.getMessage());
            }
        }

        scheduleIntervals();
        scheduleProximity();
        for (ManagedNPC npc : npcs.all()) {
            Map<String, List<Instruction>> events = scripts.get(npc.id().toLowerCase(Locale.ROOT));
            if (events != null) {
                List<Instruction> spawn = events.get("spawn");
                if (spawn != null) execute(npc, null, spawn, 0);
            }
        }
    }
    private Map<String, List<Instruction>> parse(List<String> lines, String source) {
        Map<String, List<Instruction>> result = new LinkedHashMap<>();
        String event = null;
        int bodyIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).replace("\t", "    ");
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;

            int indent = raw.length() - raw.stripLeading().length();
            String text = raw.trim();
            String lower = text.toLowerCase(Locale.ROOT);

            if (indent == 0 && lower.startsWith("on ") && text.endsWith(":")) {
                event = text.substring(3, text.length() - 1).trim().toLowerCase(Locale.ROOT);
                result.computeIfAbsent(event, ignored -> new ArrayList<>());
                bodyIndent = -1;
                continue;
            }

            if (event == null) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : instruction ignorée hors d'un événement.");
                continue;
            }

            if (bodyIndent < 0) {
                bodyIndent = indent;
                if (bodyIndent == 0) {
                    plugin.getLogger().warning(source + ":" + (i + 1) + " : le contenu d'un événement doit être indenté.");
                    event = null;
                    continue;
                }
            }

            if (indent < bodyIndent) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : indentation inattendue ; instruction ignorée.");
                event = null;
                continue;
            }

            int relativeIndent = indent - bodyIndent;
            if (relativeIndent % 4 != 0) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : indentation non multiple de quatre espaces.");
            }
            result.get(event).add(new Instruction(relativeIndent, text, i + 1));
        }
        return result;
    }
    private void scheduleIntervals() {
        long period = Math.max(1L, plugin.getConfig().getLong("script-default-interval", 20L));
        for (ManagedNPC npc : npcs.all()) {
            Map<String, List<Instruction>> events = scripts.get(npc.id().toLowerCase(Locale.ROOT));
            if (events == null) continue;
            List<Instruction> code = events.get("interval");
            if (code == null) continue;

            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> execute(npc, null, code, 0), period, period);
            tasks.add(task);
        }
    }
    private void scheduleProximity() {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double radius = Math.max(0, plugin.getConfig().getDouble("script-approach-radius", 3.0));
            double radiusSquared = radius * radius;

            for (ManagedNPC npc : npcs.all()) {
                Location location = npc.location();
                if (location.getWorld() == null) continue;

                String key = npcKey(npc);
                Set<UUID> current = new HashSet<>();
                Set<UUID> previous = nearbyPlayers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());

                for (Player player : location.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                        current.add(player.getUniqueId());
                        if (previous.add(player.getUniqueId())) fire(npc.id(), "entity approach", player);
                    }
                }

                for (UUID uuid : new HashSet<>(previous)) {
                    if (!current.contains(uuid)) {
                        previous.remove(uuid);
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) fire(npc.id(), "entity leave", player);
                    }
                }
            }
        }, 10L, 10L);
        tasks.add(task);
    }

    private String npcKey(ManagedNPC npc) {
        Location location = npc.location();
        String world = location.getWorld() == null ? "unknown" : location.getWorld().getName().toLowerCase(Locale.ROOT);
        return npc.id().toLowerCase(Locale.ROOT) + "@" + world;
    }
    public void fire(String npc, String event, Player player) {
        Map<String, List<Instruction>> events = scripts.get(npc.toLowerCase(Locale.ROOT));
        if (events == null) return;
        List<Instruction> code = events.get(event.toLowerCase(Locale.ROOT));
        ManagedNPC managed = player == null ? npcs.get(npc) : npcs.get(npc, player.getWorld());
        if (code != null && managed != null) execute(managed, player, code, 0);
    }
    private void execute(ManagedNPC npc, Player player, List<Instruction> code, int baseIndent) {
        ScriptContext context = new ScriptContext(npc, player);
        runBlock(context, code, 0, baseIndent, () -> { });
    }
    private void runBlock(ScriptContext context, List<Instruction> code, int start, int indent, Runnable continuation) {
        int index = start;
        while (index < code.size()) {
            if (context.loopControlRequested()) {
                continuation.run();
                return;
            }
            Instruction instruction = code.get(index);
            if (instruction.indent() < indent) {
                continuation.run();
                return;
            }
            if (instruction.indent() > indent) {
                plugin.getLogger().warning("Script " + context.npc().id() + ", ligne " + instruction.line()
                        + " : bloc indenté sans instruction parent ; bloc ignoré.");
                index = findEnd(code, index + 1, instruction.indent());
                continue;
            }

            String line = instruction.text();

            if (line.startsWith("loop ") && line.endsWith(":")) {
                int end = findEnd(code, index + 1, instruction.indent());
                LoopPlan plan = createLoopPlan(context, line);
                if (plan != null && index + 1 < end) {
                    int bodyIndent = code.get(index + 1).indent();
                    ScriptContext.LoopState state = context.pushLoop();
                    Set<String> existed = new HashSet<>();
                    Map<String, Object> previous = new HashMap<>();
                    for (String key : List.of(plan.variable(), "loop-number")) {
                        if (context.locals().containsKey(key)) {
                            existed.add(key);
                            previous.put(key, context.locals().get(key));
                        }
                    }
                    int bodyStart = index + 1;
                    int next = end;
                    runLoop(context, code, bodyStart, bodyIndent, plan, 0, state, existed, previous,
                            () -> runBlock(context, code, next, indent, continuation));
                    return;
                }
                index = end;
                continue;
            }

            if (line.equalsIgnoreCase("stop loop") || line.equalsIgnoreCase("skip loop")) {
                ScriptContext.LoopState state = context.currentLoop();
                if (state == null) {
                    warnInstruction(context, instruction, "instruction utilisée en dehors d'une boucle");
                } else if (line.equalsIgnoreCase("stop loop")) {
                    state.stop();
                } else {
                    state.skip();
                }
                index++;
                continue;
            }

            if (line.startsWith("if ") && line.endsWith(":")) {
                int end = findEnd(code, index + 1, instruction.indent());
                if (test(context, line.substring(3, line.length() - 1))) {
                    if (index + 1 < end) {
                        int childIndent = code.get(index + 1).indent();
                        int next = end;
                        runBlock(context, code, index + 1, childIndent,
                                () -> runBlock(context, code, next, indent, continuation));
                        return;
                    }
                }
                index = end;
                continue;
            }

            if (line.startsWith("set ")) {
                String body = line.substring(4).trim();
                int closingQuote = body.indexOf('"', 1);
                if (body.startsWith("\"") && closingQuote > 0) {
                    String name = body.substring(1, closingQuote);
                    String valueText = body.substring(closingQuote + 1).trim();
                    if (valueText.startsWith("(") && valueText.endsWith(")")) {
                        valueText = valueText.substring(1, valueText.length() - 1);
                    }
                    Object value = resolve(context, valueText);
                    context.locals().put(name, value);
                    storeVariable(context, name, value);
                } else {
                    warnInstruction(context, instruction, "syntaxe set invalide");
                }
                index++;
                continue;
            }

            if (line.startsWith("create variable ")) {
                createVariable(context, line.substring(15).trim());
                index++;
                continue;
            }

            if (line.startsWith("say local ") || line.startsWith("say global ")) {
                boolean global = line.startsWith("say global ");
                String message = unquote(line.substring(global ? 11 : 10));
                String rendered = ChatColor.translateAlternateColorCodes('&',
                        context.npc().definition().displayName() + " » " + message);
                if (global) {
                    Bukkit.broadcastMessage(rendered);
                } else if (context.player() != null) {
                    context.player().sendMessage(rendered);
                } else {
                    Location location = context.npc().location();
                    if (location.getWorld() != null) {
                        for (Player player : location.getWorld().getPlayers()) {
                            if (player.getLocation().distanceSquared(location) < 32 * 32) player.sendMessage(rendered);
                        }
                    }
                }
                index++;
                continue;
            }

            if (line.startsWith("send to ")) {
                int separator = line.indexOf(' ', 8);
                if (separator > 0) {
                    String target = line.substring(8, separator);
                    String message = unquote(line.substring(separator + 1));
                    Player player = target.equals("@p") ? nearest(context.npc().location()) : Bukkit.getPlayerExact(target);
                    if (player != null) player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
                index++;
                continue;
            }

            if (line.startsWith("play anime ")) {
                context.npc().animate(unquote(line.substring(11)));
                index++;
                continue;
            }

            if (line.startsWith("play sound ")) {
                String name = unquote(line.substring(11));
                try {
                    Sound sound = Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
                    Location location = context.npc().location();
                    if (location.getWorld() != null) location.getWorld().playSound(location, sound, 1f, 1f);
                } catch (IllegalArgumentException exception) {
                    warnInstruction(context, instruction, "son inconnu : " + name);
                }
                index++;
                continue;
            }

            if (line.startsWith("move to ")) {
                Location target = parseLocation(context, line.substring(8).trim());
                if (target != null) move(context.npc(), target);
                else warnInstruction(context, instruction, "cible de déplacement invalide");
                index++;
                continue;
            }

            if (line.startsWith("tp ")) {
                Location target = parseLocation(context, line.substring(3).trim());
                if (target != null) move(context.npc(), target);
                else warnInstruction(context, instruction, "cible de téléportation invalide");
                index++;
                continue;
            }

            if (line.startsWith("flag on ") || line.startsWith("flag off ")) {
                boolean enabled = line.startsWith("flag on ");
                String name = line.substring(enabled ? 8 : 9).replace("(", "").replace(")", "").trim();
                variables.set("npc", context.npc().id(), "flag:" + name, enabled);
                index++;
                continue;
            }

            if (line.startsWith("wait ")) {
                long ticks = parseDurationTicks(line.substring(5));
                int next = index + 1;
                BukkitTask[] holder = new BukkitTask[1];
                holder[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    tasks.remove(holder[0]);
                    runBlock(context, code, next, indent, continuation);
                }, ticks);
                tasks.add(holder[0]);
                return;
            }

            if (line.startsWith("run command ")) {
                String command = unquote(line.substring(12));
                String playerName = context.player() == null ? "" : context.player().getName();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", playerName));
                index++;
                continue;
            }

            if (line.startsWith("look at ") && context.player() != null) {
                Location target = context.player().getLocation();
                Location from = context.npc().location();
                from.setDirection(target.toVector().subtract(from.toVector()));
                context.npc().move(from);
                index++;
                continue;
            }

            if (line.equals("stop follow") || line.equals("stop attack")) {
                index++;
                continue;
            }

            if (line.startsWith("set state ")) {
                context.locals().put("state", unquote(line.substring(10)));
                index++;
                continue;
            }

            warnInstruction(context, instruction, "instruction inconnue");
            index++;
        }
        continuation.run();
    }

    private LoopPlan createLoopPlan(ScriptContext context, String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        if (lower.equals("loop all players:")) {
            return new LoopPlan("loop-player", new ArrayList<>(Bukkit.getOnlinePlayers()));
        }
        if (lower.equals("loop all npcs:")) {
            Map<String, ManagedNPC> unique = new LinkedHashMap<>();
            for (ManagedNPC npc : npcs.all()) unique.putIfAbsent(npc.id().toLowerCase(Locale.ROOT), npc);
            return new LoopPlan("loop-npc", new ArrayList<>(unique.values()));
        }
        if (lower.startsWith("loop all (") && lower.endsWith("):")) {
            String expression = line.substring(10, line.length() - 2).trim();
            Object value = resolve(context, expression);
            List<Object> values = asList(value);
            if (values == null) {
                plugin.getLogger().warning("Script " + context.npc().id() + " : la valeur de boucle n'est pas une liste : " + expression);
                return null;
            }
            return new LoopPlan("loop-value", values);
        }
        if (lower.startsWith("loop ") && lower.endsWith(" times:")) {
            String countText = line.substring(5, line.length() - 7).trim();
            try {
                int count = Math.max(0, Math.min(10000, Integer.parseInt(countText)));
                List<Object> values = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) values.add(i);
                return new LoopPlan("loop-number", values);
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Script " + context.npc().id() + " : nombre de répétitions invalide : " + countText);
                return null;
            }
        }
        return null;
    }

    private List<Object> asList(Object value) {
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) result.add(java.lang.reflect.Array.get(value, i));
            return result;
        }
        if (!(value instanceof String text)) return null;
        String content = text.trim();
        if (content.startsWith("[") && content.endsWith("]")) content = content.substring(1, content.length() - 1);
        if (content.isBlank()) return new ArrayList<>();
        List<Object> result = new ArrayList<>();
        for (String item : content.split(",")) result.add(parseStored(unquote(item.trim())));
        return result;
    }

    private void runLoop(ScriptContext context, List<Instruction> code, int bodyStart, int bodyIndent,
                        LoopPlan plan, int iteration, ScriptContext.LoopState state,
                        Set<String> existed, Map<String, Object> previous, Runnable continuation) {
        if (state.stopped() || iteration >= plan.values().size()) {
            context.popLoop();
            restoreLoopLocals(context, plan, existed, previous);
            continuation.run();
            return;
        }
        if (state.skipped()) state.clearSkip();

        context.locals().put(plan.variable(), plan.values().get(iteration));
        if (!plan.variable().equals("loop-number")) context.locals().put("loop-number", iteration + 1);

        runBlock(context, code, bodyStart, bodyIndent,
                () -> runLoop(context, code, bodyStart, bodyIndent, plan, iteration + 1,
                        state, existed, previous, continuation));
    }

    private void restoreLoopLocals(ScriptContext context, LoopPlan plan, Set<String> existed,
                                   Map<String, Object> previous) {
        for (String key : List.of(plan.variable(), "loop-number")) {
            if (existed.contains(key)) context.locals().put(key, previous.get(key));
            else context.locals().remove(key);
        }
    }

    private void warnInstruction(ScriptContext context, Instruction instruction, String reason) {
        plugin.getLogger().warning("Script " + context.npc().id() + ", ligne " + instruction.line()
                + " : " + reason + " (" + instruction.text() + ").");
    }

    private int findEnd(List<Instruction> code, int from, int parentIndent) {
        int index = from;
        while (index < code.size() && code.get(index).indent() > parentIndent) index++;
        return index;
    }
    private boolean test(ScriptContext c,String expression){String e=expression.trim();if(e.startsWith("flag (")&&e.contains(") is ")){String name=e.substring(6,e.indexOf(')'));boolean v=Boolean.parseBoolean(variables.get("npc",c.npc().id(),"flag:"+name,"false"));return e.endsWith("on")?v:!v;}String[] ops={" >= "," <= "," != "," == "," > "," < "," contains "};for(String op:ops){int p=e.indexOf(op);if(p>0){Object a=resolve(c,e.substring(0,p)),b=resolve(c,e.substring(p+op.length()));if(op.equals(" contains "))return String.valueOf(a).contains(String.valueOf(b));double x=num(a),y=num(b);return switch(op.trim()){case ">"->x>y;case "<"->x<y;case ">="->x>=y;case "<="->x<=y;case "!="->!Objects.equals(String.valueOf(a),String.valueOf(b));default->Objects.equals(String.valueOf(a),String.valueOf(b))||x==y;};}}if(e.equalsIgnoreCase("player is sneaking"))return c.player()!=null&&c.player().isSneaking();if(e.toLowerCase(Locale.ROOT).startsWith("player has permission "))return c.player()!=null&&c.player().hasPermission(unquote(e.substring(22).trim()));if(e.toLowerCase(Locale.ROOT).startsWith("player has item ")){if(c.player()==null)return false;try{return c.player().getInventory().contains(org.bukkit.Material.valueOf(e.substring(16).trim().toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException ex){return false;}}if(e.equalsIgnoreCase("npc is under attack"))return false;return false;}
    private Object resolve(ScriptContext context, String raw) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) return unquote(value);
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }

        String metadata = variables.get("meta", context.npc().id(), value, null);
        if (metadata == null) metadata = variables.get("meta", "", value, null);
        if (metadata != null) {
            String[] parts = metadata.split("\\|", 2);
            String scope = parts[0];
            String owner = parts.length > 1 ? parts[1] : context.npc().id();
            String stored = variables.get(scope, owner, value, null);
            if (stored != null) return parseStored(stored);
        }

        String local = variables.get("local", context.npc().id(), value, null);
        if (local != null) return parseStored(local);
        String global = variables.get("global", "", value, null);
        if (global != null) return parseStored(global);
        return context.value(value);
    }
    private Object parseStored(String s){if(s.equalsIgnoreCase("true")||s.equalsIgnoreCase("false"))return Boolean.parseBoolean(s);try{return Double.parseDouble(s);}catch(Exception e){return s;}}
    private void createVariable(ScriptContext context, String declaration) {
        List<String> arguments = extractGroups(declaration);
        if (arguments.size() < 2) {
            plugin.getLogger().warning("Script " + context.npc().id() + " : déclaration create variable invalide : " + declaration);
            return;
        }

        String name = unquote(arguments.get(0)).trim().toLowerCase(Locale.ROOT);
        if (name.isBlank()) return;
        Object defaultValue = resolve(context, arguments.get(1));
        String scopeDeclaration = arguments.size() > 2 ? arguments.get(2).trim() : "local";
        if (arguments.size() > 3) scopeDeclaration += " " + arguments.get(3).trim();

        String scope = "local";
        String owner = context.npc().id();

        if (scopeDeclaration.toLowerCase(Locale.ROOT).startsWith("global")) {
            scope = "global";
            owner = "";
            variables.set("meta", "", name, "global|@");
        } else if (scopeDeclaration.toLowerCase(Locale.ROOT).startsWith("shared")) {
            int open = scopeDeclaration.indexOf('[');
            int close = scopeDeclaration.indexOf(']', open + 1);
            if (open < 0 || close < 0) {
                plugin.getLogger().warning("Script " + context.npc().id() + " : une variable shared doit préciser les NPC entre crochets.");
                return;
            }
            List<String> members = Arrays.stream(scopeDeclaration.substring(open + 1, close).split(","))
                    .map(String::trim)
                    .filter(member -> !member.isBlank())
                    .map(member -> member.toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
            if (members.isEmpty()) return;
            scope = "shared";
            owner = String.join(",", members);
            for (String member : members) variables.set("meta", member, name, "shared|" + owner);
        } else {
            variables.set("meta", context.npc().id(), name, "local|" + owner);
        }

        variables.set(scope, owner, name, defaultValue);
        context.locals().put(name, defaultValue);
    }

    private List<String> extractGroups(String declaration) {
        List<String> groups = new ArrayList<>();
        boolean quoted = false;
        int start = -1;
        int depth = 0;

        for (int i = 0; i < declaration.length(); i++) {
            char character = declaration.charAt(i);
            if (character == '"' && (i == 0 || declaration.charAt(i - 1) != '\\\\')) quoted = !quoted;
            if (quoted) continue;

            if (character == '(') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (character == ')' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    groups.add(declaration.substring(start, i).trim());
                    start = -1;
                }
            }
        }
        return groups;
    }
    private void storeVariable(ScriptContext context, String name, Object value) {
        String metadata = variables.get("meta", context.npc().id(), name, null);
        if (metadata == null) metadata = variables.get("meta", "", name, null);
        if (metadata == null) {
            metadata = "local|" + context.npc().id();
            variables.set("meta", context.npc().id(), name, metadata);
        }
        String[] parts = metadata.split("\\|", 2);
        String scope = parts[0];
        String owner = parts.length > 1 ? parts[1] : context.npc().id();
        variables.set(scope, owner, name, value);
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
    private void cancelTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    public void shutdown() {
        cancelTasks();
        scripts.clear();
        nearbyPlayers.clear();
    }
}
