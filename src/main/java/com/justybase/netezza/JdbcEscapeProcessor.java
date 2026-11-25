/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.util.Locale;
import java.util.Map;

/** Translates JDBC escape sequences into Netezza SQL without touching literals or comments. */
final class JdbcEscapeProcessor {
    private static final Map<String,String> FUNCTIONS = Map.ofEntries(
            Map.entry("UCASE", "UPPER"),
            Map.entry("LCASE", "LOWER"),
            Map.entry("IFNULL", "COALESCE"),
            Map.entry("CEILING", "CEIL"),
            Map.entry("LOG", "LN"),
            Map.entry("RAND", "RANDOM"),
            Map.entry("TRUNCATE", "TRUNC"),
            Map.entry("CURDATE", "CURRENT_DATE"),
            Map.entry("CURTIME", "CURRENT_TIME"),
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CURRENT_DATE", "CURRENT_DATE"),
            Map.entry("CURRENT_TIME", "CURRENT_TIME"),
            Map.entry("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP")
    );

    private JdbcEscapeProcessor() {}

    static String process(String sql) {
        if (sql == null || sql.indexOf('{') < 0) return sql;
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = SqlScanner.skipQuoted(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = SqlScanner.skipLineComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = SqlScanner.skipBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = closingBrace(sql, i);
            if (close < 0) {
                out.append(sql, i, sql.length());
                break;
            }
            out.append(translate(sql.substring(i + 1, close).trim()));
            i = close + 1;
        }
        return out.toString();
    }

    private static String translate(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fn ")) return function(body.substring(3).trim());
        if (lower.startsWith("d ")) return "DATE " + body.substring(2).trim();
        if (lower.startsWith("t ")) return "TIME " + body.substring(2).trim();
        if (lower.startsWith("ts ")) return "TIMESTAMP " + body.substring(3).trim();
        if (lower.startsWith("escape ")) return "ESCAPE " + process(body.substring(7).trim());
        if (lower.startsWith("oj ")) return process(body.substring(3).trim());
        if (lower.startsWith("call ")) return "CALL " + process(body.substring(5).trim());
        if (lower.startsWith("? = call ")) return "SELECT " + process(body.substring(9).trim());
        return process(body);
    }

    private static String function(String expression) {
        String text = process(expression.trim());
        int open = text.indexOf('(');
        if (open <= 0) {
            String upper = text.toUpperCase(Locale.ROOT);
            return FUNCTIONS.getOrDefault(upper, text);
        }
        String name = text.substring(0, open).trim();
        String upper = name.toUpperCase(Locale.ROOT);
        String mapped = FUNCTIONS.get(upper);
        if (mapped == null) return text;
        if (upper.equals("CURDATE") || upper.equals("CURTIME") || upper.equals("NOW")
                || upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME") || upper.equals("CURRENT_TIMESTAMP")) {
            return mapped;
        }
        return mapped + text.substring(open);
    }

    private static int closingBrace(String sql, int open) {
        int depth = 1;
        int i = open + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = SqlScanner.skipQuoted(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = SqlScanner.skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = SqlScanner.skipBlockComment(sql, i);
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
            i++;
        }
        return -1;
    }
}
