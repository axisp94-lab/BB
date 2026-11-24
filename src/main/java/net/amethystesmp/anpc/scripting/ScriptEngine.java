package net.amethystesmp.anpc.scripting;

import net.amethystesmp.anpc.ANPCPlugin;
import net.amethystesmp.anpc.npc.ManagedNPC;
import net.amethystesmp.anpc.npc.NPCManager;
import net.amethystesmp.anpc.storage.VariableStore;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
            "left click", "left click while sneaking", "item click", "offhand item click", "damage");
    private record LoopPlan(String variable, List<Object> values) {}
    private record FunctionDefinition(List<String> parameters, List<Instruction> code) {}
    private record ScriptData(Map<String, List<Instruction>> events, Map<String, FunctionDefinition> functions) {}
    private record FunctionResult(boolean returned, Object value) {}
    private record Comparison(String left, String operator, String right, boolean valid) {}
    private record NpcPair(ManagedNPC first, ManagedNPC second) {}
    private static final int MAX_LOOP_ITERATIONS = 256;
    private static final int MAX_SELECTOR_RESULTS = 256;
    private static final java.util.regex.Pattern MESSAGE_VARIABLE_PATTERN =
            java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])([A-Za-z_][A-Za-z0-9_-]*)(?![\\p{L}\\p{N}_])");
    private final ANPCPlugin plugin; private final NPCManager npcs; private final VariableStore variables;
    private final Map<String, ScriptData> scripts = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> functionDepth = ThreadLocal.withInitial(() -> 0);
    private final Set<BukkitTask> tasks = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> movementTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> intervalTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> followTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> followPathAttemptAt = new ConcurrentHashMap<>();
    private final Map<String, Set<BukkitTask>> delayedTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> nearbyPlayers = new ConcurrentHashMap<>();
    private final Map<String, NpcPair> activeNpcPairs = new HashMap<>();
    private final Map<String, String> mainStates = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> secondaryStates = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAttackedAt = new ConcurrentHashMap<>();
    private final Set<String> warnedInstructions = ConcurrentHashMap.newKeySet();
    private boolean hasLoadedScripts;
    public ScriptEngine(ANPCPlugin plugin,NPCManager npcs,VariableStore variables){this.plugin=plugin;this.npcs=npcs;this.variables=variables;}
    public void loadAll() {
        File directory = new File(plugin.getDataFolder(), "scripts");
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".aa"));
        if (files == null) {
            plugin.getLogger().severe("Chargement des scripts annulé : dossier scripts inaccessible. "
                    + "Les scripts actuellement actifs sont conservés.");
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(File::getName));

        Map<String, ScriptData> loadedScripts = new LinkedHashMap<>();
        boolean readFailure = false;
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 3).toLowerCase(Locale.ROOT);
            try {
                ScriptData parsed = parse(Files.readAllLines(file.toPath()), file.getName());
                if (loadedScripts.putIfAbsent(id, parsed) != null) {
                    plugin.getLogger().warning("Script " + file.getName()
                            + " ignoré : un autre fichier .aa utilise déjà l'identifiant '" + id + "'.");
                }
            } catch (Exception exception) {
                readFailure = true;
                plugin.getLogger().warning("Erreur script " + file.getName() + " : " + exception.getMessage());
            }
        }

        if (readFailure) {
            plugin.getLogger().severe("Rechargement des scripts annulé : au moins un fichier n'a pas pu être lu. "
                    + "Les scripts actuellement actifs sont conservés.");
            return;
        }

        // Replace the live script set only after every file has been read.
        cancelTasks();
        scripts.clear();
        scripts.putAll(loadedScripts);
        nearbyPlayers.clear();
        lastAttackedAt.clear();

        if (!hasLoadedScripts) {
            hasLoadedScripts = true;
            for (ManagedNPC npc : npcs.all()) onNpcLoaded(npc);
        } else {
            scheduleIntervals();
        }
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
                    throw new IllegalArgumentException(source + ":" + (i + 1)
                            + " : événement inconnu '" + currentEvent + "'.");
                }
                bodyIndent = -1;
                continue;
            }

            if (indent == 0 && lower.startsWith("def ") && text.endsWith(":")) {
                currentEvent = null;
                String signature = text.substring(4, text.length() - 1).trim();
                int open = signature.indexOf('(');
                int close = signature.lastIndexOf(')');
                if (open <= 0 || close != signature.length() - 1 || close < open) {
                    throw new IllegalArgumentException(source + ":" + (i + 1) + " : définition de fonction invalide.");
                }
                String name = signature.substring(0, open).trim().toLowerCase(Locale.ROOT);
                String rawParameters = signature.substring(open + 1, close).trim();
                List<String> parameters = rawParameters.isEmpty()
                        ? List.of()
                        : Arrays.stream(rawParameters.split(",", -1))
                                .map(parameter -> parameter.trim().toLowerCase(Locale.ROOT))
                                .toList();
                Set<String> uniqueParameters = new HashSet<>(parameters);
                if (!name.matches("[a-z_][a-z0-9_]*")
                        || parameters.stream().anyMatch(parameter -> !parameter.matches("[a-z_][a-z0-9_]*"))
                        || uniqueParameters.size() != parameters.size()) {
                    throw new IllegalArgumentException(source + ":" + (i + 1)
                            + " : nom de fonction ou paramètres invalides (noms simples et uniques requis).");
                }
                if (functions.containsKey(name)) {
                    throw new IllegalArgumentException(source + ":" + (i + 1)
                            + " : fonction '" + name + "' déjà définie.");
                }
                currentBody = new ArrayList<>();
                functions.put(name, new FunctionDefinition(parameters, currentBody));
                bodyIndent = -1;
                continue;
            }

            if (currentBody == null) {
                throw new IllegalArgumentException(source + ":" + (i + 1)
                        + " : instruction hors d'un événement ou d'une fonction.");
            }

            if (bodyIndent < 0) {
                bodyIndent = indent;
                if (bodyIndent == 0) {
                    throw new IllegalArgumentException(source + ":" + (i + 1) + " : le contenu doit être indenté.");
                }
            }

            if (indent < bodyIndent) {
                throw new IllegalArgumentException(source + ":" + (i + 1) + " : indentation inattendue.");
            }

            int relativeIndent = indent - bodyIndent;
            if (relativeIndent % 4 != 0) {
                throw new IllegalArgumentException(source + ":" + (i + 1)
                        + " : indentation non multiple de quatre espaces.");
            }
            currentBody.add(new Instruction(relativeIndent, text, i + 1));
        }
        return new ScriptData(events, functions);
    }
    private void scheduleIntervals() {
        for (ManagedNPC npc : npcs.all()) scheduleInterval(npc);
    }

    private void scheduleInterval(ManagedNPC npc) {
        if (npc == null) return;
        String key = npcKey(npc);
        ScriptData script = scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if (script == null) return;
        List<Instruction> interval = script.events().get("interval");
        if (interval != null && !intervalTasks.containsKey(key)) {
            long period = Math.max(1L, Math.min(72_000L, plugin.getConfig().getLong("script-default-interval", 20L)));
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> execute(npc, null, interval, 0), period, period);
            intervalTasks.put(key, task);
            tasks.add(task);
        }
    }

    public void onNpcLoaded(ManagedNPC npc) {
        if (npc == null) return;
        scheduleInterval(npc);
        ScriptData script = scripts.get(npc.id().toLowerCase(Locale.ROOT));
        if (script != null) {
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
        Set<BukkitTask> delayed = delayedTasks.remove(key);
        if (delayed != null) {
            delayed.forEach(task -> {
                task.cancel();
                tasks.remove(task);
            });
        }
        nearbyPlayers.remove(key);
        lastAttackedAt.remove(key);
        String cooldownPrefix = key + ":";
        cooldowns.keySet().removeIf(cooldownKey -> cooldownKey.startsWith(cooldownPrefix));
        activeNpcPairs.entrySet().removeIf(entry ->
                entry.getValue().first() == npc || entry.getValue().second() == npc);
    }
    private void scheduleProximity() {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double radius = configDouble("script-approach-radius", 3.0, 0.0, 128.0);
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
                        if (previous.add(player.getUniqueId())) firePlayerEvent(npc, "entity approach", player);
                    }
                }

                for (UUID uuid : new HashSet<>(previous)) {
                    if (!current.contains(uuid)) {
                        previous.remove(uuid);
                        Player player = Bukkit.getPlayer(uuid);
                        firePlayerEvent(npc, "entity leave", player);
                    }
                }
            }
        }, 10L, 10L);
        tasks.add(task);
    }

    private void scheduleNpcProximity() {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double radius = configDouble("script-npc-approach-radius", 3.0, 0.0, 128.0);
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
        try {
            runBlock(context, code, 0, baseIndent, () -> { });
        } catch (RuntimeException exception) {
            logScriptException(context, exception);
        }
    }

    private void logScriptException(ScriptContext context, RuntimeException exception) {
        plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Erreur d'exécution du script du NPC '" + context.npc().id() + "' : "
                        + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()),
                exception);
    }
    private void assignVariable(ScriptContext context, Instruction instruction) {
        String body = instruction.text().substring(4).trim();
        if (!body.startsWith("\"")) {
            warnInstruction(context, instruction, "syntaxe set invalide");
            return;
        }
        int closingQuote = -1;
        for (int i = 1; i < body.length(); i++) {
            if (body.charAt(i) != '"') continue;
            int backslashes = 0;
            for (int previous = i - 1; previous >= 0 && body.charAt(previous) == '\\'; previous--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                closingQuote = i;
                break;
            }
        }
        if (closingQuote < 0) {
            warnInstruction(context, instruction, "nom de variable non terminé");
            return;
        }
        String name = unquote(body.substring(0, closingQuote + 1)).trim().toLowerCase(Locale.ROOT);
        String valueText = body.substring(closingQuote + 1).trim();
        if (name.isBlank() || valueText.isBlank()) {
            warnInstruction(context, instruction, "nom ou valeur de variable vide");
            return;
        }
        if (valueText.startsWith("(") && valueText.endsWith(")")) {
            valueText = valueText.substring(1, valueText.length() - 1).trim();
        }
        Object value = resolve(context, valueText);
        context.locals().put(name, value);
        storeVariable(context, name, value);
    }

    private String renderMessage(ScriptContext context, String raw) {
        String source = raw.trim();
        boolean quoted = isQuotedString(source);
        String template = quoted ? unquote(source) : source;
        if (!quoted) {
            Object direct = resolve(context, template);
            if (direct != null && !Objects.equals(String.valueOf(direct), template)) {
                return formatMessageValue(direct);
            }
        }

        java.util.regex.Matcher matcher = MESSAGE_VARIABLE_PATTERN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            Object value = resolve(context, token);
            String replacement = value == null ? token : formatMessageValue(value);
            matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String formatMessageValue(Object value) {
        if (value instanceof Player player) return player.getName();
        if (value instanceof ManagedNPC npc) return npc.id();
        if (value instanceof Entity entity) return entity.getType().name().toLowerCase(Locale.ROOT);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric == Math.rint(numeric)
                    && numeric >= Long.MIN_VALUE && numeric <= Long.MAX_VALUE) {
                return Long.toString((long) numeric);
            }
        }
        return String.valueOf(value);
    }

    private void sendScriptMessage(ScriptContext context, boolean global, String message) {
        String rendered = ChatColor.translateAlternateColorCodes('&',
                context.npc().definition().displayName() + " » " + renderMessage(context, message));
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
    }

    private void runBlock(ScriptContext context, List<Instruction> code, int start, int indent, Runnable continuation) {
        int index = start;
        while (index < code.size()) {
            if (context.loopControlRequested()) {
                continuation.run();
                return;
            }
            Instruction instruction = code.get(index);
            if (!context.consumeInstruction()) {
                if (context.markExecutionBudgetWarning()) {
                    plugin.getLogger().warning("Script " + context.npc().id()
                            + " : budget maximal de 10 000 instructions atteint ; exécution interrompue.");
                }
                return;
            }
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
                    if (state == null) {
                        warnInstruction(context, instruction, "profondeur maximale de boucles imbriquées atteinte (16)");
                        index = end;
                        continue;
                    }
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
                if (ticks < 1L) {
                    warnInstruction(context, instruction, "durée de cooldown invalide ; utilise des ticks, seconds ou minutes");
                    index = end;
                    continue;
                }
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
                boolean firstMatches = test(context, line.substring(3, line.length() - 1));
                int chainCursor = end;
                int selectedStart = -1;
                int selectedEnd = -1;

                while (chainCursor < code.size()
                        && code.get(chainCursor).indent() == instruction.indent()) {
                    String branch = code.get(chainCursor).text();
                    String branchLower = branch.toLowerCase(Locale.ROOT);
                    if (branchLower.equals("else:")) {
                        int branchEnd = findEnd(code, chainCursor + 1, instruction.indent());
                        if (selectedStart < 0) {
                            selectedStart = chainCursor + 1;
                            selectedEnd = branchEnd;
                        }
                        chainCursor = branchEnd;
                        break;
                    }
                    if (!branchLower.startsWith("else if ") || !branch.endsWith(":")) break;

                    int branchEnd = findEnd(code, chainCursor + 1, instruction.indent());
                    if (!firstMatches && selectedStart < 0
                            && test(context, branch.substring(8, branch.length() - 1))) {
                        selectedStart = chainCursor + 1;
                        selectedEnd = branchEnd;
                    }
                    chainCursor = branchEnd;
                }

                int afterChain = chainCursor;
                if (firstMatches) {
                    if (index + 1 < end) {
                        int childIndent = code.get(index + 1).indent();
                        runBlock(context, code, index + 1, childIndent,
                                () -> runBlock(context, code, afterChain, indent, continuation));
                        return;
                    }
                } else if (selectedStart >= 0 && selectedStart < selectedEnd) {
                    int childIndent = code.get(selectedStart).indent();
                    runBlock(context, code, selectedStart, childIndent,
                            () -> runBlock(context, code, afterChain, indent, continuation));
                    return;
                }

                index = afterChain;
                continue;
            }

            if (line.startsWith("set \"")) {
                assignVariable(context, instruction);
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
                sendScriptMessage(context, global, line.substring(global ? 11 : 10));
                index++;
                continue;
            }

            if (line.startsWith("send to ")) {
                int separator = line.indexOf(' ', 8);
                if (separator > 0) {
                    String target = line.substring(8, separator);
                    String message = ChatColor.translateAlternateColorCodes('&', renderMessage(context, line.substring(separator + 1)));
                    for (Player player : selectPlayers(context, target)) player.sendMessage(message);
                }
                index++;
                continue;
            }

            if (line.startsWith("play anime ")) {
                context.npc().animate(renderMessage(context, line.substring(11)));
                index++;
                continue;
            }

            if (line.startsWith("play sound ")) {
                String name = renderMessage(context, line.substring(11));
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
                    Location current = context.npc().location();
                    if (current.getWorld() == null || !current.getWorld().equals(target.getWorld())) {
                        warnInstruction(context, instruction,
                                "téléportation entre mondes non prise en charge ; chaque monde possède sa propre instance du NPC");
                    } else {
                        stopFollow(context.npc());
                        context.npc().move(target);
                    }
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
                if (ticks < 1L) {
                    warnInstruction(context, instruction, "durée de wait invalide ; utilise des ticks, seconds ou minutes");
                    index++;
                    continue;
                }
                int next = index + 1;
                String npcTaskKey = npcKey(context.npc());
                BukkitTask[] holder = new BukkitTask[1];
                holder[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    tasks.remove(holder[0]);
                    Set<BukkitTask> pending = delayedTasks.get(npcTaskKey);
                    if (pending != null) {
                        pending.remove(holder[0]);
                        if (pending.isEmpty()) delayedTasks.remove(npcTaskKey, pending);
                    }
                    Location npcLocation = context.npc().location();
                    if (npcLocation.getWorld() == null
                            || npcs.get(context.npc().id(), npcLocation.getWorld()) != context.npc()) return;
                    try {
                        runBlock(context, code, next, indent, continuation);
                    } catch (RuntimeException exception) {
                        logScriptException(context, exception);
                    }
                }, ticks);
                tasks.add(holder[0]);
                delayedTasks.computeIfAbsent(npcTaskKey, ignored -> ConcurrentHashMap.newKeySet()).add(holder[0]);
                return;
            }

            if (line.startsWith("run command ")) {
                String command = renderMessage(context, line.substring(12));
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
                String state = normalizeArgument(line.substring(10));
                mainStates.put(context.npc().id().toLowerCase(Locale.ROOT), state);
                variables.set("npc", context.npc().id(), "state:main", state);
                index++;
                continue;
            }

            if (line.startsWith("add state ")) {
                String state = normalizeArgument(line.substring(10)).toLowerCase(Locale.ROOT);
                secondaryStates.computeIfAbsent(context.npc().id().toLowerCase(Locale.ROOT),
                        ignored -> ConcurrentHashMap.newKeySet()).add(state);
                variables.set("npc", context.npc().id(), "state:secondary:" + state, true);
                index++;
                continue;
            }

            if (line.startsWith("remove state ")) {
                String state = normalizeArgument(line.substring(13)).toLowerCase(Locale.ROOT);
                secondaryStates.computeIfAbsent(context.npc().id().toLowerCase(Locale.ROOT),
                        ignored -> ConcurrentHashMap.newKeySet()).remove(state);
                variables.set("npc", context.npc().id(), "state:secondary:" + state, false);
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
            return new LoopPlan("loop-player",
                    boundLoopValues(context, new ArrayList<>(Bukkit.getOnlinePlayers()), "joueurs connectés"));
        }
        if (lower.equals("loop all npcs:")) {
            List<ManagedNPC> loaded = new ArrayList<>(npcs.all());
            loaded.sort(Comparator
                    .comparing((ManagedNPC npc) -> npc.id().toLowerCase(Locale.ROOT))
                    .thenComparing(npc -> {
                        org.bukkit.World world = npc.location().getWorld();
                        return world == null ? "" : world.getName().toLowerCase(Locale.ROOT);
                    }));
            return new LoopPlan("loop-npc", boundLoopValues(context, new ArrayList<>(loaded), "NPC chargés"));
        }
        if (lower.startsWith("loop all (") && lower.endsWith("):")) {
            String expression = line.substring(10, line.length() - 2).trim();
            Object value = resolve(context, expression);
            List<Object> values = asList(value);
            if (values == null) {
                plugin.getLogger().warning("Script " + context.npc().id() + " : la valeur de boucle n'est pas une liste : " + expression);
                return null;
            }
            return new LoopPlan("loop-value", boundLoopValues(context, values, "valeurs de la liste"));
        }
        if (lower.startsWith("loop ") && lower.endsWith(" times:")) {
            String countText = line.substring(5, line.length() - 7).trim();
            try {
                int requested = Integer.parseInt(countText);
                if (requested < 0) {
                    plugin.getLogger().warning("Script " + context.npc().id() + " : nombre de répétitions négatif : " + countText);
                    return null;
                }
                int count = Math.min(MAX_LOOP_ITERATIONS, requested);
                if (requested > MAX_LOOP_ITERATIONS) {
                    plugin.getLogger().warning("Script " + context.npc().id() + " : boucle limitée à "
                            + MAX_LOOP_ITERATIONS + " itérations (demandé : " + requested + ").");
                }
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

    private List<Object> boundLoopValues(ScriptContext context, List<?> values, String description) {
        if (values.size() <= MAX_LOOP_ITERATIONS) return new ArrayList<>(values);
        plugin.getLogger().warning("Script " + context.npc().id() + " : boucle sur " + description
                + " limitée à " + MAX_LOOP_ITERATIONS + " éléments (disponibles : " + values.size() + ").");
        return new ArrayList<>(values.subList(0, MAX_LOOP_ITERATIONS));
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
        List<String> items = parseFunctionArguments(content);
        if (items == null) return null;
        List<Object> result = new ArrayList<>();
        for (String item : items) result.add(parseStored(unquote(item.trim())));
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
        if (groups.size() != 2) {
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
        String warningKey = npcKey(context.npc()) + ":" + instruction.line() + ":" + reason;
        if (!warnedInstructions.add(warningKey)) return;
        plugin.getLogger().warning("Script " + context.npc().id() + ", ligne " + instruction.line()
                + " : " + reason + " (" + instruction.text() + ").");
    }

    private int findEnd(List<Instruction> code, int from, int parentIndent) {
        int index = from;
        while (index < code.size() && code.get(index).indent() > parentIndent) index++;
        return index;
    }
    private String stripOuterParentheses(String expression) {
        String value = expression.trim();
        while (value.startsWith("(") && value.endsWith(")")) {
            int depth = 0;
            boolean quoted = false;
            boolean wrapsAll = true;
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character == '"') {
                    int backslashes = 0;
                    for (int previous = i - 1; previous >= 0 && value.charAt(previous) == '\\'; previous--) {
                        backslashes++;
                    }
                    if (backslashes % 2 == 0) quoted = !quoted;
                }
                if (quoted) continue;
                if (character == '(') depth++;
                else if (character == ')' && --depth == 0 && i < value.length() - 1) {
                    wrapsAll = false;
                    break;
                }
            }
            if (!wrapsAll || depth != 0) break;
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private List<String> splitLogical(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int parentheses = 0;
        int brackets = 0;
        int start = 0;
        for (int i = 0; i <= expression.length() - operator.length(); i++) {
            char character = expression.charAt(i);
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && expression.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (quoted) continue;
            if (character == '(') parentheses++;
            else if (character == ')') parentheses--;
            else if (character == '[') brackets++;
            else if (character == ']') brackets--;
            if (parentheses == 0 && brackets == 0
                    && expression.regionMatches(true, i, operator, 0, operator.length())) {
                String part = expression.substring(start, i).trim();
                if (part.isEmpty()) return List.of(expression);
                parts.add(part);
                i += operator.length() - 1;
                start = i + 1;
            }
        }
        if (parts.isEmpty()) return List.of(expression);
        String finalPart = expression.substring(start).trim();
        if (finalPart.isEmpty()) return List.of(expression);
        parts.add(finalPart);
        return parts;
    }

    private boolean hasBalancedExpressionSyntax(String expression) {
        boolean quoted = false;
        int parentheses = 0;
        int brackets = 0;
        for (int i = 0; i < expression.length(); i++) {
            char character = expression.charAt(i);
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && expression.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (quoted) continue;
            if (character == '(') parentheses++;
            else if (character == ')' && --parentheses < 0) return false;
            else if (character == '[') brackets++;
            else if (character == ']' && --brackets < 0) return false;
        }
        return !quoted && parentheses == 0 && brackets == 0;
    }

    private boolean isRecognizedCondition(ScriptContext context, String expression) {
        if (!hasBalancedExpressionSyntax(expression)) return false;
        String candidate = stripOuterParentheses(expression.trim());
        if (candidate.isBlank()) return false;

        List<String> orParts = splitLogical(candidate, " or ");
        if (orParts.size() > 1) {
            return orParts.stream().allMatch(part -> isRecognizedCondition(context, part));
        }
        List<String> andParts = splitLogical(candidate, " and ");
        if (andParts.size() > 1) {
            return andParts.stream().allMatch(part -> isRecognizedCondition(context, part));
        }
        if (candidate.regionMatches(true, 0, "not ", 0, 4)) {
            return isRecognizedCondition(context, candidate.substring(4));
        }

        Comparison comparison = findComparison(candidate);
        if (comparison != null) {
            return comparison.valid() && !comparison.left().isBlank() && !comparison.right().isBlank();
        }

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.equals("player is sneaking") || lower.equals("npc is under attack")) return true;
        if (lower.startsWith("state is ") && !candidate.substring(9).isBlank()) return true;
        if (lower.startsWith("has state ") && !candidate.substring(10).isBlank()) return true;
        if (lower.startsWith("player has permission ")
                && !candidate.substring(22).isBlank()) return true;
        if (lower.startsWith("player has item ")
                && !candidate.substring(16).isBlank()) return true;
        if (lower.startsWith("flag (") && lower.contains(") is ")) {
            int close = candidate.indexOf(')');
            if (close <= 6) return false;
            String expected = lower.substring(lower.indexOf(") is ") + 5).trim();
            return !candidate.substring(6, close).isBlank()
                    && (expected.equals("on") || expected.equals("off"));
        }
        return false;
    }

    private boolean test(ScriptContext context, String expression) {
        if (!hasBalancedExpressionSyntax(expression)) return false;
        String expressionText = stripOuterParentheses(expression.trim());
        List<String> orParts = splitLogical(expressionText, " or ");
        if (orParts.size() > 1) {
            return orParts.stream().anyMatch(part -> test(context, part));
        }
        List<String> andParts = splitLogical(expressionText, " and ");
        if (andParts.size() > 1) {
            return andParts.stream().allMatch(part -> test(context, part));
        }
        if (expressionText.regionMatches(true, 0, "not ", 0, 4)) {
            String operand = expressionText.substring(4).trim();
            if (!isRecognizedCondition(context, operand)) return false;
            return !test(context, operand);
        }
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
            String current = mainStates.get(npcId);
            if (current == null) current = variables.get("npc", context.npc().id(), "state:main", "");
            return current.equalsIgnoreCase(expected);
        }
        if (lower.startsWith("has state ")) {
            String expected = normalizeArgument(expressionText.substring(10)).toLowerCase(Locale.ROOT);
            Set<String> current = secondaryStates.getOrDefault(npcId, Set.of());
            return current.contains(expected)
                    || Boolean.parseBoolean(variables.get("npc", context.npc().id(),
                    "state:secondary:" + expected, "false"));
        }

        Comparison comparisonExpression = findComparison(expressionText);
        if (comparisonExpression != null) {
            if (!comparisonExpression.valid()
                    || comparisonExpression.left().isBlank()
                    || comparisonExpression.right().isBlank()) return false;
            Object left = resolve(context, comparisonExpression.left());
            Object right = resolve(context, comparisonExpression.right());
            if (comparisonExpression.operator().equalsIgnoreCase("contains")) {
                return String.valueOf(left).contains(String.valueOf(right));
            }
            Double leftNumber = numericValue(left);
            Double rightNumber = numericValue(right);
            if (leftNumber != null && rightNumber != null) {
                return switch (comparisonExpression.operator()) {
                    case ">" -> leftNumber > rightNumber;
                    case "<" -> leftNumber < rightNumber;
                    case ">=" -> leftNumber >= rightNumber;
                    case "<=" -> leftNumber <= rightNumber;
                    case "!=" -> Double.compare(leftNumber, rightNumber) != 0;
                    default -> Double.compare(leftNumber, rightNumber) == 0;
                };
            }
            int comparison = String.valueOf(left).compareTo(String.valueOf(right));
            return switch (comparisonExpression.operator()) {
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
            long durationTicks = Math.max(1L, Math.min(2_000_000_000L,
                    plugin.getConfig().getLong("script-under-attack-duration-ticks", 100L)));
            return lastAttack > 0L && System.currentTimeMillis() - lastAttack <= durationTicks * 50L;
        }
        return false;
    }

    private Comparison findComparison(String expression) {
        String[] operators = {" >= ", " <= ", " != ", " == ", " > ", " < ", " contains "};
        boolean quoted = false;
        int parentheses = 0;
        int brackets = 0;
        Comparison found = null;

        for (int i = 0; i < expression.length(); i++) {
            char character = expression.charAt(i);
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && expression.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (quoted) continue;

            if (character == '(') {
                parentheses++;
                continue;
            }
            if (character == ')') {
                if (--parentheses < 0) return new Comparison("", "", "", false);
                continue;
            }
            if (character == '[') {
                brackets++;
                continue;
            }
            if (character == ']') {
                if (--brackets < 0) return new Comparison("", "", "", false);
                continue;
            }
            if (parentheses != 0 || brackets != 0) continue;

            for (String operator : operators) {
                if (!expression.regionMatches(true, i, operator, 0, operator.length())) continue;
                if (found != null) return new Comparison("", "", "", false);
                int end = i + operator.length();
                found = new Comparison(
                        expression.substring(0, i).trim(),
                        operator.trim().toLowerCase(Locale.ROOT),
                        expression.substring(end).trim(),
                        true);
                i = end - 1;
                break;
            }
        }

        if (quoted || parentheses != 0 || brackets != 0) {
            return new Comparison("", "", "", false);
        }
        return found;
    }

    private String normalizeArgument(String raw) {
        String value = raw.trim();
        if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1).trim();
        return unquote(value);
    }

    private boolean containsArithmeticOperator(String expression) {
        boolean quoted = false;
        for (int i = 0; i < expression.length(); i++) {
            char character = expression.charAt(i);
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && expression.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (!quoted && (character == '+' || character == '*' || character == '/' || character == '%')) return true;
            if (!quoted && character == '-' && i > 0) return true;
        }
        return false;
    }

    private final class ArithmeticParser {
        private final ScriptContext context;
        private final String source;
        private int position;

        private ArithmeticParser(ScriptContext context, String source) {
            this.context = context;
            this.source = source;
        }

        private Double parse() {
            try {
                double result = expression();
                skipWhitespace();
                return position == source.length() && Double.isFinite(result) ? result : null;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private double expression() {
            double result = term();
            while (true) {
                skipWhitespace();
                if (consume('+')) result += term();
                else if (consume('-')) result -= term();
                else return result;
            }
        }

        private double term() {
            double result = unary();
            while (true) {
                skipWhitespace();
                if (consume('*')) result *= unary();
                else if (consume('/')) {
                    double divisor = unary();
                    if (divisor == 0.0) throw new IllegalArgumentException("Division by zero");
                    result /= divisor;
                } else if (consume('%')) {
                    double divisor = unary();
                    if (divisor == 0.0) throw new IllegalArgumentException("Modulo by zero");
                    result %= divisor;
                } else return result;
            }
        }

        private double unary() {
            skipWhitespace();
            if (consume('+')) return unary();
            if (consume('-')) return -unary();
            return primary();
        }

        private double primary() {
            skipWhitespace();
            if (consume('(')) {
                double nested = expression();
                skipWhitespace();
                if (!consume(')')) throw new IllegalArgumentException("Missing closing parenthesis");
                return nested;
            }

            int start = position;
            while (position < source.length()) {
                char character = source.charAt(position);
                if (Character.isDigit(character) || character == '.') position++;
                else break;
            }
            if (position > start) {
                try {
                    return Double.parseDouble(source.substring(start, position));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid number", exception);
                }
            }

            start = position;
            while (position < source.length()) {
                char character = source.charAt(position);
                if (Character.isLetterOrDigit(character) || character == '_') position++;
                else break;
            }
            if (position == start) throw new IllegalArgumentException("Expected a number or variable");

            int scan = position;
            int bestHyphenatedEnd = -1;
            while (scan < source.length() && source.charAt(scan) == '-') {
                scan++;
                int segmentStart = scan;
                while (scan < source.length()) {
                    char character = source.charAt(scan);
                    if (Character.isLetterOrDigit(character) || character == '_') scan++;
                    else break;
                }
                if (scan == segmentStart) break;
                Object candidate = resolve(context, source.substring(start, scan), false);
                if (numericValue(candidate) != null) bestHyphenatedEnd = scan;
            }
            if (bestHyphenatedEnd >= 0) position = bestHyphenatedEnd;

            String variable = source.substring(start, position);
            Object resolved = resolve(context, variable, false);
            Double number = numericValue(resolved);
            if (number == null) throw new IllegalArgumentException("Variable is not numeric");
            return number;
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private boolean consume(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }

    private Object resolve(ScriptContext context, String raw) {
        return resolve(context, raw, true);
    }

    private Object resolve(ScriptContext context, String raw, boolean evaluateArithmetic) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) return unquote(value);
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        try {
            double number = Double.parseDouble(value);
            if (Double.isFinite(number)) return number;
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
        if (value.regionMatches(true, 0, "player:", 0, 7)) {
            Player player = Bukkit.getPlayerExact(value.substring(7));
            return player != null && isWithinSelectorRange(context.npc().location(), player) ? player : null;
        }
        if (value.regionMatches(true, 0, "npc:", 0, 4)) {
            return npcs.get(value.substring(4), context.npc().location().getWorld());
        }
        if (value.equalsIgnoreCase("state of npc")) {
            String state = mainStates.get(context.npc().id().toLowerCase(Locale.ROOT));
            return state == null ? variables.get("npc", context.npc().id(), "state:main", "") : state;
        }

        if (evaluateArithmetic && containsArithmeticOperator(value)) {
            Double calculated = new ArithmeticParser(context, value).parse();
            if (calculated != null && Double.isFinite(calculated)) return calculated;
        }

        FunctionResult functionResult = invokeFunction(context, value);
        if (functionResult != null) return functionResult.value();

        String normalizedNpcId = context.npc().id().toLowerCase(Locale.ROOT);
        String metadata = variables.get("meta", normalizedNpcId, value, null);
        if (metadata == null && !normalizedNpcId.equals(context.npc().id())) {
            metadata = variables.get("meta", context.npc().id(), value, null);
        }
        if (metadata == null) metadata = variables.get("meta", "", value, null);
        if (metadata != null) {
            String[] parts = metadata.split("\\|", 2);
            String scope = parts[0];
            String owner = parts.length > 1 ? parts[1] : context.npc().id();
            if (scope.equalsIgnoreCase("global") && owner.equals("@")) owner = "";
            if (scope.equalsIgnoreCase("player")) {
                if (context.player() == null) return null;
                owner = context.player().getUniqueId().toString();
            }
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
            if (arguments == null || arguments.size() != function.parameters().size()) {
                plugin.getLogger().warning("Script " + parent.npc().id() + " : nombre d'arguments invalide pour " + name + ".");
                return new FunctionResult(true, null);
            }

            ScriptContext context = new ScriptContext(parent.npc(), parent.player(), parent.eventNpc(), parent.executionBudget());
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
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && raw.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (quoted) continue;
            if (character == '(') parentheses++;
            else if (character == ')') {
                if (--parentheses < 0) return null;
            } else if (character == '[') brackets++;
            else if (character == ']') {
                if (--brackets < 0) return null;
            } else if (character == ',' && parentheses == 0 && brackets == 0) {
                String argument = raw.substring(start, i).trim();
                if (argument.isEmpty()) return null;
                arguments.add(argument);
                start = i + 1;
            }
        }
        if (quoted || parentheses != 0 || brackets != 0) return null;
        String finalArgument = raw.substring(start).trim();
        if (finalArgument.isEmpty()) return null;
        arguments.add(finalArgument);
        return arguments;
    }

    private FunctionResult runFunctionBlock(ScriptContext context, List<Instruction> code, int start, int indent) {
        int index = start;
        while (index < code.size()) {
            Instruction instruction = code.get(index);
            if (!context.consumeInstruction()) {
                if (context.markExecutionBudgetWarning()) {
                    plugin.getLogger().warning("Script " + context.npc().id()
                            + " : budget maximal de 10 000 instructions atteint ; exécution interrompue.");
                }
                return;
            }
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
                int firstEnd = findEnd(code, index + 1, instruction.indent());
                boolean firstMatches = test(context, line.substring(3, line.length() - 1));
                int chainCursor = firstEnd;
                int selectedStart = -1;
                int selectedEnd = -1;

                while (chainCursor < code.size()
                        && code.get(chainCursor).indent() == instruction.indent()) {
                    String branch = code.get(chainCursor).text();
                    String branchLower = branch.toLowerCase(Locale.ROOT);
                    if (branchLower.equals("else:")) {
                        int branchEnd = findEnd(code, chainCursor + 1, instruction.indent());
                        if (!firstMatches && selectedStart < 0) {
                            selectedStart = chainCursor + 1;
                            selectedEnd = branchEnd;
                        }
                        chainCursor = branchEnd;
                        break;
                    }
                    if (!branchLower.startsWith("else if ") || !branch.endsWith(":")) break;

                    int branchEnd = findEnd(code, chainCursor + 1, instruction.indent());
                    if (!firstMatches && selectedStart < 0
                            && test(context, branch.substring(8, branch.length() - 1))) {
                        selectedStart = chainCursor + 1;
                        selectedEnd = branchEnd;
                    }
                    chainCursor = branchEnd;
                }

                if (firstMatches && index + 1 < firstEnd) {
                    int childIndent = code.get(index + 1).indent();
                    FunctionResult result = runFunctionBlock(context, code, index + 1, childIndent);
                    if (result.returned()) return result;
                } else if (!firstMatches && selectedStart >= 0 && selectedStart < selectedEnd) {
                    int childIndent = code.get(selectedStart).indent();
                    FunctionResult result = runFunctionBlock(context, code, selectedStart, childIndent);
                    if (result.returned()) return result;
                }
                index = chainCursor;
                continue;
            }

            if (line.startsWith("set \"")) {
                assignVariable(context, instruction);
                index++;
                continue;
            }

            if (line.startsWith("say local ") || line.startsWith("say global ")) {
                boolean global = line.startsWith("say global ");
                sendScriptMessage(context, global, line.substring(global ? 11 : 10));
                index++;
                continue;
            }

            if (line.startsWith("play anime ")) {
                context.npc().animate(renderMessage(context, line.substring(11)));
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

    private Object parseStored(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : value;
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
    private void createVariable(ScriptContext context, String declaration) {
        List<String> arguments = extractGroups(declaration);
        if (arguments.size() == 1) {
            List<String> tupleArguments = parseFunctionArguments(arguments.get(0));
            if (tupleArguments == null) {
                plugin.getLogger().warning("Script " + context.npc().id()
                        + " : arguments invalides pour create variable : " + declaration);
                return;
            }
            arguments = tupleArguments;
        }
        if (arguments.size() < 2 || arguments.size() > 4) {
            plugin.getLogger().warning("Script " + context.npc().id() + " : déclaration create variable invalide : " + declaration);
            return;
        }

        String name = unquote(arguments.get(0)).trim().toLowerCase(Locale.ROOT);
        if (name.isBlank()) {
            plugin.getLogger().warning("Script " + context.npc().id() + " : nom de variable vide.");
            return;
        }
        Object defaultValue = resolve(context, arguments.get(1));
        String scopeDeclaration = arguments.size() > 2 ? arguments.get(2).trim() : "local";
        if (arguments.size() > 3) scopeDeclaration += " " + arguments.get(3).trim();
        if (scopeDeclaration.isBlank()) scopeDeclaration = "local";

        String scope = "local";
        String owner = context.npc().id();
        String normalizedNpcId = context.npc().id().toLowerCase(Locale.ROOT);

        if (scopeDeclaration.equalsIgnoreCase("global")) {
            scope = "global";
            owner = "";
            variables.set("meta", normalizedNpcId, name, "global|@");
            variables.set("meta", "", name, "global|@");
        } else if (scopeDeclaration.equalsIgnoreCase("player")
                || scopeDeclaration.equalsIgnoreCase("per-player")
                || scopeDeclaration.equalsIgnoreCase("per_player")) {
            scope = "player";
            owner = context.player() == null ? "" : context.player().getUniqueId().toString();
            variables.set("meta", normalizedNpcId, name, "player|@");
        } else if (scopeDeclaration.regionMatches(true, 0, "shared", 0, 6)) {
            int open = scopeDeclaration.indexOf('[');
            int close = open < 0 ? -1 : scopeDeclaration.indexOf(']', open + 1);
            if (open < 0 || close < 0
                    || !scopeDeclaration.substring(0, open).trim().equalsIgnoreCase("shared")
                    || !scopeDeclaration.substring(close + 1).isBlank()
                    || scopeDeclaration.indexOf('[', open + 1) >= 0
                    || scopeDeclaration.indexOf(']', close + 1) >= 0) {
                plugin.getLogger().warning("Script " + context.npc().id()
                        + " : syntaxe shared invalide ; utilise shared [npc1,npc2].");
                return;
            }
            List<String> members = Arrays.stream(scopeDeclaration.substring(open + 1, close).split(",", -1))
                    .map(String::trim)
                    .map(member -> member.toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
            if (members.isEmpty() || members.stream().anyMatch(member -> !member.matches("[a-z0-9_-]+"))) {
                plugin.getLogger().warning("Script " + context.npc().id()
                        + " : la liste shared contient un identifiant NPC invalide.");
                return;
            }
            if (!members.contains(normalizedNpcId)) {
                plugin.getLogger().warning("Script " + context.npc().id()
                        + " : la liste d'une variable shared doit inclure le NPC qui la déclare.");
                return;
            }
            scope = "shared";
            owner = String.join(",", members);
            for (String member : members) variables.set("meta", member, name, "shared|" + owner);
        } else if (scopeDeclaration.equalsIgnoreCase("local")) {
            variables.set("meta", normalizedNpcId, name, "local|" + owner);
        } else {
            plugin.getLogger().warning("Script " + context.npc().id()
                    + " : portée de variable inconnue : " + scopeDeclaration + ".");
            return;
        }

        if (!scope.equals("player") || context.player() != null) {
            variables.setIfAbsent(scope, owner, name, defaultValue);
        }
        String storedValue = scope.equals("player") && context.player() == null
                ? String.valueOf(defaultValue)
                : variables.get(scope, owner, name, String.valueOf(defaultValue));
        context.locals().put(name, parseStored(storedValue));
    }

    private List<String> extractGroups(String declaration) {
        List<String> groups = new ArrayList<>();
        boolean quoted = false;
        int start = -1;
        int depth = 0;

        for (int i = 0; i < declaration.length(); i++) {
            char character = declaration.charAt(i);
            if (character == '"') {
                int backslashes = 0;
                for (int previous = i - 1; previous >= 0 && declaration.charAt(previous) == '\\'; previous--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) quoted = !quoted;
            }
            if (quoted) continue;

            if (character == '(') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (character == ')') {
                if (--depth < 0) return List.of();
                if (depth == 0 && start >= 0) {
                    groups.add(declaration.substring(start, i).trim());
                    start = -1;
                }
            } else if (depth == 0 && !Character.isWhitespace(character)) {
                return List.of();
            }
        }
        if (quoted || depth != 0 || start >= 0) return List.of();
        return groups;
    }
    private void storeVariable(ScriptContext context, String name, Object value) {
        String normalizedNpcId = context.npc().id().toLowerCase(Locale.ROOT);
        String metadata = variables.get("meta", normalizedNpcId, name, null);
        if (metadata == null && !normalizedNpcId.equals(context.npc().id())) {
            metadata = variables.get("meta", context.npc().id(), name, null);
        }
        if (metadata == null) metadata = variables.get("meta", "", name, null);
        if (metadata == null) {
            metadata = "local|" + context.npc().id();
            variables.set("meta", normalizedNpcId, name, metadata);
        }
        String[] parts = metadata.split("\\|", 2);
        String scope = parts[0];
        String owner = parts.length > 1 ? parts[1] : context.npc().id();
        if (scope.equalsIgnoreCase("global") && owner.equals("@")) owner = "";
        if (scope.equalsIgnoreCase("player")) {
            if (context.player() == null) {
                plugin.getLogger().warning("Script " + context.npc().id()
                        + " : impossible de modifier la variable joueur '" + name + "' sans joueur associé à l'événement.");
                return;
            }
            owner = context.player().getUniqueId().toString();
        }
        variables.set(scope, owner, name, value);
    }
    private Double numericValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    private boolean isQuotedString(String value) {
        if (value.length() < 2 || value.charAt(0) != '"') return false;
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != '"') continue;
            int backslashes = 0;
            for (int previous = i - 1; previous >= 0 && value.charAt(previous) == '\\'; previous--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) return i == value.length() - 1;
        }
        return false;
    }

    private String unquote(String value) {
        String trimmed = value.trim();
        if (!isQuotedString(trimmed)) return trimmed;
        String inner = trimmed.substring(1, trimmed.length() - 1);
        StringBuilder result = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char character = inner.charAt(i);
            if (character == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    result.append(next);
                    i++;
                    continue;
                }
            }
            result.append(character);
        }
        return result.toString();
    }

    private double selectorRangeSquared(Location origin) {
        double range = configDouble("selector-range", 64.0, 1.0, 512.0);
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
            return player != null && isWithinSelectorRange(origin, player) ? List.of(player) : List.of();
        }

        Object value = resolve(context, target);
        if (value instanceof Player player) return List.of(player);
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(Player.class::isInstance).map(Player.class::cast).toList();
        }
        Player player = Bukkit.getPlayerExact(target);
        return player != null && isWithinSelectorRange(origin, player) ? List.of(player) : List.of();
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
            return player != null && isWithinSelectorRange(origin, player) ? List.of(player) : List.of();
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
        int limit = MAX_SELECTOR_RESULTS;
        boolean limitSpecified = false;
        if (!options.isBlank()) {
            for (String option : options.split(",", -1)) {
                String[] pair = option.trim().split("=", 2);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) return List.of();
                if (pair[0].equalsIgnoreCase("type")) {
                    if (requiredType != null) return List.of();
                    requiredType = pair[1].trim().replace("minecraft:", "");
                } else if (pair[0].equalsIgnoreCase("limit")) {
                    if (limitSpecified) return List.of();
                    try {
                        int requestedLimit = Integer.parseInt(pair[1].trim());
                        if (requestedLimit < 1) return List.of();
                        limit = Math.min(MAX_SELECTOR_RESULTS, requestedLimit);
                        limitSpecified = true;
                    } catch (NumberFormatException ignored) {
                        return List.of();
                    }
                } else {
                    return List.of();
                }
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

        double range = configDouble("selector-range", 64.0, 1.0, 512.0);
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
            return player != null && isWithinSelectorRange(context.npc().location(), player)
                    ? player.getLocation() : null;
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
                    double x = Double.parseDouble(coordinates[0].trim());
                    double y = Double.parseDouble(coordinates[1].trim());
                    double z = Double.parseDouble(coordinates[2].trim());
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                            || context.npc().location().getWorld() == null) return null;
                    return new Location(context.npc().location().getWorld(), x, y, z);
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
            try {
                npc.animate("idle");
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Impossible de réinitialiser l'animation du NPC '"
                        + npc.id() + "' après l'arrêt du déplacement : " + exception.getMessage());
            }
        }
    }

    private void stopFollow(ManagedNPC npc) {
        String key = movementKey(npc);
        BukkitTask follow = followTasks.remove(key);
        if (follow != null) {
            follow.cancel();
            tasks.remove(follow);
        }
        followPathAttemptAt.remove(key);
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
                if (min >= 0 || max >= 0) throw new NumberFormatException();
                selector = arguments.substring(0, at).trim();
                minimum = maximum = Double.parseDouble(arguments.substring(at + 4).trim());
            } else if (min >= 0 || max >= 0) {
                if (min <= 0 || max <= min) throw new NumberFormatException();
                selector = arguments.substring(0, min).trim();
                minimum = Double.parseDouble(arguments.substring(min + 5, max).trim());
                maximum = Double.parseDouble(arguments.substring(max + 5).trim());
            }
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum < minimum) {
                throw new NumberFormatException();
            }
            minimum = Math.max(0.5, Math.min(32.0, minimum));
            maximum = Math.max(minimum, Math.min(32.0, maximum));
            if (selector.equalsIgnoreCase("event entity") && context.player() == null) {
                warnInstruction(context, instruction, "event entity nécessite un événement associé à un joueur");
                return;
            }
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
            if (!Double.isFinite(distance)) throw new NumberFormatException();
            distance = Math.max(0.5, Math.min(32.0, distance));
            if (selector.equalsIgnoreCase("event entity") && context.player() == null) {
                warnInstruction(context, instruction, "event entity nécessite un événement associé à un joueur");
                return;
            }
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
            try {
                Entity target = fixedPlayer == null
                        ? selectEntities(selectorContext, selector).stream().findFirst().orElse(null)
                        : Bukkit.getPlayer(fixedPlayer);

                if (target == null) {
                    if (fixedPlayer != null && Bukkit.getPlayer(fixedPlayer) == null) stopFollow(npc);
                    return;
                }
                if (!target.isValid()) {
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

                long now = System.currentTimeMillis();
                long nextAttempt = followPathAttemptAt.getOrDefault(key, 0L);
                if (!movementTasks.containsKey(key) && now >= nextAttempt) {
                    followPathAttemptAt.put(key, now + 1000L);
                    double desiredDistance = tooFar ? maximumDistance : minimumDistance;
                    org.bukkit.util.Vector offset = npcLocation.toVector().subtract(targetLocation.toVector());
                    if (offset.lengthSquared() < 0.000001) offset = new org.bukkit.util.Vector(1, 0, 0);
                    offset.normalize().multiply(desiredDistance);
                    Location destination = targetLocation.clone().add(offset);
                    destination.setY(targetLocation.getY());
                    move(npc, destination);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Suivi interrompu pour le NPC '" + npc.id() + "' : "
                                + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()),
                        exception);
                try {
                    stopFollow(npc);
                } catch (RuntimeException cleanupException) {
                    plugin.getLogger().warning("Nettoyage du suivi impossible pour " + npc.id()
                            + " : " + cleanupException.getMessage());
                }
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
        stopMovement(npc);

        List<Location> checkpoints;
        if (plugin.getConfig().getBoolean("movement.pathfinding.enabled", true)) {
            checkpoints = net.amethystesmp.anpc.npc.Pathfinder.findPath(
                    start,
                    target,
                    configInt("movement.pathfinding.max-nodes", 5000, 1, 100000),
                    configDouble("movement.pathfinding.max-distance", 128.0, 1.0, 512.0),
                    configDouble("movement.checkpoint-spacing", 2.0, 0.5, 16.0));
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
        stopMovement(npc);

        String section = switch (mode) {
            case CLIMB -> "climbing";
            case SWIM -> "swimming";
            case AMPHIBIOUS -> "amphibious";
        };
        List<Location> checkpoints = net.amethystesmp.anpc.npc.AdvancedPathfinder.findPath(
                start,
                target,
                mode,
                configInt("movement." + section + ".max-nodes", 5000, 1, 100000),
                configDouble("movement." + section + ".max-distance", 96.0, 1.0, 512.0),
                configDouble("movement.checkpoint-spacing", 2.0, 0.5, 16.0));
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
        stopMovement(npc);

        List<Location> checkpoints = net.amethystesmp.anpc.npc.FlyingPathfinder.findPath(
                start,
                target,
                configInt("movement.flying.max-nodes", 5000, 1, 100000),
                configDouble("movement.flying.max-distance", 128.0, 1.0, 512.0),
                configDouble("movement.checkpoint-spacing", 2.0, 0.5, 16.0));
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
        long updateTicks = Math.max(1L, Math.min(1200L, plugin.getConfig().getLong("movement.update-ticks", 1L)));
        double speed = configDouble("movement.default-speed", 4.0, 0.1, 64.0);
        double distancePerUpdate = speed * updateTicks / 20.0;
        int[] checkpointIndex = {0};
        Location[] current = {start.clone()};
        BukkitTask[] holder = new BukkitTask[1];

        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
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
            } catch (RuntimeException exception) {
                BukkitTask failedTask = holder[0];
                if (failedTask != null) {
                    failedTask.cancel();
                    tasks.remove(failedTask);
                    movementTasks.remove(movementKey, failedTask);
                }
                try {
                    npc.animate("idle");
                } catch (RuntimeException ignored) {
                }
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Déplacement interrompu pour le NPC '" + npc.id() + "' : "
                                + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()),
                        exception);
            }
        }, 1L, updateTicks);

        tasks.add(holder[0]);
        movementTasks.put(movementKey, holder[0]);
        npc.animate("walk");
    }

    private double configDouble(String path, double fallback, double minimum, double maximum) {
        double value = plugin.getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value)) value = fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int configInt(String path, int fallback, int minimum, int maximum) {
        int value = plugin.getConfig().getInt(path, fallback);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long parseDurationTicks(String input) {
        String[] parts = input.trim().toLowerCase(Locale.ROOT).split("\\s+");
        try {
            if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) return -1L;
            long parsedAmount = Long.parseLong(parts[0]);
            if (parsedAmount < 0L) return -1L;
            long amount = Math.max(1L, parsedAmount);
            long multiplier = 1L;
            if (parts.length == 2) {
                multiplier = switch (parts[1]) {
                    case "tick", "ticks" -> 1L;
                    case "second", "seconds" -> 20L;
                    case "minute", "minutes" -> 1200L;
                    default -> 0L;
                };
                if (multiplier == 0L) return -1L;
            }
            long maximum = 2_000_000_000L;
            if (amount > maximum / multiplier) return maximum;
            return Math.min(maximum, amount * multiplier);
        } catch (Exception exception) {
            return -1L;
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcRightClick(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) return;
        String id = event.getRightClicked().getScoreboardTags().stream()
                .filter(tag -> tag.startsWith("anpc:"))
                .map(tag -> tag.substring(5))
                .findFirst().orElse(null);
        if (id == null) return;

        event.setCancelled(true);
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            fire(id, event.getPlayer().isSneaking() ? "right click while sneaking" : "right click",
                    event.getPlayer());
            if (!event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
                fire(id, "item click", event.getPlayer());
            }
        } else if (!event.getPlayer().getInventory().getItemInOffHand().getType().isAir()) {
            fire(id, "offhand item click", event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcLeftClick(PrePlayerAttackEntityEvent event) {
        String id = event.getAttacked().getScoreboardTags().stream()
                .filter(tag -> tag.startsWith("anpc:"))
                .map(tag -> tag.substring(5))
                .findFirst().orElse(null);
        if (id == null) return;

        fire(id, event.getPlayer().isSneaking() ? "left click while sneaking" : "left click",
                event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcDamaged(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        Entity target = event.getEntity();
        String id = target.getScoreboardTags().stream()
                .filter(tag -> tag.startsWith("anpc:"))
                .map(tag -> tag.substring(5))
                .findFirst()
                .orElse(null);
        if (id == null) return;

        ManagedNPC managed = npcs.get(id, target.getWorld());

        // NPCs are script-controlled actors, not ordinary mobs: environmental
        // damage must not kill or alter their backing entities. The damage
        // script still receives the event below.
        event.setCancelled(true);

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            if (managed != null) firePlayerEvent(managed, "damage", null);
            return;
        }

        // "Under attack" only tracks damage caused by another entity, not
        // environmental damage such as fire, falling or suffocation.
        lastAttackedAt.put(npcKey(id, target.getWorld()), System.currentTimeMillis());

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
        if (managed != null) {
            // The Paper pre-attack event already fires the left-click script.
            // Keep damage separate so one swing cannot trigger the click twice.
            firePlayerEvent(managed, "damage", attacker);
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        double range = configDouble("tracking-range", 64.0, 0.0, 512.0);
        double rangeSquared = range * range;
        for (ManagedNPC npc : npcs.all()) {
            Location location = npc.location();
            if (player.getWorld().equals(location.getWorld())
                    && player.getLocation().distanceSquared(location) <= rangeSquared) {
                npc.show(player);
            }
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        for (ManagedNPC npc : npcs.all()) {
            npc.hide(player);
            Set<UUID> nearby = nearbyPlayers.get(npcKey(npc));
            if (nearby != null && nearby.remove(playerId)) {
                firePlayerEvent(npc, "entity leave", player);
            }
        }
    }
    private void cancelTasks() {
        for (ManagedNPC npc : npcs.all()) stopFollow(npc);
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        movementTasks.clear();
        intervalTasks.clear();
        followTasks.clear();
        followPathAttemptAt.clear();
        delayedTasks.clear();
        activeNpcPairs.clear();
        lastAttackedAt.clear();
        cooldowns.clear();
        warnedInstructions.clear();
    }

    public void shutdown() {
        cancelTasks();
        scripts.clear();
        nearbyPlayers.clear();
    }
}
