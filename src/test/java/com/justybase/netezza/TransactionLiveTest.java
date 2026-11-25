package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit transaction lifecycle and cross-connection visibility. */
@Tag("live")
class TransactionLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_TX IF EXISTS");
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_TX (ID INTEGER, V VARCHAR(10))");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void commitMakesChangesVisibleAndRollbackDiscardsThem() throws Exception {
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 1,'a'");
        connection.commit();
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 2,'b'");
        connection.rollback();
        assertEquals(1, countRows("JAVA_TX"));
        connection.setAutoCommit(true);
    }

    @Test
    @Timeout(60)
    void setAutoCommitTrueCommitsPendingWork() throws Exception {
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 1,'a'");
        connection.setAutoCommit(true);
        assertEquals(1, countRows("JAVA_TX"));
    }

    @Test
    @Timeout(60)
    void ddlInsideTransactionCanBeRolledBack() throws Exception {
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_TX_DDL (ID INTEGER)");
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX_DDL SELECT 1");
        connection.rollback();

        SQLException missing = assertThrows(SQLException.class, () -> countRows("JAVA_TX_DDL"));
        assertEquals("42P01", missing.getSQLState());

        connection.setAutoCommit(true);
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_TX_DDL (ID INTEGER)");
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX_DDL SELECT 2");
        connection.commit();
        assertEquals(1, countRows("JAVA_TX_DDL"));
        connection.setAutoCommit(true);
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_TX_DDL IF EXISTS");
    }

    @Test
    @Timeout(60)
    void errorInsideTransactionRollsBackCleanlyAndSessionSurvives() throws Exception {
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 1,'a'");
        SQLException error = assertThrows(SQLException.class,
                () -> LiveTestSupport.execute(connection, "SELECT * FROM JAVA_TX_MISSING"));
        assertEquals("42P01", error.getSQLState());
        connection.rollback();
        assertEquals(0, countRows("JAVA_TX"));

        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 2,'b'");
        connection.commit();
        assertEquals(1, countRows("JAVA_TX"));
        connection.setAutoCommit(true);
    }

    @Test
    @Timeout(60)
    void multipleStatementsParticipateInOneTransaction() throws Exception {
        connection.setAutoCommit(false);
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 1,'a'");
        LiveTestSupport.execute(connection, "INSERT INTO JAVA_TX SELECT 2,'b'");
        LiveTestSupport.execute(connection, "UPDATE JAVA_TX SET V='updated' WHERE ID=2");
        LiveTestSupport.execute(connection, "DELETE FROM JAVA_TX WHERE ID=2");
        assertEquals(1, countRows("JAVA_TX"));
        connection.rollback();
        assertEquals(0, countRows("JAVA_TX"));
        connection.setAutoCommit(true);
    }

    @Test
    @Timeout(60)
    void savepointsAreReportedAsUnsupportedAndDoNotRollbackWork() throws Exception {
        assertThrows(java.sql.SQLFeatureNotSupportedException.class, connection::setSavepoint);
        SQLException rollback = assertThrows(SQLException.class,
                () -> connection.rollback((Savepoint) null));
        assertEquals("0A000", rollback.getSQLState());
        SQLException release = assertThrows(SQLException.class,
                () -> connection.releaseSavepoint(null));
        assertEquals("0A000", release.getSQLState());
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void commitAndRollbackWithAutoCommitTrueAreHarmless() throws Exception {
        assertTrue(connection.getAutoCommit());
        connection.commit();
        connection.rollback();
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
    }

    @Test
    @Timeout(60)
    void closingConnectionWithPendingWorkRollsItBack() throws Exception {
        requireFixtureDdl();
        String table = LiveTestSupport.schema() + ".JAVA_TX_PERSIST";
        try {
            LiveTestSupport.execute(connection, "DROP TABLE " + table + " IF EXISTS");
            LiveTestSupport.execute(connection, "CREATE TABLE " + table + " (ID INTEGER)");
            try (Connection writer = LiveTestSupport.connect()) {
                writer.setAutoCommit(false);
                LiveTestSupport.execute(writer, "INSERT INTO " + table + " SELECT 1");
            }
            try (Connection reader = LiveTestSupport.connect()) {
                assertEquals(0, LiveTestSupport.queryInt(reader, "SELECT COUNT(*) FROM " + table));
            }
        } finally {
            LiveTestSupport.execute(connection, "DROP TABLE " + table + " IF EXISTS");
        }
    }

    @Test
    @Timeout(60)
    void committedWorkIsVisibleToAnotherConnection() throws Exception {
        requireFixtureDdl();
        String table = LiveTestSupport.schema() + ".JAVA_TX_PERSIST";
        try {
            LiveTestSupport.execute(connection, "DROP TABLE " + table + " IF EXISTS");
            LiveTestSupport.execute(connection, "CREATE TABLE " + table + " (ID INTEGER)");
            try (Connection writer = LiveTestSupport.connect()) {
                writer.setAutoCommit(false);
                LiveTestSupport.execute(writer, "INSERT INTO " + table + " SELECT 1");
                writer.commit();
            }
            try (Connection reader = LiveTestSupport.connect()) {
                assertEquals(1, LiveTestSupport.queryInt(reader, "SELECT COUNT(*) FROM " + table));
            }
        } finally {
            LiveTestSupport.execute(connection, "DROP TABLE " + table + " IF EXISTS");
        }
    }

    @Test
    @Timeout(60)
    void readOnlyIsAHintAndDoesNotBreakQueries() throws Exception {
        connection.setReadOnly(true);
        assertTrue(connection.isReadOnly());
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        connection.setReadOnly(false);
        assertFalse(connection.isReadOnly());
    }

    private int countRows(String table) throws SQLException {
        return LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM " + table);
    }

    private static void requireFixtureDdl() {
        assumeTrue("1".equals(LiveTestSupport.value("NZ_DEV_ALLOW_FIXTURE_DDL")),
                "NZ_DEV_ALLOW_FIXTURE_DDL=1 is required for permanent transaction fixtures");
    }
}
