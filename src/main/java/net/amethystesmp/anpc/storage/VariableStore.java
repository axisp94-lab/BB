package net.amethystesmp.anpc.storage;

import net.amethystesmp.anpc.ANPCPlugin;

import java.io.File;
import java.sql.*;
import java.util.Locale;

public final class VariableStore implements AutoCloseable {
    private final Connection connection;

    public VariableStore(ANPCPlugin plugin) throws SQLException {
        File db = new File(plugin.getDataFolder(), "variables.db");
        File parent = db.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Impossible de créer le dossier de données.");
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS variables (
                    scope TEXT NOT NULL,
                    owner TEXT NOT NULL,
                    name TEXT NOT NULL,
                    value TEXT,
                    PRIMARY KEY(scope, owner, name)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS deleted_npc_variables (
                    npc_id TEXT NOT NULL,
                    world TEXT NOT NULL,
                    name TEXT NOT NULL,
                    value TEXT,
                    deleted_at INTEGER NOT NULL,
                    PRIMARY KEY(npc_id, world, name)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deleted_npc_variables_time ON deleted_npc_variables(deleted_at)");
            statement.executeUpdate("""
                INSERT INTO variables(scope,owner,name,value)
                SELECT 'global','',name,value FROM variables
                WHERE scope='global' AND owner='@'
                ON CONFLICT(scope,owner,name) DO NOTHING
                """);
            statement.executeUpdate("DELETE FROM variables WHERE scope='global' AND owner='@'");

        }
        purgeExpiredRecoveries();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String localOwner(String npcId, String worldName) {
        return (npcId == null ? "" : npcId.trim().toLowerCase(Locale.ROOT))
            + "@" + (worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized void set(String scope, String owner, String name, Object value) {
        String normalizedScope = normalize(scope);
        String normalizedOwner = normalize(owner);
        String normalizedName = normalize(name);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO variables(scope,owner,name,value) VALUES(?,?,?,?) " +
                "ON CONFLICT(scope,owner,name) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, normalizedScope);
            statement.setString(2, normalizedOwner);
            statement.setString(3, normalizedName);
            statement.setString(4, String.valueOf(value));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'enregistrer la variable " + name, exception);
        }
    }

    public synchronized String get(String scope, String owner, String name, String fallback) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM variables WHERE scope=? AND owner=? AND name=?")) {
            statement.setString(1, normalize(scope));
            statement.setString(2, normalize(owner));
            statement.setString(3, normalize(name));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : fallback;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de lire la variable " + name, exception);
        }
    }

    public synchronized void delete(String scope, String owner, String name) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM variables WHERE scope=? AND owner=? AND name=?")) {
            statement.setString(1, normalize(scope));
            statement.setString(2, normalize(owner));
            statement.setString(3, normalize(name));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de supprimer la variable " + name, exception);
        }
    }

    public synchronized void setLocal(String npcId, String worldName, String name, Object value) {
        set("local", localOwner(npcId, worldName), name, value);
    }

    public synchronized String getLocal(String npcId, String worldName, String name, String fallback) {
        String value = get("local", localOwner(npcId, worldName), name, null);
        // Compatibilité avec les variables locales créées avant l'isolation par monde.
        if (value != null) return value;\n        value = get("local", npcId, name, null);\n        return value != null ? value : get("npc", npcId, name, fallback);
    }

    public synchronized void setGlobal(String name, Object value) {
        set("global", "", name, value);
    }

    public synchronized String getGlobal(String name, String fallback) {
        return get("global", "", name, fallback);
    }

    public synchronized void archiveLocalVariables(String npcId, String worldName) {
        String id = normalize(npcId);
        String world = normalize(worldName);
        String owner = localOwner(id, world);
        long now = System.currentTimeMillis();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement archive = connection.prepareStatement("""
                    INSERT INTO deleted_npc_variables(npc_id,world,name,value,deleted_at)
                    SELECT ?, ?, name, value, ?
                    FROM variables
                    WHERE scope IN ('local','npc') AND owner IN (?, ?)
                    ON CONFLICT(npc_id,world,name) DO UPDATE SET
                        value=excluded.value, deleted_at=excluded.deleted_at
                    """);
                 PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM variables WHERE scope IN ('local','npc') AND owner IN (?, ?)")) {
                archive.setString(1, id);
                archive.setString(2, world);
                archive.setLong(3, now);
                archive.setString(4, owner);
                archive.setString(5, id);
                archive.executeUpdate();
                delete.setString(1, owner);
                delete.setString(2, id);
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw new IllegalStateException("Impossible d'archiver les variables locales de " + npcId, exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public synchronized boolean restoreLocalVariables(String npcId, String worldName) {
        String id = normalize(npcId);
        String world = normalize(worldName);
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        try {
            connection.setAutoCommit(false);
            int restored;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO variables(scope,owner,name,value)
                    SELECT 'local', ?, name, value
                    FROM deleted_npc_variables
                    WHERE npc_id=? AND world=? AND deleted_at>=?
                    ON CONFLICT(scope,owner,name) DO UPDATE SET value=excluded.value
                    """);
                 PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM deleted_npc_variables WHERE npc_id=? AND world=? AND deleted_at>=?");
                 PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM deleted_npc_variables WHERE npc_id=? AND world=?")) {
                insert.setString(1, localOwner(id, world));
                insert.setString(2, id);
                insert.setString(3, world);
                insert.setLong(4, cutoff);
                restored = insert.executeUpdate();
                count.setString(1, id);
                count.setString(2, world);
                count.setLong(3, cutoff);
                try (ResultSet result = count.executeQuery()) {
                    if (result.next()) restored = result.getInt(1);
                }
                delete.setString(1, id);
                delete.setString(2, world);
                delete.executeUpdate();
            }
            connection.commit();
            return restored > 0;
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw new IllegalStateException("Impossible de restaurer les variables locales de " + npcId, exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public synchronized void purgeExpiredRecoveries() {
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM deleted_npc_variables WHERE deleted_at<?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de nettoyer les sauvegardes NPC expirées.", exception);
        }
    }

    @Override
    public synchronized void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
