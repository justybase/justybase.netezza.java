/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.sql.SQLException;
import java.util.Map;

/**
 * Lexical scanning for Netezza SQL.
 *
 * <p>Netezza treats a backslash as an ordinary character inside string literals
 * and quoted identifiers; only a doubled quote escapes a quote. Block comments
 * can be nested. Keeping these rules in one place prevents the prepared
 * statement binder, the bulk INSERT parser and the JDBC escape translator from
 * disagreeing about where a literal or comment ends.</p>
 */
final class SqlScanner {
    private SqlScanner() {
    }

    @FunctionalInterface
    interface ParameterRenderer {
        String render(int index) throws SQLException;
    }

    static int parameterCount(String sql) {
        return parameterCount(sql, 0, sql.length());
    }

    static int parameterCount(String sql, int from, int to) {
        int count = 0;
        int end = Math.min(to, sql.length());
        int i = Math.max(0, from);
        while (i < end) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < end && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < end && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '?') count++;
            i++;
        }
        return count;
    }

    static String substitute(String sql, Map<Integer, Object> values, ParameterRenderer renderer)
            throws SQLException {
        return substitute(sql, values.size(), index -> {
            if (!values.containsKey(index))
                throw new SQLException("No value assigned for parameter " + index, "07001");
            return renderer.render(index);
        });
    }

    /**
     * Renders a parameter snapshot. The count is the number of parameters
     * expected by the statement, so a missing value is rendered as {@code NULL}
     * exactly like the map overload, without allocating a map per row.
     */
    static String substitute(String sql, int parameterCount, ParameterRenderer renderer)
            throws SQLException {
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int index = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = skipLineComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = skipBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                index++;
                if (index > parameterCount)
                    throw new SQLException("No value assigned for parameter " + index, "07001");
                out.append(renderer.render(index));
            } else {
                out.append(c);
            }
            i++;
        }
        if (index < parameterCount)
            throw new SQLException("Too many parameters set: expected " + index + ", got " + parameterCount, "07001");
        return out.toString();
    }

    /** Returns the index just after a quoted literal or identifier, or the input length if unterminated. */
    static int skipQuoted(String sql, int start) {
        char quote = sql.charAt(start);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i++);
            if (c == quote) {
                if (i < sql.length() && sql.charAt(i) == quote) {
                    i++;
                    continue;
                }
                return i;
            }
        }
        return i;
    }

    /** Returns the index of the terminating newline, or the input length. */
    static int skipLineComment(String sql, int start) {
        int i = start + 2;
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        return i;
    }

    /** Returns the index just after the matching comment terminator, supporting nested comments. */
    static int skipBlockComment(String sql, int start) {
        int depth = 1;
        int i = start + 2;
        while (i < sql.length()) {
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
                continue;
            }
            if (i + 1 < sql.length() && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                if (--depth == 0) return i + 2;
                i += 2;
                continue;
            }
            i++;
        }
        return sql.length();
    }

    static int skipTrivia(String sql, int index) {
        int i = index;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            break;
        }
        return i;
    }

    static boolean keywordAt(String sql, int index, String keyword) {
        int end = index + keyword.length();
        if (end > sql.length() || !sql.regionMatches(true, index, keyword, 0, keyword.length())) return false;
        return (index == 0 || !identifierPart(sql.charAt(index - 1)))
                && (end == sql.length() || !identifierPart(sql.charAt(end)));
    }

    static int findTopLevelKeyword(String sql, String keyword, int from) {
        int depth = 0;
        for (int i = from; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) depth--;
                i++;
                continue;
            }
            if (depth == 0 && keywordAt(sql, i, keyword)) return i;
            i++;
        }
        return -1;
    }

    static int matchingParen(String sql, int open) {
        int depth = 0;
        for (int i = open; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (--depth == 0) return i;
                i++;
                continue;
            }
            i++;
        }
        return -1;
    }

    private static boolean identifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
