package net.amethystesmp.anpc.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class VariableStoreTest {
    @TempDir
    Path temporaryDirectory;

    private VariableStore openStore() throws Exception {
        return new VariableStore(temporaryDirectory.resolve("variables.db").toFile());
    }

    @Test
    void legacyGlobalOwnerIsMigratedToCanonicalGlobalStorage() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement create = connection.prepareStatement("""
                     CREATE TABLE variables (
                         scope TEXT NOT NULL,
                         owner TEXT NOT NULL,
                         name TEXT NOT NULL,
                         value TEXT,
                         PRIMARY KEY(scope, owner, name)
                     )
                     """)) {
            create.executeUpdate();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO variables(scope,owner,name,value) VALUES('global','@','season','autumn')")) {
                insert.executeUpdate();
            }
        }

        try (VariableStore store = new VariableStore(database.toFile())) {
            assertEquals("autumn", store.getGlobal("season", null));
            assertNull(store.get("global", "@", "season", null));
        }
    }

    @Test
    void localVariablesAreIsolatedByWorld() throws Exception {
        try (VariableStore store = openStore()) {
            store.setLocal("guardian", "world", "counter", 12);
            store.setLocal("guardian", "world_nether", "counter", 3);

            assertEquals("12", store.getLocal("guardian", "world", "counter", null));
            assertEquals("3", store.getLocal("guardian", "world_nether", "counter", null));
            assertEquals("fallback", store.getLocal("guardian", "world_the_end", "counter", "fallback"));
        }
    }

    @Test
    void legacyNpcScopedValuesRemainReadableAndCanBeRecovered() throws Exception {
        try (VariableStore store = openStore()) {
            store.set("npc", "guardian", "legacy-flag", true);
            assertEquals("true", store.getLocal("guardian", "world", "legacy-flag", null));

            store.archiveLocalVariables("guardian", "world");
            assertNull(store.getLocal("guardian", "world", "legacy-flag", null));

            assertTrue(store.restoreLocalVariables("guardian", "world"));
            assertEquals("true", store.getLocal("guardian", "world", "legacy-flag", null));
        }
    }

    @Test
    void worldSpecificValueWinsOverLegacyNpcValueDuringRecovery() throws Exception {
        try (VariableStore store = openStore()) {
            store.setLocal("guard", "world", "rank", "world-specific");
            store.set("local", "guard", "rank", "legacy");
            store.archiveLocalVariables("guard", "world");
            assertTrue(store.restoreLocalVariables("guard", "world"));
            assertEquals("world-specific", store.getLocal("guard", "world", "rank", null));
        }
    }

    @Test
    void archiveRestoresWorldLocalValuesWithoutTouchingGlobalOrSharedValues() throws Exception {
        try (VariableStore store = openStore()) {
            store.setLocal("merchant", "world", "coins", 45);
            store.setGlobal("server-season", "winter");
            store.set("shared", "merchant,guard", "stock", 7);

            store.archiveLocalVariables("merchant", "world");

            assertNull(store.getLocal("merchant", "world", "coins", null));
            assertEquals("winter", store.getGlobal("server-season", null));
            assertEquals("7", store.get("shared", "merchant,guard", "stock", null));

            assertTrue(store.restoreLocalVariables("merchant", "world"));
            assertEquals("45", store.getLocal("merchant", "world", "coins", null));
        }
    }

    @Test
    void allWorldVariablesCanBeArchivedAndRestoredWithoutLoadedWorldList() throws Exception {
        try (VariableStore store = openStore()) {
            store.setLocal("guard", "world", "coins", 12);
            store.setLocal("guard", "world_nether", "coins", 8);
            store.set("local", "guard", "legacy", "kept");

            store.archiveAllLocalVariables("guard");

            assertNull(store.getLocal("guard", "world", "coins", null));
            assertNull(store.getLocal("guard", "world_nether", "coins", null));
            assertTrue(store.restoreAllLocalVariables("guard"));

            assertEquals("12", store.getLocal("guard", "world", "coins", null));
            assertEquals("8", store.getLocal("guard", "world_nether", "coins", null));
            assertEquals("kept", store.getLocal("guard", "world_the_end", "legacy", null));
        }
    }

    @Test
    void ownerPrefixDeletionRemovesRuntimeStateForUnloadedWorlds() throws Exception {
        try (VariableStore store = openStore()) {
            store.set("npc-runtime", "guard@world", "health", 10);
            store.set("npc-runtime", "guard@world_nether", "health", 8);
            store.set("npc-runtime", "other@world", "health", 5);

            store.deleteOwnerAndPrefix("npc-runtime", "guard");

            assertNull(store.get("npc-runtime", "guard@world", "health", null));
            assertNull(store.get("npc-runtime", "guard@world_nether", "health", null));
            assertEquals("5", store.get("npc-runtime", "other@world", "health", null));
        }
    }

    @Test
    void expiredRecoveryRowsArePurged() throws Exception {
        try (VariableStore store = openStore()) {
            store.setLocal("guard", "world", "old-value", "expired");
            store.archiveLocalVariables("guard", "world");

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + temporaryDirectory.resolve("variables.db").toAbsolutePath());
                 PreparedStatement statement = connection.prepareStatement(
                    "UPDATE deleted_npc_variables SET deleted_at=? WHERE npc_id=? AND world=?")) {
                statement.setLong(1, System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
                statement.setString(2, "guard");
                statement.setString(3, "world");
                statement.executeUpdate();
            }

            store.purgeExpiredRecoveries();
            assertFalse(store.restoreLocalVariables("guard", "world"));
        }
    }
}
