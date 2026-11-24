package net.amethystesmp.anpc.storage;

import net.amethystesmp.anpc.ANPCPlugin;
import java.io.File;
import java.sql.*;
import java.util.Locale;

public final class VariableStore implements AutoCloseable {
    private final Connection connection;
    private final java.util.logging.Logger logger;
    public VariableStore(ANPCPlugin plugin) throws SQLException {
        logger = plugin.getLogger();
        File db = new File(plugin.getDataFolder(), "variables.db");
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS variables (scope TEXT NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL, value TEXT, PRIMARY KEY(scope, owner, name))");
        } catch (SQLException exception) {
            try {
                opened.close();
            } catch (SQLException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        connection = opened;
    }
    public synchronized void set(String scope,String owner,String name,Object value) {
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO variables(scope,owner,name,value) VALUES(?,?,?,?) ON CONFLICT(scope,owner,name) DO UPDATE SET value=excluded.value")) { p.setString(1,scope);p.setString(2,owner==null?"":owner);p.setString(3,name.toLowerCase(Locale.ROOT));p.setString(4,String.valueOf(value));p.executeUpdate(); } catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized boolean setIfAbsent(String scope, String owner, String name, Object value) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO variables(scope,owner,name,value) VALUES(?,?,?,?)")) {
            statement.setString(1, scope);
            statement.setString(2, owner == null ? "" : owner);
            statement.setString(3, name.toLowerCase(Locale.ROOT));
            statement.setString(4, String.valueOf(value));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public synchronized String get(String scope,String owner,String name,String fallback) {
        try(PreparedStatement p=connection.prepareStatement("SELECT value FROM variables WHERE scope=? AND owner=? AND name=?")){p.setString(1,scope);p.setString(2,owner==null?"":owner);p.setString(3,name.toLowerCase(Locale.ROOT));try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):fallback;}}catch(SQLException e){throw new IllegalStateException(e);}
    }
    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            // La fermeture ne doit pas masquer l'arrêt du plugin, mais l'erreur
            // doit rester visible pour diagnostiquer une éventuelle perte de données.
            logger.warning("Impossible de fermer proprement variables.db : " + exception.getMessage());
        }
    }
}
