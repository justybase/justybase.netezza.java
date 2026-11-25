package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
class JdbcMetadataLiveTest {
    private static Connection connection;
    private static DatabaseMetaData metadata;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        metadata = connection.getMetaData();
    }

    @Test
    void reportsServerAndDriverVersionsWithoutUnsupportedExceptions() throws Exception {
        assertTrue(metadata.getDatabaseMajorVersion() > 0);
        assertTrue(metadata.getDatabaseMinorVersion() >= 0);
        assertNotNull(metadata.getDatabaseProductVersion());
        assertTrue(metadata.getDatabaseProductVersion().matches("(?s).*\\d+\\.\\d+.*"),
                metadata.getDatabaseProductVersion());
        assertNotNull(metadata.getDriverVersion());
        assertEquals(4, metadata.getJDBCMajorVersion());
        assertEquals(3, metadata.getJDBCMinorVersion());
    }

    @Test
    void answersAllInformationalCapabilitiesUsedByJdbcTools() throws Exception {
        assertTrue(metadata.supportsTransactions());
        assertTrue(metadata.supportsBatchUpdates());
        assertTrue(metadata.supportsMultipleResultSets());
        assertTrue(metadata.supportsCorrelatedSubqueries());
        assertTrue(metadata.supportsMixedCaseQuotedIdentifiers());
        assertTrue(metadata.supportsCatalogsInTableDefinitions());
        assertTrue(metadata.supportsSchemasInTableDefinitions());
        assertTrue(metadata.isCatalogAtStart());
        assertFalse(metadata.supportsSavepoints());
        assertTrue(metadata.supportsStoredProcedures());
        assertFalse(metadata.storesUpperCaseQuotedIdentifiers());
        assertFalse(metadata.generatedKeyAlwaysReturned());
        assertFalse(metadata.doesMaxRowSizeIncludeBlobs());
        assertFalse(metadata.locatorsUpdateCopy());
        assertTrue(metadata.autoCommitFailureClosesAllResultSets());
    }

    @Test
    void exposesSchemasCatalogsTablesAndColumns() throws Exception {
        assertTrue(count(metadata.getCatalogs()) > 0);
        assertTrue(count(metadata.getSchemas()) > 0);
        assertTrue(count(metadata.getTableTypes()) >= 2);

        try (ResultSet tables = metadata.getTables(connection.getCatalog(), "ADMIN", "DIMDATE",
                new String[]{"TABLE", "VIEW"})) {
            assertTrue(tables.next(), "ADMIN.DIMDATE should be visible through getTables");
            assertEquals("ADMIN", tables.getString("TABLE_SCHEM"));
            assertEquals("DIMDATE", tables.getString("TABLE_NAME"));
            assertEquals("TABLE", tables.getString("TABLE_TYPE"));
            assertFalse(tables.next(), "exact table pattern must not return other schemas");
        }
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), "ADMIN", "DIMDATE", "%")) {
            List<String> names = new ArrayList<>();
            int dateKeyType = -1;
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME"));
                if ("DATEKEY".equals(columns.getString("COLUMN_NAME"))) {
                    dateKeyType = columns.getInt("DATA_TYPE");
                    assertEquals("INTEGER", columns.getString("TYPE_NAME"));
                    assertEquals("NO", columns.getString("IS_AUTOINCREMENT"));
                    assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
                }
            }
            assertEquals(19, names.size(), names.toString());
            assertEquals(java.sql.Types.INTEGER, dateKeyType);
        }
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), "ADMIN", "DIMDATE",
                "ENGLISHDAYNAMEOFWEEK")) {
            assertTrue(columns.next());
            assertEquals(java.sql.Types.NVARCHAR, columns.getInt("DATA_TYPE"));
            assertEquals("NVARCHAR", columns.getString("TYPE_NAME"));
            assertEquals(14, columns.getInt("COLUMN_SIZE"));
            assertEquals(56, columns.getInt("CHAR_OCTET_LENGTH"));
        }
    }

    @Test
    void exposesPrimaryKeysAndReferentialMetadata() throws Exception {
        try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), "ADMIN", "DIMDATE")) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"),
                    columnNames(keys));
            assertTrue(keys.next(), "DIMDATE should have a primary key in _V_RELATION_KEYDATA");
            assertEquals("DATEKEY", keys.getString("COLUMN_NAME"));
            assertEquals(1, keys.getInt("KEY_SEQ"));
            assertEquals("PK_DIMDATE", keys.getString("PK_NAME"));
            assertFalse(keys.next());
        }
        try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), "ADMIN", "FACT_SALES")) {
            assertEquals(14, columnNames(keys).size());
            boolean found = false;
            while (keys.next()) {
                if (!"FK_SALES_DATE".equals(keys.getString("FK_NAME"))) continue;
                found = true;
                assertEquals("DIMDATE", keys.getString("PKTABLE_NAME"));
                assertEquals("DATEKEY", keys.getString("PKCOLUMN_NAME"));
                assertEquals("FACT_SALES", keys.getString("FKTABLE_NAME"));
                assertEquals("SALE_DATE_ID", keys.getString("FKCOLUMN_NAME"));
                assertEquals("PK_DIMDATE", keys.getString("PK_NAME"));
                assertEquals(DatabaseMetaData.importedKeyNoAction, keys.getShort("UPDATE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNoAction, keys.getShort("DELETE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNotDeferrable, keys.getShort("DEFERRABILITY"));
            }
            assertTrue(found, "FACT_SALES should expose its foreign key to DIMDATE");
        }
        try (ResultSet keys = metadata.getExportedKeys(connection.getCatalog(), "ADMIN", "DIMDATE")) {
            boolean found = false;
            while (keys.next()) {
                assertEquals("DIMDATE", keys.getString("PKTABLE_NAME"));
                if ("FACT_SALES".equals(keys.getString("FKTABLE_NAME"))) found = true;
            }
            assertTrue(found, "DIMDATE exports a key to FACT_SALES");
        }
        try (ResultSet keys = metadata.getCrossReference(connection.getCatalog(), "ADMIN", "DIMDATE",
                connection.getCatalog(), "ADMIN", "FACT_SALES")) {
            assertTrue(keys.next());
            assertEquals("FK_SALES_DATE", keys.getString("FK_NAME"));
            assertEquals("DIMDATE", keys.getString("PKTABLE_NAME"));
            assertEquals("FACT_SALES", keys.getString("FKTABLE_NAME"));
        }
        try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), "ADMIN", "DIMDATE", false, true)) {
            assertEquals(13, columns(indexes).size());
            assertFalse(indexes.next(), "Netezza does not expose database indexes");
        }
    }

    @Test
    void exposesTypesFunctionsAndProceduresWithStandardShapes() throws Exception {
        try (ResultSet types = metadata.getTypeInfo()) {
            assertTrue(countRows(types) >= 10);
        }
        try (ResultSet functions = metadata.getFunctions(connection.getCatalog(), "DEFINITION_SCHEMA", "%")) {
            assertEquals(6, columnNames(functions).size());
            assertTrue(countRows(functions) > 0, "Netezza system functions should be visible");
        }
        try (ResultSet procedures = metadata.getProcedures(connection.getCatalog(), null, "%")) {
            assertEquals(9, columnNames(procedures).size());
        }
        try (ResultSet columns = metadata.getFunctionColumns(connection.getCatalog(), "DEFINITION_SCHEMA", "%", "%")) {
            assertEquals(17, columnNames(columns).size());
        }
        try (ResultSet columns = metadata.getProcedureColumns(connection.getCatalog(), null, "%", "%")) {
            assertEquals(20, columnNames(columns).size());
        }
    }

    @Test
    void returnsStandardShapesForOptionalMetadata() throws Exception {
        assertShape(metadata.getTablePrivileges(connection.getCatalog(), null, "%"), 7);
        assertShape(metadata.getColumnPrivileges(connection.getCatalog(), null, "%", "%"), 8);
        assertShape(metadata.getBestRowIdentifier(connection.getCatalog(), null, "DIMDATE",
                DatabaseMetaData.bestRowSession, false), 8);
        assertShape(metadata.getVersionColumns(connection.getCatalog(), null, "DIMDATE"), 8);
        assertShape(metadata.getClientInfoProperties(), 4);
        assertShape(metadata.getPseudoColumns(connection.getCatalog(), null, "%", "%"), 12);
        assertShape(metadata.getUDTs(connection.getCatalog(), null, "%", null), 7);
        assertShape(metadata.getAttributes(connection.getCatalog(), null, "%", "%"), 21);
        assertShape(metadata.getSuperTypes(connection.getCatalog(), null, "%"), 6);
        assertShape(metadata.getSuperTables(connection.getCatalog(), null, "%"), 4);
    }

    @Test
    void throwsOnlyForGenuinelyUnsupportedOptionalBehavior() throws Exception {
        try (var callable = connection.prepareCall("CALL X()")) {
            assertNotNull(callable);
        }
        assertThrows(SQLException.class, () -> connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertThrows(SQLException.class, () -> connection.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertThrows(SQLException.class, () -> connection.prepareStatement(
                "SELECT 1", Statement.RETURN_GENERATED_KEYS));
    }

    private static int count(ResultSet result) throws SQLException {
        try (result) {
            return countRows(result);
        }
    }

    private static int countRows(ResultSet result) throws SQLException {
        int rows = 0;
        while (result.next()) rows++;
        return rows;
    }

    private static List<String> columns(ResultSet result) throws SQLException {
        try (result) {
            return columnNames(result);
        }
    }

    private static List<String> columnNames(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) names.add(metadata.getColumnLabel(i));
        return names;
    }

    private static void assertShape(ResultSet result, int expectedColumns) throws SQLException {
        try (result) {
            assertEquals(expectedColumns, result.getMetaData().getColumnCount());
        }
    }
}
