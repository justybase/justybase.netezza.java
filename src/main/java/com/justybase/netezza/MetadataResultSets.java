/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Builds standard JDBC result sets for catalog metadata without extra SQL round-trips. */
final class MetadataResultSets {
    private MetadataResultSets() {}

    record Column(String name, int sqlType, String typeName, boolean nullable,
                  int precision, int scale, int displaySize,
                  boolean caseSensitive, boolean signed, boolean searchable) {
    }

    static Column text(String name, int size) {
        return new Column(name, Types.VARCHAR, "VARCHAR", true, size, 0, size, true, false, true);
    }

    static Column character(String name, int size) {
        return new Column(name, Types.CHAR, "CHAR", true, size, 0, size, true, false, true);
    }

    static Column smallint(String name) {
        return new Column(name, Types.SMALLINT, "SMALLINT", true, 5, 0, 6, false, true, true);
    }

    static Column integer(String name) {
        return new Column(name, Types.INTEGER, "INTEGER", true, 10, 0, 11, false, true, true);
    }

    static Column bigint(String name) {
        return new Column(name, Types.BIGINT, "BIGINT", true, 19, 0, 20, false, true, true);
    }

    static Column bool(String name) {
        return new Column(name, Types.BOOLEAN, "BOOLEAN", true, 1, 0, 5, false, false, true);
    }

    static ResultSet create(List<Column> columns, List<List<Object>> rows) throws SQLException {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            int index = i + 1;
            metadata.setColumnName(index, column.name());
            metadata.setColumnLabel(index, column.name());
            metadata.setColumnType(index, column.sqlType());
            metadata.setColumnTypeName(index, column.typeName());
            metadata.setNullable(index, column.nullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls);
            metadata.setPrecision(index, column.precision());
            metadata.setScale(index, column.scale());
            metadata.setColumnDisplaySize(index, column.displaySize());
            metadata.setCaseSensitive(index, column.caseSensitive());
            metadata.setSigned(index, column.signed());
            metadata.setSearchable(index, column.searchable());
            metadata.setCurrency(index, false);
            metadata.setAutoIncrement(index, false);
            metadata.setTableName(index, "");
            metadata.setSchemaName(index, "");
            metadata.setCatalogName(index, "");
        }
        CachedRowSet result = RowSetProvider.newFactory().createCachedRowSet();
        result.setMetaData(metadata);
        // CachedRowSet inserts the insert-row immediately before the current row. With the
        // cursor before the first row, inserting rows back-to-front preserves caller order.
        for (int rowIndex = rows.size() - 1; rowIndex >= 0; rowIndex--) {
            List<Object> row = rows.get(rowIndex);
            result.moveToInsertRow();
            for (int i = 0; i < columns.size(); i++) {
                Object value = i < row.size() ? row.get(i) : null;
                if (value == null) result.updateNull(i + 1);
                else result.updateObject(i + 1, value);
            }
            result.insertRow();
            result.moveToCurrentRow();
        }
        result.beforeFirst();
        return result;
    }
}
