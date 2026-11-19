package net.amethystesmp.anpc.command;

import net.amethystesmp.anpc.ANPCPlugin;
import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ANPCCommand implements TabExecutor {
    private static final long DELETE_CONFIRMATION_MILLIS = 30_000L;
    private final ANPCPlugin plugin;
    private final Map<String, Long> pendingDeletes = new ConcurrentHashMap<>();

    public ANPCCommand(ANPCPlugin plugin) { this.plugin = plugin; }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6[ANPC] &f" + text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anpc.admin")) {
            msg(sender, "Permission insuffisante.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender, "/anpc list | create <id> <modele> | tp <id> | center <id> | rename <id> <nom>");
            msg(sender, "/anpc kill <id> | remove <id> | restore <id> | delete <id> [confirm]");
            msg(sender, "/anpc disable <id> | enable <id> | reload [script]");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (plugin.npcs().all().isEmpty()) msg(sender, "Aucun NPC actif.");
                else plugin.npcs().all().forEach(npc -> msg(sender,
                        npc.id() + " — " + npc.definition().type() + " — "
                                + npc.location().getWorld().getName() + " — scripts "
                                + (npc.scriptsEnabled() ? "activés" : "désactivés")));
            }
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "Commande réservée aux joueurs.");
                    return true;
                }
                if (args.length < 3) {
                    msg(sender, "Usage : /anpc create <id> <modele|limb:skin>");
                    return true;
                }
                if (plugin.npcs().create(args[1], args[2], player.getLocation())) {
                    plugin.scripts().reloadScript(args[1]);
                    msg(sender, "NPC créé : " + args[1]);
                }
                else msg(sender, "Création impossible : ID déjà utilisé ou modèle BetterModel introuvable.");
            }
            case "tp" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    msg(sender, "Usage : /anpc tp <id> (joueur)");
                    return true;
                }
                ManagedNPC npc = plugin.npcs().get(args[1], player.getWorld());
                if (npc == null) npc = plugin.npcs().get(args[1]);
                if (npc == null) msg(sender, "NPC introuvable ou retiré.");
                else player.teleport(npc.location());
            }
            case "center" -> {
                if (args.length < 2) { msg(sender, "Usage : /anpc center <id>"); return true; }
                if (!plugin.npcs().exists(args[1])) msg(sender, "NPC introuvable.");
                else { plugin.npcs().center(args[1]); msg(sender, "Toutes les instances du NPC ont été centrées."); }
            }
            case "rename" -> {
                if (args.length < 3) { msg(sender, "Usage : /anpc rename <id> <nouveau nom>"); return true; }
                if (!plugin.npcs().exists(args[1])) msg(sender, "NPC introuvable.");
                else {
                    plugin.npcs().rename(args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    msg(sender, "Nom modifié sur toutes les instances.");
                }
            }
            case "kill" -> {
                if (args.length < 2) { msg(sender, "Usage : /anpc kill <id>"); return true; }
                plugin.scripts().pauseNpc(args[1]);
                if (plugin.npcs().kill(args[1])) {
                    plugin.scripts().clearNpcTracking(args[1]);
                    msg(sender, "Instances retirées jusqu'au prochain reload.");
                }
                else msg(sender, "NPC introuvable.");
            }
            case "remove" -> {
                if (args.length < 2) { msg(sender, "Usage : /anpc remove <id>"); return true; }
                plugin.scripts().pauseNpc(args[1]);
                if (plugin.npcs().remove(args[1])) {
                    plugin.scripts().clearNpcTracking(args[1]);
                    msg(sender, "NPC masqué. Sa définition et ses données sont conservées. Utilise /anpc restore " + args[1] + " pour le réactiver.");
                }
                else msg(sender, "NPC introuvable.");
            }
            case "restore" -> {
                if (args.length < 2) { msg(sender, "Usage : /anpc restore <id>"); return true; }
                if (plugin.npcs().restore(args[1])) {
                    plugin.scripts().reloadScript(args[1]);
                    plugin.scripts().resumeNpc(args[1]);
                    msg(sender, "NPC restauré.");
                }
                else msg(sender, "Restauration impossible : NPC inconnu ou modèle indisponible.");
            }
            case "delete" -> handleDelete(sender, args);
            case "disable", "enable" -> {
                if (args.length < 2) {
                    msg(sender, "Usage : /anpc " + args[0].toLowerCase(Locale.ROOT) + " <id>");
                    return true;
                }
                boolean enabled = args[0].equalsIgnoreCase("enable");
                if (!enabled) plugin.scripts().pauseNpc(args[1]);
                if (plugin.npcs().setScriptsEnabled(args[1], enabled)) {
                    if (enabled) plugin.scripts().resumeNpc(args[1]);
                    msg(sender, enabled ? "Scripts réactivés. Le NPC reprend son fonctionnement." : "Scripts désactivés. Le NPC et son modèle restent présents.");
                }
                else msg(sender, "NPC introuvable ou retiré. Utilise /anpc restore <id> si nécessaire.");
            }
            case "reload" -> {
                if (args.length >= 2) {
                    if (plugin.scripts().reloadScript(args[1]))
                        msg(sender, "Script '" + args[1] + "' rechargé. Les fonctions globales ont été réindexées.");
                    else msg(sender, "Script introuvable ou impossible à recharger : " + args[1]);
                } else {
                    plugin.scripts().shutdown();
                    plugin.reloadConfig();
                    plugin.npcs().reload();
                    plugin.scripts().loadAll();
                    msg(sender, "Configuration, NPC et scripts rechargés. Les variables SQLite sont conservées.");
                }
            }
            default -> msg(sender, "Sous-commande inconnue. /anpc help");
        }
        return true;
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage : /anpc delete <id> puis /anpc delete <id> confirm");
            return;
        }
        String id = args[1];
        if (!plugin.npcs().exists(id)) {
            pendingDeletes.remove(id.toLowerCase(Locale.ROOT));
            msg(sender, "NPC introuvable.");
            return;
        }
        String key = id.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            pendingDeletes.put(key, now + DELETE_CONFIRMATION_MILLIS);
            msg(sender, "Attention : suppression définitive de toutes les instances et de la définition de '" + id + "'.");
            msg(sender, "Les variables locales seront récupérables pendant 24 h. Confirme avec /anpc delete " + id + " confirm (30 secondes).");
            return;
        }
        Long expiresAt = pendingDeletes.remove(key);
        if (expiresAt == null || now > expiresAt) {
            msg(sender, "Confirmation expirée. Relance /anpc delete " + id + " pour recommencer.");
            return;
        }
        plugin.scripts().pauseNpc(id);
        if (plugin.npcs().delete(id)) {
            plugin.scripts().unloadScript(id);
            msg(sender, "NPC '" + id + "' définitivement supprimé. Sa définition, son script et ses variables locales restent récupérables pendant 24 h.");
        } else msg(sender, "Suppression impossible : NPC introuvable.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("help", "list", "create", "tp", "center", "rename",
                "kill", "remove", "restore", "delete", "disable", "enable", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) return filter(plugin.scripts().scriptIds(), args[1]);
        if (args.length == 2 && Set.of("tp", "center", "rename", "kill", "remove", "restore",
                "delete", "disable", "enable").contains(args[0].toLowerCase(Locale.ROOT))) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            List<String> ids;
            if (subcommand.equals("restore")) {
                ids = plugin.npcs().allIds();
            } else if (Set.of("delete", "rename", "disable", "enable").contains(subcommand)) {
                ids = plugin.npcs().allIds().stream().filter(plugin.npcs()::exists).toList();
            } else {
                ids = plugin.npcs().all().stream().map(ManagedNPC::id).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
            return filter(ids, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("delete")) return filter(List.of("confirm"), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT)
                .startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
