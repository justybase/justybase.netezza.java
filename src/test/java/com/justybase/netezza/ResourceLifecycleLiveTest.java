package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Close/drain/abort behavior for connections, statements and result sets. */
@Tag("live")
class ResourceLifecycleLiveTest {
    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @Test
    @Timeout(120)
    void repeatedConnectionOpenCloseCyclesRemainHealthy() throws Exception {
        for (int i = 0; i < 20; i++) {
            try (Connection connection = LiveTestSupport.connect()) {
                assertTrue(connection.isValid(2));
                assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            }
        }
    }

    @Test
    @Timeout(60)
    void closingConnectionMarksOpenStatementAndResultClosed() throws Exception {
        Connection connection = LiveTestSupport.connect();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT DATEKEY FROM JUST_DATA..DIMDATE LIMIT 100");
        assertTrue(result.next());
        connection.close();

        assertTrue(connection.isClosed());
        assertTrue(statement.isClosed());
        assertTrue(result.isClosed());
        assertFalse(connection.isValid(1));
        SQLException next = assertThrows(SQLException.class, result::next);
        assertEquals("24000", next.getSQLState());
        SQLException execute = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT 1"));
        assertEquals("07000", execute.getSQLState());
    }

    @Test
    @Timeout(60)
    void abortMarksOpenResourcesClosed() throws Exception {
        Connection connection = LiveTestSupport.connect();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT 1 AS V");
        assertTrue(result.next());
        connection.abort(directExecutor());

        assertTrue(connection.isClosed());
        assertTrue(statement.isClosed());
        assertTrue(result.isClosed());
        assertThrows(SQLException.class, result::next);
    }

    @Test
    @Timeout(90)
    void manyStatementsAndResultSetsCanBeClosedIndividually() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            for (int i = 0; i < 50; i++) {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT " + i + " AS V")) {
                    assertTrue(result.next());
                    assertEquals(i, result.getInt(1));
                }
            }
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        }
    }

    @Test
    @Timeout(90)
    void earlyCloseOfLargeUnreadResultDrainsAndReusesSession() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT * FROM JUST_DATA..FACTPRODUCTINVENTORY LIMIT 5000")) {
                assertTrue(result.next());
            }
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    @Timeout(90)
    void statementCloseBeforeReadingAnyRowsDrainsTheResponse() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery(
                    "SELECT * FROM JUST_DATA..FACTPRODUCTINVENTORY LIMIT 5000");
            statement.close();
            assertTrue(statement.isClosed());
            assertTrue(result.isClosed());
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        }
    }

    @Test
    @Timeout(90)
    void unreadMultipleResultsAreDrainedOnStatementClose() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            Statement statement = connection.createStatement();
            assertTrue(statement.execute("SELECT 1 AS A; SELECT 2 AS B; SELECT 3 AS C"));
            try (ResultSet first = statement.getResultSet()) {
                assertTrue(first.next());
                assertEquals(1, first.getInt(1));
            }
            statement.close();
            assertEquals(4, LiveTestSupport.queryInt(connection, "SELECT 4"));
        }
    }

    @Test
    @Timeout(60)
    void failedQueriesDoNotLeakProtocolState() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            for (int i = 0; i < 10; i++) {
                final int attempt = i;
                SQLException error = assertThrows(SQLException.class,
                        () -> LiveTestSupport.queryInt(connection, "SELECT * FROM JAVA_MISSING_" + attempt));
                assertEquals("42P01", error.getSQLState());
                assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            }
        }
    }

    @Test
    @Timeout(60)
    void tryWithResourcesClosesAllResourcesInOrder() throws Exception {
        try (Connection connection = LiveTestSupport.connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 AS V")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
        try (Connection connection = LiveTestSupport.connect()) {
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        }
    }

    @Test
    @Timeout(60)
    void resultSetCloseDoesNotCloseItsStatement() throws Exception {
        try (Connection connection = LiveTestSupport.connect();
             Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery("SELECT 1 AS V");
            assertTrue(result.next());
            result.close();
            assertTrue(result.isClosed());
            assertFalse(statement.isClosed());
            try (ResultSet next = statement.executeQuery("SELECT 2 AS V")) {
                assertTrue(next.next());
                assertEquals(2, next.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void doubleCloseOfStatementAndConnectionIsIdempotent() throws Exception {
        Connection connection = LiveTestSupport.connect();
        Statement statement = connection.createStatement();
        statement.close();
        statement.close();
        assertTrue(statement.isClosed());
        connection.close();
        connection.close();
        assertTrue(connection.isClosed());
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
