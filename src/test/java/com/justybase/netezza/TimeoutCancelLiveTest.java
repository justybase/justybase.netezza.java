package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
class TimeoutCancelLiveTest {
    private static final String HEAVY_SQL = """
            SELECT F1.PRODUCTKEY, COUNT(DISTINCT (F1.PRODUCTKEY / F2.PRODUCTKEY))
            FROM (SELECT * FROM JUST_DATA..FACTPRODUCTINVENTORY LIMIT 30000) F1,
                 (SELECT * FROM JUST_DATA..FACTPRODUCTINVENTORY LIMIT 30000) F2
            GROUP BY 1
            LIMIT 500
            """;
    private static final String READER_SQL = """
            SELECT 2, F1.*
            FROM JUST_DATA..FACTPRODUCTINVENTORY F1
            JOIN JUST_DATA..DIMDATE D1 ON 1=1
            LIMIT 50000000
            """;

    private Connection connection;
    private long sessionId;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void openAndCreateSurvivor() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        sessionId = LiveTestSupport.queryLong(connection, "SELECT CURRENT_SID");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_TIMEOUT_SURVIVOR IF EXISTS");
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_TIMEOUT_SURVIVOR AS SELECT 1 AS ID");
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(45)
    void queryTimeoutCancelsServerWorkAndKeepsSessionUsable() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(4);
            long started = System.nanoTime();
            SQLException error = assertThrows(SQLException.class, () -> statement.execute(HEAVY_SQL));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(error instanceof SQLTimeoutException, "expected SQLTimeoutException, got " + error.getClass());
            assertEquals("57014", error.getSQLState());
            assertTrue(elapsed.toMillis() >= 3_500, "timeout fired too early: " + elapsed.toMillis() + " ms");
            assertTrue(elapsed.toMillis() < 12_000, "timeout took too long: " + elapsed.toMillis() + " ms");
        }
        awaitNoActiveCommand("SELECT F1.PRODUCTKEY%");
        assertConnectionAndTempTableSurvived();
    }

    @Test
    @Timeout(45)
    void cancelFromAnotherThreadInterruptsExecuteAndKeepsSession() throws Exception {
        try (Statement statement = connection.createStatement()) {
            CompletableFuture<Void> canceller = CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.SECONDS.sleep(1);
                    statement.cancel();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            long started = System.nanoTime();
            SQLException error = assertThrows(SQLException.class, () -> statement.execute(HEAVY_SQL));
            canceller.get(10, TimeUnit.SECONDS);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals("57014", error.getSQLState(), error.getMessage());
            assertTrue(elapsedMs < 10_000, "cancel took too long: " + elapsedMs + " ms");
        }
        awaitNoActiveCommand("SELECT F1.PRODUCTKEY%");
        assertConnectionAndTempTableSurvived();
    }

    @Test
    void reportsJdbcClientTypeToNetezza() throws Exception {
        String type = LiveTestSupport.queryString(connection,
                "SELECT TYPE FROM _V_SESSION WHERE ID = " + sessionId);
        assertTrue(type.toLowerCase().contains("jdbc"), "expected a JDBC client type, got " + type);
    }

    @Test
    void negativeQueryTimeoutIsRejected() throws Exception {
        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1));
            assertEquals("HY092", error.getSQLState());
        }
    }

    @Test
    @Timeout(30)
    void staleCancelAfterCompletedQueryDoesNotCancelNextQuery() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertTrue(statement.executeQuery("SELECT 1 AS VALUE").next());
            statement.cancel();
            try (ResultSet result = statement.executeQuery("SELECT 2 AS VALUE")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void cancelAnActiveReaderThenCloseWithinSla() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet reader = statement.executeQuery(READER_SQL)) {
            int rows = 0;
            while (rows < 1_000 && reader.next()) rows++;
            assertTrue(rows > 0);

            statement.cancel();
            long started = System.nanoTime();
            try {
                reader.close();
            } catch (SQLException expected) {
                assertEquals("57014", expected.getSQLState(), expected.getMessage());
            }
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMs < 2_000, "reader.close after cancel took " + elapsedMs + " ms");
        }
        awaitNoActiveCommand("SELECT 2, F1%");
        assertConnectionAndTempTableSurvived();
    }

    @Test
    @Timeout(60)
    void cancelAnActiveReaderAndContinueWithNextSqlQuickly() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(1_000);
            try (ResultSet reader = statement.executeQuery(READER_SQL)) {
                int rows = 0;
                while (rows < 1_000 && reader.next()) rows++;
                assertTrue(rows > 0, "reader should produce rows before cancel");

                long started = System.nanoTime();
                statement.cancel();
                try {
                    while (reader.next()) {
                        // Ignore rows that were already buffered; the cancel error follows.
                    }
                } catch (SQLException expected) {
                    assertEquals("57014", expected.getSQLState(), expected.getMessage());
                }
                long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                assertTrue(elapsedMs < 2_000, "cancel -> reader end took " + elapsedMs + " ms");
            }
        }
        long nextSqlStarted = System.nanoTime();
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT ID FROM JAVA_TIMEOUT_SURVIVOR"));
        long nextSqlMs = Duration.ofNanos(System.nanoTime() - nextSqlStarted).toMillis();
        assertTrue(nextSqlMs < 2_000, "next SQL after cancel took " + nextSqlMs + " ms");
        assertConnectionAndTempTableSurvived();
    }

    private void assertConnectionAndTempTableSurvived() throws Exception {
        assertTrue(connection.isValid(5));
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT ID FROM JAVA_TIMEOUT_SURVIVOR"));
    }

    private void awaitNoActiveCommand(String commandPattern) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            int active = LiveTestSupport.queryInt(connection,
                    "SELECT COUNT(*) FROM _V_SESSION WHERE ID = " + sessionId +
                            " AND STATUS = 'active' AND COMMAND LIKE '" + commandPattern.replace("'", "''") + "'");
            if (active == 0) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Server still reports an active command matching " + commandPattern);
    }
}
