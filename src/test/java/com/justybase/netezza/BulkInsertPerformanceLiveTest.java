package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in live performance tests for the prepared-statement bulk path.
 *
 * <p>They are intentionally not part of the regular LIVE suite. Run them with
 * {@code mvn -Pperf test}; results are printed and appended to
 * {@code target/performance/bulk-insert.csv}. The timing includes {@code addBatch}
 * and {@code executeBatch}, but excludes table setup and row-count verification.
 */
@Tag("performance")
class BulkInsertPerformanceLiveTest {
    private static final String TABLE = "JAVA_PERF_BULK";
    private static final String INSERT =
            "INSERT INTO " + TABLE + " (ID, NAME, AMOUNT, DT, TS, FLAG) VALUES (?, ?, ?, ?, ?, ?)";
    private static final Path RESULTS = Path.of("target", "performance", "bulk-insert.csv");
    private static final double MIN_SPEEDUP =
            Double.parseDouble(System.getProperty("nz.perf.minSpeedup", "2.0"));

    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        createTable(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(900)
    void bulkIsFasterThanRowByRow() throws Exception {
        int rows = 500;
        int width = 80;
        int rounds = 3;

        try (Connection rowByRow = rowByRowConnection()) {
            warmUp(connection, width);
            warmUp(rowByRow, width);

            List<Measurement> bulk = new ArrayList<>();
            List<Measurement> row = new ArrayList<>();
            for (int round = 0; round < rounds; round++) {
                bulk.add(measure(connection, rows, width, round, "bulk-vs-row", "bulk"));
                row.add(measure(rowByRow, rows, width, round, "bulk-vs-row", "row"));
            }

            double bulkMillis = medianMillis(bulk);
            double rowMillis = medianMillis(row);
            double speedup = rowMillis / bulkMillis;
            System.out.printf(
                    "BULK_PERF bulk_vs_row rows=%d rounds=%d bulk_median_ms=%.1f row_median_ms=%.1f speedup=%.1fx%n",
                    rows, rounds, bulkMillis, rowMillis, speedup);
            assertTrue(bulkMillis > 0, "bulk timing must be positive");
            assertTrue(rowMillis > 0, "row-by-row timing must be positive");
            assertTrue(speedup >= MIN_SPEEDUP,
                    "bulk insert speedup " + speedup + "x is below the required " + MIN_SPEEDUP + "x");
            verifyRows(connection, rows);
            verifyRows(rowByRow, rows);
        }
    }

    @Test
    @Timeout(900)
    void bulkScalesAcrossBatchSizes() throws Exception {
        warmUp(connection, 80);
        int width = 80;
        for (int rows : new int[]{10, 100, 1_000, 5_000}) {
            List<Measurement> runs = new ArrayList<>();
            for (int round = 0; round < 2; round++) {
                runs.add(measure(connection, rows, width, round, "batch-scaling", "bulk"));
            }
            Measurement measurement = medianMeasurement(runs);
            verifyRows(connection, rows);
            System.out.printf(
                    "BULK_PERF batch_scaling rows=%d millis=%.1f rows_per_second=%.0f per_row_us=%.1f%n",
                    rows, measurement.millis(), measurement.rowsPerSecond(),
                    measurement.millis() * 1_000.0 / rows);
        }
    }

    @Test
    @Timeout(900)
    void bulkSustainsWideUnicodeRows() throws Exception {
        warmUp(connection, 80);
        int rows = 2_000;
        int width = 400;
        List<Measurement> runs = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            runs.add(measure(connection, rows, width, round, "wide-unicode", "bulk"));
        }
        Measurement measurement = medianMeasurement(runs);
        verifyRows(connection, rows);
        System.out.printf(
                "BULK_PERF wide_unicode rows=%d width=%d millis=%.1f rows_per_second=%.0f name_mb_per_second=%.2f%n",
                rows, width, measurement.millis(), measurement.rowsPerSecond(),
                measurement.nameMbPerSecond());
        assertTrue(measurement.nameMbPerSecond() > 0);
    }

    @Test
    @Timeout(900)
    void bulkWorksInsideOneTransaction() throws Exception {
        warmUp(connection, 80);
        int rows = 2_000;
        connection.setAutoCommit(false);
        try {
            Measurement measurement = measure(connection, rows, 80, 0, "transaction", "bulk");
            long commitStart = System.nanoTime();
            connection.commit();
            double commitMillis = (System.nanoTime() - commitStart) / 1_000_000.0;
            verifyRows(connection, rows);
            System.out.printf(
                    "BULK_PERF transaction rows=%d execute_millis=%.1f commit_millis=%.1f rows_per_second=%.0f%n",
                    rows, measurement.millis(), commitMillis, measurement.rowsPerSecond());
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Connection rowByRowConnection() throws SQLException {
        Properties properties = LiveTestSupport.connectionProperties();
        properties.setProperty("bulkInsert", "false");
        Connection result = DriverManager.getConnection(LiveTestSupport.url(), properties);
        createTable(result);
        return result;
    }

    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TABLE + " IF EXISTS");
            statement.execute("CREATE TEMP TABLE " + TABLE + " (" +
                    "ID INT, NAME NVARCHAR(500), AMOUNT NUMERIC(12,2), DT DATE, TS TIMESTAMP, FLAG BOOLEAN)");
        }
    }

    private static void warmUp(Connection connection, int width) throws SQLException {
        measure(connection, 100, width, -1, "warmup", "warmup");
        truncate(connection);
    }

    private static Measurement measure(Connection connection, int rows, int width, int round,
                                       String scenario, String mode) throws SQLException {
        truncate(connection);
        try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            long start = System.nanoTime();
            long nameBytes = addRows(insert, rows, width);
            int[] counts = insert.executeBatch();
            long elapsed = System.nanoTime() - start;
            assertEquals(rows, counts.length);
            if ("bulk".equals(mode)) {
                for (int count : counts) assertEquals(Statement.SUCCESS_NO_INFO, count);
            }
            Measurement measurement = new Measurement(rows, width, elapsed, nameBytes);
            if (round >= 0) appendResult(scenario, mode, round, measurement);
            return measurement;
        }
    }

    private static long addRows(PreparedStatement insert, int rows, int width) throws SQLException {
        long nameBytes = 0;
        String suffix = "z".repeat(Math.max(1, width - 12));
        for (int i = 0; i < rows; i++) {
            String name = "row-" + i + "-" + suffix;
            nameBytes += name.getBytes(StandardCharsets.UTF_8).length;
            insert.setInt(1, i);
            insert.setString(2, name);
            insert.setBigDecimal(3, BigDecimal.valueOf(i, 2));
            insert.setObject(4, LocalDate.of(2024, 1, 1).plusDays(i % 365));
            insert.setObject(5, LocalDateTime.of(2024, 1, 1, 0, 0).plusSeconds(i));
            if (i % 7 == 0) insert.setNull(6, Types.BOOLEAN);
            else insert.setBoolean(6, (i & 1) == 0);
            insert.addBatch();
        }
        return nameBytes;
    }

    private static void truncate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + TABLE);
        }
    }

    private static void verifyRows(Connection connection, int expected) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*), SUM(ID) FROM " + TABLE)) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
            long sum = (long) expected * (expected - 1) / 2;
            assertEquals(sum, result.getLong(2));
        }
    }

    private static double medianMillis(List<Measurement> measurements) {
        List<Double> values = measurements.stream().map(Measurement::millis).sorted().toList();
        return values.get(values.size() / 2);
    }

    private static Measurement medianMeasurement(List<Measurement> measurements) {
        List<Measurement> sorted = measurements.stream()
                .sorted(java.util.Comparator.comparingDouble(Measurement::millis))
                .toList();
        return sorted.get(sorted.size() / 2);
    }

    private static synchronized void appendResult(String scenario, String mode, int round,
                                                  Measurement measurement) throws SQLException {
        try {
            Files.createDirectories(RESULTS.getParent());
            boolean header = !Files.exists(RESULTS) || Files.size(RESULTS) == 0;
            StringBuilder line = new StringBuilder();
            if (header) line.append("timestamp,java,scenario,mode,round,rows,width,millis,rows_per_second,name_mb_per_second\n");
            line.append(System.currentTimeMillis()).append(',')
                    .append(System.getProperty("java.version")).append(',')
                    .append(scenario).append(',')
                    .append(mode).append(',')
                    .append(round).append(',')
                    .append(measurement.rows()).append(',')
                    .append(measurement.width()).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", measurement.millis())).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.0f", measurement.rowsPerSecond())).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", measurement.nameMbPerSecond())).append('\n');
            Files.writeString(RESULTS, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new SQLException("Could not write performance results to " + RESULTS, "HY000", e);
        }
    }

    private record Measurement(int rows, int width, long nanos, long nameBytes) {
        double millis() {
            return nanos / 1_000_000.0;
        }

        double rowsPerSecond() {
            return rows / (nanos / 1_000_000_000.0);
        }

        double nameMbPerSecond() {
            return nameBytes / (1024.0 * 1024.0) / (nanos / 1_000_000_000.0);
        }
    }
}
