package com.justybase.netezza.testutil;

import com.justybase.netezza.NetezzaDriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/** Shared setup for opt-in live tests. Credentials are read from NZ_DEV_* only. */
public final class LiveTestSupport {
    private LiveTestSupport() {}

    public static boolean configured() {
        return value("NZ_DEV_HOST") != null
                && value("NZ_DEV_USER") != null
                && System.getenv("NZ_DEV_PASSWORD") != null
                && database() != null;
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), connectionProperties());
    }

    /** JDBC URL built from NZ_DEV_* settings. */
    public static String url() {
        String host = required("NZ_DEV_HOST");
        int port = Integer.parseInt(Optional.ofNullable(value("NZ_DEV_PORT")).orElse("5480"));
        String database = database();
        if (database == null || database.isBlank()) throw new IllegalStateException("NZ_DEV_DB or NZ_DEV_DATABASE is required");
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:netezza://" + authority + ":" + port + "/" + database;
    }

    /** Connection properties built from NZ_DEV_* settings. */
    public static Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", required("NZ_DEV_USER"));
        properties.setProperty("password", System.getenv("NZ_DEV_PASSWORD"));
        properties.setProperty("sslmode", Optional.ofNullable(value("NZ_DEV_SSLMODE")).orElse("prefer"));
        properties.setProperty("connectTimeout", Optional.ofNullable(value("NZ_CONFORMANCE_CONNECT_TIMEOUT")).orElse("15000"));
        properties.setProperty("socketTimeout", Optional.ofNullable(value("NZ_CONFORMANCE_SOCKET_TIMEOUT")).orElse("30000"));
        return properties;
    }

    public static String schema() {
        return Optional.ofNullable(value("NZ_CONFORMANCE_SCHEMA"))
                .filter(v -> !v.isBlank())
                .orElse("CONFORMANCE")
                .toUpperCase(Locale.ROOT);
    }

    public static Path tempDir() {
        String configured = value("NZ_LOCAL_TMP_DIR");
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "justybase-netezza-java-tests")
                : Path.of(configured);
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create live-test temp directory " + path, e);
        }
        return path;
    }

    public static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                // Drain all results so the next statement starts from a clean protocol state.
            }
        }
    }

    public static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Query returned no rows: " + sql);
            return result.getInt(1);
        }
    }

    public static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Query returned no rows: " + sql);
            return result.getLong(1);
        }
    }

    public static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Query returned no rows: " + sql);
            return result.getString(1);
        }
    }

    public static String database() {
        return Optional.ofNullable(value("NZ_DEV_DB"))
                .filter(v -> !v.isBlank())
                .orElseGet(() -> value("NZ_DEV_DATABASE"));
    }

    public static String required(String name) {
        String value = value(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for live tests");
        return value;
    }

    public static String value(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    public static void loadDriver() throws ClassNotFoundException {
        Class.forName(NetezzaDriver.class.getName());
    }
}
