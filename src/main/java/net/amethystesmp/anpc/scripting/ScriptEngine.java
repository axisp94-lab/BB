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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
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
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "spawn", "interval", "entity approach", "entity leave",
            "npc approach", "npc leave", "right click", "right click while sneaking",
            "left click", "left click while sneaking", "item click", "damage");
    private record LoopPlan(String variable, List<Object> values) {}
    private record FunctionDefinition(List<String> parameters, List<Instruction> code) {}
    private record ScriptData(Map<String, List<Instruction>> events, Map<String, FunctionDefinition> functions) {}
    private record FunctionResult(boolean returned, Object value) {}
    private record NpcPair(ManagedNPC first, ManagedNPC second) {}
    private final ANPCPlugin plugin; private final NPCManager npcs; private final VariableStore variables;
    private final Map<String, ScriptData> scripts = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> functionDepth = ThreadLocal.withInitial(() -> 0);
    private final Set<BukkitTask> tasks = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> movementTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> intervalTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> followTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> nearbyPlayers = new ConcurrentHashMap<>();
    private final Map<String, NpcPair> activeNpcPairs = new HashMap<>();
    private final Map<String, String> mainStates = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> secondaryStates = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAttackedAt = new ConcurrentHashMap<>();
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll() {
        cancelTasks();
        scripts.clear();
        nearbyPlayers.clear();
        lastAttackedAt.clear();

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
        scheduleNpcProximity();
    }
    private ScriptData parse(List<String> lines, String source) {
        Map<String, List<Instruction>> events = new LinkedHashMap<>();
        Map<String, FunctionDefinition> functions = new LinkedHashMap<>();
        String currentEvent = null;
        List<Instruction> currentBody = null;
        int bodyIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).replace("\t", "    ");
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;

            int indent = raw.length() - raw.stripLeading().length();
            String text = raw.trim();
            String lower = text.toLowerCase(Locale.ROOT);

            if (indent == 0 && lower.startsWith("on ") && text.endsWith(":")) {
                currentEvent = text.substring(3, text.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (SUPPORTED_EVENTS.contains(currentEvent)) {
                    currentBody = events.computeIfAbsent(currentEvent, ignored -> new ArrayList<>());
                } else {
                    plugin.getLogger().warning(source + ":" + (i + 1)
                            + " : événement inconnu '" + currentEvent + "' ; bloc ignoré.");
                    currentBody = new ArrayList<>();
                }
                bodyIndent = -1;
                continue;
            }

            if (indent == 0 && lower.startsWith("def ") && text.endsWith(":")) {
                currentEvent = null;
                String signature = text.substring(4, text.length() - 1).trim();
                int open = signature.indexOf('(');
                int close = signature.lastIndexOf(')');
                if (open <= 0 || close < open) {
                    plugin.getLogger().warning(source + ":" + (i + 1) + " : définition de fonction invalide.");
                    currentBody = null;
                    bodyIndent = -1;
                    continue;
                }
                String name = signature.substring(0, open).trim().toLowerCase(Locale.ROOT);
                List<String> parameters = Arrays.stream(signature.substring(open + 1, close).split(","))
                        .map(String::trim)
                        .filter(parameter -> !parameter.isBlank())
                        .map(parameter -> parameter.toLowerCase(Locale.ROOT))
                        .toList();
                currentBody = new ArrayList<>();
                functions.put(name, new FunctionDefinition(parameters, currentBody));
                bodyIndent = -1;
                continue;
            }

            if (currentBody == null) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : instruction ignorée hors d'un événement ou d'une fonction.");
                continue;
            }

            if (bodyIndent < 0) {
                bodyIndent = indent;
                if (bodyIndent == 0) {
                    plugin.getLogger().warning(source + ":" + (i + 1) + " : le contenu doit être indenté.");
                    currentBody = null;
                    currentEvent = null;
                    continue;
                }
            }

            if (indent < bodyIndent) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : indentation inattendue ; instruction ignorée.");
                currentBody = null;
                currentEvent = null;
                continue;
            }

            int relativeIndent = indent - bodyIndent;
            if (relativeIndent % 4 != 0) {
                plugin.getLogger().warning(source + ":" + (i + 1) + " : indentation non multiple de quatre espaces.");
            }
            currentBody.add(new Instruction(relativeIndent, text, i + 1));
        }
        return new ScriptData(events, functions);
    }
    private void scheduleIntervals() {
        for (ManagedNPC npc : npcs.all()) onNpcLoaded(npc);
    }

    public void onNpcLoaded(ManagedNPC npc) {
        if (npc == null) return;
        String key = npcKey(npc);
        ScriptData script = scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if (script != null) {
            List<Instruction> interval = script.events().get("interval");
            if (interval != null && !intervalTasks.containsKey(key)) {
                long period = Math.max(1L, plugin.getConfig().getLong("script-default-interval", 20L));
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                        () -> execute(npc, null, interval, 0), period, period);
                intervalTasks.put(key, task);
                tasks.add(task);
            }
            List<Instruction> spawn = script.events().get("spawn");
            if (spawn != null) execute(npc, null, spawn, 0);
        }
    }

    public void onNpcUnloaded(ManagedNPC npc) {
        if (npc == null) return;
        String key = npcKey(npc);
        BukkitTask interval = intervalTasks.remove(key);
        if (interval != null) {
            interval.cancel();
            tasks.remove(interval);
        }
        stopFollow(npc);
        stopMovement(npc);
        nearbyPlayers.remove(key);
        lastAttackedAt.remove(key);
        activeNpcPairs.entrySet().removeIf(entry ->
                entry.getValue().first() == npc || entry.getValue().second() == npc);
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

    private void scheduleNpcProximity() {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double radius = Math.max(0, plugin.getConfig().getDouble("script-npc-approach-radius", 3.0));
            double radiusSquared = radius * radius;
            List<ManagedNPC> loaded = new ArrayList<>(npcs.all());
            Map<String, NpcPair> currentPairs = new HashMap<>();

            for (int i = 0; i < loaded.size(); i++) {
                ManagedNPC first = loaded.get(i);
                Location firstLocation = first.location();
                if (firstLocation.getWorld() == null) continue;

                for (int j = i + 1; j < loaded.size(); j++) {
                    ManagedNPC second = loaded.get(j);
                    Location secondLocation = second.location();
                    if (secondLocation.getWorld() == null
                            || !firstLocation.getWorld().equals(secondLocation.getWorld())
                            || firstLocation.distanceSquared(secondLocation) > radiusSquared) continue;

                    String firstKey = npcKey(first);
                    String secondKey = npcKey(second);
                    String pairKey = firstKey.compareTo(secondKey) <= 0
                            ? firstKey + "|" + secondKey : secondKey + "|" + firstKey;
                    NpcPair pair = new NpcPair(first, second);
                    currentPairs.put(pairKey, pair);

                    if (!activeNpcPairs.containsKey(pairKey)) {
                        fire(first, "npc approach", second);
                        fire(second, "npc approach", first);
                    }
                }
            }

            for (Map.Entry<String, NpcPair> previous : new HashMap<>(activeNpcPairs).entrySet()) {
                if (currentPairs.containsKey(previous.getKey())) continue;
                NpcPair pair = previous.getValue();
                if (loaded.contains(pair.first())) fire(pair.first(), "npc leave", pair.second());
                if (loaded.contains(pair.second())) fire(pair.second(), "npc leave", pair.first());
            }

            activeNpcPairs.clear();
            activeNpcPairs.putAll(currentPairs);
        }, 10L, 10L);
        tasks.add(task);
    }

    private void fire(ManagedNPC npc, String event, ManagedNPC eventNpc) {
        ScriptData script = scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if (script == null) return;
        List<Instruction> code = script.events().get(event.toLowerCase(Locale.ROOT));
        if (code != null) execute(npc, null, eventNpc, code, 0);
    }

    private void firePlayerEvent(ManagedNPC npc, String event, Player player) {
        ScriptData script = scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if (script == null) return;
        List<Instruction> code = script.events().get(event.toLowerCase(Locale.ROOT));
        if (code != null) execute(npc, player, code, 0);
    }

    private String npcKey(ManagedNPC npc) {
        Location location = npc.location();
        return npcKey(npc.id(), location.getWorld());
    }

    private String npcKey(String id, org.bukkit.World world) {
        String worldName = world == null ? "unknown" : world.getName().toLowerCase(Locale.ROOT);
        return id.toLowerCase(Locale.ROOT) + "@" + worldName;
    }
    public void fire(String npc, String event, Player player) {
        ScriptData script = scripts.get(npc.toLowerCase(Locale.ROOT));
        if (script == null) return;
        List<Instruction> code = script.events().get(event.toLowerCase(Locale.ROOT));
        ManagedNPC managed = player == null ? npcs.get(npc) : npcs.get(npc, player.getWorld());
        if (code != null && managed != null) execute(managed, player, code, 0);
    }
    private void execute(ManagedNPC npc, Player player, List<Instruction> code, int baseIndent) {
        execute(npc, player, null, code, baseIndent);
    }

    private void execute(ManagedNPC npc, Player player, ManagedNPC eventNpc,
                         List<Instruction> code, int baseIndent) {
        ScriptContext context = new ScriptContext(npc, player, eventNpc);
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

            if (line.startsWith("cooldown ") && line.endsWith(":")) {
                int end = findEnd(code, index + 1, instruction.indent());
                long ticks = parseDurationTicks(line.substring(9, line.length() - 1));
                String cooldownKey = npcKey(context.npc()) + ":" + instruction.line();
                long now = System.currentTimeMillis();
                long availableAt = cooldowns.getOrDefault(cooldownKey, 0L);
                if (now >= availableAt && index + 1 < end) {
                    cooldowns.put(cooldownKey, now + ticks * 50L);
                    int childIndent = code.get(index + 1).indent();
                    int next = end;
                    runBlock(context, code, index + 1, childIndent,
                            () -> runBlock(context, code, next, indent, continuation));
                    return;
                }
                index = end;
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

            if (line.startsWith("set \"")) {
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
                    String message = ChatColor.translateAlternateColorCodes('&', unquote(line.substring(separator + 1)));
                    for (Player player : selectPlayers(context, target)) player.sendMessage(message);
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
                Location location = context.npc().location();
                if (location.getWorld() != null) {
                    if (name.contains(":")) {
                        location.getWorld().playSound(location, name, 1f, 1f);
                    } else {
                        try {
                            Sound sound = Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
                            location.getWorld().playSound(location, sound, 1f, 1f);
                        } catch (IllegalArgumentException exception) {
                            warnInstruction(context, instruction, "son inconnu : " + name
                                    + " (utilise un identifiant namespace:sound pour un son custom)");
                        }
                    }
                }
                index++;
                continue;
            }

            if (line.startsWith("climb to ")) {
                Location target = parseLocation(context, line.substring(9).trim());
                if (target != null) moveAdvanced(context.npc(), target,
                        net.amethystesmp.anpc.npc.AdvancedPathfinder.Mode.CLIMB);
                else warnInstruction(context, instruction, "cible d'escalade invalide");
                index++;
                continue;
            }

            if (line.startsWith("swim to ")) {
                Location target = parseLocation(context, line.substring(8).trim());
                if (target != null) moveAdvanced(context.npc(), target,
                        net.amethystesmp.anpc.npc.AdvancedPathfinder.Mode.SWIM);
                else warnInstruction(context, instruction, "cible de nage invalide");
                index++;
                continue;
            }

            if (line.startsWith("amphibious to ")) {
                Location target = parseLocation(context, line.substring(14).trim());
                if (target != null) moveAdvanced(context.npc(), target,
                        net.amethystesmp.anpc.npc.AdvancedPathfinder.Mode.AMPHIBIOUS);
                else warnInstruction(context, instruction, "cible amphibie invalide");
                index++;
                continue;
            }

            if (line.startsWith("fly to ")) {
                Location target = parseLocation(context, line.substring(7).trim());
                if (target != null) flyTo(context.npc(), target);
                else warnInstruction(context, instruction, "cible de vol invalide");
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
                if (target != null) {
                    stopMovement(context.npc());
                    context.npc().move(target);
                } else {
                    warnInstruction(context, instruction, "cible de téléportation invalide");
                }
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

            if (line.startsWith("follow ")) {
                configureFollow(context, instruction, line.substring(7).trim());
                index++;
                continue;
            }

            if (line.startsWith("keep distance ")) {
                configureKeepDistance(context, instruction, line.substring(14).trim());
                index++;
                continue;
            }

            if (line.equals("stop follow")) {
                stopFollow(context.npc());
                index++;
                continue;
            }

            if (line.equals("stop attack")) {
                warnInstruction(context, instruction, "cette action n'est pas disponible dans cette version");
                index++;
                continue;
            }

            if (line.startsWith("setblock ")) {
                simulateSetBlock(context, instruction, line.substring(9).trim());
                index++;
                continue;
            }

            if (line.startsWith("set state ")) {
                mainStates.put(context.npc().id().toLowerCase(Locale.ROOT),
                        normalizeArgument(line.substring(10)));
                index++;
                continue;
            }

            if (line.startsWith("add state ")) {
                String state = normalizeArgument(line.substring(10)).toLowerCase(Locale.ROOT);
                secondaryStates.computeIfAbsent(context.npc().id().toLowerCase(Locale.ROOT),
                        ignored -> ConcurrentHashMap.newKeySet()).add(state);
                index++;
                continue;
            }

            if (line.startsWith("remove state ")) {
                String state = normalizeArgument(line.substring(13)).toLowerCase(Locale.ROOT);
                secondaryStates.computeIfAbsent(context.npc().id().toLowerCase(Locale.ROOT),
                        ignored -> ConcurrentHashMap.newKeySet()).remove(state);
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

    private void simulateSetBlock(ScriptContext context, Instruction instruction, String arguments) {
        boolean real = false;
        String raw = arguments.trim();
        if (raw.toLowerCase(Locale.ROOT).startsWith("real ")) {
            real = true;
            raw = raw.substring(5).trim();
        } else if (raw.toLowerCase(Locale.ROOT).endsWith(" real")) {
            real = true;
            raw = raw.substring(0, raw.length() - 5).trim();
        }

        List<String> groups = extractGroups(raw);
        if (groups.size() < 2) {
            warnInstruction(context, instruction, "syntaxe : setblock (position) (bloc), ou setblock real (position) (bloc)");
            return;
        }

        String positionExpression = groups.get(0).trim();
        if (!positionExpression.startsWith("(") && positionExpression.contains(",")) {
            positionExpression = "(" + positionExpression + ")";
        }
        Location target = parseLocation(context, positionExpression);
        if (target == null || target.getWorld() == null) {
            warnInstruction(context, instruction, "position de bloc invalide");
            return;
        }

        String blockName = groups.get(1).trim().replace("minecraft:", "");
        org.bukkit.Material requested = org.bukkit.Material.matchMaterial(blockName);
        if (requested == null || !requested.isBlock()) {
            warnInstruction(context, instruction, "bloc inconnu : " + blockName);
            return;
        }

        org.bukkit.block.Block targetBlock = target.getBlock();
        org.bukkit.Material particleMaterial = requested.isAir() ? targetBlock.getType() : requested;
        if (particleMaterial.isAir()) particleMaterial = org.bukkit.Material.STONE;
        org.bukkit.block.data.BlockData particleData = Bukkit.createBlockData(particleMaterial);
        Location particleLocation = targetBlock.getLocation().add(0.5, 0.5, 0.5);

        if (real) targetBlock.setType(requested, false);

        target.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, particleLocation,
                18, 0.25, 0.25, 0.25, 0.08, particleData);
        target.getWorld().playSound(particleLocation,
                requested.isAir() ? Sound.BLOCK_STONE_BREAK : Sound.BLOCK_STONE_PLACE,
                1.0f, 1.0f);
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
    private boolean test(ScriptContext context, String expression) {
        String expressionText = expression.trim();
        String lower = expressionText.toLowerCase(Locale.ROOT);
        String npcId = context.npc().id().toLowerCase(Locale.ROOT);

        if (lower.startsWith("flag (") && lower.contains(") is ")) {
            int separator = lower.indexOf(") is ");
            String name = expressionText.substring(6, expressionText.indexOf(')'));
            String expected = lower.substring(separator + 5).trim();
            boolean enabled = Boolean.parseBoolean(variables.get("npc", context.npc().id(), "flag:" + name, "false"));
            if (expected.equals("on")) return enabled;
            if (expected.equals("off")) return !enabled;
            return false;
        }
        if (lower.startsWith("state is ")) {
            String expected = normalizeArgument(expressionText.substring(9));
            return mainStates.getOrDefault(npcId, "").equalsIgnoreCase(expected);
        }
        if (lower.startsWith("has state ")) {
            String expected = normalizeArgument(expressionText.substring(10)).toLowerCase(Locale.ROOT);
            return secondaryStates.getOrDefault(npcId, Set.of()).contains(expected);
        }

        String[] operators = {" >= ", " <= ", " != ", " == ", " > ", " < ", " contains "};
        for (String operator : operators) {
            int position = expressionText.indexOf(operator);
            if (position <= 0) continue;
            Object left = resolve(context, expressionText.substring(0, position));
            Object right = resolve(context, expressionText.substring(position + operator.length()));
            if (operator.equals(" contains ")) return String.valueOf(left).contains(String.valueOf(right));
            Double leftNumber = numericValue(left);
            Double rightNumber = numericValue(right);
            if (leftNumber != null && rightNumber != null) {
                return switch (operator.trim()) {
                    case ">" -> leftNumber > rightNumber;
                    case "<" -> leftNumber < rightNumber;
                    case ">=" -> leftNumber >= rightNumber;
                    case "<=" -> leftNumber <= rightNumber;
                    case "!=" -> Double.compare(leftNumber, rightNumber) != 0;
                    default -> Double.compare(leftNumber, rightNumber) == 0;
                };
            }
            int comparison = String.valueOf(left).compareTo(String.valueOf(right));
            return switch (operator.trim()) {
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case "!=" -> !Objects.equals(String.valueOf(left), String.valueOf(right));
                default -> Objects.equals(String.valueOf(left), String.valueOf(right));
            };
        }

        if (lower.equals("player is sneaking")) return context.player() != null && context.player().isSneaking();
        if (lower.startsWith("player has permission ")) {
            return context.player() != null && context.player().hasPermission(unquote(expressionText.substring(22).trim()));
        }
        if (lower.startsWith("player has item ")) {
            if (context.player() == null) return false;
            try {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(expressionText.substring(16).trim());
                return material != null && context.player().getInventory().contains(material);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        if (lower.equals("npc is under attack")) {
            long lastAttack = lastAttackedAt.getOrDefault(npcKey(context.npc()), 0L);
            long durationTicks = Math.max(1L, plugin.getConfig().getLong("script-under-attack-duration-ticks", 100L));
            return lastAttack > 0L && System.currentTimeMillis() - lastAttack <= durationTicks * 50L;
        }
        return false;
    }

    private String normalizeArgument(String raw) {
        String value = raw.trim();
        if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1).trim();
        return unquote(value);
    }

    private Object resolve(ScriptContext context, String raw) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) return unquote(value);
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }
        for (Map.Entry<String, Object> local : context.locals().entrySet()) {
            if (local.getKey().equalsIgnoreCase(value)) return local.getValue();
        }

        if (value.startsWith("@")) {
            List<Entity> selected = selectEntities(context, value);
            if (value.equalsIgnoreCase("@p") || value.toLowerCase(Locale.ROOT).startsWith("@n")) {
                return selected.isEmpty() ? null : selected.get(0);
            }
            return selected;
        }
        if (value.regionMatches(true, 0, "player:", 0, 7)) return Bukkit.getPlayerExact(value.substring(7));
        if (value.regionMatches(true, 0, "npc:", 0, 4)) {
            return npcs.get(value.substring(4), context.npc().location().getWorld());
        }
        if (value.equalsIgnoreCase("state of npc")) {
            return mainStates.getOrDefault(context.npc().id().toLowerCase(Locale.ROOT), "");
        }

        FunctionResult functionResult = invokeFunction(context, value);
        if (functionResult != null) return functionResult.value();

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
    private FunctionResult invokeFunction(ScriptContext parent, String expression) {
        int open = expression.indexOf('(');
        if (open <= 0 || !expression.endsWith(")")) return null;

        String name = expression.substring(0, open).trim().toLowerCase(Locale.ROOT);
        ScriptData script = scripts.get(parent.npc().id().toLowerCase(Locale.ROOT));
        if (script == null) return null;
        FunctionDefinition function = script.functions().get(name);
        if (function == null) return null;

        int depth = functionDepth.get();
        if (depth >= 32) {
            plugin.getLogger().warning("Script " + parent.npc().id() + " : profondeur maximale d'appel de fonctions atteinte pour " + name + ".");
            return new FunctionResult(true, null);
        }

        functionDepth.set(depth + 1);
        try {
            List<String> arguments = parseFunctionArguments(expression.substring(open + 1, expression.length() - 1));
            if (arguments.size() != function.parameters().size()) {
                plugin.getLogger().warning("Script " + parent.npc().id() + " : nombre d'arguments invalide pour " + name + ".");
                return new FunctionResult(true, null);
            }

            ScriptContext context = new ScriptContext(parent.npc(), parent.player(), parent.eventNpc());
            context.locals().putAll(parent.locals());
            for (int i = 0; i < arguments.size(); i++) {
                context.locals().put(function.parameters().get(i), resolve(parent, arguments.get(i)));
            }
            return runFunctionBlock(context, function.code(), 0, 0);
        } finally {
            functionDepth.set(depth);
        }
    }

    private List<String> parseFunctionArguments(String raw) {
        List<String> arguments = new ArrayList<>();
        if (raw.isBlank()) return arguments;

        boolean quoted = false;
        int parentheses = 0;
        int brackets = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (character == '(') parentheses++;
            else if (character == ')') parentheses--;
            else if (character == '[') brackets++;
            else if (character == ']') brackets--;
            else if (character == ',' && parentheses == 0 && brackets == 0) {
                arguments.add(raw.substring(start, i).trim());
                start = i + 1;
            }
        }
        arguments.add(raw.substring(start).trim());
        return arguments;
    }

    private FunctionResult runFunctionBlock(ScriptContext context, List<Instruction> code, int start, int indent) {
        int index = start;
        while (index < code.size()) {
            Instruction instruction = code.get(index);
            if (instruction.indent() < indent) return new FunctionResult(false, null);
            if (instruction.indent() > indent) {
                index = findEnd(code, index + 1, instruction.indent());
                continue;
            }

            String line = instruction.text();
            if (line.equalsIgnoreCase("return") || line.regionMatches(true, 0, "return ", 0, 7)) {
                String expression = line.length() > 6 ? line.substring(6).trim() : "";
                return new FunctionResult(true, expression.isBlank() ? null : resolve(context, expression));
            }

            if (line.startsWith("if ") && line.endsWith(":")) {
                int end = findEnd(code, index + 1, instruction.indent());
                if (test(context, line.substring(3, line.length() - 1)) && index + 1 < end) {
                    int childIndent = code.get(index + 1).indent();
                    FunctionResult result = runFunctionBlock(context, code, index + 1, childIndent);
                    if (result.returned()) return result;
                }
                index = end;
                continue;
            }

            if (line.startsWith("set \"")) {
                String body = line.substring(4).trim();
                int closingQuote = body.indexOf('"', 1);
                if (body.startsWith("\"") && closingQuote > 0) {
                    String name = body.substring(1, closingQuote).toLowerCase(Locale.ROOT);
                    String value = body.substring(closingQuote + 1).trim();
                    if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1);
                    context.locals().put(name, resolve(context, value));
                }
                index++;
                continue;
            }

            if (line.startsWith("say local ") || line.startsWith("say global ")) {
                boolean global = line.startsWith("say global ");
                String message = ChatColor.translateAlternateColorCodes('&',
                        context.npc().definition().displayName() + " » " + unquote(line.substring(global ? 11 : 10)));
                if (global) Bukkit.broadcastMessage(message);
                else if (context.player() != null) context.player().sendMessage(message);
                index++;
                continue;
            }

            if (line.startsWith("play anime ")) {
                context.npc().animate(unquote(line.substring(11)));
                index++;
                continue;
            }

            if (line.startsWith("wait ")) {
                warnInstruction(context, instruction, "wait n'est pas autorisé dans une fonction qui retourne une valeur");
                index++;
                continue;
            }

            warnInstruction(context, instruction, "instruction non prise en charge dans une fonction");
            index++;
        }
        return new FunctionResult(false, null);
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
            if (character == '"' && (i == 0 || declaration.charAt(i - 1) != '\\')) quoted = !quoted;
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
    private Double numericValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    private String unquote(String s){s=s.trim();return s.length()>=2&&s.startsWith("\"")&&s.endsWith("\"")?s.substring(1,s.length()-1):s;}
    private double selectorRangeSquared(Location origin) {
        double range = Math.max(1.0, plugin.getConfig().getDouble("selector-range", 64.0));
        return range * range;
    }

    private boolean isWithinSelectorRange(Location origin, Entity entity) {
        return origin.getWorld() != null
                && origin.getWorld().equals(entity.getWorld())
                && entity.getLocation().distanceSquared(origin) <= selectorRangeSquared(origin);
    }

    private Player nearest(Location location) {
        if (location.getWorld() == null) return null;
        double rangeSquared = selectorRangeSquared(location);
        return location.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(location) <= rangeSquared)
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
                .orElse(null);
    }

    private List<Player> selectPlayers(ScriptContext context, String selector) {
        String target = selector.trim();
        Location origin = context.npc().location();
        if (target.equalsIgnoreCase("event entity") && context.player() != null) return List.of(context.player());
        if (target.equalsIgnoreCase("@p")) {
            Player nearest = nearest(origin);
            return nearest == null ? List.of() : List.of(nearest);
        }
        if (target.equalsIgnoreCase("@a")) {
            return origin.getWorld() == null ? List.of() : origin.getWorld().getPlayers().stream()
                    .filter(player -> isWithinSelectorRange(origin, player))
                    .toList();
        }
        if (target.regionMatches(true, 0, "player:", 0, 7)) {
            Player player = Bukkit.getPlayerExact(target.substring(7));
            return player == null ? List.of() : List.of(player);
        }

        Object value = resolve(context, target);
        if (value instanceof Player player) return List.of(player);
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(Player.class::isInstance).map(Player.class::cast).toList();
        }
        Player player = Bukkit.getPlayerExact(target);
        return player == null ? List.of() : List.of(player);
    }

    private List<Entity> selectEntities(ScriptContext context, String selector) {
        String target = selector.trim();
        Location origin = context.npc().location();
        if (origin.getWorld() == null) return List.of();

        if (target.equalsIgnoreCase("@p")) {
            Player player = nearest(origin);
            return player == null ? List.of() : List.of(player);
        }
        if (target.equalsIgnoreCase("@a")) {
            return origin.getWorld().getPlayers().stream()
                    .filter(player -> isWithinSelectorRange(origin, player))
                    .sorted(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin)))
                    .map(player -> (Entity) player)
                    .toList();
        }
        if (target.equalsIgnoreCase("event entity") && context.player() != null) return List.of(context.player());
        if (target.regionMatches(true, 0, "player:", 0, 7)) {
            Player player = Bukkit.getPlayerExact(target.substring(7));
            return player == null ? List.of() : List.of(player);
        }

        String selectorName = target;
        String options = "";
        int open = target.indexOf('[');
        if (open >= 0 && target.endsWith("]")) {
            selectorName = target.substring(0, open);
            options = target.substring(open + 1, target.length() - 1);
        }
        if (!selectorName.equalsIgnoreCase("@e") && !selectorName.equalsIgnoreCase("@n")) return List.of();

        String requiredType = null;
        int limit = Integer.MAX_VALUE;
        for (String option : options.split(",")) {
            String[] pair = option.trim().split("=", 2);
            if (pair.length != 2) continue;
            if (pair[0].equalsIgnoreCase("type")) requiredType = pair[1].trim().replace("minecraft:", "");
            if (pair[0].equalsIgnoreCase("limit")) {
                try { limit = Math.max(1, Integer.parseInt(pair[1].trim())); }
                catch (NumberFormatException ignored) { }
            }
        }

        final org.bukkit.entity.EntityType entityType;
        if (requiredType == null || requiredType.isBlank()) {
            entityType = null;
        } else {
            try {
                entityType = org.bukkit.entity.EntityType.valueOf(requiredType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return List.of();
            }
        }

        double range = Math.max(1, plugin.getConfig().getDouble("selector-range", 64));
        List<Entity> matches = origin.getWorld().getNearbyEntities(origin, range, range, range).stream()
                .filter(Entity::isValid)
                .filter(entity -> isWithinSelectorRange(origin, entity))
                .filter(entity -> entity.getScoreboardTags().stream().noneMatch(tag -> tag.startsWith("anpc:")))
                .filter(entity -> entityType == null || entity.getType() == entityType)
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(origin)))
                .limit(limit)
                .toList();

        if (selectorName.equalsIgnoreCase("@n")) return matches.isEmpty() ? List.of() : List.of(matches.get(0));
        return matches;
    }

    private Location parseLocation(ScriptContext context, String selector) {
        String target = selector.trim();
        if (target.equalsIgnoreCase("location of npc")) return context.npc().location();

        if (target.equalsIgnoreCase("event npc") && context.eventNpc() != null) {
            return context.eventNpc().location();
        }
        if (target.regionMatches(true, 0, "npc:", 0, 4)) {
            ManagedNPC npc = npcs.get(target.substring(4), context.npc().location().getWorld());
            return npc == null ? null : npc.location();
        }
        if (target.startsWith("@")) {
            return selectEntities(context, target).stream().findFirst().map(Entity::getLocation).orElse(null);
        }
        if (target.regionMatches(true, 0, "player:", 0, 7)) {
            Player player = Bukkit.getPlayerExact(target.substring(7));
            return player == null ? null : player.getLocation();
        }
        if (target.equalsIgnoreCase("event entity") && context.player() != null) return context.player().getLocation();

        Object resolved = resolve(context, target);
        if (resolved instanceof Location location) return location.clone();
        if (resolved instanceof Player player) return player.getLocation();
        if (resolved instanceof Entity entity) return entity.getLocation();
        if (resolved instanceof ManagedNPC npc) return npc.location();

        if (target.startsWith("(") && target.endsWith(")")) {
            String[] coordinates = target.substring(1, target.length() - 1).split(",");
            if (coordinates.length == 3) {
                try {
                    return new Location(context.npc().location().getWorld(),
                            Double.parseDouble(coordinates[0].trim()),
                            Double.parseDouble(coordinates[1].trim()),
                            Double.parseDouble(coordinates[2].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }
    private String movementKey(ManagedNPC npc) {
        Location location = npc.location();
        String world = location.getWorld() == null ? "unknown" : location.getWorld().getName().toLowerCase(Locale.ROOT);
        return npc.id().toLowerCase(Locale.ROOT) + "@" + world;
    }

    private void stopMovement(ManagedNPC npc) {
        String key = movementKey(npc);
        BukkitTask task = movementTasks.remove(key);
        if (task != null) {
            task.cancel();
            tasks.remove(task);
        }
    }

    private void stopFollow(ManagedNPC npc) {
        String key = movementKey(npc);
        BukkitTask follow = followTasks.remove(key);
        if (follow != null) {
            follow.cancel();
            tasks.remove(follow);
        }
        stopMovement(npc);
    }

    private void configureFollow(ScriptContext context, Instruction instruction, String arguments) {
        String lower = arguments.toLowerCase(Locale.ROOT);
        String selector = arguments;
        double minimum = 1.5;
        double maximum = 1.5;

        int at = lower.lastIndexOf(" at ");
        int min = lower.lastIndexOf(" min ");
        int max = lower.lastIndexOf(" max ");
        try {
            if (at > 0) {
                selector = arguments.substring(0, at).trim();
                minimum = maximum = Double.parseDouble(arguments.substring(at + 4).trim());
            } else if (min > 0 && max > min) {
                selector = arguments.substring(0, min).trim();
                minimum = Double.parseDouble(arguments.substring(min + 5, max).trim());
                maximum = Double.parseDouble(arguments.substring(max + 5).trim());
            }
            minimum = Math.max(0.5, Math.min(32.0, minimum));
            maximum = Math.max(minimum, Math.min(32.0, maximum));
            startFollow(context.npc(), selector, minimum, maximum, false, context.player());
        } catch (NumberFormatException exception) {
            warnInstruction(context, instruction, "syntaxe : follow <cible> at <distance> ou follow <cible> min <min> max <max>");
        }
    }

    private void configureKeepDistance(ScriptContext context, Instruction instruction, String arguments) {
        String lower = arguments.toLowerCase(Locale.ROOT);
        int from = lower.lastIndexOf(" from ");
        try {
            String selector;
            double distance;
            if (from > 0) {
                distance = Double.parseDouble(arguments.substring(0, from).trim());
                selector = arguments.substring(from + 6).trim();
            } else {
                int separator = arguments.lastIndexOf(' ');
                if (separator <= 0) throw new NumberFormatException();
                selector = arguments.substring(0, separator).trim();
                distance = Double.parseDouble(arguments.substring(separator + 1).trim());
            }
            distance = Math.max(0.5, Math.min(32.0, distance));
            startFollow(context.npc(), selector, distance, distance, true, context.player());
        } catch (NumberFormatException exception) {
            warnInstruction(context, instruction, "syntaxe : keep distance <distance> from <cible>");
        }
    }

    private void startFollow(ManagedNPC npc, String selector, double minimumDistance,
                             double maximumDistance, boolean keepDistance, Player eventPlayer) {
        stopFollow(npc);
        if (selector.isBlank()) return;

        String key = movementKey(npc);
        UUID fixedPlayer = selector.equalsIgnoreCase("event entity") && eventPlayer != null
                ? eventPlayer.getUniqueId() : null;
        ScriptContext selectorContext = new ScriptContext(npc, null);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Entity target = fixedPlayer == null
                    ? selectEntities(selectorContext, selector).stream().findFirst().orElse(null)
                    : Bukkit.getPlayer(fixedPlayer);

            if (target == null || !target.isValid()) {
                stopFollow(npc);
                return;
            }

            Location npcLocation = npc.location();
            Location targetLocation = target.getLocation();
            if (npcLocation.getWorld() == null || !npcLocation.getWorld().equals(targetLocation.getWorld())) {
                stopMovement(npc);
                return;
            }

            double distance = npcLocation.distance(targetLocation);
            boolean tooFar = !keepDistance && distance > maximumDistance + 0.35;
            boolean tooClose = distance < minimumDistance - 0.35;
            if (!tooFar && !tooClose) {
                stopMovement(npc);
                return;
            }

            if (!movementTasks.containsKey(key)) {
                double desiredDistance = tooFar ? maximumDistance : minimumDistance;
                org.bukkit.util.Vector offset = npcLocation.toVector().subtract(targetLocation.toVector());
                if (offset.lengthSquared() < 0.000001) offset = new org.bukkit.util.Vector(1, 0, 0);
                offset.normalize().multiply(desiredDistance);
                Location destination = targetLocation.clone().add(offset);
                destination.setY(targetLocation.getY());
                move(npc, destination);
            }
        }, 10L, 10L);

        followTasks.put(key, task);
        tasks.add(task);
    }

    private void move(ManagedNPC npc, Location target) {
        if (target == null || target.getWorld() == null) return;
        Location start = npc.location();
        if (start.getWorld() == null || !start.getWorld().equals(target.getWorld())) {
            plugin.getLogger().warning("Déplacement impossible pour " + npc.id() + " : la cible est dans un autre monde.");
            return;
        }

        List<Location> checkpoints;
        if (plugin.getConfig().getBoolean("movement.pathfinding.enabled", true)) {
            checkpoints = net.amethystesmp.anpc.npc.Pathfinder.findPath(
                    start,
                    target,
                    plugin.getConfig().getInt("movement.pathfinding.max-nodes", 5000),
                    plugin.getConfig().getDouble("movement.pathfinding.max-distance", 128.0),
                    plugin.getConfig().getDouble("movement.checkpoint-spacing", 2.0));
        } else {
            checkpoints = List.of(target.clone());
        }

        if (checkpoints.isEmpty()) {
            plugin.getLogger().warning("Aucun chemin praticable trouvé pour le NPC " + npc.id() + ".");
            return;
        }
        moveAlongCheckpoints(npc, target, checkpoints);
    }

    private void moveAdvanced(ManagedNPC npc, Location target,
                              net.amethystesmp.anpc.npc.AdvancedPathfinder.Mode mode) {
        if (target == null || target.getWorld() == null) return;
        Location start = npc.location();
        if (start.getWorld() == null || !start.getWorld().equals(target.getWorld())) {
            plugin.getLogger().warning("Déplacement spécial impossible pour " + npc.id()
                    + " : la cible est dans un autre monde.");
            return;
        }

        String section = switch (mode) {
            case CLIMB -> "climbing";
            case SWIM -> "swimming";
            case AMPHIBIOUS -> "amphibious";
        };
        List<Location> checkpoints = net.amethystesmp.anpc.npc.AdvancedPathfinder.findPath(
                start,
                target,
                mode,
                plugin.getConfig().getInt("movement." + section + ".max-nodes", 5000),
                plugin.getConfig().getDouble("movement." + section + ".max-distance", 96.0),
                plugin.getConfig().getDouble("movement.checkpoint-spacing", 2.0));
        if (checkpoints.isEmpty()) {
            plugin.getLogger().warning("Aucun chemin " + section + " praticable trouvé pour le NPC "
                    + npc.id() + ". Vérifie la présence d'un mur continu ou d'eau selon le mode.");
            return;
        }
        moveAlongCheckpoints(npc, target, checkpoints);
    }

    private void flyTo(ManagedNPC npc, Location target) {
        if (target == null || target.getWorld() == null) return;
        Location start = npc.location();
        if (start.getWorld() == null || !start.getWorld().equals(target.getWorld())) {
            plugin.getLogger().warning("Vol impossible pour " + npc.id() + " : la cible est dans un autre monde.");
            return;
        }

        List<Location> checkpoints = net.amethystesmp.anpc.npc.FlyingPathfinder.findPath(
                start,
                target,
                plugin.getConfig().getInt("movement.flying.max-nodes", 5000),
                plugin.getConfig().getDouble("movement.flying.max-distance", 128.0),
                plugin.getConfig().getDouble("movement.checkpoint-spacing", 2.0));
        if (checkpoints.isEmpty()) {
            plugin.getLogger().warning("Aucun chemin aérien praticable trouvé pour le NPC " + npc.id() + ".");
            return;
        }
        moveAlongCheckpoints(npc, target, checkpoints);
    }

    private void moveAlongCheckpoints(ManagedNPC npc, Location target, List<Location> checkpoints) {
        if (checkpoints.isEmpty()) return;

        String movementKey = movementKey(npc);
        BukkitTask previousTask = movementTasks.remove(movementKey);
        if (previousTask != null) {
            previousTask.cancel();
            tasks.remove(previousTask);
        }

        Location start = npc.location();
        long updateTicks = Math.max(1L, plugin.getConfig().getLong("movement.update-ticks", 1L));
        double speed = Math.max(0.1, plugin.getConfig().getDouble("movement.default-speed", 4.0));
        double distancePerUpdate = speed * updateTicks / 20.0;
        int[] checkpointIndex = {0};
        Location[] current = {start.clone()};
        BukkitTask[] holder = new BukkitTask[1];

        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double remaining = distancePerUpdate;
            while (remaining > 0.000001 && checkpointIndex[0] < checkpoints.size()) {
                Location checkpoint = checkpoints.get(checkpointIndex[0]);
                double distance = current[0].distance(checkpoint);
                if (distance <= 0.000001) {
                    current[0] = checkpoint.clone();
                    checkpointIndex[0]++;
                } else if (distance <= remaining) {
                    current[0] = checkpoint.clone();
                    remaining -= distance;
                    checkpointIndex[0]++;
                } else {
                    org.bukkit.util.Vector direction = checkpoint.toVector().subtract(current[0].toVector()).normalize();
                    current[0].add(direction.multiply(remaining));
                    remaining = 0;
                }
            }

            if (checkpointIndex[0] < checkpoints.size()) {
                org.bukkit.util.Vector direction = checkpoints.get(checkpointIndex[0]).toVector()
                        .subtract(current[0].toVector());
                if (direction.lengthSquared() > 0.000001) current[0].setDirection(direction);
            } else {
                current[0].setYaw(target.getYaw());
                current[0].setPitch(target.getPitch());
            }

            npc.move(current[0].clone());
            if (checkpointIndex[0] >= checkpoints.size()) {
                holder[0].cancel();
                tasks.remove(holder[0]);
                movementTasks.remove(movementKey, holder[0]);
                npc.animate("idle");
            }
        }, 1L, updateTicks);

        tasks.add(holder[0]);
        movementTasks.put(movementKey, holder[0]);
        npc.animate("walk");
    }

    private long parseDurationTicks(String input) {
        String[] parts = input.trim().toLowerCase(Locale.ROOT).split("\\s+");
        try {
            long amount = Math.max(1L, Long.parseLong(parts[0]));
            long multiplier = 1L;
            if (parts.length >= 2) {
                if (parts[1].startsWith("minute")) multiplier = 1200L;
                else if (parts[1].startsWith("second")) multiplier = 20L;
            }
            long maximum = 2_000_000_000L;
            if (amount > maximum / multiplier) return maximum;
            return Math.min(maximum, amount * multiplier);
        } catch (Exception exception) {
            return 1L;
        }
    }
    @EventHandler public void onNpcRightClick(PlayerInteractAtEntityEvent e){
        String id=e.getRightClicked().getScoreboardTags().stream().filter(t->t.startsWith("anpc:")).map(t->t.substring(5)).findFirst().orElse(null);
        if(id!=null){e.setCancelled(true);fire(id,e.getPlayer().isSneaking()?"right click while sneaking":"right click",e.getPlayer());if(!e.getPlayer().getInventory().getItemInMainHand().getType().isAir())fire(id,"item click",e.getPlayer());}
    }
    @EventHandler
    public void onNpcDamaged(EntityDamageEvent event) {
        Entity target = event.getEntity();
        String id = target.getScoreboardTags().stream()
                .filter(tag -> tag.startsWith("anpc:"))
                .map(tag -> tag.substring(5))
                .findFirst()
                .orElse(null);
        if (id == null) return;

        // The condition stays true for a configurable window after the latest hit.
        lastAttackedAt.put(npcKey(id, target.getWorld()), System.currentTimeMillis());
        ManagedNPC managed = npcs.get(id, target.getWorld());

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            if (managed != null) firePlayerEvent(managed, "damage", null);
            return;
        }

        Player attacker = null;
        if (byEntity.getDamager() instanceof Player player) {
            attacker = player;
        } else if (byEntity.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) attacker = player;
        }

        if (attacker == null) {
            if (managed != null) firePlayerEvent(managed, "damage", null);
            return;
        }
        // NPC hitboxes and backing entities should not take ordinary player damage.
        event.setCancelled(true);
        if (managed != null) {
            firePlayerEvent(managed, attacker.isSneaking() ? "left click while sneaking" : "left click", attacker);
            firePlayerEvent(managed, "damage", attacker);
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent e){for(ManagedNPC npc:npcs.all())if(e.getPlayer().getWorld().equals(npc.location().getWorld())&&e.getPlayer().getLocation().distanceSquared(npc.location())<64*64)npc.show(e.getPlayer());}
    @EventHandler public void onQuit(PlayerQuitEvent e){for(ManagedNPC npc:npcs.all())npc.hide(e.getPlayer());}
    private void cancelTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        movementTasks.clear();
        intervalTasks.clear();
        followTasks.clear();
        activeNpcPairs.clear();
        lastAttackedAt.clear();
    }

    public void shutdown() {
        cancelTasks();
        scripts.clear();
        nearbyPlayers.clear();
    }
}
