package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Error mapping, batch failures and protocol/session recovery. */
@Tag("live")
class ErrorRecoveryLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void catalogErrorsUseStandardStatesAndDoNotPoisonTheSession() throws Exception {
        assertSqlState("42601", "SELEC 1");
        assertSqlState("42P01", "SELECT 1 FROM JAVA_ERR_MISSING");
        assertSqlState("42703", "SELECT NO_SUCH_COLUMN FROM (SELECT 1 AS ID) T");

        String original = connection.getSchema();
        SQLException schema = assertThrows(SQLException.class,
                () -> connection.setSchema("NO_SUCH_SCHEMA_XYZ"));
        assertEquals("3F000", schema.getSQLState());
        assertEquals(original, connection.getSchema());
        assertEquals(original, LiveTestSupport.queryString(connection, "SELECT CURRENT_SCHEMA"));
        assertSqlState("42P01", "SELECT 1 FROM JAVA_ERR_MISSING");
    }

    @Test
    @Timeout(60)
    void dataErrorsUseStandardStatesAndRecover() throws Exception {
        assertSqlState("22012", "SELECT 1/0");
        assertSqlState("22P02", "SELECT CAST('abc' AS INTEGER)");
        assertSqlState("22003", "SELECT 128::BYTEINT");

        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_ERR_NN (ID INTEGER NOT NULL)");
        assertSqlState("23502", "INSERT INTO JAVA_ERR_NN SELECT NULL");

        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_ERR_AMB (ID INTEGER)");
        assertSqlState("42702", "SELECT ID FROM JAVA_ERR_AMB A, JAVA_ERR_AMB B");

        LiveTestSupport.execute(connection, "DROP TABLE JAVA_ERR_NN");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_ERR_AMB");
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void errorMessagesKeepTheOffendingObjectNames() throws Exception {
        SQLException relation = assertThrows(SQLException.class,
                () -> execute("SELECT 1 FROM JAVA_ERR_MISSING"));
        assertNotNull(relation.getMessage());
        assertTrue(relation.getMessage().contains("JAVA_ERR_MISSING"), relation.getMessage());

        SQLException column = assertThrows(SQLException.class,
                () -> execute("SELECT NO_SUCH_COLUMN FROM (SELECT 1 AS ID) T"));
        assertNotNull(column.getMessage());
        assertTrue(column.getMessage().contains("NO_SUCH_COLUMN"), column.getMessage());

        SQLException syntax = assertThrows(SQLException.class, () -> execute("SELEC 1"));
        assertNotNull(syntax.getMessage());
        assertTrue(syntax.getMessage().toLowerCase().contains("found"), syntax.getMessage());
    }

    @Test
    @Timeout(60)
    void preparedStatementRecoversFromAnExecutionError() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CAST(? AS INTEGER) AS V")) {
            statement.setString(1, "abc");
            SQLException error = assertThrows(SQLException.class, statement::executeQuery);
            assertEquals("22P02", error.getSQLState());

            statement.setInt(1, 42);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void plainBatchFailureRaisesBatchUpdateExceptionWithCompletedCounts() throws Exception {
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_ERR_BATCH (ID INTEGER NOT NULL)");
        try (Statement statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO JAVA_ERR_BATCH SELECT 1");
            statement.addBatch("INSERT INTO JAVA_ERR_BATCH SELECT NULL");
            statement.addBatch("INSERT INTO JAVA_ERR_BATCH SELECT 3");

            BatchUpdateException error = assertThrows(BatchUpdateException.class, statement::executeBatch);
            assertArrayEquals(new int[]{1}, error.getUpdateCounts());
            assertEquals("23502", error.getSQLState());
            assertNotNull(error.getCause());
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_ERR_BATCH"));
            assertEquals(0, statement.executeBatch().length);
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_ERR_BATCH"));
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void preparedBatchFailureIsAtomicInBulkMode() throws Exception {
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_ERR_BATCH2 (ID INTEGER NOT NULL)");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO JAVA_ERR_BATCH2 VALUES (?)")) {
            statement.setInt(1, 1);
            statement.addBatch();
            statement.setNull(1, java.sql.Types.INTEGER);
            statement.addBatch();
            statement.setInt(1, 3);
            statement.addBatch();

            BatchUpdateException error = assertThrows(BatchUpdateException.class, statement::executeBatch);
            assertArrayEquals(new int[0], error.getUpdateCounts());
            assertEquals("23502", error.getSQLState());
            assertEquals(0, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_ERR_BATCH2"));
            assertEquals(0, statement.executeBatch().length);
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void replacingAnOpenResultWithANewStatementKeepsTheSessionUsable() throws Exception {
        try (Statement statement = connection.createStatement()) {
            ResultSet open = statement.executeQuery("SELECT 1 AS ID");
            assertTrue(open.next());
            ResultSet replacement = statement.executeQuery("SELECT 2 AS ID");
            assertTrue(open.isClosed());
            assertTrue(replacement.next());
            assertEquals(2, replacement.getInt(1));
            replacement.close();
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    private void assertSqlState(String expected, String sql) throws Exception {
        SQLException error = assertThrows(SQLException.class, () -> execute(sql), sql);
        assertEquals(expected, error.getSQLState(), sql + " -> " + error.getMessage());
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"), sql);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
