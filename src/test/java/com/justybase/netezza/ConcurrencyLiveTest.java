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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Parallel sessions, timeout/cancel behavior and connection-level flags. */
@Tag("live")
class ConcurrencyLiveTest {
    private static final String READER_SQL = """
            SELECT 2, F1.*
            FROM JUST_DATA..FACTPRODUCTINVENTORY F1
            JOIN JUST_DATA..DIMDATE D1 ON 1=1
            LIMIT 50000000
            """;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @Test
    @Timeout(120)
    void independentConnectionsRunQueriesInParallel() throws Exception {
        int workers = 4;
        int iterations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                final int base = worker * 100;
                futures.add(pool.submit(() -> {
                    try (Connection connection = LiveTestSupport.connect()) {
                        int sum = 0;
                        for (int i = 0; i < iterations; i++) {
                            sum += LiveTestSupport.queryInt(connection, "SELECT " + (base + i));
                        }
                        return sum;
                    }
                }));
            }
            for (int worker = 0; worker < workers; worker++) {
                int expected = 0;
                for (int i = 0; i < iterations; i++) expected += worker * 100 + i;
                assertEquals(expected, futures.get(worker).get(90, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    void parallelSessionsGetDistinctServerSessionIds() throws Exception {
        int workers = 6;
        Set<Long> sessionIds = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    try (Connection connection = LiveTestSupport.connect()) {
                        sessionIds.add(LiveTestSupport.queryLong(connection, "SELECT CURRENT_SID"));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(workers, sessionIds.size(), sessionIds.toString());
    }

    @Test
    @Timeout(60)
    void networkTimeoutCanBeChangedAndRestored() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            assertEquals(30_000, connection.getNetworkTimeout());
            connection.setNetworkTimeout(directExecutor(), 8_000);
            assertEquals(8_000, connection.getNetworkTimeout());
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            connection.setNetworkTimeout(directExecutor(), 30_000);
            assertEquals(30_000, connection.getNetworkTimeout());
        }
    }

    @Test
    @Timeout(60)
    void isValidHandlesZeroTimeoutAndRejectsNegativeValues() throws Exception {
        try (Connection connection = LiveTestSupport.connect()) {
            assertTrue(connection.isValid(0));
            assertTrue(connection.isValid(5));
            SQLException error = assertThrows(SQLException.class, () -> connection.isValid(-1));
            assertEquals("HY092", error.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void staleCancelCallsDoNotAffectLaterQueries() throws Exception {
        try (Connection connection = LiveTestSupport.connect(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT 1 AS V")) {
                assertTrue(result.next());
            }
            statement.cancel();
            statement.cancel();
            assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT 2"));
            statement.close();
            assertTrue(statement.isClosed());
        }
    }

    @Test
    @Timeout(90)
    void queryTimeoutDuringResultReadingCancelsTheReaderAndKeepsSessionUsable() throws Exception {
        try (Connection connection = LiveTestSupport.connect(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (ResultSet result = statement.executeQuery(READER_SQL)) {
                int rows = 0;
                while (rows < 10 && result.next()) rows++;
                assertTrue(rows > 0, "reader should deliver rows before the timeout");
                Thread.sleep(4_000);
                SQLException error = null;
                try {
                    while (result.next()) {
                        // Consume the already buffered rows until cancellation arrives.
                    }
                } catch (SQLException e) {
                    error = e;
                }
                assertNotNull(error, "query timeout should cancel the active reader");
                assertEquals("57014", error.getSQLState(), error.getMessage());
            }
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
            assertTrue(connection.isValid(5));
        }
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
