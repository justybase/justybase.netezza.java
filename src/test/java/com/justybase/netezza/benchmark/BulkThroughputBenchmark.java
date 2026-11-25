package com.justybase.netezza.benchmark;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Standalone bulk-insert throughput benchmark for prepared batches. It intentionally uses
 * only JDBC APIs so the same class can run against any JDBC driver in a separate JVM.
 *
 * <pre>
 * java -cp "target/test-classes;target/classes" \
 *      com.justybase.netezza.benchmark.BulkThroughputBenchmark --label justybase --rows 500
 * </pre>
 *
 * <p>The measured section covers {@code addBatch} and {@code executeBatch}; table creation,
 * {@code TRUNCATE}, row-count verification and cleanup are outside the timed region. The
 * benchmark creates and drops a table named {@code PERF_BULK_<LABEL>}.</p>
 */
public final class BulkThroughputBenchmark {
    private BulkThroughputBenchmark() {}

    public static void main(String[] args) throws Exception {
        String label = "driver";
        String driverClass = "org.netezza.Driver";
        int rows = 500;
        int warmup = 2;
        int iterations = 5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--driver" -> driverClass = args[++i];
                case "--rows" -> rows = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (rows < 1) throw new IllegalArgumentException("--rows must be positive");
        if (warmup < 0) throw new IllegalArgumentException("--warmup must not be negative");
        if (iterations < 1) throw new IllegalArgumentException("--iterations must be positive");

        String host = required("NZ_DEV_HOST");
        String port = value("NZ_DEV_PORT", "5480");
        String database = database();
        String user = required("NZ_DEV_USER");
        String password = value("NZ_DEV_PASSWORD", "");
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String url = "jdbc:netezza://" + authority + ":" + port + "/" + database;
        String table = "PERF_BULK_" + label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");

        Class.forName(driverClass);
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);

        try (Connection connection = DriverManager.getConnection(url, properties);
             Statement statement = connection.createStatement()) {
            drop(statement, table);
            statement.execute("CREATE TABLE " + table
                    + " (ID INTEGER, NAME VARCHAR(100), AMOUNT NUMERIC(12,2), FLAG BOOLEAN)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table + " (ID, NAME, AMOUNT, FLAG) VALUES (?, ?, ?, ?)")) {
                List<Long> durations = new ArrayList<>();
                for (int run = 0; run < warmup + iterations; run++) {
                    statement.execute("TRUNCATE TABLE " + table);
                    long start = System.nanoTime();
                    for (int row = 0; row < rows; row++) {
                        insert.setInt(1, row);
                        insert.setString(2, "name-" + row);
                        insert.setBigDecimal(3, new BigDecimal(row).movePointLeft(2));
                        insert.setBoolean(4, (row & 1) == 0);
                        insert.addBatch();
                    }
                    int[] counts = insert.executeBatch();
                    long elapsed = System.nanoTime() - start;
                    long count;
                    try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        result.next();
                        count = result.getLong(1);
                    }
                    if (count != rows) throw new IllegalStateException("Expected " + rows + " rows, found " + count);
                    if (counts.length != rows)
                        throw new IllegalStateException("Expected " + rows + " update counts, found " + counts.length);
                    if (run < warmup) continue;
                    durations.add(elapsed);
                    System.out.printf(Locale.ROOT,
                            "%s mode=bulk iteration=%d rows=%d millis=%.1f rows_per_second=%.0f%n",
                            label, run - warmup + 1, rows, elapsed / 1_000_000.0,
                            rows / (elapsed / 1_000_000_000.0));
                }
                durations.sort(Long::compare);
                long median = durations.get(durations.size() / 2);
                long min = durations.get(0);
                long total = durations.stream().mapToLong(Long::longValue).sum();
                System.out.printf(Locale.ROOT,
                        "%s mode=bulk rows=%d median_millis=%.1f min_millis=%.1f mean_millis=%.1f median_rows_per_second=%.0f%n",
                        label, rows, median / 1_000_000.0, min / 1_000_000.0,
                        total / 1_000_000.0 / durations.size(),
                        rows / (median / 1_000_000_000.0));
            } finally {
                drop(statement, table);
            }
        }
    }

    private static void drop(Statement statement, String table) {
        try {
            statement.execute("DROP TABLE " + table);
        } catch (SQLException ignored) {
            // The table does not exist on the first run.
        }
    }

    private static String database() {
        String database = System.getenv("NZ_DEV_DATABASE");
        return database == null || database.isBlank() ? required("NZ_DEV_DB") : database;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not set");
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
