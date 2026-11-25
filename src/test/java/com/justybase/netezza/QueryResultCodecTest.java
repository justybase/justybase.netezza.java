package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryResultCodecTest {
    @Test
    void decodesTextDataRowAcrossNullBitmapByteBoundary() throws Exception {
        List<QueryResult.Column> columns = new ArrayList<>();
        for (int i = 0; i < 10; i++) columns.add(new QueryResult.Column("c" + i, 23, (short) 0, 0, 0));
        columns.get(7).oid = 2500;
        columns.get(8).oid = 1043;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0x81); // columns 1 and 8 are present
        bytes.write(0x80); // column 9 is present; column 10 is NULL
        field(bytes, "-2147483648");
        field(bytes, "127");
        field(bytes, "hello");

        Object[] row = QueryResult.decodeDataRow(columns, bytes.toByteArray());
        assertEquals(-2147483648, row[0]);
        for (int i = 1; i < 7; i++) assertNull(row[i]);
        assertEquals(127, row[7]);
        assertEquals("hello", row[8]);
        assertNull(row[9]);
    }

    @Test
    void rejectsTruncatedAndTrailingDataRowBytes() throws Exception {
        List<QueryResult.Column> one = List.of(new QueryResult.Column("v", 23, (short) 0, 0, 0));
        assertThrows(IOException.class, () -> QueryResult.decodeDataRow(one, new byte[0]));
        assertThrows(IOException.class, () -> QueryResult.decodeDataRow(one, new byte[] {(byte) 0x80, 0, 0}));
        assertThrows(IOException.class, () -> QueryResult.decodeDataRow(one,
                new byte[] {0, 1})); // NULL bitmap followed by unexpected data
    }

    @Test
    void decodesExactTextValuesWithoutDroppingNumericScaleOrTimestampPrecision() {
        assertEquals(new BigDecimal("123.4500"), text(1700, "123.4500"));
        assertEquals(4, ((BigDecimal) text(1700, "123.4500")).scale());
        assertEquals(Date.valueOf("0001-01-01"), text(1082, "0001-01-01"));
        assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"),
                text(1114, "2024-02-29 12:34:56.123456"));
        assertArrayEquals(new byte[] {1, 2, (byte) 255}, (byte[]) text(17, "\\x0102ff"));
        assertEquals("Zażółć", text(1043, "Zażółć"));
    }

    @Test
    void decodesLatinCharVarcharSeparatelyFromUnicodeNcharNvarchar() {
        byte[] latin9 = "café ö ü ñ".getBytes(Charset.forName("ISO-8859-1"));
        byte[] unicode = "Zażółć gęślą jaźń".getBytes(StandardCharsets.UTF_8);
        assertEquals("café ö ü ñ", QueryResult.decodeText(16, latin9, 0, latin9.length, Charset.forName("ISO-8859-1")));
        assertEquals("Zażółć gęślą jaźń", QueryResult.decodeText(26, unicode, 0, unicode.length, Charset.forName("ISO-8859-1")));
        assertEquals("cafÃ©", QueryResult.decodeText(16, "café".getBytes(StandardCharsets.UTF_8), 0,
                "café".getBytes(StandardCharsets.UTF_8).length, Charset.forName("ISO-8859-1")));
        assertEquals("café", QueryResult.decodeText(16, "café".getBytes(StandardCharsets.UTF_8), 0,
                "café".getBytes(StandardCharsets.UTF_8).length, StandardCharsets.UTF_8));
    }

    @Test
    void decodesDbosNumericAtNonzeroRowOffsetAndPreservesScale() throws Exception {
        QueryResult.Column c = new QueryResult.Column("amount", 0, (short) 0, 0, 0);
        c.size = (10 << 8) | 2;
        c.trueSize = 4;
        byte[] row = {99, 99, (byte) 0xD2, 0x04, 0, 0};
        assertEquals(new BigDecimal("12.34"), QueryResult.decodeNumeric(c, row, 2, 4));
    }

    @Test
    void decodesTwoWordDbosNumericWithWordOrderAndScale() throws Exception {
        QueryResult.Column c = new QueryResult.Column("amount", 0, (short) 0, 0, 0);
        c.size = (18 << 8) | 2;
        c.trueSize = 8;
        byte[] row = {1, 0, 0, 0, 2, 0, 0, 0};
        assertEquals(new BigDecimal("42949672.98"), QueryResult.decodeNumeric(c, row, 0, 8));
    }

    @Test
    void decodesLazyDbosRowForFixedAndVariableColumns() throws Exception {
        byte[] descriptor = dbosDescriptor(
                new int[] {3, 16, 20}, new int[] {4, 100, 8}, new int[] {4, 4, 8},
                new int[] {0, 1, 2}, new int[] {1, 0, 1}, new int[] {4, 100, 8}, 16);
        byte[] row = new byte[24];
        row[2] = 0; // no NULLs
        row[4] = 42;
        long big = 1234567890123L;
        for (int i = 0; i < 8; i++) row[8 + i] = (byte) (big >>> (8 * i));
        row[16] = 7;
        row[17] = 0;
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(hello, 0, row, 18, hello.length);
        row[23] = 0; // even-length padding

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'X', descriptor);
        writeDbosRow(wire, row);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));

        assertTrue(result.next());
        assertEquals(42, result.intValue(0));
        assertEquals("hello", result.value(1));
        assertEquals(big, result.longValue(2));
        assertFalse(result.isNull(1));
    }

    @Test
    void honoursDbosNullBitmapForFixedColumns() throws Exception {
        byte[] descriptor = dbosDescriptor(
                new int[] {3, 20}, new int[] {4, 8}, new int[] {4, 8},
                new int[] {0, 1}, new int[] {1, 1}, new int[] {4, 8}, 16);
        byte[] row = new byte[16];
        row[2] = 1; // column 0 is NULL
        row[8] = 7;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'X', descriptor);
        writeDbosRow(wire, row);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));

        assertTrue(result.next());
        assertTrue(result.isNull(0));
        assertNull(result.value(0));
        assertEquals(7L, result.longValue(1));
    }

    @Test
    void readsFollowingDataRowsFromTheReusableFrameBuffer() throws Exception {
        // The second DataRow goes through readExactReusable(), whose backing array is
        // larger than the frame. Parsing must use the frame length, not the capacity.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'T', textRowDescription("value", 23));
        writeBackendMessage(wire, 'D', textDataRow("1"));
        writeBackendMessage(wire, 'D', textDataRow("2"));
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));

        assertTrue(result.next());
        assertEquals(1, result.intValue(0));
        assertTrue(result.next());
        assertEquals(2, result.intValue(0));
    }

    @Test
    void mapsNetezzaTimestampOidAndTimetzToJdbcTemporalTypes() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'T', textRowDescription("ts", 1184));
        writeBackendMessage(wire, 'D', textDataRow("2024-01-02 03:04:05.123456"));
        writeBackendMessage(wire, 'Z', new byte[0]);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));
        ResultSet rows = result.resultSet();
        ResultSetMetaData metadata = rows.getMetaData();
        assertEquals(Types.TIMESTAMP, metadata.getColumnType(1));
        assertEquals("TIMESTAMP", metadata.getColumnTypeName(1));
        assertEquals(Timestamp.class.getName(), metadata.getColumnClassName(1));
        assertTrue(rows.next());
        assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123456"), rows.getObject(1));
        assertEquals(OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_000, ZoneOffset.UTC),
                rows.getObject(1, OffsetDateTime.class));

        ByteArrayOutputStream timeWire = new ByteArrayOutputStream();
        writeBackendMessage(timeWire, 'T', textRowDescription("tz", 1266));
        writeBackendMessage(timeWire, 'D', textDataRow("03:04:05+02:00"));
        writeBackendMessage(timeWire, 'Z', new byte[0]);
        QueryResult timeResult = new QueryResult(new Protocol(new ByteArrayInputStream(timeWire.toByteArray())));
        ResultSet timeRows = timeResult.resultSet();
        assertEquals(Types.TIME_WITH_TIMEZONE, timeRows.getMetaData().getColumnType(1));
        assertEquals(OffsetTime.class.getName(), timeRows.getMetaData().getColumnClassName(1));
        assertTrue(timeRows.next());
        assertEquals(OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(2)), timeRows.getObject(1));
    }

    @Test
    void namesJsonpathDbosColumn() throws Exception {
        byte[] descriptor = dbosDescriptor(
                new int[] {32}, new int[] {16}, new int[] {0}, new int[] {1}, new int[] {0},
                new int[] {16}, 4);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'X', descriptor);
        writeBackendMessage(wire, 'Z', new byte[0]);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));
        assertEquals("JSONPATH", result.resultSet().getMetaData().getColumnTypeName(1));
    }

    @Test
    void decodesDbosIntervalAsCanonicalText() throws Exception {
        byte[] descriptor = dbosDescriptor(
                new int[] {10}, new int[] {12}, new int[] {4}, new int[] {0}, new int[] {1},
                new int[] {12}, 16);
        long[][] values = {
                {0, 0},
                {183_845_000_000L, 0},
                {-79_016_000_000L, 0},
                {273_906_000_000L, 14},
                {0, -14},
                {100_000L, 0},
                {-100_000L, 0},
                {172_800_000_000L, 12},
                {172_800_000_000L, -12},
                {-172_800_000_000L, -12}
        };
        String[] expected = {
                "00:00:00", "2 days 03:04:05", "-21:56:56", "1 year 2 mons 3 days 04:05:06",
                "-1 year -2 mons", "00:00:00.1", "-00:00:00.1", "1 year 2 days", "-1 year +2 days",
                "-1 year -2 days"
        };
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'X', descriptor);
        for (long[] value : values) {
            byte[] row = new byte[16];
            ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(4, value[0]).putInt(12, (int) value[1]);
            writeDbosRow(wire, row);
        }
        writeBackendMessage(wire, 'Z', new byte[0]);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));

        for (String text : expected) {
            assertTrue(result.next());
            assertEquals(text, result.value(0));
        }
        assertFalse(result.next());
    }

    @Test
    void decodesDbosTimetzMicrosecondsAndOffset() throws Exception {
        byte[] descriptor = dbosDescriptor(
                new int[] {11}, new int[] {12}, new int[] {4}, new int[] {0}, new int[] {1},
                new int[] {12}, 16);
        long[] micros = {
                11_045_123_456L,
                45_296_000_001L,
                86_399_999_999L,
                1L,
                45_296_123_456L
        };
        int[] zones = {-19_800, -19_800, 43_200, 43_200, 0};
        String[] expected = {
                "03:04:05.123456+05:30", "12:34:56.000001+05:30", "23:59:59.999999-12",
                "00:00:00.000001-12", "12:34:56.123456+00"
        };
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeBackendMessage(wire, 'X', descriptor);
        for (int i = 0; i < micros.length; i++) {
            byte[] row = new byte[16];
            ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(4, micros[i]).putInt(12, zones[i]);
            writeDbosRow(wire, row);
        }
        writeBackendMessage(wire, 'Z', new byte[0]);
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(wire.toByteArray())));

        for (String text : expected) {
            assertTrue(result.next());
            assertEquals(text, result.value(0));
        }
        assertFalse(result.next());
    }

    private static byte[] dbosDescriptor(int[] types, int[] sizes, int[] offsets, int[] physical,
                                         int[] fixed, int[] trueSizes, int fixedSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(1); // version
        data.writeInt(1); // null bitmap present
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(0);
        data.writeInt(fixedSize);
        data.writeInt(0);
        data.writeInt(types.length);
        for (int i = 0; i < types.length; i++) {
            data.writeInt(types[i]);
            data.writeInt(sizes[i]);
            data.writeInt(trueSizes[i]);
            data.writeInt(offsets[i]);
            data.writeInt(physical[i]);
            data.writeInt(i);
            data.writeInt(1); // nullable
            data.writeInt(fixed[i]);
            data.writeInt(0);
        }
        data.writeLong(0L);
        return out.toByteArray();
    }

    private static void writeDbosRow(ByteArrayOutputStream out, byte[] row) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeByte('Y');
        data.writeInt(0);
        data.writeInt(0); // row length placeholder
        data.writeInt(row.length);
        data.write(row);
    }

    private static void writeBackendMessage(ByteArrayOutputStream out, int type, byte[] payload) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeByte(type);
        data.writeInt(0);
        data.writeInt(payload.length);
        data.write(payload);
    }

    private static byte[] textRowDescription(String name, int oid) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeShort(1);
        data.write(name.getBytes(StandardCharsets.UTF_8));
        data.writeByte(0);
        data.writeInt(oid);
        data.writeShort(4);
        data.writeInt(0);
        data.writeByte(0);
        return out.toByteArray();
    }

    private static byte[] textDataRow(String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeByte(0x80);
        data.writeInt(raw.length + 4);
        data.write(raw);
        return out.toByteArray();
    }

    private static Object text(int oid, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return QueryResult.decodePg(oid, bytes, 0, bytes.length);
    }

    private static void field(ByteArrayOutputStream bytes, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(raw.length + 4);
        data.write(raw);
    }
}
