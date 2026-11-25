package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataResultSetsTest {
    @Test
    void preservesRowOrderAndExposesDeclaredTypes() throws Exception {
        try (ResultSet result = MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_SCHEM", 128),
                MetadataResultSets.integer("KEY_SEQ"),
                MetadataResultSets.bool("CASE_SENSITIVE")), List.of(
                List.of("ADMIN", 1, true),
                List.of("CONFORMANCE", 2, false),
                List.of("INFORMATION_SCHEMA", 3, true)))) {
            assertTrue(result.next());
            assertEquals("ADMIN", result.getString(1));
            assertEquals(1, result.getInt("KEY_SEQ"));
            assertTrue(result.getBoolean("CASE_SENSITIVE"));
            assertFalse(result.wasNull());

            assertTrue(result.next());
            assertEquals("CONFORMANCE", result.getString("TABLE_SCHEM"));
            assertEquals(2, result.getInt(2));
            assertFalse(result.getBoolean(3));

            assertTrue(result.next());
            assertEquals("INFORMATION_SCHEMA", result.getString(1));
            assertFalse(result.next());

            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(3, metadata.getColumnCount());
            assertEquals("TABLE_SCHEM", metadata.getColumnLabel(1));
            assertEquals(Types.VARCHAR, metadata.getColumnType(1));
            assertEquals(Types.INTEGER, metadata.getColumnType(2));
            assertEquals(Types.BOOLEAN, metadata.getColumnType(3));
            assertEquals("java.lang.String", metadata.getColumnClassName(1));
        }
    }

    @Test
    void keepsMetadataForEmptyResults() throws Exception {
        try (ResultSet result = MetadataResultSets.create(List.of(
                MetadataResultSets.text("TABLE_CAT", 128),
                MetadataResultSets.smallint("KEY_SEQ"),
                MetadataResultSets.bigint("CARDINALITY")), List.of())) {
            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(3, metadata.getColumnCount());
            assertEquals("TABLE_CAT", metadata.getColumnName(1));
            assertEquals(Types.SMALLINT, metadata.getColumnType(2));
            assertEquals(Types.BIGINT, metadata.getColumnType(3));
            assertFalse(result.next());
        }
    }

    @Test
    void allowsNullValuesAndFindColumnByLabel() throws Exception {
        try (ResultSet result = MetadataResultSets.create(List.of(
                MetadataResultSets.text("A", 10),
                MetadataResultSets.text("B", 10)), List.of(
                java.util.Arrays.asList("value", null)))) {
            assertTrue(result.next());
            assertEquals("value", result.getString("a"));
            assertEquals(1, result.findColumn("A"));
            assertEquals(2, result.findColumn("b"));
            assertEquals(null, result.getString("B"));
            assertTrue(result.wasNull());
        }
    }
}
