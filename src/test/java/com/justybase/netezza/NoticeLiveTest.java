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
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Server notices from NZPLSQL procedures, including delivery while the procedure is running. */
@Tag("live")
class NoticeLiveTest {
    private Connection connection;
    private String schema;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        assumeTrue("1".equals(LiveTestSupport.value("NZ_DEV_ALLOW_FIXTURE_DDL")),
                "NZ_DEV_ALLOW_FIXTURE_DDL=1 is required for notice procedure tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        schema = LiveTestSupport.schema();
        dropProcedure("JAVA_NOTICE_BASIC");
        dropProcedure("JAVA_NOTICE_TIMING");
        dropProcedure("JAVA_NOTICE_LISTENER_THROWS");
        dropProcedure("JAVA_NOTICE_FAILURE");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null && !connection.isClosed()) {
                dropProcedure("JAVA_NOTICE_BASIC");
                dropProcedure("JAVA_NOTICE_TIMING");
                dropProcedure("JAVA_NOTICE_LISTENER_THROWS");
                dropProcedure("JAVA_NOTICE_FAILURE");
            }
        } finally {
            if (connection != null) connection.close();
        }
    }

    @Test
    @Timeout(60)
    void noticesAreExposedOnStatementResultSetAndConnection() throws Exception {
        createProcedure("JAVA_NOTICE_BASIC",
                "BEGIN RAISE NOTICE 'notice-one'; RAISE NOTICE 'notice-two'; RETURN 1; END;");

        try (Statement statement = connection.createStatement()) {
            assertTrue(statement.execute("CALL " + schema + ".JAVA_NOTICE_BASIC()"));
            assertContains(statement.getWarnings(), "notice-one", "notice-two");

            try (ResultSet result = statement.getResultSet()) {
                assertNotNull(result, "CALL should return a result set");
                assertTrue(result.next());
                assertContains(result.getWarnings(), "notice-one", "notice-two");
            }

            statement.clearWarnings();
            assertNull(statement.getWarnings(), "clearWarnings must clear statement notices");
        }

        assertContains(connection.getWarnings(), "notice-one", "notice-two");
        connection.clearWarnings();
        assertNull(connection.getWarnings(), "clearWarnings must clear connection notices");

        LiveTestSupport.execute(connection, "SELECT 1");
        assertNull(connection.getWarnings(), "a later statement must not resurrect cleared notices");
    }

    @Test
    @Timeout(60)
    void listenerReceivesNoticesWhileProcedureIsStillRunning() throws Exception {
        createProcedure("JAVA_NOTICE_TIMING",
                "DECLARE i BIGINT; BEGIN " +
                        "RAISE NOTICE 'timing-before'; " +
                        "i := 0; WHILE i < 300000 LOOP i := i + 1; END LOOP; " +
                        "RAISE NOTICE 'timing-after'; " +
                        "RETURN 1; END;");

        NetezzaConnectionExt extended = connection.unwrap(NetezzaConnectionExt.class);
        List<String> notices = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch bothNotices = new CountDownLatch(2);
        AtomicBoolean executeReturned = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        extended.setNoticeListener(warning -> {
            notices.add(warning.getMessage());
            bothNotices.countDown();
        });

        try {
            Thread worker = new Thread(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CALL " + schema + ".JAVA_NOTICE_TIMING()");
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    executeReturned.set(true);
                }
            }, "netezza-notice-live-test");
            worker.start();

            assertTrue(bothNotices.await(10, TimeUnit.SECONDS),
                    "both notices should arrive while CALL is still executing");
            assertFalse(executeReturned.get(),
                    "the listener must fire before execute() returns, not only after the procedure ends");

            worker.join(TimeUnit.SECONDS.toMillis(45));
            assertFalse(worker.isAlive(), "CALL did not finish");
            assertNull(failure.get(), () -> "CALL failed: " + failure.get());
        } finally {
            extended.setNoticeListener(null);
        }

        assertEquals(2, notices.size());
        assertTrue(notices.get(0).contains("timing-before"), notices.toString());
        assertTrue(notices.get(1).contains("timing-after"), notices.toString());
    }

    @Test
    @Timeout(60)
    void listenerFailureDoesNotBreakProtocolOrWarningChain() throws Exception {
        createProcedure("JAVA_NOTICE_LISTENER_THROWS",
                "BEGIN RAISE NOTICE 'listener-throws'; RETURN 1; END;");

        NetezzaConnectionExt extended = connection.unwrap(NetezzaConnectionExt.class);
        extended.setNoticeListener(warning -> {
            throw new IllegalStateException("listener must not break the protocol");
        });
        try (Statement statement = connection.createStatement()) {
            assertTrue(statement.execute("CALL " + schema + ".JAVA_NOTICE_LISTENER_THROWS()"));
            assertContains(statement.getWarnings(), "listener-throws");
        } finally {
            extended.setNoticeListener(null);
        }

        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1::INTEGER"));
    }

    @Test
    @Timeout(60)
    void noticeRaisedBeforeProcedureFailureRemainsOnStatement() throws Exception {
        createProcedure("JAVA_NOTICE_FAILURE",
                "BEGIN RAISE NOTICE 'failure-notice'; RAISE EXCEPTION 'expected failure'; END;");

        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.execute("CALL " + schema + ".JAVA_NOTICE_FAILURE()"));
            assertTrue(error.getMessage().contains("expected failure"), error.getMessage());
            assertContains(statement.getWarnings(), "failure-notice");
        }
        assertContains(connection.getWarnings(), "failure-notice");

        connection.clearWarnings();
        try (Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.execute("CALL " + schema + ".JAVA_NOTICE_FAILURE()"));
            assertContains(statement.getWarnings(), "failure-notice");
        }
        assertContains(connection.getWarnings(), "failure-notice");

        connection.clearWarnings();
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1::INTEGER"));
    }

    private void createProcedure(String name, String body) throws SQLException {
        LiveTestSupport.execute(connection, "CREATE OR REPLACE PROCEDURE " + schema + "." + name +
                "() RETURNS INTEGER LANGUAGE NZPLSQL AS BEGIN_PROC " + body + " END_PROC");
    }

    private void dropProcedure(String name) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP PROCEDURE " + schema + "." + name + "()");
        } catch (SQLException ignored) {
            // The procedure may not exist yet; CREATE OR REPLACE handles the normal path.
        }
    }

    private static void assertContains(SQLWarning warning, String... fragments) {
        assertNotNull(warning, "expected a warning chain");
        List<String> messages = new ArrayList<>();
        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            messages.add(current.getMessage());
        }
        for (String fragment : fragments) {
            assertTrue(messages.stream().anyMatch(message -> message != null && message.contains(fragment)),
                    () -> "missing notice '" + fragment + "' in " + messages);
        }
    }
}
