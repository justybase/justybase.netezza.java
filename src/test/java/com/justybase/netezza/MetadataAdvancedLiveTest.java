package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowIdLifetime;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Catalog-wide metadata filtering, shapes, type information and capability flags. */
@Tag("live")
class MetadataAdvancedLiveTest {
    private static Connection connection;
    private static DatabaseMetaData metadata;
    private static String catalog;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        metadata = connection.getMetaData();
        catalog = connection.getCatalog();
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void catalogsSchemasAndTableTypesFilterCorrectly() throws Exception {
        try (ResultSet catalogs = metadata.getCatalogs()) {
            assertEquals(1, catalogs.getMetaData().getColumnCount());
            assertTrue(names(catalogs, "TABLE_CAT").contains(catalog));
        }
        try (ResultSet schemas = metadata.getSchemas(catalog, "ADMIN")) {
            assertEquals(2, schemas.getMetaData().getColumnCount());
            assertTrue(schemas.next());
            assertEquals("ADMIN", schemas.getString("TABLE_SCHEM"));
            assertEquals(catalog, schemas.getString("TABLE_CATALOG"));
            assertFalse(schemas.next());
        }
        try (ResultSet schemas = metadata.getSchemas("WRONG_CATALOG", null)) {
            assertEquals(2, schemas.getMetaData().getColumnCount());
            assertFalse(schemas.next());
        }
        try (ResultSet types = metadata.getTableTypes()) {
            Set<String> values = new HashSet<>(names(types, "TABLE_TYPE"));
            assertTrue(values.contains("TABLE"));
            assertTrue(values.contains("VIEW"));
            assertTrue(values.contains("EXTERNAL TABLE"));
        }
    }

    @Test
    @Timeout(60)
    void tablePatternsSchemaFiltersAndTypeArraysAreHonoured() throws Exception {
        try (ResultSet tables = metadata.getTables(catalog, "ADMIN", "DIM%", new String[]{"TABLE"})) {
            assertEquals(10, tables.getMetaData().getColumnCount());
            List<String> names = new ArrayList<>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
                assertEquals("TABLE", tables.getString("TABLE_TYPE"));
            }
            assertTrue(names.contains("DIMDATE"), names.toString());
        }
        try (ResultSet tables = metadata.getTables(catalog, "ADMIN", "DIM_ATE", new String[]{"TABLE"})) {
            List<String> names = new ArrayList<>();
            while (tables.next()) names.add(tables.getString("TABLE_NAME"));
            assertTrue(names.contains("DIMDATE"), names.toString());
        }
        try (ResultSet tables = metadata.getTables(catalog, "ADMIN", "DIMDATE", new String[0])) {
            assertFalse(tables.next(), "an empty type array must select no table types");
        }
        try (ResultSet tables = metadata.getTables(catalog, "ADMIN", "DIMDATE", null)) {
            assertTrue(tables.next());
            assertEquals(catalog, tables.getString("TABLE_CAT"));
            assertEquals("ADMIN", tables.getString("TABLE_SCHEM"));
        }
        try (ResultSet views = metadata.getTables(catalog, null, "%", new String[]{"VIEW"})) {
            while (views.next()) assertEquals("VIEW", views.getString("TABLE_TYPE"));
        }
    }

    @Test
    @Timeout(60)
    void columnMetadataProvidesOrdinalTypeSizeAndNullability() throws Exception {
        try (ResultSet columns = metadata.getColumns(catalog, "ADMIN", "DIMDATE", "%")) {
            assertEquals(24, columns.getMetaData().getColumnCount());
            int ordinal = 0;
            boolean dateKey = false;
            while (columns.next()) {
                ordinal++;
                assertEquals(ordinal, columns.getInt("ORDINAL_POSITION"));
                String name = columns.getString("COLUMN_NAME");
                assertEquals(catalog, columns.getString("TABLE_CAT"));
                assertEquals("ADMIN", columns.getString("TABLE_SCHEM"));
                assertEquals("DIMDATE", columns.getString("TABLE_NAME"));
                assertNotNull(columns.getString("TYPE_NAME"));
                assertTrue(columns.getString("IS_NULLABLE").equals("YES") ||
                        columns.getString("IS_NULLABLE").equals("NO"));
                assertEquals("NO", columns.getString("IS_AUTOINCREMENT"));
                assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
                if ("DATEKEY".equals(name)) {
                    dateKey = true;
                    assertEquals(Types.INTEGER, columns.getInt("DATA_TYPE"));
                    assertEquals("INTEGER", columns.getString("TYPE_NAME"));
                }
                if ("ENGLISHDAYNAMEOFWEEK".equals(name)) {
                    assertEquals(Types.NVARCHAR, columns.getInt("DATA_TYPE"));
                    assertEquals("NVARCHAR", columns.getString("TYPE_NAME"));
                    assertEquals(14, columns.getInt("COLUMN_SIZE"));
                    assertEquals(56, columns.getInt("CHAR_OCTET_LENGTH"));
                }
            }
            assertTrue(dateKey);
            assertEquals(19, ordinal);
        }
        try (ResultSet columns = metadata.getColumns(catalog, "ADMIN", "DIMDATE", "ENGLISH%")) {
            int count = 0;
            while (columns.next()) {
                count++;
                assertTrue(columns.getString("COLUMN_NAME").startsWith("ENGLISH"));
            }
            assertTrue(count > 0);
        }
    }

    @Test
    @Timeout(60)
    void primaryAndForeignKeyShapesHandleMissingObjects() throws Exception {
        try (ResultSet keys = metadata.getPrimaryKeys(catalog, "ADMIN", "DIMDATE")) {
            assertEquals(6, keys.getMetaData().getColumnCount());
            assertTrue(keys.next());
            assertEquals("DATEKEY", keys.getString("COLUMN_NAME"));
            assertEquals(1, keys.getInt("KEY_SEQ"));
        }
        try (ResultSet keys = metadata.getPrimaryKeys(catalog, "ADMIN", "NO_SUCH_TABLE")) {
            assertEquals(6, keys.getMetaData().getColumnCount());
            assertFalse(keys.next());
        }
        try (ResultSet keys = metadata.getImportedKeys(catalog, "ADMIN", "NO_SUCH_TABLE")) {
            assertEquals(14, keys.getMetaData().getColumnCount());
            assertFalse(keys.next());
        }
        try (ResultSet keys = metadata.getExportedKeys(catalog, "ADMIN", "NO_SUCH_TABLE")) {
            assertEquals(14, keys.getMetaData().getColumnCount());
            assertFalse(keys.next());
        }
        try (ResultSet keys = metadata.getCrossReference(catalog, "ADMIN", "NO_SUCH_TABLE",
                catalog, "ADMIN", "NO_SUCH_TABLE")) {
            assertEquals(14, keys.getMetaData().getColumnCount());
            assertFalse(keys.next());
        }
        try (ResultSet indexes = metadata.getIndexInfo(catalog, "ADMIN", "DIMDATE", false, true)) {
            assertEquals(13, indexes.getMetaData().getColumnCount());
            assertFalse(indexes.next());
        }
    }

    @Test
    @Timeout(60)
    void typeInfoDescribesTheCoreTypeMatrix() throws Exception {
        try (ResultSet types = metadata.getTypeInfo()) {
            assertEquals(18, types.getMetaData().getColumnCount());
            boolean integer = false;
            boolean varchar = false;
            boolean numeric = false;
            while (types.next()) {
                String name = types.getString("TYPE_NAME");
                int type = types.getInt("DATA_TYPE");
                if ("INTEGER".equals(name)) {
                    integer = true;
                    assertEquals(Types.INTEGER, type);
                    assertEquals(10, types.getInt("PRECISION"));
                    assertEquals(DatabaseMetaData.typeNullable, types.getShort("NULLABLE"));
                } else if ("VARCHAR".equals(name)) {
                    varchar = true;
                    assertEquals(Types.VARCHAR, type);
                    assertEquals(64000, types.getInt("PRECISION"));
                    assertTrue(types.getBoolean("CASE_SENSITIVE"));
                } else if ("NUMERIC".equals(name)) {
                    numeric = true;
                    assertEquals(Types.NUMERIC, type);
                    assertEquals(38, types.getInt("PRECISION"));
                    assertTrue(types.getString("CREATE_PARAMS").contains("SCALE"));
                }
            }
            assertTrue(integer && varchar && numeric);
        }
    }

    @Test
    @Timeout(60)
    void functionsProceduresAndTheirColumnsHaveStandardShapes() throws Exception {
        try (ResultSet functions = metadata.getFunctions(catalog, "DEFINITION_SCHEMA", "%")) {
            assertEquals(6, functions.getMetaData().getColumnCount());
            int count = 0;
            while (functions.next()) {
                count++;
                assertNotNull(functions.getString("FUNCTION_NAME"));
                assertNotNull(functions.getString("FUNCTION_TYPE"));
            }
            assertTrue(count > 0);
        }
        try (ResultSet columns = metadata.getFunctionColumns(catalog, "DEFINITION_SCHEMA", "%", "%")) {
            assertEquals(17, columns.getMetaData().getColumnCount());
        }
        try (ResultSet procedures = metadata.getProcedures(catalog, null, "%")) {
            assertEquals(9, procedures.getMetaData().getColumnCount());
        }
        try (ResultSet columns = metadata.getProcedureColumns(catalog, null, "%", "%")) {
            assertEquals(20, columns.getMetaData().getColumnCount());
        }
    }

    @Test
    @Timeout(60)
    void capabilityMatrixMatchesTheDriverContract() throws Exception {
        assertTrue(metadata.supportsTransactions());
        assertTrue(metadata.supportsBatchUpdates());
        assertTrue(metadata.supportsMultipleResultSets());
        assertTrue(metadata.supportsMultipleTransactions());
        assertTrue(metadata.supportsCorrelatedSubqueries());
        assertTrue(metadata.supportsSubqueriesInComparisons());
        assertTrue(metadata.supportsSubqueriesInExists());
        assertTrue(metadata.supportsSubqueriesInIns());
        assertTrue(metadata.supportsUnion());
        assertTrue(metadata.supportsUnionAll());
        assertTrue(metadata.supportsOuterJoins());
        assertTrue(metadata.supportsFullOuterJoins());
        assertTrue(metadata.supportsColumnAliasing());
        assertTrue(metadata.supportsExpressionsInOrderBy());
        assertTrue(metadata.supportsGroupBy());
        assertTrue(metadata.supportsAlterTableWithAddColumn());
        assertTrue(metadata.supportsSchemasInDataManipulation());
        assertTrue(metadata.supportsCatalogsInDataManipulation());
        assertTrue(metadata.supportsSchemasInTableDefinitions());
        assertTrue(metadata.supportsCatalogsInTableDefinitions());
        assertTrue(metadata.supportsMixedCaseQuotedIdentifiers());
        assertTrue(metadata.autoCommitFailureClosesAllResultSets());
        assertTrue(metadata.storesUpperCaseIdentifiers());
        assertTrue(metadata.nullsAreSortedLow());
        assertFalse(metadata.nullsAreSortedHigh());
        assertFalse(metadata.nullsAreSortedAtStart());
        assertFalse(metadata.nullsAreSortedAtEnd());
        assertTrue(metadata.allProceduresAreCallable());
        assertTrue(metadata.allTablesAreSelectable());
        assertTrue(metadata.nullPlusNonNullIsNull());
        assertFalse(metadata.dataDefinitionCausesTransactionCommit());
        assertFalse(metadata.dataDefinitionIgnoredInTransactions());
        assertTrue(metadata.supportsMinimumSQLGrammar());
        assertTrue(metadata.supportsOpenStatementsAcrossCommit());
        assertTrue(metadata.supportsOpenStatementsAcrossRollback());
        assertTrue(metadata.supportsSchemasInProcedureCalls());
        assertTrue(metadata.supportsStoredFunctionsUsingCallSyntax());
        assertFalse(metadata.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertEquals(0L, metadata.getMaxLogicalLobSize());
        assertEquals(metadata.getUserName().toUpperCase(java.util.Locale.ROOT), metadata.getUserName());
        assertFalse(metadata.supportsSavepoints());
        assertTrue(metadata.supportsStoredProcedures());
        assertFalse(metadata.supportsGetGeneratedKeys());
        assertFalse(metadata.supportsNamedParameters());
        assertFalse(metadata.supportsMultipleOpenResults());
        assertFalse(metadata.supportsConvert());
        assertTrue(metadata.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metadata.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
        assertTrue(metadata.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertFalse(metadata.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertTrue(metadata.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
        assertFalse(metadata.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertTrue(metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, metadata.getDefaultTransactionIsolation());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, metadata.getResultSetHoldability());
        assertEquals(DatabaseMetaData.sqlStateSQL, metadata.getSQLStateType());
        assertEquals(RowIdLifetime.ROWID_UNSUPPORTED, metadata.getRowIdLifetime());
        assertEquals(0, metadata.getMaxConnections());
        assertEquals(0, metadata.getMaxStatements());
    }

    @Test
    @Timeout(60)
    void identifierRulesAndFunctionListsAreUsable() throws Exception {
        assertEquals("\"", metadata.getIdentifierQuoteString());
        assertEquals("\\", metadata.getSearchStringEscape());
        assertEquals(".", metadata.getCatalogSeparator());
        assertEquals("database", metadata.getCatalogTerm());
        assertEquals("schema", metadata.getSchemaTerm());
        assertEquals("procedure", metadata.getProcedureTerm());
        assertEquals("$", metadata.getExtraNameCharacters());
        assertTrue(metadata.getSQLKeywords().contains("ANALYZE"));
        assertTrue(metadata.getNumericFunctions().contains("ABS"));
        assertTrue(metadata.getStringFunctions().contains("LOWER"));
        assertTrue(metadata.getTimeDateFunctions().contains("CURRENT_DATE"));
        assertTrue(metadata.getSystemFunctions().contains("CURRENT_SCHEMA"));
    }

    @Test
    @Timeout(60)
    void wrongCatalogReturnsEmptyResultsWithStableShapes() throws Exception {
        try (ResultSet tables = metadata.getTables("WRONG_CATALOG", null, "%", null)) {
            assertEquals(10, tables.getMetaData().getColumnCount());
            assertFalse(tables.next());
        }
        try (ResultSet columns = metadata.getColumns("WRONG_CATALOG", null, "%", "%")) {
            assertEquals(24, columns.getMetaData().getColumnCount());
            assertFalse(columns.next());
        }
        try (ResultSet keys = metadata.getPrimaryKeys("WRONG_CATALOG", null, "DIMDATE")) {
            assertEquals(6, keys.getMetaData().getColumnCount());
            assertFalse(keys.next());
        }
        try (ResultSet functions = metadata.getFunctions("WRONG_CATALOG", null, "%")) {
            assertEquals(6, functions.getMetaData().getColumnCount());
            assertFalse(functions.next());
        }
        try (ResultSet procedures = metadata.getProcedures("WRONG_CATALOG", null, "%")) {
            assertEquals(9, procedures.getMetaData().getColumnCount());
            assertFalse(procedures.next());
        }
    }

    @Test
    @Timeout(60)
    void optionalMetadataKeepsStandardShapes() throws Exception {
        assertShape(metadata.getTablePrivileges(catalog, null, "%"), 7);
        assertShape(metadata.getColumnPrivileges(catalog, null, "%", "%"), 8);
        assertShape(metadata.getBestRowIdentifier(catalog, "ADMIN", "DIMDATE",
                DatabaseMetaData.bestRowSession, false), 8);
        assertShape(metadata.getVersionColumns(catalog, "ADMIN", "DIMDATE"), 8);
        assertShape(metadata.getClientInfoProperties(), 4);
        try (ResultSet properties = metadata.getClientInfoProperties()) {
            Set<String> names = new HashSet<>();
            while (properties.next()) names.add(properties.getString("NAME"));
            assertTrue(names.contains("ApplicationName"));
            assertTrue(names.contains("ClientUser"));
            assertTrue(names.contains("ClientHostname"));
        }
        assertShape(metadata.getPseudoColumns(catalog, null, "%", "%"), 12);
        assertShape(metadata.getUDTs(catalog, null, "%", null), 7);
        assertShape(metadata.getAttributes(catalog, null, "%", "%"), 21);
        assertShape(metadata.getSuperTypes(catalog, null, "%"), 6);
        assertShape(metadata.getSuperTables(catalog, null, "%"), 4);
    }

    @Test
    @Timeout(60)
    void metadataResultsCanBeIteratedAndClosed() throws Exception {
        try (ResultSet tables = metadata.getTables(catalog, "ADMIN", "DIMDATE", null)) {
            assertTrue(tables.isBeforeFirst());
            assertTrue(tables.next());
            assertEquals(1, tables.getRow());
            ResultSetMetaData resultMetadata = tables.getMetaData();
            assertEquals("TABLE_NAME", resultMetadata.getColumnLabel(3));
            assertFalse(tables.next());
            assertTrue(tables.isAfterLast());
        }
        try (ResultSet catalogs = metadata.getCatalogs()) {
            List<String> values = new ArrayList<>();
            while (catalogs.next()) values.add(catalogs.getString(1));
            assertTrue(values.contains(catalog));
        }
    }

    private static List<String> names(ResultSet result, String column) throws Exception {
        List<String> values = new ArrayList<>();
        try (result) {
            while (result.next()) values.add(result.getString(column));
        }
        return values;
    }

    private static void assertShape(ResultSet result, int columns) throws Exception {
        try (result) {
            assertEquals(columns, result.getMetaData().getColumnCount());
        }
    }
}
