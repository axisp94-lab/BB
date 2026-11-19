package net.amethystesmp.anpc.command;

import net.amethystesmp.anpc.ANPCPlugin;
import net.amethystesmp.anpc.npc.ManagedNPC;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ANPCCommand implements TabExecutor {
    private final ANPCPlugin plugin;

    public ANPCCommand(ANPCPlugin plugin) {
        this.plugin = plugin;
    }

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
            msg(sender, "/anpc list | create <id> <modele|limb:nom|mythicmob:id> | tp <id> | center <id> | rename <id> <nom> | kill <id> | reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<ManagedNPC> all = plugin.npcs().all().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                npc -> npc.id().toLowerCase(Locale.ROOT),
                                npc -> npc,
                                (first, ignored) -> first,
                                java.util.LinkedHashMap::new))
                        .values().stream().toList();

                if (all.isEmpty()) {
                    msg(sender, "Aucun NPC chargé.");
                } else {
                    all.forEach(npc -> {
                        String world = npc.location().getWorld() == null ? "monde inconnu" : npc.location().getWorld().getName();
                        msg(sender, npc.id() + " — " + npc.definition().type() + " — " + world);
                    });
                }
            }
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "Commande réservée aux joueurs.");
                    return true;
                }
                if (args.length < 3) {
                    msg(sender, "Usage : /anpc create <id> <modele|limb:nom|mythicmob:id>");
                    return true;
                }
                if (plugin.npcs().create(args[1], args[2], player.getLocation())) {
                    msg(sender, "NPC créé : " + args[1]);
                } else {
                    msg(sender, "Création impossible : vérifie l'ID, le modèle BetterModel et l'absence de doublon.");
                }
            }
            case "tp" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    msg(sender, "Usage : /anpc tp <id> (joueur)");
                    return true;
                }
                ManagedNPC npc = plugin.npcs().get(args[1], player.getWorld());
                if (npc == null) {
                    msg(sender, "NPC introuvable dans ton monde : " + args[1]);
                    return true;
                }
                if (player.teleport(npc.location())) {
                    msg(sender, "Téléportation vers " + npc.id() + ".");
                } else {
                    msg(sender, "Téléportation refusée pour " + npc.id() + ".");
                }
            }
            case "center" -> {
                if (args.length < 2) {
                    msg(sender, "Usage : /anpc center <id>");
                    return true;
                }
                if (plugin.npcs().center(args[1])) msg(sender, "NPC centré : " + args[1]);
                else msg(sender, "NPC introuvable : " + args[1]);
            }
            case "rename" -> {
                if (args.length < 3) {
                    msg(sender, "Usage : /anpc rename <id> <nouveau nom>");
                    return true;
                }
                String displayName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                if (plugin.npcs().rename(args[1], displayName)) msg(sender, "Nom du NPC modifié : " + args[1]);
                else msg(sender, "NPC introuvable ou nom invalide.");
            }
            case "kill", "remove", "delete" -> {
                if (args.length < 2) {
                    msg(sender, "Usage : /anpc kill <id>");
                    return true;
                }
                if (plugin.npcs().remove(args[1])) msg(sender, "NPC supprimé : " + args[1]);
                else msg(sender, "NPC introuvable : " + args[1]);
            }
            case "reload" -> {
                try {
                    plugin.reloadConfig();
                    plugin.scripts().shutdown();
                    plugin.npcs().reload();
                    plugin.scripts().loadAll();
                    msg(sender, "Configuration, NPC et scripts rechargés. Les variables SQLite sont conservées.");
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("Erreur pendant /anpc reload : " + exception.getMessage());
                    msg(sender, "Le rechargement a échoué. Consulte la console pour le détail.");
                }
            }
            default -> msg(sender, "Sous-commande inconnue. Utilise /anpc help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "list", "create", "tp", "center", "rename", "kill", "remove", "delete", "reload"), args[0]);
        }
        if (args.length == 2 && Set.of("tp", "center", "rename", "kill", "remove", "delete")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(plugin.npcs().all().stream().map(ManagedNPC::id).distinct().toList(), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                .toList();
    }
}
