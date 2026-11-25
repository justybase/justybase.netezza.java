package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Broad JDBC session contract against a live appliance. */
@Tag("live")
class JdbcSessionContractLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_CONTRACT IF EXISTS");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_CONTRACT (ID INTEGER, NAME VARCHAR(20), AMOUNT NUMERIC(10,2), FLAG BOOLEAN, TS TIMESTAMP)");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_CONTRACT SELECT 1,'one',1.25,TRUE,TIMESTAMP '2024-01-02 03:04:05' " +
                        "UNION ALL SELECT 2,'two',2.50,FALSE,TIMESTAMP '2024-01-03 00:00:00' " +
                        "UNION ALL SELECT 3,NULL,NULL,NULL,NULL");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void executeUpdateAndLargeUpdateReturnAffectedRowCounts() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("CREATE TEMP TABLE JAVA_CONTRACT_UPD (ID INTEGER, V VARCHAR(10))"));
            assertEquals(1, statement.executeUpdate("INSERT INTO JAVA_CONTRACT_UPD SELECT 1, 'a'"));
            assertEquals(2, statement.executeUpdate("INSERT INTO JAVA_CONTRACT_UPD SELECT 2, 'b' UNION ALL SELECT 3, 'c'"));
            assertEquals(2, statement.executeUpdate("UPDATE JAVA_CONTRACT_UPD SET V='updated' WHERE ID>=2"));
            assertEquals(1, statement.executeUpdate("DELETE FROM JAVA_CONTRACT_UPD WHERE ID=3"));
            assertEquals(1, statement.executeLargeUpdate("INSERT INTO JAVA_CONTRACT_UPD SELECT 99, 'z'"));
            assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_CONTRACT_UPD"));
            assertEquals(0, statement.executeUpdate("DROP TABLE JAVA_CONTRACT_UPD"));
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void executeReportsResultSetsAndUpdateCountsConsistently() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertFalse(statement.execute("UPDATE JAVA_CONTRACT SET NAME='updated' WHERE ID=1"));
            assertEquals(1, statement.getUpdateCount());
            assertNull(statement.getResultSet());

            assertTrue(statement.execute("SELECT ID FROM JAVA_CONTRACT ORDER BY ID"));
            assertEquals(-1, statement.getUpdateCount());
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertFalse(statement.getMoreResults());
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    @Timeout(60)
    void wrongExecutionMethodFailsButLeavesTheSessionUsable() throws Exception {
        try (Statement statement = connection.createStatement()) {
            SQLException queryOnDml = assertThrows(SQLException.class,
                    () -> statement.executeQuery("UPDATE JAVA_CONTRACT SET NAME='x'"));
            assertEquals("02000", queryOnDml.getSQLState());

            assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 1"));
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    @Timeout(60)
    void closeOnCompletionClosesTheStatementAfterItsResultSet() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.closeOnCompletion();
            assertTrue(statement.isCloseOnCompletion());
            try (ResultSet result = statement.executeQuery("SELECT ID FROM JAVA_CONTRACT ORDER BY ID")) {
                assertTrue(result.next());
                assertFalse(statement.isClosed());
            }
            assertTrue(statement.isClosed());
        }
    }

    @Test
    @Timeout(60)
    void setSchemaUsesRealSqlAndRejectsUnknownSchemasWithoutLosingTheDefault() throws Exception {
        String original = connection.getSchema();
        assertNotNull(original);
        connection.setSchema(original);
        assertEquals(original, connection.getSchema());
        assertEquals(original, LiveTestSupport.queryString(connection, "SELECT CURRENT_SCHEMA"));

        connection.setSchema(null);
        assertEquals(original, connection.getSchema());

        SQLException blank = assertThrows(SQLException.class, () -> connection.setSchema(" "));
        assertEquals("HY092", blank.getSQLState());

        SQLException missing = assertThrows(SQLException.class,
                () -> connection.setSchema("NO_SUCH_SCHEMA_XYZ"));
        assertEquals("3F000", missing.getSQLState());
        assertEquals(original, connection.getSchema());
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void commonSqlErrorsExposeStandardStatesAndTheSessionRecovers() throws Exception {
        assertSqlState("42601", "SELEC 1");
        assertSqlState("42P01", "SELECT 1 FROM JAVA_CONTRACT_MISSING");
        assertSqlState("42703", "SELECT NO_SUCH_COLUMN_XYZ FROM JAVA_CONTRACT");
        assertSqlState("22012", "SELECT 1/0");
        assertSqlState("22P02", "SELECT CAST('abc' AS INTEGER)");

        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_CONTRACT_NN (ID INTEGER NOT NULL)");
        assertSqlState("23502", "INSERT INTO JAVA_CONTRACT_NN SELECT NULL");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_CONTRACT_NN");

        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        assertTrue(connection.isValid(5));
    }

    @Test
    @Timeout(60)
    void multipleStatementsAdvanceThroughResultSetsInOrder() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertTrue(statement.execute("SELECT 1 AS A; SELECT 2 AS B"));
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertTrue(statement.getMoreResults());
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            assertFalse(statement.getMoreResults());

            assertFalse(statement.execute(
                    "UPDATE JAVA_CONTRACT SET NAME='mixed' WHERE ID=1; SELECT COUNT(*) FROM JAVA_CONTRACT"));
            assertEquals(1, statement.getUpdateCount());
            assertTrue(statement.getMoreResults());
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
            }
            assertFalse(statement.getMoreResults());
        }
    }

    @Test
    @Timeout(60)
    void maxRowsStopsTheResultSetAndDrainsTheRestOfTheResponse() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(2);
            assertEquals(2, statement.getMaxRows());
            assertEquals(2L, statement.getLargeMaxRows());
            try (ResultSet result = statement.executeQuery("SELECT ID FROM JAVA_CONTRACT ORDER BY ID")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
                assertFalse(result.next());
            }
            assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_CONTRACT"));
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        }
    }

    @Test
    @Timeout(90)
    void closingAStatementDrainsUnreadRowsAndKeepsTheConnectionHealthy() throws Exception {
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT * FROM JUST_DATA..FACTPRODUCTINVENTORY LIMIT 5000");
        assertTrue(result.next());
        statement.close();
        assertTrue(result.isClosed());
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        assertTrue(connection.isValid(5));
    }

    @Test
    @Timeout(60)
    void connectionFlagsRoundTripAndUnsupportedModesFail() throws Exception {
        assertTrue(connection.getAutoCommit());
        connection.setAutoCommit(false);
        assertFalse(connection.getAutoCommit());
        connection.rollback();
        connection.setAutoCommit(true);

        connection.setReadOnly(true);
        assertTrue(connection.isReadOnly());
        connection.setReadOnly(false);

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
        connection.setTransactionIsolation(Connection.TRANSACTION_NONE);
        assertEquals(Connection.TRANSACTION_NONE, connection.getTransactionIsolation());
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertThrows(SQLException.class, () -> connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ));

        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, connection.getHoldability());
        connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThrows(SQLException.class, () -> connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));

        assertNotNull(connection.getTypeMap());
        assertTrue(connection.getTypeMap().isEmpty());
        connection.setTypeMap(new HashMap<String, Class<?>>());

        Properties clientInfo = connection.getClientInfo();
        assertNotNull(clientInfo);
        connection.setClientInfo("ApplicationName", "junit");
        assertEquals("junit", connection.getClientInfo("ApplicationName"));
        connection.clearWarnings();
        assertNull(connection.getWarnings());
        connection.clearWarnings();

        assertEquals("SELECT 1", connection.nativeSQL("SELECT 1"));
        assertEquals(connection.getCatalog(), connection.getCatalog());
        connection.setCatalog(connection.getCatalog());

        connection.setNetworkTimeout(directExecutor(), 12_345);
        assertEquals(12_345, connection.getNetworkTimeout());
        assertThrows(SQLException.class, () -> connection.setNetworkTimeout(directExecutor(), -1));
        assertThrows(SQLException.class, () -> connection.setNetworkTimeout(null, 10));
        connection.setNetworkTimeout(directExecutor(), 30_000);
    }

    @Test
    @Timeout(60)
    void statementMetadataFlagsAndGeneratedKeysFollowTheContract() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, statement.getResultSetType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, statement.getResultSetConcurrency());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, statement.getResultSetHoldability());
            assertEquals(0, statement.getQueryTimeout());
            statement.setQueryTimeout(3);
            assertEquals(3, statement.getQueryTimeout());
            assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1));

            statement.setFetchSize(128);
            assertEquals(128, statement.getFetchSize());
            statement.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertFalse(statement.isPoolable());
            statement.setPoolable(true);
            assertTrue(statement.isPoolable());
            assertSame(connection, statement.getConnection());
            assertNull(statement.getWarnings());
            statement.clearWarnings();

            assertFalse(statement.isCloseOnCompletion());
            statement.closeOnCompletion();
            assertTrue(statement.isCloseOnCompletion());

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertEquals(0, keys.getMetaData().getColumnCount());
                assertFalse(keys.next());
            }
            statement.setEscapeProcessing(true);
            statement.setCursorName("ignored");
        }
    }

    @Test
    @Timeout(60)
    void plainStatementBatchesExecuteInOrderAndCanBeCleared() throws Exception {
        try (Statement statement = connection.createStatement()) {
            LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_CONTRACT_BATCH (ID INTEGER)");
            statement.addBatch("INSERT INTO JAVA_CONTRACT_BATCH SELECT 1");
            statement.addBatch("INSERT INTO JAVA_CONTRACT_BATCH SELECT 2");
            assertArrayEquals(new int[]{1, 1}, statement.executeBatch());
            assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_CONTRACT_BATCH"));

            statement.addBatch("INSERT INTO JAVA_CONTRACT_BATCH SELECT 3");
            long[] large = statement.executeLargeBatch();
            assertEquals(1, large.length);
            assertEquals(1L, large[0]);
            assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_CONTRACT_BATCH"));

            statement.addBatch("INSERT INTO JAVA_CONTRACT_BATCH SELECT 4");
            statement.clearBatch();
            assertEquals(0, statement.executeBatch().length);
            assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_CONTRACT_BATCH"));
        }
    }

    @Test
    @Timeout(60)
    void closedConnectionAndAbortRejectFurtherWork() throws Exception {
        connection.close();
        assertTrue(connection.isClosed());
        assertFalse(connection.isValid(1));
        assertThrows(SQLException.class, connection::createStatement);
        connection.close();

        try (Connection second = LiveTestSupport.connect()) {
            second.abort(directExecutor());
            assertTrue(second.isClosed());
            assertFalse(second.isValid(1));
        }
    }

    @Test
    @Timeout(60)
    void unwrapAndWrapperForExposeTheJdbcObjects() throws Exception {
        assertSame(connection, connection.unwrap(Connection.class));
        assertTrue(connection.isWrapperFor(Connection.class));
        assertFalse(connection.isWrapperFor(Statement.class));
        try (Statement statement = connection.createStatement()) {
            assertSame(statement, statement.unwrap(Statement.class));
            assertTrue(statement.isWrapperFor(Statement.class));
            assertFalse(statement.isWrapperFor(Connection.class));
            try (ResultSet result = statement.executeQuery("SELECT 1 AS V")) {
                assertSame(result, result.unwrap(ResultSet.class));
                assertTrue(result.isWrapperFor(ResultSet.class));
                assertTrue(result.next());
                assertEquals(1, result.getInt("V"));
            }
        }
        assertTrue(connection.getMetaData().isWrapperFor(DatabaseMetaData.class));
    }

    @Test
    @Timeout(120)
    void setCatalogReconnectsAndFallsBackToTheCurrentDatabase() throws Exception {
        String original = connection.getCatalog();
        String other = null;
        String fallback = null;
        try (ResultSet catalogs = connection.getMetaData().getCatalogs()) {
            while (catalogs.next()) {
                String candidate = catalogs.getString("TABLE_CAT");
                if (candidate == null || candidate.equalsIgnoreCase(original)) continue;
                if (candidate.equalsIgnoreCase("JUST_DATA_2")) {
                    other = candidate;
                    break;
                }
                if (fallback == null && !candidate.equalsIgnoreCase("SYSTEM")) fallback = candidate;
            }
        }
        if (other == null) other = fallback;
        assumeTrue(other != null, "the appliance exposes only one user database");

        Statement stale = connection.createStatement();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try {
            connection.setCatalog(other);
            assertEquals(other, connection.getCatalog());
            assertEquals(other, LiveTestSupport.queryString(connection, "SELECT CURRENT_CATALOG"));
            assertFalse(connection.getAutoCommit());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
            assertThrows(SQLException.class, () -> stale.executeQuery("SELECT 1"));
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            connection.rollback();
            connection.setAutoCommit(true);

            connection.setCatalog(original);
            assertEquals(original, connection.getCatalog());
            assertEquals(original, LiveTestSupport.queryString(connection, "SELECT CURRENT_CATALOG"));

            connection.setCatalog("NO_SUCH_DATABASE_FOR_JDBC_TEST");
            assertEquals(original, connection.getCatalog());
            assertEquals(original, LiveTestSupport.queryString(connection, "SELECT CURRENT_CATALOG"));
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));

            connection.setCatalog(null);
            assertEquals(original, connection.getCatalog());
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        } finally {
            stale.close();
            if (!original.equalsIgnoreCase(connection.getCatalog())) connection.setCatalog(original);
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    private void assertSqlState(String expected, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
            assertEquals(expected, error.getSQLState(), sql + " -> " + error.getMessage());
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"), "session must remain usable after " + sql);
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
