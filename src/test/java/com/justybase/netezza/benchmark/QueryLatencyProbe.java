package com.justybase.netezza.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/** Diagnostic probe that reports where per-query latency is spent. */
public final class QueryLatencyProbe {
    private QueryLatencyProbe() {}

    public static void main(String[] args) throws Exception {
        String driverClass = "org.netezza.Driver";
        String sql = "SELECT COUNT(*) FROM CONFORMANCE.FACT200K";
        int warmup = 5;
        int iterations = 20;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--driver" -> driverClass = args[++i];
                case "--sql" -> sql = args[++i];
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        String host = required("NZ_DEV_HOST");
        String port = value("NZ_DEV_PORT", "5480");
        String database = value("NZ_DEV_DB", value("NZ_DEV_DATABASE", null));
        if (database == null) throw new IllegalStateException("NZ_DEV_DB is required");
        String url = "jdbc:netezza://" + host + ":" + port + "/" + database;
        Class.forName(driverClass);
        Properties properties = new Properties();
        properties.setProperty("user", required("NZ_DEV_USER"));
        properties.setProperty("password", value("NZ_DEV_PASSWORD", ""));
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            for (int i = 0; i < warmup + iterations; i++) {
                long t0 = System.nanoTime();
                Statement statement = connection.createStatement();
                long t1 = System.nanoTime();
                ResultSet result = statement.executeQuery(sql);
                long t2 = System.nanoTime();
                boolean any = result.next();
                long t3 = System.nanoTime();
                result.close();
                long t4 = System.nanoTime();
                statement.close();
                long t5 = System.nanoTime();
                if (i >= warmup) {
                    System.out.printf("query=%d any=%s create=%.2f exec=%.2f next=%.2f rsClose=%.2f stClose=%.2f total=%.2f%n",
                            i - warmup, any, ms(t1 - t0), ms(t2 - t1), ms(t3 - t2), ms(t4 - t3), ms(t5 - t4), ms(t5 - t0));
                }
            }
        }
    }

    private static double ms(long n) {
        return n / 1_000_000.0;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
