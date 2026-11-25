package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises catalog metadata against tables, a view and composite keys in the conformance schema. */
@Tag("live")
class JdbcMetadataDdlLiveTest {
    private Connection connection;
    private DatabaseMetaData metadata;
    private String schema;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        assumeTrue("1".equals(LiveTestSupport.value("NZ_DEV_ALLOW_FIXTURE_DDL")),
                "NZ_DEV_ALLOW_FIXTURE_DDL=1 is required for metadata DDL tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        metadata = connection.getMetaData();
        schema = LiveTestSupport.schema();
        dropFixtures();
        LiveTestSupport.execute(connection, "CREATE TABLE " + schema
                + ".META_PARENT (P_ID INTEGER, P_SUB INTEGER, LABEL VARCHAR(20), "
                + "CONSTRAINT PK_META_PARENT PRIMARY KEY (P_ID, P_SUB))");
        LiveTestSupport.execute(connection, "CREATE TABLE " + schema
                + ".META_CHILD (C_ID INTEGER, P_ID INTEGER, P_SUB INTEGER, "
                + "CONSTRAINT FK_META_CHILD FOREIGN KEY (P_ID, P_SUB) "
                + "REFERENCES " + schema + ".META_PARENT (P_ID, P_SUB))");
        LiveTestSupport.execute(connection, "CREATE VIEW " + schema
                + ".META_VIEW AS SELECT P_ID, P_SUB, LABEL FROM " + schema + ".META_PARENT");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection == null) return;
        try {
            dropFixtures();
        } finally {
            connection.close();
        }
    }

    @Test
    @Timeout(60)
    void exposesTablesViewsAndColumns() throws Exception {
        List<String> found = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), schema, "META_%",
                new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                String type = tables.getString("TABLE_TYPE");
                found.add(name + ":" + type);
                if (name.equals("META_VIEW")) assertEquals("VIEW", type);
                else assertEquals("TABLE", type);
                assertEquals(schema, tables.getString("TABLE_SCHEM"));
                assertEquals(connection.getCatalog(), tables.getString("TABLE_CAT"));
            }
        }
        assertTrue(found.contains("META_PARENT:TABLE"), found.toString());
        assertTrue(found.contains("META_CHILD:TABLE"), found.toString());
        assertTrue(found.contains("META_VIEW:VIEW"), found.toString());

        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), schema, "META_PARENT", "%")) {
            List<String> names = new ArrayList<>();
            while (columns.next()) names.add(columns.getString("COLUMN_NAME"));
            assertEquals(List.of("P_ID", "P_SUB", "LABEL"), names);
        }
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), schema, "META_VIEW", "%")) {
            List<String> names = new ArrayList<>();
            while (columns.next()) names.add(columns.getString("COLUMN_NAME"));
            assertEquals(List.of("P_ID", "P_SUB", "LABEL"), names);
        }
    }

    @Test
    @Timeout(60)
    void exposesCompositePrimaryKeyInOrder() throws Exception {
        List<String> columns = new ArrayList<>();
        List<Integer> sequences = new ArrayList<>();
        try (ResultSet keys = metadata.getPrimaryKeys(connection.getCatalog(), schema, "META_PARENT")) {
            while (keys.next()) {
                columns.add(keys.getString("COLUMN_NAME"));
                sequences.add(keys.getInt("KEY_SEQ"));
                assertEquals("PK_META_PARENT", keys.getString("PK_NAME"));
            }
        }
        assertEquals(List.of("P_ID", "P_SUB"), columns);
        assertEquals(List.of(1, 2), sequences);
    }

    @Test
    @Timeout(60)
    void exposesCompositeForeignKeysInBothDirections() throws Exception {
        List<String> imported = new ArrayList<>();
        List<Integer> importedSequences = new ArrayList<>();
        try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), schema, "META_CHILD")) {
            while (keys.next()) {
                imported.add(keys.getString("FKCOLUMN_NAME") + "->" + keys.getString("PKCOLUMN_NAME"));
                importedSequences.add(keys.getInt("KEY_SEQ"));
                assertEquals("FK_META_CHILD", keys.getString("FK_NAME"));
                assertEquals("PK_META_PARENT", keys.getString("PK_NAME"));
                assertEquals(schema, keys.getString("FKTABLE_SCHEM"));
                assertEquals(schema, keys.getString("PKTABLE_SCHEM"));
                assertEquals(connection.getCatalog(), keys.getString("FKTABLE_CAT"));
                assertEquals(connection.getCatalog(), keys.getString("PKTABLE_CAT"));
                assertEquals(DatabaseMetaData.importedKeyNoAction, keys.getShort("UPDATE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNoAction, keys.getShort("DELETE_RULE"));
                assertEquals(DatabaseMetaData.importedKeyNotDeferrable, keys.getShort("DEFERRABILITY"));
            }
        }
        assertEquals(List.of("P_ID->P_ID", "P_SUB->P_SUB"), imported);
        assertEquals(List.of(1, 2), importedSequences);

        int exported = 0;
        try (ResultSet keys = metadata.getExportedKeys(connection.getCatalog(), schema, "META_PARENT")) {
            while (keys.next()) {
                exported++;
                assertEquals("META_CHILD", keys.getString("FKTABLE_NAME"));
                assertEquals("FK_META_CHILD", keys.getString("FK_NAME"));
            }
        }
        assertEquals(2, exported);

        int crossed = 0;
        try (ResultSet keys = metadata.getCrossReference(connection.getCatalog(), schema, "META_PARENT",
                connection.getCatalog(), schema, "META_CHILD")) {
            while (keys.next()) crossed++;
        }
        assertEquals(2, crossed);

        try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), schema, "META_PARENT")) {
            assertFalse(keys.next(), "META_PARENT has no imported keys");
        }
    }

    private void dropFixtures() throws SQLException {
        try {
            LiveTestSupport.execute(connection, "DROP VIEW " + schema + ".META_VIEW");
        } catch (SQLException ignored) {
            // Netezza does not support DROP VIEW IF EXISTS.
        }
        LiveTestSupport.execute(connection, "DROP TABLE " + schema + ".META_CHILD IF EXISTS");
        LiveTestSupport.execute(connection, "DROP TABLE " + schema + ".META_PARENT IF EXISTS");
    }
}
