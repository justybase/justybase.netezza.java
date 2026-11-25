package com.justybase.netezza.conformance;

import com.justybase.netezza.NetezzaDriver;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free Java implementation of conformance/runner-contract.md v1.
 * Run with: java -cp "target/test-classes;target/classes" com.justybase.netezza.conformance.Runner --profile smoke
 */
public final class Runner {
    private static final Set<String> INTEGER_TYPES = Set.of("BYTEINT", "INT1", "SMALLINT", "INT2", "INTEGER", "INT4", "BIGINT", "INT8");
    private static final Map<String, String> TYPE_CLASSES = Map.ofEntries(
            Map.entry("BOOLEAN", "bool"), Map.entry("BOOL", "bool"), Map.entry("BYTEINT", "int1"), Map.entry("INT1", "int1"),
            Map.entry("SMALLINT", "int2"), Map.entry("INT2", "int2"), Map.entry("INTEGER", "int4"), Map.entry("INT4", "int4"),
            Map.entry("BIGINT", "int8"), Map.entry("INT8", "int8"), Map.entry("REAL", "float4"), Map.entry("FLOAT4", "float4"),
            Map.entry("DOUBLE", "float8"), Map.entry("FLOAT", "float8"), Map.entry("FLOAT8", "float8"),
            Map.entry("NUMERIC", "numeric"), Map.entry("DECIMAL", "numeric"), Map.entry("CHAR", "char"), Map.entry("BPCHAR", "char"),
            Map.entry("VARCHAR", "text"), Map.entry("TEXT", "text"), Map.entry("NAME", "text"), Map.entry("NCHAR", "nchar"),
            Map.entry("NVARCHAR", "nvarchar"), Map.entry("DATE", "date"), Map.entry("TIME", "time"), Map.entry("TIMETZ", "timetz"),
            Map.entry("TIMESTAMP", "timestamp"), Map.entry("TIMESTAMPTZ", "timestamptz"), Map.entry("INTERVAL", "interval"),
            Map.entry("VARBINARY", "bytes"), Map.entry("BYTEA", "bytes"), Map.entry("JSON", "text"), Map.entry("JSONB", "text"),
            Map.entry("GEOMETRY", "text"), Map.entry("UUID", "text"), Map.entry("VECTOR", "text"));
    private static final Pattern INTERVAL_PART = Pattern.compile("([+-]?\\d+)\\s*(years?|yrs?|mons?|months?|days?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERVAL_TIME = Pattern.compile("([+-]?)(\\d{1,4}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?");

    private Runner() {}

    public static void main(String[] argv) {
        int exit;
        try {
            exit = run(argv);
        } catch (ConfigurationException e) {
            System.err.println("java runner: " + e.getMessage());
            exit = 2;
        } catch (Throwable e) {
            System.err.println("java runner crash: " + safeMessage(e));
            e.printStackTrace(System.err);
            exit = 3;
        }
        System.exit(exit);
    }

    private static int run(String[] argv) throws Exception {
        Arguments args = Arguments.parse(argv);
        loadEnv(args.env);
        Settings settings = Settings.fromEnvironment();
        Path casesDir = args.casesDir != null ? args.casesDir :
                Path.of("..", "conformance", "cases").toAbsolutePath().normalize();
        List<Map<String, Object>> cases = loadCases(casesDir);
        List<Map<String, Object>> selected = selectCases(cases, args.profile, args.caseFilters);
        if (selected.isEmpty()) throw new ConfigurationException("no conformance cases selected");
        Class.forName(NetezzaDriver.class.getName());

        Writer output = args.out == null
                ? new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
                : Files.newBufferedWriter(args.out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        boolean closeOutput = args.out != null;
        int failures = 0;
        Connection connection = null;
        try {
            connection = openConnection(settings, settings.sslmode, 0);
        } catch (SQLException e) {
            throw new ConfigurationException("connection failed (" + safeMessage(e) + ")");
        }
        try {
            for (Map<String, Object> item : selected) {
                String id = string(item.get("id"), "");
                Map<String, Object> result;
                if (isIntentionallyUnsupported(item)) {
                    result = skipped(id, item, unsupportedReason(item));
                } else if (requiresFixturePermission(item) && !settings.allowFixtureDdl) {
                    result = skipped(id, item, "mutating case requires NZ_DEV_ALLOW_FIXTURE_DDL=1");
                } else if (requiresFixturePermission(item) && !fixtureScopeIsSafe(item, settings.schema)) {
                    result = skipped(id, item, "mutating SQL is outside the configured conformance schema/TEMP scope");
                } else {
                    try {
                        if (connection.isClosed()) connection = openConnection(settings, settings.sslmode, 0);
                        result = executeCase(connection, settings, item);
                    } catch (SQLException e) {
                        result = unexpectedCaseError(id, item, e);
                        try { if (connection.isClosed()) connection = openConnection(settings, settings.sslmode, 0); }
                        catch (SQLException reconnect) { System.err.println("java runner: reconnect failed after " + id + ": " + safeMessage(reconnect)); }
                    }
                }
                output.write(Json.write(result));
                output.write('\n');
                output.flush();
                String status = string(result.get("status"), "error");
                if (args.verbose || !status.equals("pass")) System.err.println(status + " " + id);
                if (status.equals("error") || status.equals("fail")) {
                    Map<String, Object> expectedError = mapOrNull(map(item.get("expect")).get("error"));
                    boolean matchedExpectedError = status.equals("error") && expectedError != null &&
                            errorMatches(expectedError, map(result.get("error")));
                    if (!matchedExpectedError) {
                        failures++;
                        if (args.failFast) break;
                    }
                }
            }
        } finally {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
            if (closeOutput) output.close();
        }
        return failures == 0 ? 0 : 1;
    }

    private static Connection openConnection(Settings s, String sslmode, int socketTimeout) throws SQLException {
        String host = s.host.contains(":") && !s.host.startsWith("[") ? "[" + s.host + "]" : s.host;
        String url = "jdbc:netezza://" + host + ":" + s.port + "/" + encodePath(s.database);
        Properties p = new Properties();
        p.setProperty("user", s.user);
        p.setProperty("password", s.password);
        p.setProperty("sslmode", sslmode);
        p.setProperty("connectTimeout", Integer.toString(s.connectTimeout));
        p.setProperty("socketTimeout", Integer.toString(socketTimeout > 0 ? socketTimeout : s.socketTimeout));
        return DriverManager.getConnection(url, p);
    }

    private static Map<String, Object> executeCase(Connection connection, Settings settings, Map<String, Object> item) {
        String id = string(item.get("id"), "");
        long started = System.nanoTime();
        Map<String, Object> result = resultBase(id, item);
        SQLException failure = null;
        List<Map<String, Object>> sets = new ArrayList<>();
        try {
            runSetup(connection, item, settings.schema);
            if (item.containsKey("api")) {
                Map<String, Object> apiResult = executeApi(connection, settings, map(item.get("api")));
                if (apiResult != null) sets.add(apiResult);
            } else {
                String sql = renderSql(string(item.get("sql"), ""), settings.schema);
                List<Map<String, Object>> params = maps(item.get("params"));
                if (params.isEmpty() && Boolean.TRUE.equals(item.get("multi_statement"))) {
                    sets.addAll(executeMulti(connection, sql, item, settings));
                } else {
                    Double timeout = number(item.get("timeout"));
                    sets.addAll(executeSql(connection, sql, params, timeout, settings));
                }
            }
        } catch (SQLException e) {
            failure = e;
        } finally {
            try { runCleanup(connection, item, settings.schema, settings); }
            catch (SQLException e) { System.err.println("java runner cleanup warning [" + id + "]: " + safeMessage(e)); }
        }
        Map<String, Object> expectedError = mapOrNull(map(item.get("expect")).get("error"));
        if (failure != null) {
            Map<String, Object> error = classifyError(failure);
            result.put("error", error);
            result.put("status", "error");
        } else if (expectedError != null) {
            result.put("status", "fail");
            result.put("error", errorValue("other", "Expected an error, but the SQL completed", null));
        } else {
            result.put("status", "pass");
        }
        setPrimaryResults(result, sets);
        result.put("duration_ms", elapsedMillis(started));
        return result;
    }

    private static List<Map<String, Object>> executeMulti(Connection c, String sql, Map<String, Object> item, Settings settings) throws SQLException {
        List<Map<String, Object>> sets = new ArrayList<>();
        Double timeout = number(item.get("timeout"));
        for (String statement : splitSql(sql)) sets.addAll(executeSql(c, statement, List.of(), timeout, settings));
        return sets;
    }

    private static List<Map<String, Object>> executeSql(Connection c, String sql, List<Map<String, Object>> params,
                                                        Double timeout, Settings settings) throws SQLException {
        List<Map<String, Object>> sets = new ArrayList<>();
        if (!params.isEmpty()) {
            String preparedSql = dollarParametersToJdbc(sql);
            try (PreparedStatement statement = c.prepareStatement(preparedSql)) {
                for (int i = 0; i < params.size(); i++) bind(statement, i + 1, params.get(i));
                applyTimeout(c, statement, timeout);
                collectResults(statement, statement.execute(), sets);
            }
        } else {
            try (Statement statement = c.createStatement()) {
                applyTimeout(c, statement, timeout);
                collectResults(statement, statement.execute(sql), sets);
            }
        }
        return sets;
    }

    private static void collectResults(Statement statement, boolean hasRows, List<Map<String, Object>> sets) throws SQLException {
        boolean current = hasRows;
        while (true) {
            if (current) {
                try (ResultSet rs = statement.getResultSet()) {
                    if (rs != null) sets.add(readResultSet(rs));
                }
            } else if (statement.getUpdateCount() == -1) break;
            current = statement.getMoreResults();
        }
    }

    private static Map<String, Object> readResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String typeName = md.getColumnTypeName(i);
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", md.getColumnLabel(i));
            column.put("type_name", typeName);
            column.put("type_class", typeClass(typeName));
            columns.add(column);
        }
        List<Object> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                String typeClass = string(columns.get(i).get("type_class"), "unknown");
                // java.sql.Time has millisecond precision; JDBC 4.2 LocalTime preserves the full value.
                Object value = "time".equals(typeClass)
                        ? rs.getObject(i + 1, LocalTime.class)
                        : rs.getObject(i + 1);
                row.add(canonical(value, typeClass));
            }
            rows.add(row);
        }
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("columns", columns);
        set.put("rows", rows);
        set.put("row_count", rows.size());
        return set;
    }

    private static void bind(PreparedStatement statement, int index, Map<String, Object> parameter) throws SQLException {
        String type = string(parameter.get("type"), "").toLowerCase(Locale.ROOT);
        Object value = parameter.get("value");
        if (value == null) { statement.setNull(index, sqlType(type)); return; }
        switch (type) {
            case "numeric", "decimal" -> statement.setBigDecimal(index, new BigDecimal(value.toString()));
            case "bool", "boolean" -> statement.setBoolean(index, value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString()));
            case "bytes", "bytea", "varbinary" -> statement.setBytes(index, Base64.getDecoder().decode(value.toString()));
            case "int1", "byte", "int2", "short", "int4", "int", "integer", "int8", "long" ->
                    statement.setObject(index, new BigDecimal(value.toString()).longValueExact());
            default -> statement.setObject(index, value);
        }
    }

    private static int sqlType(String type) {
        return switch (type) {
            case "bool", "boolean" -> Types.BOOLEAN;
            case "int1", "byte" -> Types.TINYINT;
            case "int2", "short" -> Types.SMALLINT;
            case "int4", "int", "integer" -> Types.INTEGER;
            case "int8", "long" -> Types.BIGINT;
            case "numeric", "decimal" -> Types.NUMERIC;
            case "date" -> Types.DATE;
            case "time" -> Types.TIME;
            case "timestamp" -> Types.TIMESTAMP;
            case "bytes", "bytea", "varbinary" -> Types.VARBINARY;
            default -> Types.VARCHAR;
        };
    }

    private static void applyTimeout(Connection c, Statement statement, Double seconds) throws SQLException {
        if (seconds == null || seconds <= 0) return;
        statement.setQueryTimeout(Math.max(1, (int) Math.ceil(seconds)));
    }

    private static Map<String, Object> executeApi(Connection c, Settings settings, Map<String, Object> api) throws SQLException {
        String operation = string(api.get("operation"), "");
        if (operation.equals("tls_fail_closed")) {
            try (Connection ignored = openConnection(settings, "verify-full", 0)) {
                throw new SQLException("TLS verification unexpectedly succeeded", "08001");
            }
        }
        if (operation.equals("external_roundtrip")) return executeExternalRoundtrip(c, settings, api);
        if (!operation.equals("metadata_ddl")) return null;
        String schema = string(api.get("schema"), settings.schema).toUpperCase(Locale.ROOT);
        String table = string(api.get("table"), "");
        DatabaseMetaData md = c.getMetaData();
        Set<String> tables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (ResultSet rs = md.getTables(c.getCatalog(), schema, "%", new String[] {"TABLE", "VIEW"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        Set<String> columns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> ddlParts = new ArrayList<>();
        try (ResultSet rs = md.getColumns(c.getCatalog(), schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME"), typeName = rs.getString("TYPE_NAME");
                columns.add(name);
                ddlParts.add(name + " " + typeName);
            }
        }
        String distribution = "";
        String sql = "SELECT attname FROM _v_table_dist_map WHERE tablename = " + sqlLiteral(table) + " AND schema = " + sqlLiteral(schema) + " ORDER BY distattnum";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            if (!names.isEmpty()) distribution = " DISTRIBUTE ON (" + String.join(", ", names) + ")";
        }
        String ddl = String.join(" ", ddlParts) + distribution;
        List<List<Object>> rows = new ArrayList<>();
        for (Object value : list(api.get("tables"))) rows.add(Arrays.asList("tables", value.toString(), tables.contains(value.toString())));
        for (Object value : list(api.get("columns"))) rows.add(Arrays.asList("columns", value.toString(), columns.contains(value.toString())));
        for (Object value : list(api.get("fragments"))) rows.add(Arrays.asList("ddl", value.toString(), containsNormalized(ddl, value.toString())));
        List<Map<String, Object>> resultColumns = List.of(
                column("check", "VARCHAR", "text"), column("name", "VARCHAR", "text"), column("ok", "BOOLEAN", "bool"));
        return resultSet(resultColumns, rows);
    }

    private static Map<String, Object> executeExternalRoundtrip(Connection c, Settings settings, Map<String, Object> api) throws SQLException {
        String schema = string(api.get("schema"), settings.schema).toUpperCase(Locale.ROOT);
        String source = schema + ".CASE_EXT_SRC";
        String destination = schema + ".CASE_EXT_DST";
        Path directory;
        try {
            directory = Files.createTempDirectory("nz-conformance-external-");
        } catch (IOException e) {
            throw new SQLException("Could not create external-table temp directory", "HY000", e);
        }
        String sqlDirectory = directory.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        String externalFile = sqlDirectory + "/export.txt";
        try {
            String[] statements = {
                    "DROP TABLE " + source + " IF EXISTS",
                    "DROP TABLE " + destination + " IF EXISTS",
                    "CREATE TABLE " + source + " (ID INTEGER, LABEL VARCHAR(20)) DISTRIBUTE ON (ID)",
                    "INSERT INTO " + source + " VALUES (1, 'one')",
                    "INSERT INTO " + source + " VALUES (2, 'two')",
                    "CREATE EXTERNAL TABLE '" + externalFile + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                            sqlDirectory + "') AS SELECT * FROM " + source,
                    "CREATE TABLE " + destination + " (ID INTEGER, LABEL VARCHAR(20)) DISTRIBUTE ON (ID)",
                    "INSERT INTO " + destination + " SELECT * FROM EXTERNAL '" + externalFile +
                            "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + sqlDirectory + "')"
            };
            for (String statement : statements) executeSql(c, statement, List.of(), null, settings);
            List<Map<String, Object>> sets = executeSql(c,
                    "SELECT COUNT(*)::INTEGER AS row_count, COALESCE(SUM(ID), 0)::INTEGER AS checksum FROM " + destination,
                    List.of(), null, settings);
            if (sets.isEmpty()) throw new SQLException("External round-trip query returned no result set", "HY000");
            @SuppressWarnings("unchecked")
            List<List<Object>> rows = (List<List<Object>>) sets.get(0).get("rows");
            List<Map<String, Object>> columns = List.of(
                    column("row_count", "INTEGER", "int4"), column("checksum", "INTEGER", "int4"));
            return resultSet(columns, rows);
        } finally {
            for (String statement : new String[]{"DROP TABLE " + source + " IF EXISTS", "DROP TABLE " + destination + " IF EXISTS"}) {
                try { executeSql(c, statement, List.of(), null, settings); }
                catch (SQLException ignored) {}
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    private static void runSetup(Connection c, Map<String, Object> item, String schema) throws SQLException {
        for (Object raw : list(item.get("setup"))) {
            String sql = renderSql(raw.toString(), schema);
            try { executeSql(c, sql, List.of(), null, new Settings()); }
            catch (SQLException e) { if (!safeMessage(e).toLowerCase(Locale.ROOT).contains("does not exist")) throw e; }
        }
    }

    private static void runCleanup(Connection c, Map<String, Object> item, String schema, Settings settings) throws SQLException {
        if (list(item.get("cleanup")).isEmpty()) return;
        Connection cleanupConnection = c;
        boolean openedForCleanup = false;
        if (c.isClosed()) { cleanupConnection = openConnection(settings, settings.sslmode, 0); openedForCleanup = true; }
        try {
            for (Object raw : list(item.get("cleanup"))) {
                String sql = renderSql(raw.toString(), schema);
                try { executeSql(cleanupConnection, sql, List.of(), null, settings); }
                catch (SQLException e) { if (!safeMessage(e).toLowerCase(Locale.ROOT).contains("does not exist")) throw e; }
            }
        } finally {
            if (openedForCleanup) try { cleanupConnection.close(); } catch (SQLException ignored) {}
        }
    }

    private static Map<String, Object> unexpectedCaseError(String id, Map<String, Object> item, SQLException e) {
        Map<String, Object> result = resultBase(id, item);
        result.put("status", "error");
        result.put("error", classifyError(e));
        return result;
    }

    private static Map<String, Object> resultBase(String id, Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", id);
        result.put("status", "pass");
        result.put("duration_ms", 0);
        result.put("columns", List.of());
        result.put("rows", List.of());
        result.put("row_count", 0);
        result.put("result_sets", List.of());
        result.put("error", null);
        result.put("profile", string(item.get("profile"), "full"));
        return result;
    }

    private static Map<String, Object> skipped(String id, Map<String, Object> item, String reason) {
        Map<String, Object> result = resultBase(id, item);
        result.put("status", "skip");
        result.put("skip_reason", reason);
        return result;
    }

    private static void setPrimaryResults(Map<String, Object> result, List<Map<String, Object>> sets) {
        result.put("result_sets", sets);
        if (!sets.isEmpty()) {
            Map<String, Object> first = sets.get(0);
            result.put("columns", first.get("columns"));
            result.put("rows", first.get("rows"));
            result.put("row_count", first.get("row_count"));
        }
    }

    private static Map<String, Object> resultSet(List<Map<String, Object>> columns, List<List<Object>> rows) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("columns", columns);
        set.put("rows", rows);
        set.put("row_count", rows.size());
        return set;
    }

    private static Map<String, Object> column(String name, String typeName, String typeClass) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("type_name", typeName);
        column.put("type_class", typeClass);
        return column;
    }

    private static Map<String, Object> classifyError(SQLException e) {
        String message = safeMessage(e), state = e.getSQLState() == null ? "" : e.getSQLState();
        String lower = message.toLowerCase(Locale.ROOT);
        String kind;
        if (e instanceof SQLTimeoutException) kind = "timeout";
        else if (state.startsWith("08")) kind = lower.contains("timeout") || lower.contains("timed out") ? "timeout" : "connection";
        else if (state.startsWith("42")) {
            if (state.equals("42P01") || lower.contains("relation") || lower.contains("table") && lower.contains("does not exist")) kind = "relation";
            else if (state.equals("42703") || lower.contains("column") || lower.contains("attribute")) kind = "column";
            else if (state.equals("42883") || lower.contains("function") && lower.contains("does not exist")) kind = "function";
            else kind = "syntax";
        } else if (state.startsWith("22")) kind = "type";
        else if (state.startsWith("23")) kind = "constraint";
        else if (state.equals("42501") || lower.contains("permission") || lower.contains("denied")) kind = "permission";
        else if (state.startsWith("25")) kind = "transaction";
        else if (state.equals("57014") || lower.contains("cancel")) kind = "cancelled";
        else if (lower.contains("syntax") || lower.contains("expecting") || lower.contains("found \"")) kind = "syntax";
        else if (lower.contains("timeout") || lower.contains("timed out")) kind = "timeout";
        else if (lower.contains("does not exist") || lower.contains("not found")) {
            if (lower.contains("function")) kind = "function";
            else if (lower.contains("column") || lower.contains("attribute")) kind = "column";
            else kind = "relation";
        } else if (lower.contains("divide by 0") || lower.contains("can't parse") || lower.contains("invalid input") ||
                lower.contains("out of range") || lower.contains("cannot cast")) kind = "type";
        else if (lower.contains("not null") || lower.contains("cannot contain null") || lower.contains("constraint") || lower.contains("duplicate key")) kind = "constraint";
        else if (lower.contains("permission") || lower.contains("denied")) kind = "permission";
        else if (lower.contains("transaction")) kind = "transaction";
        else kind = "other";
        return errorValue(kind, message, e.getErrorCode() == 0 ? state : Integer.toString(e.getErrorCode()));
    }

    private static Map<String, Object> errorValue(String kind, String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("class", kind);
        error.put("message", message);
        error.put("code", code);
        return error;
    }

    private static boolean errorMatches(Map<String, Object> expected, Map<String, Object> actual) {
        if (!string(expected.get("class"), "other").equals(string(actual.get("class"), "other"))) return false;
        String contains = string(expected.get("message_contains"), "");
        return contains.isEmpty() || string(actual.get("message"), "").toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT));
    }

    private static String typeClass(String typeName) {
        if (typeName == null) return "unknown";
        String base = typeName.toUpperCase(Locale.ROOT).replaceAll("\\s*\\(.*$", "").trim();
        return TYPE_CLASSES.getOrDefault(base, "unknown");
    }

    private static Object canonical(Object value, String typeClass) {
        if (value == null) return null;
        if (INTEGER_TYPES.stream().anyMatch(t -> TYPE_CLASSES.get(t).equals(typeClass))) return value.toString();
        return switch (typeClass) {
            case "numeric" -> value instanceof BigDecimal d ? d.toPlainString() : value.toString();
            case "float4", "float8" -> {
                double d = ((Number) value).doubleValue();
                if (Double.isNaN(d)) yield "NaN";
                if (d == Double.POSITIVE_INFINITY) yield "Infinity";
                if (d == Double.NEGATIVE_INFINITY) yield "-Infinity";
                yield d;
            }
            case "bool" -> value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            case "bytes" -> value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString();
            case "time" -> canonicalTime(value);
            case "timetz" -> canonicalTimeZone(value);
            case "timestamp" -> canonicalTimestamp(value);
            case "interval" -> intervalValue(value.toString());
            default -> value.toString();
        };
    }

    private static String canonicalTime(Object value) {
        LocalTime time = value instanceof LocalTime t ? t : value instanceof Time t ? t.toLocalTime() : null;
        if (time == null) return value.toString();
        String base = time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return time.getNano() == 0 ? base : base + "." + String.format(Locale.ROOT, "%06d", time.getNano() / 1000);
    }

    private static String canonicalTimestamp(Object value) {
        LocalDateTime timestamp = value instanceof LocalDateTime t ? t : value instanceof Timestamp t ? t.toLocalDateTime() : null;
        if (timestamp == null) return value.toString();
        String base = timestamp.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
        return timestamp.getNano() == 0 ? base : base + "." + String.format(Locale.ROOT, "%06d", timestamp.getNano() / 1000);
    }

    private static String canonicalTimeZone(Object value) {
        String text = value.toString();
        if (text.matches(".*[+-]\\d{2}$")) return text + ":00";
        if (text.matches(".*[+-]\\d{4}$")) return text.substring(0, text.length() - 2) + ":" + text.substring(text.length() - 2);
        return text;
    }

    private static Object intervalValue(String text) {
        int months = 0, days = 0;
        long micros = 0;
        boolean matched = false;
        Matcher parts = INTERVAL_PART.matcher(text);
        StringBuffer remainder = new StringBuffer();
        while (parts.find()) {
            matched = true;
            int n = Integer.parseInt(parts.group(1));
            String unit = parts.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("y") || unit.startsWith("yr")) months += n * 12;
            else if (unit.startsWith("mon")) months += n;
            else days += n;
            parts.appendReplacement(remainder, " ");
        }
        parts.appendTail(remainder);
        Matcher time = INTERVAL_TIME.matcher(remainder);
        if (time.find()) {
            matched = true;
            long h = Long.parseLong(time.group(2)), m = Long.parseLong(time.group(3)), s = Long.parseLong(time.group(4));
            long fraction = time.group(5) == null ? 0 : Long.parseLong((time.group(5) + "000000").substring(0, 6));
            micros += ((h * 60 + m) * 60 + s) * 1_000_000 + fraction;
            if (time.group(1).equals("-")) micros = -micros;
        }
        if (!matched) return text;
        return Map.of("months", months, "days", days, "microseconds", micros);
    }

    private static boolean isIntentionallyUnsupported(Map<String, Object> item) {
        String operation = string(map(item.get("api")).get("operation"), "");
        return operation.equals("pool_reuse");
    }

    private static String unsupportedReason(Map<String, Object> item) {
        String operation = string(map(item.get("api")).get("operation"), "");
        return operation.equals("pool_reuse")
                ? "n/a: pooling is provided by external JDBC DataSource pool integrations"
                : "not implemented: external-table/file-transfer API is outside the current Java driver surface";
    }

    private static boolean requiresFixturePermission(Map<String, Object> item) {
        if ("external_roundtrip".equals(string(map(item.get("api")).get("operation"), ""))) return true;
        if (!list(item.get("setup")).isEmpty() || !list(item.get("cleanup")).isEmpty()) return true;
        String sql = string(item.get("sql"), "").toUpperCase(Locale.ROOT);
        return Pattern.compile("\\b(INSERT|UPDATE|DELETE|MERGE|CREATE|DROP|ALTER|TRUNCATE|BEGIN|COMMIT|ROLLBACK)\\b").matcher(sql).find();
    }

    private static boolean fixtureScopeIsSafe(Map<String, Object> item, String schema) {
        if ("external_roundtrip".equals(string(map(item.get("api")).get("operation"), ""))) return true;
        List<String> statements = new ArrayList<>();
        for (Object s : list(item.get("setup"))) statements.add(s.toString());
        statements.add(string(item.get("sql"), ""));
        for (Object s : list(item.get("cleanup"))) statements.add(s.toString());
        String upper = String.join("\n", statements).toUpperCase(Locale.ROOT);
        if (upper.contains("CONFORMANCE.") || upper.contains(schema.toUpperCase(Locale.ROOT) + ".")) return true;
        return upper.contains("TEMP") && (upper.contains("CASE_") || upper.contains("TEMP TABLE"));
    }

    private static String renderSql(String sql, String schema) {
        return schema.equalsIgnoreCase("CONFORMANCE") ? sql :
                sql.replaceAll("(?i)CONFORMANCE\\.", Matcher.quoteReplacement(schema.toUpperCase(Locale.ROOT) + "."))
                        .replace("'CONFORMANCE'", "'" + schema.toUpperCase(Locale.ROOT) + "'");
    }

    private static boolean containsNormalized(String text, String fragment) {
        return normalize(text).contains(normalize(fragment));
    }

    private static String normalize(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String sqlLiteral(String s) { return "'" + s.replace("'", "''") + "'"; }

    private static String dollarParametersToJdbc(String sql) throws SQLException {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        boolean line = false, block = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), n = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (line) { out.append(c); if (c == '\n') line = false; continue; }
            if (block) { out.append(c); if (c == '*' && n == '/') { out.append(n); i++; block = false; } continue; }
            if (quote != 0) {
                out.append(c);
                if (c == quote) { if (n == quote) { out.append(n); i++; } else quote = 0; }
                else if (c == '\\' && n != 0) { out.append(n); i++; }
                continue;
            }
            if (c == '-' && n == '-') { out.append(c).append(n); i++; line = true; continue; }
            if (c == '/' && n == '*') { out.append(c).append(n); i++; block = true; continue; }
            if (c == '\'' || c == '"') { quote = c; out.append(c); continue; }
            if (c == '$' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < sql.length() && Character.isDigit(sql.charAt(end))) end++;
                out.append('?'); i = end - 1; continue;
            }
            out.append(c);
        }
        if (quote != 0 || block) throw new SQLException("Unterminated SQL literal/comment", "42000");
        return out.toString();
    }

    private static List<String> splitSql(String sql) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean line = false, block = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), n = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (line) { current.append(c); if (c == '\n') line = false; continue; }
            if (block) { current.append(c); if (c == '*' && n == '/') { current.append(n); i++; block = false; } continue; }
            if (quote != 0) {
                current.append(c);
                if (c == quote) { if (n == quote) { current.append(n); i++; } else quote = 0; }
                else if (c == '\\' && n != 0) { current.append(n); i++; }
                continue;
            }
            if (c == '-' && n == '-') { current.append(c).append(n); i++; line = true; continue; }
            if (c == '/' && n == '*') { current.append(c).append(n); i++; block = true; continue; }
            if (c == '\'' || c == '"') { quote = c; current.append(c); continue; }
            if (c == ';') { if (!current.toString().isBlank()) parts.add(current.toString().trim()); current.setLength(0); }
            else current.append(c);
        }
        if (!current.toString().isBlank()) parts.add(current.toString().trim());
        return parts;
    }

    private static List<Map<String, Object>> loadCases(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new ConfigurationException("cases directory is unreadable: " + dir);
        List<Map<String, Object>> cases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (var paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Object parsed;
                try { parsed = Json.parse(Files.readString(path, StandardCharsets.UTF_8)); }
                catch (RuntimeException e) { throw new ConfigurationException("invalid case JSON in " + path.getFileName()); }
                Map<String, Object> document = map(parsed);
                if (!Objects.equals(number(document.get("kit_version")), 1.0) && !Objects.equals(document.get("kit_version"), BigDecimal.ONE))
                    throw new ConfigurationException("unsupported kit_version in " + path.getFileName());
                for (Object raw : list(document.get("cases"))) {
                    Map<String, Object> item = map(raw);
                    String id = string(item.get("id"), "");
                    if (id.isBlank() || !seen.add(id)) throw new ConfigurationException("invalid or duplicate case id " + id);
                    cases.add(item);
                }
            }
        }
        return cases;
    }

    private static List<Map<String, Object>> selectCases(List<Map<String, Object>> cases, String profile, List<String> filters) {
        return cases.stream().filter(item -> {
            String id = string(item.get("id"), "");
            if (!filters.isEmpty()) return filters.stream().anyMatch(id::contains);
            String caseProfile = string(item.get("profile"), "full");
            return profile.equals("full") ? caseProfile.equals("full") || caseProfile.equals("smoke") : caseProfile.equals(profile);
        }).toList();
    }

    private static void loadEnv(Path path) throws IOException {
        if (path == null) return;
        if (!Files.isRegularFile(path)) throw new ConfigurationException("dotenv file is unreadable: " + path);
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim(), value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            if (System.getenv(key) == null) System.setProperty("nz.env." + key, value);
        }
    }

    private static String env(String key) { return System.getenv(key) != null ? System.getenv(key) : System.getProperty("nz.env." + key); }
    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static String encodePath(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String safeMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static String string(Object o, String fallback) { return o == null ? fallback : o.toString(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap(); }
    private static Map<String, Object> mapOrNull(Object o) { return o instanceof Map<?, ?> ? map(o) : null; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    private static List<Map<String, Object>> maps(Object o) { return list(o).stream().map(Runner::map).toList(); }
    private static Double number(Object o) { return o instanceof Number n ? n.doubleValue() : null; }

    private static final class Settings {
        String host = "localhost", database = "", user = "", password = "", schema = "CONFORMANCE", sslmode = "prefer";
        int port = 5480, connectTimeout = 15000, socketTimeout = 30000;
        boolean allowFixtureDdl;
        static Settings fromEnvironment() {
            Settings s = new Settings();
            s.host = env("NZ_DEV_HOST");
            String db = env("NZ_DEV_DB");
            if (db == null || db.isBlank()) db = env("NZ_DEV_DATABASE");
            s.database = db;
            s.user = env("NZ_DEV_USER");
            s.password = env("NZ_DEV_PASSWORD");
            s.schema = Optional.ofNullable(env("NZ_CONFORMANCE_SCHEMA")).filter(v -> !v.isBlank()).orElse("CONFORMANCE").toUpperCase(Locale.ROOT);
            s.allowFixtureDdl = "1".equals(env("NZ_DEV_ALLOW_FIXTURE_DDL"));
            s.sslmode = Optional.ofNullable(env("NZ_DEV_SSLMODE")).filter(v -> !v.isBlank()).orElse("prefer");
            s.port = integerEnv("NZ_DEV_PORT", 5480);
            s.connectTimeout = integerEnv("NZ_CONFORMANCE_CONNECT_TIMEOUT", 15000);
            s.socketTimeout = integerEnv("NZ_CONFORMANCE_SOCKET_TIMEOUT", 30000);
            List<String> missing = new ArrayList<>();
            if (s.host == null || s.host.isBlank()) missing.add("NZ_DEV_HOST");
            if (s.user == null || s.user.isBlank()) missing.add("NZ_DEV_USER");
            if (s.password == null) missing.add("NZ_DEV_PASSWORD");
            if (s.database == null || s.database.isBlank()) missing.add("NZ_DEV_DB (or NZ_DEV_DATABASE)");
            if (!missing.isEmpty()) throw new ConfigurationException("missing configuration: " + String.join(", ", missing));
            return s;
        }
        private static int integerEnv(String key, int fallback) {
            String value = env(key);
            if (value == null || value.isBlank()) return fallback;
            try { return Integer.parseInt(value); }
            catch (NumberFormatException e) { throw new ConfigurationException("invalid " + key); }
        }
    }

    private static final class Arguments {
        String profile;
        final List<String> caseFilters = new ArrayList<>();
        Path casesDir, out, env;
        boolean failFast, verbose;
        static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--profile" -> parsed.profile = requiredValue(args, ++i, "--profile");
                    case "--case" -> parsed.caseFilters.add(requiredValue(args, ++i, "--case"));
                    case "--cases-dir" -> parsed.casesDir = Path.of(requiredValue(args, ++i, "--cases-dir"));
                    case "--out" -> parsed.out = Path.of(requiredValue(args, ++i, "--out"));
                    case "--env" -> parsed.env = Path.of(requiredValue(args, ++i, "--env"));
                    case "--fail-fast" -> parsed.failFast = true;
                    case "--verbose" -> parsed.verbose = true;
                    default -> throw new ConfigurationException("unknown argument " + args[i]);
                }
            }
            if (parsed.profile == null) throw new ConfigurationException("--profile is required");
            return parsed;
        }
        private static String requiredValue(String[] args, int i, String flag) {
            if (i >= args.length || args[i].startsWith("--")) throw new ConfigurationException("missing value for " + flag);
            return args[i];
        }
    }

    private static final class ConfigurationException extends RuntimeException {
        ConfigurationException(String message) { super(message); }
    }

    private static final class Json {
        static Object parse(String input) {
            Parser p = new Parser(input);
            Object value = p.value();
            p.whitespace();
            if (p.index != input.length()) throw new IllegalArgumentException("trailing JSON input");
            return value;
        }
        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            append(out, value);
            return out.toString();
        }
        private static void append(StringBuilder out, Object value) {
            if (value == null) out.append("null");
            else if (value instanceof String s) quote(out, s);
            else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) out.append(value);
            else if (value instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isFinite(d)) out.append(n);
                else quote(out, Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
            } else if (value instanceof Map<?, ?> m) {
                out.append('{'); boolean first = true;
                for (var e : m.entrySet()) { if (!first) out.append(','); first = false; quote(out, String.valueOf(e.getKey())); out.append(':'); append(out, e.getValue()); }
                out.append('}');
            } else if (value instanceof Iterable<?> items) {
                out.append('['); boolean first = true;
                for (Object item : items) { if (!first) out.append(','); first = false; append(out, item); }
                out.append(']');
            } else quote(out, value.toString());
        }
        private static void quote(StringBuilder out, String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
                }
            }
            out.append('"');
        }
        private static final class Parser {
            final String input; int index;
            Parser(String input) { this.input = input; }
            Object value() {
                whitespace();
                if (index >= input.length()) throw new IllegalArgumentException("missing JSON value");
                return switch (input.charAt(index)) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> keyword("true", Boolean.TRUE);
                    case 'f' -> keyword("false", Boolean.FALSE);
                    case 'n' -> keyword("null", null);
                    default -> number();
                };
            }
            Map<String, Object> object() {
                index++; whitespace(); Map<String, Object> result = new LinkedHashMap<>();
                if (take('}')) return result;
                while (true) {
                    whitespace(); if (index >= input.length() || input.charAt(index) != '"') throw new IllegalArgumentException("expected object key");
                    String key = string(); whitespace(); require(':'); result.put(key, value()); whitespace();
                    if (take('}')) return result;
                    require(',');
                }
            }
            List<Object> array() {
                index++; whitespace(); List<Object> result = new ArrayList<>();
                if (take(']')) return result;
                while (true) { result.add(value()); whitespace(); if (take(']')) return result; require(','); }
            }
            String string() {
                require('"'); StringBuilder out = new StringBuilder();
                while (index < input.length()) {
                    char c = input.charAt(index++);
                    if (c == '"') return out.toString();
                    if (c == '\\') {
                        if (index >= input.length()) throw new IllegalArgumentException("incomplete JSON escape");
                        char e = input.charAt(index++);
                        switch (e) {
                            case '"', '\\', '/' -> out.append(e);
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'u' -> {
                                if (index + 4 > input.length()) throw new IllegalArgumentException("incomplete unicode escape");
                                out.append((char) Integer.parseInt(input.substring(index, index + 4), 16)); index += 4;
                            }
                            default -> throw new IllegalArgumentException("invalid JSON escape");
                        }
                    } else { if (c < 0x20) throw new IllegalArgumentException("control character in JSON string"); out.append(c); }
                }
                throw new IllegalArgumentException("unterminated JSON string");
            }
            Object keyword(String word, Object value) {
                if (!input.startsWith(word, index)) throw new IllegalArgumentException("invalid JSON token");
                index += word.length(); return value;
            }
            BigDecimal number() {
                int start = index;
                if (take('-')) {}
                while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
                if (take('.')) while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
                if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                    index++; if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                    while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
                }
                if (start == index) throw new IllegalArgumentException("invalid JSON number");
                return new BigDecimal(input.substring(start, index));
            }
            void whitespace() { while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++; }
            boolean take(char c) { if (index < input.length() && input.charAt(index) == c) { index++; return true; } return false; }
            void require(char c) { if (!take(c)) throw new IllegalArgumentException("expected '" + c + "'"); }
        }
    }
}
