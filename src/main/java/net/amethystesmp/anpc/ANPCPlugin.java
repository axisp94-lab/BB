package net.amethystesmp.anpc;

import net.amethystesmp.anpc.command.ANPCCommand;
import net.amethystesmp.anpc.npc.NPCManager;
import net.amethystesmp.anpc.scripting.ScriptEngine;
import net.amethystesmp.anpc.storage.VariableStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

public final class ANPCPlugin extends JavaPlugin {
    private NPCManager npcManager;
    private ScriptEngine scriptEngine;
    private VariableStore variableStore;

    @Override
    public void onEnable() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IllegalStateException("Impossible de créer le dossier du plugin.");
            }

            saveDefaultConfig();
            File npcFile = new File(getDataFolder(), "npc.yml");
            if (!npcFile.exists()) saveResource("npc.yml", false);

            Path dataPath = getDataFolder().toPath();
            Files.createDirectories(dataPath.resolve("scripts"));
            Files.createDirectories(dataPath.resolve("npcs"));

            if (getServer().getPluginManager().getPlugin("BetterModel") == null) {
                getLogger().severe("BetterModel 2.2.0 est requis pour afficher les NPC. Plugin désactivé.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            variableStore = new VariableStore(this);
            npcManager = new NPCManager(this);
            scriptEngine = new ScriptEngine(this, npcManager, variableStore);

            npcManager.load();
            scriptEngine.loadAll();

            PluginCommand command = getCommand("anpc");
            if (command == null) {
                throw new IllegalStateException("La commande /anpc est absente de plugin.yml.");
            }
            ANPCCommand executor = new ANPCCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);

            getServer().getPluginManager().registerEvents(scriptEngine, this);
            getLogger().info("AmethyysNPCs active : " + npcManager.size() + " NPC chargé(s).");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Impossible d'initialiser AmethyysNPCs.", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (scriptEngine != null) scriptEngine.shutdown();
        if (npcManager != null) npcManager.shutdown();
        if (variableStore != null) variableStore.close();
    }

    public NPCManager npcs() {
        return npcManager;
    }

    public ScriptEngine scripts() {
        return scriptEngine;
    }

    public VariableStore variables() {
        return variableStore;
    }
}
