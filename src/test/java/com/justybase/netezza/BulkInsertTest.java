package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BulkInsertTest {
    @Test
    void rewritesSingleRowValuesInsert() {
        BulkInsertPlan plan = BulkInsertPlan.parse("INSERT INTO T (A, B) VALUES (?, ?)", 2);
        assertNotNull(plan);
        BulkInsertData data = data(
                row(1, "one"),
                row(2, "two"));
        String sql = plan.sql("NZ_BULK_1", data);
        assertTrue(sql.startsWith("INSERT INTO T (A, B) SELECT c0, c1 FROM EXTERNAL 'NZ_BULK_1'"));
        assertTrue(sql.contains("c0 nvarchar(1)"));
        assertTrue(sql.contains("c1 nvarchar(3)"));
        assertTrue(sql.contains("REMOTESOURCE 'jdbc'"));
        assertTrue(sql.contains("NULLVALUE '\\N'"));
        assertFalse(sql.contains("VALUES"));
    }

    @Test
    void parsesBareInsertWithoutColumnList() {
        BulkInsertPlan plan = BulkInsertPlan.parse("INSERT INTO JAVA_BULK_INSERT VALUES (?, ?, ?, ?, ?, ?)", 6);
        assertNotNull(plan);
    }

    @Test
    void keepsExpressionsAroundPlaceholders() {
        BulkInsertPlan plan = BulkInsertPlan.parse("insert into T values (?, ? + 1)", 2);
        assertNotNull(plan);
        assertTrue(plan.sql("M", data(row(1, 2), row(3, 4))).contains("SELECT c0, c1 + 1 FROM EXTERNAL"));
    }

    @Test
    void rejectsStatementsThatNeedRowByRowExecution() {
        assertNull(BulkInsertPlan.parse("INSERT INTO T SELECT ?", 1));
        assertNull(BulkInsertPlan.parse("INSERT INTO T VALUES (?, ?), (?, ?)", 4));
        assertNull(BulkInsertPlan.parse("UPDATE T SET A = ?", 1));
        assertNull(BulkInsertPlan.parse("INSERT INTO T VALUES (?, ?)", 1));
        assertNull(BulkInsertPlan.parse("INSERT INTO T (A, B) VALUES (?, DEFAULT)", 1));
        assertNull(BulkInsertPlan.parse("INSERT INTO T (A, B) VALUES (DEFAULT, ?)", 1));
    }

    @Test
    void handlesBackslashBeforeQuoteAndNestedComments() {
        BulkInsertPlan literal = BulkInsertPlan.parse("INSERT INTO T VALUES (?, 'C:\\')", 1);
        assertNotNull(literal);
        assertTrue(literal.sql("M", data(row(1))).contains("SELECT c0, 'C:\\' FROM EXTERNAL"));

        BulkInsertPlan commented = BulkInsertPlan.parse(
                "INSERT INTO T VALUES (?, /* outer /* inner */ still outer */ ?)", 2);
        assertNotNull(commented);
        assertTrue(commented.sql("M", data(row(1, 2), row(3, 4)))
                .contains("SELECT c0, /* outer /* inner */ still outer */ c1 FROM EXTERNAL"));
    }

    @Test
    void rendersUuidValues() throws Exception {
        java.util.UUID uuid = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(uuid + "\n", payload(data(row(uuid)), 1024));
    }

    @Test
    void rendersNullsTabsNewlinesAndBackslashes() throws Exception {
        BulkInsertData data = data(row(1, "a\tb\nc\\d", null));
        assertEquals("c0 nvarchar(1), c1 nvarchar(7), c2 nvarchar(1)", data.columnDefinitions());
        String text = payload(data, 7);
        assertEquals("1\ta\\\tb\\\nc\\\\d\t\\N\n", text);
    }

    @Test
    void escapesTheNullMarkerInsteadOfStoringNull() throws Exception {
        assertEquals("\\\\N\n", payload(data(row("\\N")), 1024));
    }

    @Test
    void rendersTemporalAndNumericValues() throws Exception {
        BulkInsertData data = data(row(42, new java.math.BigDecimal("12.50"),
                LocalDate.of(2024, 2, 29), LocalDateTime.of(2024, 2, 29, 23, 59, 58, 123_456_000)));
        String text = payload(data, 1024);
        assertEquals("42\t12.50\t2024-02-29\t2024-02-29 23:59:58.123456\n", text);
    }

    @Test
    void fallsBackForBinaryValues() throws Exception {
        assertNull(BulkInsertData.create(1, List.<Object[]>of(new Object[]{new byte[]{1, 2, 3}})));
    }

    @Test
    void splitsLargeRowsAcrossBlocks() throws Exception {
        String value = "x".repeat(40);
        BulkInsertData data = data(row(value));
        String text = payload(data, 8);
        assertEquals(value + "\n", text);
    }

    @Test
    void matchesStringGetBytesForSurrogatesAndUnicode() throws Exception {
        // String.getBytes(UTF_8) replaces a lone surrogate with '?'; the direct
        // encoder must produce byte-identical output.
        assertEquals("a?b?c\n", payload(data(row("a\uD800b\uDC00c")), 1024));
        assertEquals("😀 zażółć\n", payload(data(row("😀 zażółć")), 3));
    }

    @Test
    void streamsRowsLargerThanTheBlockWithTinyBlocks() throws Exception {
        String value = "abcdefghij".repeat(100);
        assertEquals(value + "\n", payload(data(row(value)), 5));
    }

    private static BulkInsertData data(Object[]... rows) {
        try {
            BulkInsertData data = BulkInsertData.create(rows[0].length, List.of(rows));
            assertNotNull(data);
            return data;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static String payload(BulkInsertData data, int blockSize) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        data.writeRows(out, blockSize);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            int status = in.readInt();
            if (status == 3) break;
            assertEquals(1, status);
            int length = in.readInt();
            payload.write(in.readNBytes(length));
        }
        return payload.toString(StandardCharsets.UTF_8);
    }
}
