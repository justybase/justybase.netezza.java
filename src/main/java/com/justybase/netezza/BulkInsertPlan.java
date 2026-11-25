/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

/**
 * Rewrites a simple {@code INSERT ... VALUES (...)} statement into one
 * {@code INSERT ... SELECT ... FROM EXTERNAL} statement. The external table
 * columns are supplied by {@link BulkInsertData}; the original value
 * expressions are preserved, so casts and simple expressions keep working.
 */
final class BulkInsertPlan {
    private final String prefix;
    private final String values;
    private final int columns;

    private BulkInsertPlan(String prefix, String values, int columns) {
        this.prefix = prefix;
        this.values = values;
        this.columns = columns;
    }

    int columns() {
        return columns;
    }

    /**
     * Returns a plan when {@code sql} is a single-row {@code INSERT ... VALUES}
     * whose placeholders are exactly the batch parameters, otherwise {@code null}.
     */
    static BulkInsertPlan parse(String sql, int parameterCount) {
        if (sql == null || parameterCount < 1) return null;
        int start = SqlScanner.skipTrivia(sql, 0);
        if (!SqlScanner.keywordAt(sql, start, "INSERT")) return null;

        int valuesStart = SqlScanner.findTopLevelKeyword(sql, "VALUES", start);
        if (valuesStart < 0) return null;
        if (SqlScanner.parameterCount(sql, start, valuesStart) != 0) return null;

        int open = SqlScanner.skipTrivia(sql, valuesStart + "VALUES".length());
        if (open >= sql.length() || sql.charAt(open) != '(') return null;
        int close = SqlScanner.matchingParen(sql, open);
        if (close < 0) return null;

        int after = SqlScanner.skipTrivia(sql, close + 1);
        if (after < sql.length() && sql.charAt(after) == ';') after = SqlScanner.skipTrivia(sql, after + 1);
        if (after < sql.length()) return null;

        String tuple = sql.substring(open + 1, close);
        // DEFAULT is only valid in an INSERT target list, not in a SELECT list.
        if (SqlScanner.findTopLevelKeyword(tuple, "DEFAULT", 0) >= 0) return null;
        StringBuilder rewritten = new StringBuilder(tuple.length() + 8);
        int parameter = 0;
        for (int i = 0; i < tuple.length(); ) {
            char c = tuple.charAt(i);
            if (c == '\'' || c == '"') {
                int end = SqlScanner.skipQuoted(tuple, i);
                rewritten.append(tuple, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < tuple.length() && tuple.charAt(i + 1) == '-') {
                int end = SqlScanner.skipLineComment(tuple, i);
                rewritten.append(tuple, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < tuple.length() && tuple.charAt(i + 1) == '*') {
                int end = SqlScanner.skipBlockComment(tuple, i);
                rewritten.append(tuple, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                if (++parameter > parameterCount) return null;
                rewritten.append('c').append(parameter - 1);
                i++;
                continue;
            }
            rewritten.append(c);
            i++;
        }
        if (parameter != parameterCount) return null;

        String prefix = sql.substring(0, valuesStart).trim();
        if (prefix.isEmpty()) return null;
        return new BulkInsertPlan(prefix, rewritten.toString(), parameterCount);
    }

    String sql(String marker, BulkInsertData data) {
        return prefix + " SELECT " + values
                + " FROM EXTERNAL '" + marker + "' (" + data.columnDefinitions() + ")"
                + " USING (" + BulkInsertData.USING_CLAUSE + ")";
    }
}
