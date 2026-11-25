package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Regression coverage for JDBC surface and metadata gaps found in the post-bulk audit. */
@Tag("live")
class JdbcGapsLiveTest {
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
    void statementDefaultMethodsAndFieldSizeWork() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertEquals("\"a b\"", statement.enquoteIdentifier("a b", false));
            assertEquals("\"abc\"", statement.enquoteIdentifier("abc", true));
            assertEquals("abc", statement.enquoteIdentifier("abc", false));
            assertEquals("'a''b'", statement.enquoteLiteral("a'b"));
            assertEquals("N'a''b'", statement.enquoteNCharLiteral("a'b"));
            assertTrue(statement.isSimpleIdentifier("abc"));
            assertFalse(statement.isSimpleIdentifier("a b"));

            assertEquals(32768, statement.getMaxFieldSize());
            statement.setMaxFieldSize(1024);
            assertEquals(1024, statement.getMaxFieldSize());
            assertThrows(SQLException.class, () -> statement.setMaxFieldSize(-1));
        }
    }

    @Test
    @Timeout(60)
    void resultSetUrlAndUnicodeStreamWork() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 'http://example.com/x'::VARCHAR(100) AS U, 'zażółć'::NVARCHAR(20) AS V")) {
            assertTrue(result.next());
            assertEquals(new URL("http://example.com/x"), result.getURL(1));
            assertEquals(new URL("http://example.com/x"), result.getURL("U"));
            assertEquals(new URL("http://example.com/x"), result.getObject(1, URL.class));
            assertEquals("zażółć", new String(result.getUnicodeStream(2).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertEquals("zażółć", new String(result.getUnicodeStream("V").readAllBytes(),
                    StandardCharsets.UTF_8));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 'not a url'::VARCHAR(20) AS U")) {
            assertTrue(result.next());
            SQLException error = assertThrows(SQLException.class, () -> result.getURL(1));
            assertEquals("22018", error.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void preparedUnicodeStreamAndUnsupportedSetObjectAreHandled() throws Exception {
        byte[] text = "zażółć".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?::NVARCHAR(20) AS V")) {
            statement.setUnicodeStream(1, new ByteArrayInputStream(text), text.length);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("zażółć", result.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?::NVARCHAR(20)")) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.setObject(1, new ByteArrayInputStream(new byte[0])));
            assertNotNull(error.getSQLState(), error.getMessage());
            assertThrows(SQLException.class,
                    () -> statement.setObject(1, new ByteArrayInputStream(new byte[0]), Types.BINARY));
        }
    }

    @Test
    @Timeout(120)
    void currentSchemaUrlPropertyIsApplied() throws Exception {
        assumeTrue("1".equals(LiveTestSupport.value("NZ_DEV_ALLOW_FIXTURE_DDL")),
                "NZ_DEV_ALLOW_FIXTURE_DDL=1 is required for the currentSchema test");
        String schema = "JAVA_GAP_SCHEMA_" + Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        try (Connection admin = LiveTestSupport.connect(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            Properties properties = LiveTestSupport.connectionProperties();
            try (Connection scoped = DriverManager.getConnection(
                    LiveTestSupport.url() + "?currentSchema=" + schema, properties)) {
                assertEquals(schema, scoped.getSchema());
            }
        } finally {
            try (Connection admin = LiveTestSupport.connect(); Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    @Timeout(60)
    void serializableIsolationIsAcceptedAndReported() throws Exception {
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            assertTrue(connection.getMetaData()
                    .supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
        } finally {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    @Test
    @Timeout(60)
    void metadataInformationalMethodsReturnServerFacts() throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        assertTrue(metadata.allProceduresAreCallable());
        assertTrue(metadata.allTablesAreSelectable());
        assertTrue(metadata.nullPlusNonNullIsNull());
        assertFalse(metadata.dataDefinitionCausesTransactionCommit());
        assertFalse(metadata.dataDefinitionIgnoredInTransactions());
        assertTrue(metadata.supportsMinimumSQLGrammar());
        assertTrue(metadata.supportsOpenStatementsAcrossCommit());
        assertTrue(metadata.supportsOpenStatementsAcrossRollback());
        assertTrue(metadata.supportsSchemasInProcedureCalls());
        assertTrue(metadata.supportsStoredFunctionsUsingCallSyntax());
        assertTrue(metadata.supportsCatalogsInPrivilegeDefinitions());
        assertTrue(metadata.nullsAreSortedLow());
        assertFalse(metadata.nullsAreSortedHigh());
        assertFalse(metadata.nullsAreSortedAtStart());
        assertFalse(metadata.nullsAreSortedAtEnd());
        assertEquals(0L, metadata.getMaxLogicalLobSize());
        assertEquals(metadata.getUserName().toUpperCase(Locale.ROOT), metadata.getUserName());
        assertFalse(metadata.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
    }

    @Test
    @Timeout(120)
    void bulkInsertFallsBackForDefaultKeyword() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE JAVA_GAP_DEFAULT IF EXISTS");
            statement.execute("CREATE TEMP TABLE JAVA_GAP_DEFAULT (A INT, B INT DEFAULT 42)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_GAP_DEFAULT (A, B) VALUES (?, DEFAULT)")) {
            insert.setInt(1, 1);
            insert.addBatch();
            insert.setInt(1, 2);
            insert.addBatch();
            // Netezza itself rejects DEFAULT in a VALUES tuple. The important part is that
            // the driver falls back to the original statement instead of rewriting it to
            // INSERT ... SELECT ... FROM EXTERNAL.
            SQLException error = assertThrows(SQLException.class, insert::executeBatch);
            assertFalse(error.getMessage().toUpperCase(Locale.ROOT).contains("EXTERNAL"),
                    error.getMessage());
        }
        assertEquals(0, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_GAP_DEFAULT"));
    }

    @Test
    @Timeout(120)
    void preparedBatchAppliesJdbcEscapesInBulkAndRowByRowModes() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE JAVA_GAP_ESCAPE_BULK IF EXISTS");
            statement.execute("CREATE TEMP TABLE JAVA_GAP_ESCAPE_BULK (V VARCHAR(20))");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_GAP_ESCAPE_BULK (V) VALUES ({fn UCASE(?)})")) {
            insert.setString(1, "abc");
            insert.addBatch();
            insert.setString(1, "def");
            insert.addBatch();
            assertArrayEquals(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO},
                    insert.executeBatch());
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT V FROM JAVA_GAP_ESCAPE_BULK ORDER BY V")) {
            assertTrue(rows.next());
            assertEquals("ABC", rows.getString(1));
            assertTrue(rows.next());
            assertEquals("DEF", rows.getString(1));
        }

        Properties properties = LiveTestSupport.connectionProperties();
        properties.setProperty("bulkInsert", "false");
        try (Connection plain = DriverManager.getConnection(LiveTestSupport.url(), properties);
             Statement statement = plain.createStatement()) {
            statement.execute("CREATE TEMP TABLE JAVA_GAP_ESCAPE_ROW (V VARCHAR(20))");
            try (PreparedStatement insert = plain.prepareStatement(
                    "INSERT INTO JAVA_GAP_ESCAPE_ROW (V) VALUES ({fn UCASE(?)})")) {
                insert.setString(1, "ghi");
                insert.addBatch();
                insert.setString(1, "jkl");
                insert.addBatch();
                assertArrayEquals(new int[]{1, 1}, insert.executeBatch());
            }
            try (ResultSet rows = statement.executeQuery("SELECT V FROM JAVA_GAP_ESCAPE_ROW ORDER BY V")) {
                assertTrue(rows.next());
                assertEquals("GHI", rows.getString(1));
                assertTrue(rows.next());
                assertEquals("JKL", rows.getString(1));
            }
        }
    }

    @Test
    @Timeout(120)
    void preparedStatementHandlesBackslashBeforeQuote() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 'C:\\' AS P, ?::INTEGER AS V")) {
            statement.setInt(1, 7);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("C:\\", result.getString(1));
                assertEquals(7, result.getInt(2));
            }
        }
    }

    @Test
    @Timeout(120)
    void bulkInsertSupportsCastsAndQuotedIdentifiers() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE \"JAVA_GAP_QUOTED\" IF EXISTS");
            statement.execute("CREATE TEMP TABLE \"JAVA_GAP_QUOTED\" (\"A\" INT, \"B\" VARCHAR(20))");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO \"JAVA_GAP_QUOTED\" (\"A\", \"B\") VALUES (?::INTEGER, ?::VARCHAR(20))")) {
            insert.setInt(1, 1);
            insert.setString(2, "x");
            insert.addBatch();
            insert.setInt(1, 2);
            insert.setString(2, "y");
            insert.addBatch();
            assertArrayEquals(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO},
                    insert.executeBatch());
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT \"A\", \"B\" FROM \"JAVA_GAP_QUOTED\" ORDER BY \"A\"")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("x", rows.getString(2));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertEquals("y", rows.getString(2));
        }
    }
}
