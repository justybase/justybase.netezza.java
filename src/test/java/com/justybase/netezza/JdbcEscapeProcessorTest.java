package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC escape processing without a live appliance. */
class JdbcEscapeProcessorTest {
    @Test
    void translatesDateAndTimestampEscapes() {
        assertEquals("DATE '2024-02-29'", JdbcEscapeProcessor.process("{d '2024-02-29'}"));
        assertEquals("TIME '12:34:56'", JdbcEscapeProcessor.process("{t '12:34:56'}"));
        assertEquals("TIMESTAMP '2024-02-29 12:34:56.123456'",
                JdbcEscapeProcessor.process("{ts '2024-02-29 12:34:56.123456'}"));
    }

    @Test
    void translatesFunctionEscapesAndNestedBraces() {
        assertEquals("UPPER('abc')", JdbcEscapeProcessor.process("{fn UCASE('abc')}"));
        assertEquals("CONCAT(UPPER('a'), 'b')",
                JdbcEscapeProcessor.process("{fn CONCAT({fn UCASE('a')}, 'b')}"));
        assertEquals("CURRENT_DATE", JdbcEscapeProcessor.process("{fn CURRENT_DATE()}"));
        assertEquals("LN(10)", JdbcEscapeProcessor.process("{fn LOG(10)}"));
    }

    @Test
    void translatesCallOuterJoinAndLikeEscapes() {
        assertEquals("CALL CONFORMANCE.P(?, ?)", JdbcEscapeProcessor.process("{call CONFORMANCE.P(?, ?)}"));
        assertEquals("SELECT F(?)", JdbcEscapeProcessor.process("{? = call F(?)}"));
        assertEquals("T LEFT OUTER JOIN U ON T.ID = U.ID",
                JdbcEscapeProcessor.process("{oj T LEFT OUTER JOIN U ON T.ID = U.ID}"));
        assertEquals("NAME LIKE 'a!_%' ESCAPE '!'",
                JdbcEscapeProcessor.process("NAME LIKE 'a!_%' {escape '!'}"));
    }

    @Test
    void leavesQuotedTextAndCommentsUntouched() {
        String sql = "SELECT '{fn UCASE(x)}' AS S, \"{d '2024-01-01'}\" AS Q -- {ts 'x'}\n"
                + "/* {call P()} */ FROM T";
        assertEquals(sql, JdbcEscapeProcessor.process(sql));
    }

    @Test
    void treatsBackslashAsAnOrdinaryCharacterInLiterals() {
        assertEquals("SELECT 'C:\\', UPPER('a')",
                JdbcEscapeProcessor.process("SELECT 'C:\\', {fn UCASE('a')}"));
    }

    @Test
    void handlesNestedBlockComments() {
        String sql = "SELECT 1 /* outer /* inner */ {fn UCASE('a')} still outer */, {fn UCASE('b')}";
        assertEquals("SELECT 1 /* outer /* inner */ {fn UCASE('a')} still outer */, UPPER('b')",
                JdbcEscapeProcessor.process(sql));
    }

    @Test
    void leavesUnclosedBraceVerbatim() {
        assertEquals("SELECT {fn UCASE(x)", JdbcEscapeProcessor.process("SELECT {fn UCASE(x)"));
    }
}
