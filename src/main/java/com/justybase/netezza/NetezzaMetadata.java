/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.sql.RowIdLifetime;
import java.util.*;
import java.util.logging.Logger;

/** JDBC DatabaseMetaData backed by Netezza's catalog views. */
final class NetezzaMetadata {
    /** Reported by {@code DatabaseMetaData.getDriverVersion}; keep in sync with pom.xml. */
    private static final String DRIVER_VERSION = "0.1.0";
    private static final String SYSTEM_SCHEMA_FILTER =
            " schema NOT IN ('DEFINITION_SCHEMA','INZA','NZ_QUERY_HISTORY') ";

    private NetezzaMetadata() {}

    static DatabaseMetaData create(NetezzaConnection c) {
        return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class}, (p, m, a) -> {
                    String n = m.getName();
                    if (n.equals("toString")) return "NetezzaDatabaseMetaData[" + c.database + "]";
                    if (n.equals("hashCode")) return System.identityHashCode(p);
                    if (n.equals("equals")) return p == a[0];
                    return switch (n) {
                        case "getConnection" -> c.proxy;
                        case "getURL" -> "jdbc:netezza://" + c.host + ":" + c.port + "/" + c.database;
                        case "getUserName" -> c.user.toUpperCase(Locale.ROOT);
                        case "getDatabaseProductName" -> "IBM Netezza";
                        case "getDatabaseProductVersion" -> c.databaseVersion();
                        case "getDriverName" -> "JustyBase NetezzaSQL JDBC Driver";
                        case "getDriverVersion" -> DRIVER_VERSION;
                        case "getDriverMajorVersion" -> 0;
                        case "getDriverMinorVersion" -> 1;
                        case "getJDBCMajorVersion" -> 4;
                        case "getJDBCMinorVersion" -> 3;
                        case "getDatabaseMajorVersion" -> c.databaseMajorVersion();
                        case "getDatabaseMinorVersion" -> c.databaseMinorVersion();
                        case "getIdentifierQuoteString" -> "\"";
                        case "getSearchStringEscape" -> "\\";
                        case "getCatalogSeparator" -> ".";
                        case "getCatalogTerm" -> "database";
                        case "getSchemaTerm" -> "schema";
                        case "getProcedureTerm" -> "procedure";
                        case "getSQLKeywords" -> "ANALYZE,COMMENT,CONTROL,DECODE,DISTRIBUTE,EXPRESS,GENERATE,GROOM,LOCK,ONLINE,ORGANIZE,RESET,SHOW,SYNONYM,ZONE";
                        case "getNumericFunctions" -> "ABS,CEIL,FLOOR,MOD,ROUND,TRUNC";
                        case "getStringFunctions" -> "LENGTH,LOWER,SUBSTR,TRIM,UPPER";
                        case "getTimeDateFunctions" -> "CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,DATE_PART,DATE_TRUNC,TIMEOFDAY";
                        case "getSystemFunctions" -> "CURRENT_CATALOG,CURRENT_SCHEMA,CURRENT_SID,CURRENT_USER";
                        case "getExtraNameCharacters" -> "$";
                        case "getSQLStateType" -> DatabaseMetaData.sqlStateSQL;
                        case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                        case "getRowIdLifetime" -> RowIdLifetime.ROWID_UNSUPPORTED;
                        case "supportsResultSetType" -> (int) a[0] == ResultSet.TYPE_FORWARD_ONLY;
                        case "supportsResultSetConcurrency" -> (int) a[0] == ResultSet.TYPE_FORWARD_ONLY
                                && (int) a[1] == ResultSet.CONCUR_READ_ONLY;
                        case "supportsTransactionIsolationLevel" -> (int) a[0] == Connection.TRANSACTION_READ_COMMITTED
                                || (int) a[0] == Connection.TRANSACTION_SERIALIZABLE;
                        case "supportsResultSetHoldability" -> (int) a[0] == ResultSet.CLOSE_CURSORS_AT_COMMIT;
                        case "getMaxConnections", "getMaxStatements", "getMaxTableNameLength", "getMaxColumnNameLength",
                                "getMaxSchemaNameLength", "getMaxCatalogNameLength", "getMaxProcedureNameLength",
                                "getMaxUserNameLength", "getMaxColumnsInTable" -> maxValue(n);
                        case "getMaxConcurrentResultSets" -> 1;
                        case "getMaxLogicalLobSize" -> 0L;
                        case "getParentLogger" -> Logger.getLogger("com.justybase.netezza");
                        case "getSchemas" -> a == null || a.length == 0
                                ? schemas(c, null, null) : schemas(c, (String) a[0], (String) a[1]);
                        case "getCatalogs" -> catalogs(c);
                        case "getTables" -> tables(c, a);
                        case "getColumns" -> columns(c, a);
                        case "getTableTypes" -> tableTypes(c);
                        case "getTypeInfo" -> typeInfo(c);
                        case "getPrimaryKeys" -> primaryKeys(c, a);
                        case "getImportedKeys" -> foreignKeys(c, a, false, false);
                        case "getExportedKeys" -> foreignKeys(c, a, true, false);
                        case "getCrossReference" -> foreignKeys(c, a, false, true);
                        case "getIndexInfo" -> indexInfo(c);
                        case "getProcedures" -> procedures(c, a);
                        case "getProcedureColumns" -> procedureColumns(c);
                        case "getFunctions" -> functions(c, a);
                        case "getFunctionColumns" -> functionColumns(c);
                        case "getTablePrivileges" -> tablePrivileges(c);
                        case "getColumnPrivileges" -> columnPrivileges(c);
                        case "getBestRowIdentifier" -> bestRowIdentifier(c);
                        case "getVersionColumns" -> versionColumns(c);
                        case "getClientInfoProperties" -> clientInfoProperties(c);
                        case "getPseudoColumns" -> pseudoColumns(c);
                        case "getUDTs" -> udts(c);
                        case "getAttributes" -> attributes(c);
                        case "getSuperTypes" -> superTypes(c);
                        case "getSuperTables" -> superTables(c);
                        case "unwrap" -> unwrap(c, p, a);
                        case "isWrapperFor" -> ((Class<?>) a[0]).isInstance(p);
                        default -> {
                            if (m.isDefault()) yield java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
                            yield booleanOrZero(n);
                        }
                    };
                });
    }

    private static Object unwrap(NetezzaConnection c, Object proxy, Object[] args) throws SQLException {
        Class<?> type = (Class<?>) args[0];
        if (type.isInstance(proxy)) return proxy;
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    /** All informational supports/stores/uses methods are answered without throwing. */
    private static Object booleanOrZero(String name) throws SQLException {
        return switch (name) {
            case "supportsTransactions", "supportsBatchUpdates", "supportsMultipleResultSets",
                 "supportsMultipleTransactions", "supportsCatalogsInDataManipulation",
                 "supportsSchemasInDataManipulation", "supportsCatalogsInTableDefinitions",
                 "supportsSchemasInTableDefinitions", "supportsCatalogsInPrivilegeDefinitions",
                 "supportsSchemasInPrivilegeDefinitions", "supportsCorrelatedSubqueries",
                 "supportsSubqueriesInComparisons", "supportsSubqueriesInExists", "supportsSubqueriesInIns",
                 "supportsSubqueriesInQuantifieds", "supportsUnion", "supportsUnionAll",
                 "supportsOuterJoins", "supportsFullOuterJoins", "supportsLimitedOuterJoins",
                 "supportsOrderByUnrelated", "supportsGroupBy", "supportsGroupByUnrelated",
                 "supportsGroupByBeyondSelect", "supportsColumnAliasing", "supportsExpressionsInOrderBy",
                 "supportsLikeEscapeClause", "supportsNonNullableColumns", "supportsAlterTableWithAddColumn",
                 "supportsAlterTableWithDropColumn", "supportsTableCorrelationNames",
                 "supportsDifferentTableCorrelationNames", "supportsANSI92EntryLevelSQL",
                 "supportsCoreSQLGrammar", "supportsExtendedSQLGrammar",
                 "supportsIntegrityEnhancementFacility",
                 "supportsDataDefinitionAndDataManipulationTransactions",
                 "supportsMixedCaseQuotedIdentifiers", "storesMixedCaseQuotedIdentifiers",
                 "supportsStoredProcedures", "supportsMinimumSQLGrammar",
                 "supportsOpenStatementsAcrossCommit", "supportsOpenStatementsAcrossRollback",
                 "supportsSchemasInProcedureCalls", "supportsStoredFunctionsUsingCallSyntax",
                 "isCatalogAtStart", "allProceduresAreCallable", "allTablesAreSelectable",
                 "nullPlusNonNullIsNull", "nullsAreSortedLow", "storesUpperCaseIdentifiers",
                 "autoCommitFailureClosesAllResultSets" -> true;
            case "supportsSavepoints", "supportsGetGeneratedKeys", "supportsNamedParameters",
                 "supportsOpenCursorsAcrossCommit", "supportsOpenCursorsAcrossRollback",
                 "supportsConvert", "supportsRefCursors", "supportsSharding",
                 "supportsMultipleOpenResults", "doesMaxRowSizeIncludeBlobs",
                 "generatedKeyAlwaysReturned", "locatorsUpdateCopy",
                 "dataDefinitionCausesTransactionCommit", "dataDefinitionIgnoredInTransactions",
                 "deletesAreDetected", "insertsAreDetected", "updatesAreDetected",
                 "othersDeletesAreVisible", "othersInsertsAreVisible", "othersUpdatesAreVisible",
                 "ownDeletesAreVisible", "ownInsertsAreVisible", "ownUpdatesAreVisible",
                 "nullsAreSortedHigh", "nullsAreSortedAtStart", "nullsAreSortedAtEnd" -> false;
            default -> {
                if (name.startsWith("supports") || name.startsWith("stores") || name.startsWith("uses")
                        || name.startsWith("is") || name.startsWith("nullsAreSorted")) yield false;
                if (name.startsWith("getMax")) yield maxValue(name);
                throw new SQLFeatureNotSupportedException("DatabaseMetaData." + name + " is not implemented", "0A000");
            }
        };
    }

    /** Known Netezza identifier limits; unknown or unlimited values remain 0 as JDBC requires. */
    private static int maxValue(String name) {
        return switch (name) {
            case "getMaxTableNameLength", "getMaxColumnNameLength", "getMaxSchemaNameLength",
                    "getMaxCatalogNameLength", "getMaxProcedureNameLength", "getMaxUserNameLength" -> 128;
            case "getMaxColumnsInTable" -> 1600;
            case "getMaxConcurrentResultSets" -> 1;
            default -> 0;
        };
    }

    private static ResultSet schemas(NetezzaConnection c, String catalog, String pattern) throws SQLException {
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptySchemas();
        String where = pattern == null || pattern.isEmpty() ? "" : " WHERE schema LIKE " + lit(pattern);
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, "SELECT schema FROM _v_schema" + where + " ORDER BY schema")) {
            rows.add(Arrays.asList(text(row, 0), c.database));
        }
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_SCHEM", 128), MetadataResultSets.text("TABLE_CATALOG", 128)), rows);
    }

    private static ResultSet emptySchemas() throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_SCHEM", 128), MetadataResultSets.text("TABLE_CATALOG", 128)), List.of());
    }

    private static ResultSet catalogs(NetezzaConnection c) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, "SELECT database FROM _v_database ORDER BY database"))
            rows.add(List.of(text(row, 0)));
        return MetadataResultSets.create(List.of(MetadataResultSets.text("TABLE_CAT", 128)), rows);
    }

    private static ResultSet tables(NetezzaConnection c, Object[] a) throws SQLException {
        String catalog = (String) a[0], schema = (String) a[1], pattern = (String) a[2];
        String[] types = (String[]) a[3];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyTables();
        if (types != null && types.length == 0) return emptyTables();
        // Netezza stores tables, views and external tables in separate catalog views.
        StringBuilder sql = new StringBuilder(
                "SELECT schema, tablename, objtype FROM _v_table WHERE tablename IS NOT NULL AND "
                        + SYSTEM_SCHEMA_FILTER + " AND objtype NOT IN ('SYSTEM TABLE','MANAGEMENT TABLE')");
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND tablename LIKE ").append(lit(pattern));
        sql.append(" UNION ALL SELECT schema, viewname, objtype FROM _v_view WHERE viewname IS NOT NULL AND ")
                .append(SYSTEM_SCHEMA_FILTER).append(" AND objtype NOT IN ('SYSTEM VIEW','MANAGEMENT VIEW')");
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND viewname LIKE ").append(lit(pattern));
        sql.append(" UNION ALL SELECT schema, tablename, 'EXTERNAL TABLE' FROM _v_external WHERE tablename IS NOT NULL AND ")
                .append(SYSTEM_SCHEMA_FILTER);
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND tablename LIKE ").append(lit(pattern));
        sql.append(" ORDER BY schema, tablename");
        Set<String> requested = types == null ? null : new HashSet<>(Arrays.asList(types));
        List<List<Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Object> row : rows(c, sql.toString())) {
            String type = tableType(text(row, 2));
            if (requested != null && requested.stream().noneMatch(type::equalsIgnoreCase)) continue;
            String key = text(row, 0) + "." + text(row, 1);
            if (!seen.add(key)) continue;
            rows.add(Arrays.asList(c.database, text(row, 0), text(row, 1), type, null, null, null, null, null, null));
        }
        return MetadataResultSets.create(tableColumns(), rows);
    }

    private static ResultSet emptyTables() throws SQLException {
        return MetadataResultSets.create(tableColumns(), List.of());
    }

    private static List<MetadataResultSets.Column> tableColumns() {
        return List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("TABLE_TYPE", 32),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.text("TYPE_CAT", 128),
                MetadataResultSets.text("TYPE_SCHEM", 128), MetadataResultSets.text("TYPE_NAME", 128),
                MetadataResultSets.text("SELF_REFERENCING_COL_NAME", 128), MetadataResultSets.text("REF_GENERATION", 32));
    }

    private static String tableType(String objType) {
        String type = objType == null ? "" : objType.toUpperCase(Locale.ROOT);
        if (type.contains("VIEW")) return "VIEW";
        if (type.contains("EXTERNAL")) return "EXTERNAL TABLE";
        return "TABLE";
    }

    private static ResultSet tableTypes(NetezzaConnection c) throws SQLException {
        List<List<Object>> rows = List.of(List.of("TABLE"), List.of("VIEW"), List.of("EXTERNAL TABLE"));
        return MetadataResultSets.create(List.of(MetadataResultSets.text("TABLE_TYPE", 32)), rows);
    }

    private static ResultSet columns(NetezzaConnection c, Object[] a) throws SQLException {
        String catalog = (String) a[0], schema = (String) a[1], table = (String) a[2], pattern = (String) a[3];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyColumns();
        StringBuilder sql = new StringBuilder("SELECT schema, name, attname, format_type, attnotnull, attnum, description "
                + "FROM _v_relation_column WHERE attname IS NOT NULL AND " + SYSTEM_SCHEMA_FILTER);
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (table != null && !table.isEmpty()) sql.append(" AND name LIKE ").append(lit(table));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND attname LIKE ").append(lit(pattern));
        sql.append(" ORDER BY schema, name, attnum");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString())) {
            String format = text(row, 3);
            TypeInfo type = parseType(format);
            boolean notNull = booleanValue(row.get(4));
            rows.add(Arrays.asList(
                    c.database, text(row, 0), text(row, 1), text(row, 2),
                    type.sqlType, type.typeName, type.precision, null, type.scale, 10,
                    notNull ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable,
                    row.get(6), null, 0, 0, type.charOctetLength,
                    intValue(row.get(5)), notNull ? "NO" : "YES",
                    null, null, null, null, "NO", "NO"));
        }
        return MetadataResultSets.create(columnColumns(), rows);
    }

    private static ResultSet emptyColumns() throws SQLException {
        return MetadataResultSets.create(columnColumns(), List.of());
    }

    private static List<MetadataResultSets.Column> columnColumns() {
        return List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.text("TYPE_NAME", 128),
                MetadataResultSets.integer("COLUMN_SIZE"), MetadataResultSets.integer("BUFFER_LENGTH"),
                MetadataResultSets.integer("DECIMAL_DIGITS"), MetadataResultSets.integer("NUM_PREC_RADIX"),
                MetadataResultSets.integer("NULLABLE"), MetadataResultSets.text("REMARKS", 1024),
                MetadataResultSets.text("COLUMN_DEF", 1024), MetadataResultSets.integer("SQL_DATA_TYPE"),
                MetadataResultSets.integer("SQL_DATETIME_SUB"), MetadataResultSets.integer("CHAR_OCTET_LENGTH"),
                MetadataResultSets.integer("ORDINAL_POSITION"), MetadataResultSets.text("IS_NULLABLE", 3),
                MetadataResultSets.text("SCOPE_CATALOG", 128), MetadataResultSets.text("SCOPE_SCHEMA", 128),
                MetadataResultSets.text("SCOPE_TABLE", 128), MetadataResultSets.integer("SOURCE_DATA_TYPE"),
                MetadataResultSets.text("IS_AUTOINCREMENT", 3), MetadataResultSets.text("IS_GENERATEDCOLUMN", 3));
    }

    private record TypeInfo(int sqlType, String typeName, int precision, int scale,
                            Integer charOctetLength, boolean caseSensitive, boolean signed) {}

    private static TypeInfo parseType(String formatType) {
        String text = formatType == null ? "VARCHAR" : formatType.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        int paren = upper.indexOf('(');
        String base = paren < 0 ? upper : upper.substring(0, paren).trim();
        Integer size = null;
        Integer scale = null;
        if (paren >= 0) {
            int close = upper.indexOf(')', paren);
            if (close > paren) {
                String[] parts = upper.substring(paren + 1, close).split(",");
                try {
                    if (parts.length > 0 && !parts[0].isBlank()) size = Integer.parseInt(parts[0].trim());
                    if (parts.length > 1 && !parts[1].isBlank()) scale = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (base.startsWith("BYTEINT") || base.startsWith("INT1")) return new TypeInfo(Types.TINYINT, "BYTEINT", 3, 0, null, false, true);
        if (base.startsWith("SMALLINT") || base.startsWith("INT2")) return new TypeInfo(Types.SMALLINT, "SMALLINT", 5, 0, null, false, true);
        if (base.startsWith("BIGINT") || base.startsWith("INT8")) return new TypeInfo(Types.BIGINT, "BIGINT", 19, 0, null, false, true);
        if (base.startsWith("INTEGER") || base.startsWith("INT4")) return new TypeInfo(Types.INTEGER, "INTEGER", 10, 0, null, false, true);
        if (base.startsWith("REAL") || base.startsWith("FLOAT4")) return new TypeInfo(Types.REAL, "REAL", 7, 0, null, false, true);
        if (base.startsWith("DOUBLE") || base.startsWith("FLOAT8") || base.equals("FLOAT")) return new TypeInfo(Types.DOUBLE, "DOUBLE", 15, 0, null, false, true);
        if (base.startsWith("NUMERIC") || base.startsWith("DECIMAL")) {
            int precision = size == null ? 38 : size;
            int decimalDigits = scale == null ? 0 : scale;
            return new TypeInfo(Types.NUMERIC, "NUMERIC", precision, decimalDigits, null, false, true);
        }
        if (base.startsWith("NATIONAL CHARACTER VARYING") || base.startsWith("NCHAR VARYING"))
            return new TypeInfo(Types.NVARCHAR, "NVARCHAR", size == null ? 0 : size, 0,
                    size == null ? null : multiplied(size), true, false);
        if (base.startsWith("NATIONAL CHARACTER") || base.startsWith("NCHAR"))
            return new TypeInfo(Types.NCHAR, "NCHAR", size == null ? 0 : size, 0,
                    size == null ? null : multiplied(size), true, false);
        if (base.startsWith("CHARACTER VARYING") || base.startsWith("VARCHAR") || base.startsWith("TEXT") || base.startsWith("NAME"))
            return new TypeInfo(Types.VARCHAR, "VARCHAR", size == null ? 0 : size, 0, size, true, false);
        if (base.startsWith("CHARACTER") || base.startsWith("CHAR") || base.startsWith("BPCHAR"))
            return new TypeInfo(Types.CHAR, "CHAR", size == null ? 0 : size, 0, size, true, false);
        if (base.startsWith("DATE")) return new TypeInfo(Types.DATE, "DATE", 10, 0, null, false, false);
        if (base.startsWith("TIMESTAMP WITH TIME ZONE") || base.startsWith("TIMESTAMPTZ"))
            return new TypeInfo(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ", 26, scale == null ? 6 : scale, null, false, false);
        if (base.startsWith("TIMESTAMP")) return new TypeInfo(Types.TIMESTAMP, "TIMESTAMP", 26, scale == null ? 6 : scale, null, false, false);
        if (base.startsWith("TIME WITH TIME ZONE") || base.startsWith("TIMETZ"))
            return new TypeInfo(Types.TIME_WITH_TIMEZONE, "TIMETZ", 8, scale == null ? 6 : scale, null, false, false);
        if (base.startsWith("TIME")) return new TypeInfo(Types.TIME, "TIME", 8, scale == null ? 6 : scale, null, false, false);
        if (base.startsWith("BOOLEAN") || base.equals("BOOL")) return new TypeInfo(Types.BOOLEAN, "BOOLEAN", 1, 0, null, false, false);
        if (base.startsWith("BINARY VARYING") || base.startsWith("VARBINARY") || base.startsWith("BYTEA"))
            return new TypeInfo(Types.VARBINARY, "VARBINARY", size == null ? 0 : size, 0, null, false, false);
        if (base.startsWith("INTERVAL")) return new TypeInfo(Types.OTHER, "INTERVAL", 0, 0, null, false, false);
        if (base.startsWith("ST_GEOMETRY") || base.startsWith("GEOMETRY"))
            return new TypeInfo(Types.OTHER, "GEOMETRY", 0, 0, null, true, false);
        if (base.startsWith("JSON")) return new TypeInfo(Types.OTHER, base, 0, 0, null, true, false);
        if (base.startsWith("UUID") || base.startsWith("VECTOR"))
            return new TypeInfo(Types.OTHER, base, 0, 0, null, true, false);
        return new TypeInfo(Types.VARCHAR, "VARCHAR", size == null ? 0 : size, 0, size, true, false);
    }

    private static Integer multiplied(Integer value) {
        long result = (long) value * 4;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static ResultSet typeInfo(NetezzaConnection c) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        addType(rows, "BOOLEAN", Types.BOOLEAN, 1, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 2);
        addType(rows, "BYTEINT", Types.TINYINT, 3, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "SMALLINT", Types.SMALLINT, 5, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "INTEGER", Types.INTEGER, 10, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "BIGINT", Types.BIGINT, 19, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "REAL", Types.REAL, 7, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "DOUBLE", Types.DOUBLE, 15, null, null, null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 10);
        addType(rows, "NUMERIC", Types.NUMERIC, 38, null, null, "PRECISION,SCALE", false, DatabaseMetaData.typeNullable, 3, 0, 38, 10);
        addType(rows, "CHAR", Types.CHAR, 64000, "'", "'", "LENGTH", true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "VARCHAR", Types.VARCHAR, 64000, "'", "'", "LENGTH", true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "NCHAR", Types.NCHAR, 16000, "'", "'", "LENGTH", true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "NVARCHAR", Types.NVARCHAR, 16000, "'", "'", "LENGTH", true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "DATE", Types.DATE, 10, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "TIME", Types.TIME, 8, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 6, 0);
        addType(rows, "TIMETZ", Types.TIME_WITH_TIMEZONE, 8, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 6, 0);
        addType(rows, "TIMESTAMP", Types.TIMESTAMP, 26, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 6, 0);
        addType(rows, "TIMESTAMPTZ", Types.TIMESTAMP_WITH_TIMEZONE, 26, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 6, 0);
        addType(rows, "INTERVAL", Types.OTHER, 0, "'", "'", null, false, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "VARBINARY", Types.VARBINARY, 64000, null, null, "LENGTH", false, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "JSON", Types.OTHER, 0, null, null, null, true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "JSONB", Types.OTHER, 0, null, null, null, true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "GEOMETRY", Types.OTHER, 0, null, null, null, true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "UUID", Types.OTHER, 0, null, null, null, true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        addType(rows, "VECTOR", Types.OTHER, 0, null, null, null, true, DatabaseMetaData.typeNullable, 3, 0, 0, 0);
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.integer("DATA_TYPE"),
                MetadataResultSets.integer("PRECISION"), MetadataResultSets.text("LITERAL_PREFIX", 16),
                MetadataResultSets.text("LITERAL_SUFFIX", 16), MetadataResultSets.text("CREATE_PARAMS", 128),
                MetadataResultSets.smallint("NULLABLE"), MetadataResultSets.bool("CASE_SENSITIVE"),
                MetadataResultSets.smallint("SEARCHABLE"), MetadataResultSets.bool("UNSIGNED_ATTRIBUTE"),
                MetadataResultSets.bool("FIXED_PREC_SCALE"), MetadataResultSets.bool("AUTO_INCREMENT"),
                MetadataResultSets.text("LOCAL_TYPE_NAME", 128), MetadataResultSets.smallint("MINIMUM_SCALE"),
                MetadataResultSets.smallint("MAXIMUM_SCALE"), MetadataResultSets.integer("SQL_DATA_TYPE"),
                MetadataResultSets.integer("SQL_DATETIME_SUB"), MetadataResultSets.integer("NUM_PREC_RADIX")), rows);
    }

    private static void addType(List<List<Object>> rows, String name, int type, int precision,
                                String prefix, String suffix, String params, boolean caseSensitive,
                                int nullable, int searchable, int minScale, int maxScale, int radix) {
        rows.add(Arrays.asList(name, type, precision, prefix, suffix, params, nullable, caseSensitive,
                searchable, false, false, false, name, minScale, maxScale, 0, 0, radix));
    }

    private static ResultSet primaryKeys(NetezzaConnection c, Object[] a) throws SQLException {
        String catalog = (String) a[0], schema = (String) a[1], table = (String) a[2];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyPrimaryKeys();
        if (table == null || table.isEmpty()) return emptyPrimaryKeys();
        StringBuilder sql = new StringBuilder("SELECT schema, relation, attname, conseq, constraintname "
                + "FROM _v_relation_keydata WHERE contype = 'p' AND database = " + lit(c.database)
                + " AND relation = " + lit(table));
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema = ").append(lit(schema));
        sql.append(" ORDER BY schema, relation, conseq");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString()))
            rows.add(Arrays.asList(c.database, text(row, 0), text(row, 1), text(row, 2), intValue(row.get(3)), text(row, 4)));
        return MetadataResultSets.create(primaryKeyColumns(), rows);
    }

    private static List<MetadataResultSets.Column> primaryKeyColumns() {
        return List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.smallint("KEY_SEQ"), MetadataResultSets.text("PK_NAME", 128));
    }

    private static ResultSet emptyPrimaryKeys() throws SQLException {
        return MetadataResultSets.create(primaryKeyColumns(), List.of());
    }

    private static List<MetadataResultSets.Column> keyColumns() {
        return List.of(
                MetadataResultSets.text("PKTABLE_CAT", 128), MetadataResultSets.text("PKTABLE_SCHEM", 128),
                MetadataResultSets.text("PKTABLE_NAME", 128), MetadataResultSets.text("PKCOLUMN_NAME", 128),
                MetadataResultSets.text("FKTABLE_CAT", 128), MetadataResultSets.text("FKTABLE_SCHEM", 128),
                MetadataResultSets.text("FKTABLE_NAME", 128), MetadataResultSets.text("FKCOLUMN_NAME", 128),
                MetadataResultSets.smallint("KEY_SEQ"), MetadataResultSets.smallint("UPDATE_RULE"),
                MetadataResultSets.smallint("DELETE_RULE"), MetadataResultSets.text("FK_NAME", 128),
                MetadataResultSets.text("PK_NAME", 128), MetadataResultSets.smallint("DEFERRABILITY"));
    }

    private static ResultSet emptyForeignKeys() throws SQLException {
        return MetadataResultSets.create(keyColumns(), List.of());
    }

    private static ResultSet foreignKeys(NetezzaConnection c, Object[] a, boolean exported, boolean cross) throws SQLException {
        if (cross) return crossReference(c, a);
        String catalog = (String) a[0], schema = (String) a[1], table = (String) a[2];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyForeignKeys();
        if (table == null || table.isEmpty()) return emptyForeignKeys();
        String relationColumn = exported ? "pkrelation" : "relation";
        StringBuilder sql = new StringBuilder("SELECT schema, relation, attname, conseq, constraintname, pkdatabase, pkschema, "
                + "pkrelation, pkattname, updt_type, del_type, refconstRname, deferrable, deferred "
                + "FROM _v_relation_keydata WHERE contype = 'f' AND database = " + lit(c.database) + " AND "
                + relationColumn + " = " + lit(table));
        if (schema != null && !schema.isEmpty()) sql.append(" AND ").append(exported ? "pkschema" : "schema").append(" = ").append(lit(schema));
        sql.append(" ORDER BY schema, relation, conseq");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString())) {
            String childCat = c.database, childSchema = text(row, 0), childTable = text(row, 1), childColumn = text(row, 2);
            String parentCat = textOr(row, 5, c.database), parentSchema = textOr(row, 6, schema);
            String parentTable = text(row, 7), parentColumn = text(row, 8);
            int keySeq = intValue(row.get(3));
            int update = rule(row.get(9), DatabaseMetaData.importedKeyNoAction);
            int delete = rule(row.get(10), DatabaseMetaData.importedKeyNoAction);
            rows.add(Arrays.asList(parentCat, parentSchema, parentTable, parentColumn,
                    childCat, childSchema, childTable, childColumn, keySeq, update, delete,
                    text(row, 4), text(row, 11), deferrability(row.get(12), row.get(13))));
        }
        return MetadataResultSets.create(keyColumns(), rows);
    }

    private static ResultSet crossReference(NetezzaConnection c, Object[] a) throws SQLException {
        String parentCatalog = (String) a[0], parentSchema = (String) a[1], parentTable = (String) a[2];
        String childCatalog = (String) a[3], childSchema = (String) a[4], childTable = (String) a[5];
        if (parentCatalog != null && !parentCatalog.isEmpty() && !parentCatalog.equalsIgnoreCase(c.database)) return emptyForeignKeys();
        if (childCatalog != null && !childCatalog.isEmpty() && !childCatalog.equalsIgnoreCase(c.database)) return emptyForeignKeys();
        StringBuilder sql = new StringBuilder("SELECT schema, relation, attname, conseq, constraintname, pkdatabase, pkschema, "
                + "pkrelation, pkattname, updt_type, del_type, refconstRname, deferrable, deferred "
                + "FROM _v_relation_keydata WHERE contype = 'f' AND database = " + lit(c.database)
                + " AND pkrelation = " + lit(parentTable) + " AND relation = " + lit(childTable));
        if (parentSchema != null && !parentSchema.isEmpty()) sql.append(" AND pkschema = ").append(lit(parentSchema));
        if (childSchema != null && !childSchema.isEmpty()) sql.append(" AND schema = ").append(lit(childSchema));
        sql.append(" ORDER BY schema, relation, conseq");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString()))
            rows.add(Arrays.asList(textOr(row, 5, c.database), textOr(row, 6, parentSchema), text(row, 7), text(row, 8),
                    c.database, text(row, 0), text(row, 1), text(row, 2), intValue(row.get(3)),
                    rule(row.get(9), DatabaseMetaData.importedKeyNoAction), rule(row.get(10), DatabaseMetaData.importedKeyNoAction),
                    text(row, 4), text(row, 11), deferrability(row.get(12), row.get(13))));
        return MetadataResultSets.create(keyColumns(), rows);
    }

    private static int rule(Object value, int fallback) {
        String text = value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
        if (text.startsWith("CASCADE")) return DatabaseMetaData.importedKeyCascade;
        if (text.startsWith("SET NULL")) return DatabaseMetaData.importedKeySetNull;
        if (text.startsWith("SET DEFAULT")) return DatabaseMetaData.importedKeySetDefault;
        if (text.startsWith("RESTRICT")) return DatabaseMetaData.importedKeyRestrict;
        if (text.startsWith("NO ACTION")) return DatabaseMetaData.importedKeyNoAction;
        return fallback;
    }

    private static int deferrability(Object deferrable, Object deferred) {
        String deferrableText = deferrable == null ? "" : deferrable.toString().trim().toUpperCase(Locale.ROOT);
        if (deferrableText.startsWith("NOT DEFERRABLE")) return DatabaseMetaData.importedKeyNotDeferrable;
        String deferredText = deferred == null ? "" : deferred.toString().trim().toUpperCase(Locale.ROOT);
        return deferredText.startsWith("INITIALLY DEFERRED")
                ? DatabaseMetaData.importedKeyInitiallyDeferred
                : DatabaseMetaData.importedKeyInitiallyImmediate;
    }

    private static ResultSet indexInfo(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.bool("NON_UNIQUE"),
                MetadataResultSets.text("INDEX_QUALIFIER", 128), MetadataResultSets.text("INDEX_NAME", 128),
                MetadataResultSets.smallint("TYPE"), MetadataResultSets.smallint("ORDINAL_POSITION"),
                MetadataResultSets.text("COLUMN_NAME", 128), MetadataResultSets.text("ASC_OR_DESC", 1),
                MetadataResultSets.bigint("CARDINALITY"), MetadataResultSets.bigint("PAGES"),
                MetadataResultSets.text("FILTER_CONDITION", 128)), List.of());
    }

    private static ResultSet procedures(NetezzaConnection c, Object[] a) throws SQLException {
        String catalog = (String) a[0], schema = (String) a[1], pattern = (String) a[2];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyProcedures();
        StringBuilder sql = new StringBuilder("SELECT schema, procedure, description, proceduresignature FROM _v_procedure "
                + "WHERE procedure IS NOT NULL");
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND procedure LIKE ").append(lit(pattern));
        sql.append(" ORDER BY schema, procedure");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString()))
            rows.add(Arrays.asList(c.database, text(row, 0), text(row, 1), null, null, null, row.get(2),
                    DatabaseMetaData.procedureResultUnknown, textOr(row, 3, text(row, 1))));
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("PROCEDURE_CAT", 128), MetadataResultSets.text("PROCEDURE_SCHEM", 128),
                MetadataResultSets.text("PROCEDURE_NAME", 128), MetadataResultSets.text("RESERVED1", 128),
                MetadataResultSets.text("RESERVED2", 128), MetadataResultSets.text("RESERVED3", 128),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.smallint("PROCEDURE_TYPE"),
                MetadataResultSets.text("SPECIFIC_NAME", 128)), rows);
    }

    private static ResultSet emptyProcedures() throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("PROCEDURE_CAT", 128), MetadataResultSets.text("PROCEDURE_SCHEM", 128),
                MetadataResultSets.text("PROCEDURE_NAME", 128), MetadataResultSets.text("RESERVED1", 128),
                MetadataResultSets.text("RESERVED2", 128), MetadataResultSets.text("RESERVED3", 128),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.smallint("PROCEDURE_TYPE"),
                MetadataResultSets.text("SPECIFIC_NAME", 128)), List.of());
    }

    private static ResultSet procedureColumns(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("PROCEDURE_CAT", 128), MetadataResultSets.text("PROCEDURE_SCHEM", 128),
                MetadataResultSets.text("PROCEDURE_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.smallint("COLUMN_TYPE"), MetadataResultSets.integer("DATA_TYPE"),
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.integer("PRECISION"),
                MetadataResultSets.integer("LENGTH"), MetadataResultSets.smallint("SCALE"),
                MetadataResultSets.smallint("RADIX"), MetadataResultSets.smallint("NULLABLE"),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.text("COLUMN_DEF", 1024),
                MetadataResultSets.integer("SQL_DATA_TYPE"), MetadataResultSets.integer("SQL_DATETIME_SUB"),
                MetadataResultSets.integer("CHAR_OCTET_LENGTH"), MetadataResultSets.integer("ORDINAL_POSITION"),
                MetadataResultSets.text("IS_NULLABLE", 3), MetadataResultSets.text("SPECIFIC_NAME", 128)), List.of());
    }

    private static ResultSet functions(NetezzaConnection c, Object[] a) throws SQLException {
        String catalog = (String) a[0], schema = (String) a[1], pattern = (String) a[2];
        if (catalog != null && !catalog.isEmpty() && !catalog.equalsIgnoreCase(c.database)) return emptyFunctions();
        StringBuilder sql = new StringBuilder("SELECT schema, function, description, functionsignature FROM _v_function "
                + "WHERE function IS NOT NULL");
        if (schema != null && !schema.isEmpty()) sql.append(" AND schema LIKE ").append(lit(schema));
        if (pattern != null && !pattern.isEmpty()) sql.append(" AND function LIKE ").append(lit(pattern));
        sql.append(" ORDER BY schema, function");
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : rows(c, sql.toString()))
            rows.add(Arrays.asList(c.database, text(row, 0), text(row, 1), row.get(2),
                    DatabaseMetaData.functionResultUnknown, textOr(row, 3, text(row, 1))));
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("FUNCTION_CAT", 128), MetadataResultSets.text("FUNCTION_SCHEM", 128),
                MetadataResultSets.text("FUNCTION_NAME", 128), MetadataResultSets.text("REMARKS", 1024),
                MetadataResultSets.smallint("FUNCTION_TYPE"), MetadataResultSets.text("SPECIFIC_NAME", 128)), rows);
    }

    private static ResultSet emptyFunctions() throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("FUNCTION_CAT", 128), MetadataResultSets.text("FUNCTION_SCHEM", 128),
                MetadataResultSets.text("FUNCTION_NAME", 128), MetadataResultSets.text("REMARKS", 1024),
                MetadataResultSets.smallint("FUNCTION_TYPE"), MetadataResultSets.text("SPECIFIC_NAME", 128)), List.of());
    }

    private static ResultSet functionColumns(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("FUNCTION_CAT", 128), MetadataResultSets.text("FUNCTION_SCHEM", 128),
                MetadataResultSets.text("FUNCTION_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.smallint("COLUMN_TYPE"), MetadataResultSets.integer("DATA_TYPE"),
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.integer("PRECISION"),
                MetadataResultSets.integer("LENGTH"), MetadataResultSets.smallint("SCALE"),
                MetadataResultSets.smallint("RADIX"), MetadataResultSets.smallint("NULLABLE"),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.integer("CHAR_OCTET_LENGTH"),
                MetadataResultSets.integer("ORDINAL_POSITION"), MetadataResultSets.text("IS_NULLABLE", 3),
                MetadataResultSets.text("SPECIFIC_NAME", 128)), List.of());
    }

    private static ResultSet tablePrivileges(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("GRANTOR", 128),
                MetadataResultSets.text("GRANTEE", 128), MetadataResultSets.text("PRIVILEGE", 128),
                MetadataResultSets.text("IS_GRANTABLE", 3)), List.of());
    }

    private static ResultSet columnPrivileges(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.text("GRANTOR", 128), MetadataResultSets.text("GRANTEE", 128),
                MetadataResultSets.text("PRIVILEGE", 128), MetadataResultSets.text("IS_GRANTABLE", 3)), List.of());
    }

    private static ResultSet bestRowIdentifier(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.smallint("SCOPE"), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.text("TYPE_NAME", 128),
                MetadataResultSets.integer("COLUMN_SIZE"), MetadataResultSets.integer("BUFFER_LENGTH"),
                MetadataResultSets.smallint("DECIMAL_DIGITS"), MetadataResultSets.smallint("PSEUDO_COLUMN")), List.of());
    }

    private static ResultSet versionColumns(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.smallint("SCOPE"), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.text("TYPE_NAME", 128),
                MetadataResultSets.integer("COLUMN_SIZE"), MetadataResultSets.integer("BUFFER_LENGTH"),
                MetadataResultSets.smallint("DECIMAL_DIGITS"), MetadataResultSets.smallint("PSEUDO_COLUMN")), List.of());
    }

    private static ResultSet clientInfoProperties(NetezzaConnection c) throws SQLException {
        List<List<Object>> rows = List.of(
                Arrays.asList("ApplicationName", 255, "", "Application name sent during the handshake"),
                Arrays.asList("ClientUser", 255, "", "Client user name"),
                Arrays.asList("ClientHostname", 255, "", "Client host name"));
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("NAME", 128), MetadataResultSets.integer("MAX_LEN"),
                MetadataResultSets.text("DEFAULT_VALUE", 128), MetadataResultSets.text("DESCRIPTION", 1024)), rows);
    }

    private static ResultSet pseudoColumns(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("COLUMN_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.integer("COLUMN_SIZE"),
                MetadataResultSets.integer("DECIMAL_DIGITS"), MetadataResultSets.integer("NUM_PREC_RADIX"),
                MetadataResultSets.text("COLUMN_USAGE", 128), MetadataResultSets.text("REMARKS", 1024),
                MetadataResultSets.integer("CHAR_OCTET_LENGTH"), MetadataResultSets.text("IS_NULLABLE", 3)), List.of());
    }

    private static ResultSet udts(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TYPE_CAT", 128), MetadataResultSets.text("TYPE_SCHEM", 128),
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.text("CLASS_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.text("REMARKS", 1024),
                MetadataResultSets.smallint("BASE_TYPE")), List.of());
    }

    private static ResultSet attributes(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TYPE_CAT", 128), MetadataResultSets.text("TYPE_SCHEM", 128),
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.text("ATTR_NAME", 128),
                MetadataResultSets.integer("DATA_TYPE"), MetadataResultSets.text("ATTR_TYPE_NAME", 128),
                MetadataResultSets.integer("ATTR_SIZE"), MetadataResultSets.integer("DECIMAL_DIGITS"),
                MetadataResultSets.integer("NUM_PREC_RADIX"), MetadataResultSets.integer("NULLABLE"),
                MetadataResultSets.text("REMARKS", 1024), MetadataResultSets.text("ATTR_DEF", 1024),
                MetadataResultSets.integer("SQL_DATA_TYPE"), MetadataResultSets.integer("SQL_DATETIME_SUB"),
                MetadataResultSets.integer("CHAR_OCTET_LENGTH"), MetadataResultSets.integer("ORDINAL_POSITION"),
                MetadataResultSets.text("IS_NULLABLE", 3), MetadataResultSets.text("SCOPE_CATALOG", 128),
                MetadataResultSets.text("SCOPE_SCHEMA", 128), MetadataResultSets.text("SCOPE_TABLE", 128),
                MetadataResultSets.smallint("SOURCE_DATA_TYPE")), List.of());
    }

    private static ResultSet superTypes(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TYPE_CAT", 128), MetadataResultSets.text("TYPE_SCHEM", 128),
                MetadataResultSets.text("TYPE_NAME", 128), MetadataResultSets.text("SUPERTYPE_CAT", 128),
                MetadataResultSets.text("SUPERTYPE_SCHEM", 128), MetadataResultSets.text("SUPERTYPE_NAME", 128)), List.of());
    }

    private static ResultSet superTables(NetezzaConnection c) throws SQLException {
        return MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128), MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.text("TABLE_NAME", 128), MetadataResultSets.text("SUPERTABLE_NAME", 128)), List.of());
    }

    private static List<List<Object>> rows(NetezzaConnection c, String sql) throws SQLException {
        QueryResult result = c.execute(sql, 0);
        if (!result.hasRows) {
            result.close();
            throw new SQLException("Metadata query returned no result set", "HY000");
        }
        try (ResultSet rs = result.resultSet()) {
            ResultSetMetaData metadata = rs.getMetaData();
            int count = metadata.getColumnCount();
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return rows;
        } finally {
            result.finish();
        }
    }

    private static String text(List<Object> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).toString() : null;
    }

    private static String textOr(List<Object> row, int index, String fallback) {
        String value = text(row, index);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && (value.toString().equalsIgnoreCase("t") || value.toString().equalsIgnoreCase("true")
                || value.toString().equals("1"));
    }

    private static String lit(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
