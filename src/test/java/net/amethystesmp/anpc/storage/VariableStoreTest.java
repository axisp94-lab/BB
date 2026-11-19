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
