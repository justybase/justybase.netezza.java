package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Literal substitution edge cases for client-side prepared statements. */
class PreparedSqlEdgeTest {
    @Test
    void preservesCommentsAndDoesNotBindQuestionMarksInsideThem() throws Exception {
        String sql = """
                SELECT ? AS first, ? AS second -- trailing ? comment
                /* block ? comment
                   spanning lines ? */
                FROM (SELECT 1 AS dummy) X
                """;
        String rendered = NetezzaStatement.substitute(sql, Map.of(1, 10, 2, "twenty"));
        assertTrue(rendered.contains("SELECT 10 AS first, 'twenty' AS second"), rendered);
        assertTrue(rendered.contains("-- trailing ? comment"), rendered);
        assertTrue(rendered.contains("/* block ? comment"), rendered);
        assertTrue(rendered.contains("spanning lines ? */"), rendered);
    }

    @Test
    void preservesQuotedIdentifiersAndDoubledQuotes() throws Exception {
        assertEquals("SELECT 'O''Brien', \"odd?\"\"name\", 7 FROM T",
                NetezzaStatement.substitute(
                        "SELECT 'O''Brien', \"odd?\"\"name\", ? FROM T", Map.of(1, 7)));
    }

    @Test
    void treatsBackslashAsAnOrdinaryCharacterInLiterals() throws Exception {
        String sql = "SELECT 'C:\\' AS PATH, ? AS VALUE";
        assertEquals(1, NetezzaStatement.parameterCount(sql));
        assertEquals("SELECT 'C:\\' AS PATH, 7 AS VALUE",
                NetezzaStatement.substitute(sql, Map.of(1, 7)));
    }

    @Test
    void handlesNestedBlockComments() throws Exception {
        String sql = "SELECT ? /* outer /* inner ? */ still outer */, ?";
        assertEquals(2, NetezzaStatement.parameterCount(sql));
        assertEquals("SELECT 1 /* outer /* inner ? */ still outer */, 2",
                NetezzaStatement.substitute(sql, ordered(1, 1, 2, 2)));
    }

    @Test
    void rejectsUnsupportedLiteralTypes() throws Exception {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> NetezzaStatement.literal(new java.io.ByteArrayInputStream(new byte[0])));
        assertEquals("0A000", error.getSQLState());
        assertEquals("'11111111-2222-3333-4444-555555555555'",
                NetezzaStatement.literal(java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")));
    }

    @Test
    void bindsParametersInOrderAcrossWhitespaceAndFunctions() throws Exception {
        String rendered = NetezzaStatement.substitute(
                "SELECT COALESCE(?, ?), CASE WHEN ? THEN ? ELSE ? END, ?", ordered(
                        1, null,
                        2, "fallback",
                        3, true,
                        4, 4.5,
                        5, 6,
                        6, 'z'));
        assertEquals("SELECT COALESCE(NULL, 'fallback'), CASE WHEN TRUE THEN 4.5 ELSE 6 END, 'z'", rendered);
    }

    @Test
    void rendersEverySupportedLiteralType() throws Exception {
        byte[] bytes = {0, 1, (byte) 0xFF};
        Map<Integer, Object> values = ordered(
                1, null,
                2, true,
                3, 42,
                4, -17L,
                5, new BigDecimal("123.4500"),
                6, 1.5d,
                7, bytes,
                8, Date.valueOf("2024-02-29"),
                9, Time.valueOf("12:34:56"),
                10, Timestamp.valueOf("2024-02-29 12:34:56.123456"),
                11, new java.util.Date(0),
                12, "O'Brien\n\u017c\u00f3\u0142\u0107");
        String rendered = NetezzaStatement.substitute(
                "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?", values);
        String epoch = new Timestamp(0).toLocalDateTime().toString().replace('T', ' ');
        if (epoch.length() == 16) epoch += ":00";
        assertEquals("SELECT NULL, TRUE, 42, -17, 123.4500, 1.5, x'0001ff', "
                + "DATE '2024-02-29', TIME '12:34:56', TIMESTAMP '2024-02-29 12:34:56.123456', "
                + "TIMESTAMP '" + epoch + "', 'O''Brien\n\u017c\u00f3\u0142\u0107'", rendered);
    }

    @Test
    void escapesSingleQuotesButLeavesPercentAndUnderscoreUntouched() throws Exception {
        assertEquals("SELECT 'a''b', '100%', 'a_b', 'line1\nline2'",
                NetezzaStatement.substitute("SELECT ?, ?, ?, ?",
                        ordered(1, "a'b", 2, "100%", 3, "a_b", 4, "line1\nline2")));
    }

    @Test
    void rejectsMissingAndExtraParametersWithJdbcState() {
        SQLException missingFirst = assertThrows(SQLException.class,
                () -> NetezzaStatement.substitute("SELECT ?, ?", Map.of(2, 'b')));
        assertEquals("07001", missingFirst.getSQLState());
        assertTrue(missingFirst.getMessage().contains("1"));

        SQLException extra = assertThrows(SQLException.class,
                () -> NetezzaStatement.substitute("SELECT ?", ordered(1, 'a', 2, 'b')));
        assertEquals("07001", extra.getSQLState());

        SQLException noMarker = assertThrows(SQLException.class,
                () -> NetezzaStatement.substitute("SELECT 1", Map.of(1, 'a')));
        assertEquals("07001", noMarker.getSQLState());
    }

    @Test
    void countsParametersOutsideQuotesAndComments() {
        assertEquals(2, NetezzaStatement.parameterCount(
                "SELECT ?, '?' AS literal -- ? in comment\n, ? /* ? in block */ FROM T"));
        assertEquals(0, NetezzaStatement.parameterCount("SELECT '?', \"?\" -- ?"));
    }

    @Test
    void handlesQuestionMarksAtEveryBoundary() throws Exception {
        assertEquals("10", NetezzaStatement.substitute("?", Map.of(1, 10)));
        assertEquals("10 -- ?\n", NetezzaStatement.substitute("? -- ?\n", Map.of(1, 10)));
        assertEquals("SELECT 10", NetezzaStatement.substitute("SELECT ?", Map.of(1, 10)));
        assertEquals("SELECT 10\n", NetezzaStatement.substitute("SELECT ?\n", Map.of(1, 10)));
    }

    @Test
    void leavesAnUnterminatedQuotedSectionVerbatim() throws Exception {
        String sql = "SELECT 'unterminated ?";
        assertEquals(sql, NetezzaStatement.substitute(sql, Map.of()));
    }

    @Test
    void doesNotTreatQuestionMarksInStringsAsPlaceholdersEvenAfterCommentMarkers() throws Exception {
        String sql = "SELECT '-- not a comment ?', '/* not a comment ? */', ? AS bound";
        assertEquals("SELECT '-- not a comment ?', '/* not a comment ? */', 7 AS bound",
                NetezzaStatement.substitute(sql, Map.of(1, 7)));
    }

    private static Map<Integer, Object> ordered(Object... pairs) {
        Map<Integer, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((Integer) pairs[i], pairs[i + 1]);
        return values;
    }
}
