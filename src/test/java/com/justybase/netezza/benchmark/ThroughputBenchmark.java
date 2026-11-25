package com.justybase.netezza.benchmark;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Standalone read-throughput benchmark. It intentionally uses only JDBC APIs so the
 * same class can run against any JDBC driver in a separate JVM.
 *
 * <pre>
 * java -cp "target/test-classes;target/classes" \
 *      com.justybase.netezza.benchmark.ThroughputBenchmark --label justybase --iterations 5
 * </pre>
 */
public final class ThroughputBenchmark {
    private ThroughputBenchmark() {}

    public static void main(String[] args) throws Exception {
        String label = "driver";
        String driverClass = "org.netezza.Driver";
        String sql = "SELECT ROW_ID, DATE_KEY, ACCOUNT_KEY, AMOUNT, QUANTITY, LABEL, NOTE FROM CONFORMANCE.FACT200K";
        String mode = "typed";
        int warmup = 2;
        int iterations = 5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--label" -> label = args[++i];
                case "--driver" -> driverClass = args[++i];
                case "--sql" -> sql = args[++i];
                case "--mode" -> mode = args[++i];
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }

        String host = required("NZ_DEV_HOST");
        String port = value("NZ_DEV_PORT", "5480");
        String database = database();
        String user = required("NZ_DEV_USER");
        String password = value("NZ_DEV_PASSWORD", "");
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String url = "jdbc:netezza://" + authority + ":" + port + "/" + database;

        Class.forName(driverClass);
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);

        try (Connection connection = DriverManager.getConnection(url, properties)) {
            ResultSetShape shape = null;
            for (int i = 0; i < warmup; i++) shape = read(connection, sql, mode);
            List<Long> durations = new ArrayList<>();
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                ResultSetShape current = read(connection, sql, mode);
                long elapsed = System.nanoTime() - start;
                durations.add(elapsed);
                if (shape == null) shape = current;
                System.out.printf("%s mode=%s iteration=%d rows=%d millis=%.1f rows_per_second=%.0f%n",
                        label, mode, i + 1, current.rows, elapsed / 1_000_000.0,
                        current.rows / (elapsed / 1_000_000_000.0));
            }
            durations.sort(Long::compare);
            long median = durations.get(durations.size() / 2);
            long min = durations.get(0);
            long total = durations.stream().mapToLong(Long::longValue).sum();
            long expected = shape == null ? 0 : shape.rows;
            System.out.printf("%s mode=%s rows=%d median_millis=%.1f min_millis=%.1f mean_millis=%.1f median_rows_per_second=%.0f%n",
                    label, mode, expected, median / 1_000_000.0, min / 1_000_000.0,
                    total / 1_000_000.0 / durations.size(),
                    expected / (median / 1_000_000_000.0));
        }
    }

    private static ResultSetShape read(Connection connection, String sql, String mode) throws Exception {
        long rows = 0;
        long checksum = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows++;
                switch (mode) {
                    case "next" -> {
                    }
                    case "ints" -> {
                        checksum += result.getLong(1);
                        checksum += result.getInt(2);
                        checksum += result.getInt(3);
                        checksum += result.getInt(5);
                    }
                    case "object" -> {
                        for (int column = 1; column <= 7; column++) {
                            Object value = result.getObject(column);
                            if (value instanceof Number number) checksum += number.longValue();
                            else if (value != null) checksum += value.toString().length();
                        }
                    }
                    case "typed" -> {
                        checksum += result.getLong(1);
                        checksum += result.getInt(2);
                        checksum += result.getInt(3);
                        BigDecimal amount = result.getBigDecimal(4);
                        if (amount != null) checksum += amount.longValue();
                        checksum += result.getInt(5);
                        String label = result.getString(6);
                        if (label != null) checksum += label.length();
                        String note = result.getString(7);
                        if (note != null) checksum += note.length();
                    }
                    default -> throw new IllegalArgumentException("Unknown mode " + mode);
                }
            }
        }
        return new ResultSetShape(rows, checksum);
    }

    private record ResultSetShape(long rows, long checksum) {}

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String database() {
        String value = value("NZ_DEV_DB", null);
        return value != null ? value : required("NZ_DEV_DATABASE");
    }
}
