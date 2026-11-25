package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full live type matrix: a fixture table with every usable Netezza type, prepared-statement
 * round-trips, NULLs, boundaries, metadata, parameters and explicit getObject conversions.
 */
@Tag("live")
class DataTypeMatrixLiveTest {
    private static final String TABLE = "JAVA_TYPE_MATRIX";
    private static final String COLUMNS =
            "ID INTEGER NOT NULL, B BYTEINT, S SMALLINT, I INTEGER, L BIGINT, R REAL, " +
                    "D DOUBLE PRECISION, N NUMERIC(38,10), N0 NUMERIC(38,0), " +
                    "C CHAR(12), V VARCHAR(4000), NC NCHAR(12), NV NVARCHAR(4000), " +
                    "DT DATE, TM TIME, TZ TIMETZ, TS TIMESTAMP, BO BOOLEAN, BIN VARBINARY(1000), " +
                    "IV INTERVAL, IVYM INTERVAL YEAR TO MONTH, IVDS INTERVAL DAY TO SECOND";
    private static final String INSERT = "INSERT INTO " + TABLE +
            " (ID,B,S,I,L,R,D,N,N0,C,V,NC,NV,DT,TM,TZ,TS,BO, BIN,IV,IVYM,IVDS) VALUES " +
            "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private Connection connection;
    private String schema;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        schema = connection.getSchema();
        dropTable();
        LiveTestSupport.execute(connection, "CREATE TABLE " + TABLE + " (" + COLUMNS + ")");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null && !connection.isClosed()) dropTable();
        } finally {
            if (connection != null) connection.close();
        }
    }

    @Test
    @Timeout(90)
    void preparedStatementRoundTripsEverySupportedType() throws Exception {
        byte[] binary = baseBinary();
        String special = baseSpecial();
        String unicode = baseUnicode();
        OffsetTime timetz = baseTimetz();
        insertBaseRow();

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID=1")) {
            assertTrue(result.next());
            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(22, metadata.getColumnCount());

            assertEquals(-128, result.getByte("B"));
            assertEquals(32767, result.getShort("S"));
            assertEquals(Integer.MIN_VALUE, result.getInt("I"));
            assertEquals(Long.MAX_VALUE, result.getLong("L"));
            assertEquals(3.25f, result.getFloat("R"));
            assertEquals(-2.5d, result.getDouble("D"));
            assertEquals(new BigDecimal("1234567890123456789012345678.1234567890"),
                    result.getBigDecimal("N"));
            assertEquals(10, result.getBigDecimal("N").scale());
            assertEquals(new BigDecimal("99999999999999999999999999999999999999"),
                    result.getBigDecimal("N0"));
            assertEquals(0, result.getBigDecimal("N0").scale());

            assertEquals("chars" + " ".repeat(7), result.getString("C"));
            assertEquals(12, result.getString("C").length());
            assertEquals(special, result.getString("V"));
            assertEquals("\u017b\u00f3\u0142\u0107", result.getNString("NC"));
            assertEquals(unicode, result.getNString("NV"));
            assertEquals(unicode, result.getString("NV"));

            assertEquals(Date.valueOf("2024-02-29"), result.getDate("DT"));
            assertEquals(Time.valueOf("23:59:59"), result.getTime("TM"));
            assertEquals(LocalTime.of(23, 59, 59), result.getObject("TM", LocalTime.class));
            assertEquals(timetz, result.getObject("TZ"));
            assertEquals(timetz, result.getObject("TZ", OffsetTime.class));
            assertEquals("03:04:05.123456+05:30", result.getString("TZ"));
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"), result.getTimestamp("TS"));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
            assertTrue(result.getBoolean("BO"));
            assertArrayEquals(binary, result.getBytes("BIN"));
            assertEquals("2 days 03:04:05", result.getString("IV"));
            assertEquals("1 year 2 mons", result.getString("IVYM"));
            assertEquals("2 days 03:04:05.123456", result.getString("IVDS"));

            assertFalse(result.wasNull());
            assertEquals(Types.INTEGER, metadata.getColumnType(1));
            assertEquals(Types.TINYINT, metadata.getColumnType(2));
            assertEquals(Types.SMALLINT, metadata.getColumnType(3));
            assertEquals(Types.BIGINT, metadata.getColumnType(5));
            assertEquals(Types.REAL, metadata.getColumnType(6));
            assertEquals(Types.DOUBLE, metadata.getColumnType(7));
            assertEquals(Types.NUMERIC, metadata.getColumnType(8));
            assertEquals(Types.CHAR, metadata.getColumnType(10));
            assertEquals(Types.VARCHAR, metadata.getColumnType(11));
            assertEquals(Types.NCHAR, metadata.getColumnType(12));
            assertEquals(Types.NVARCHAR, metadata.getColumnType(13));
            assertEquals(Types.DATE, metadata.getColumnType(14));
            assertEquals(Types.TIME, metadata.getColumnType(15));
            assertEquals(Types.TIME_WITH_TIMEZONE, metadata.getColumnType(16));
            assertEquals(Types.TIMESTAMP, metadata.getColumnType(17));
            assertEquals(Types.BOOLEAN, metadata.getColumnType(18));
            assertEquals(Types.VARBINARY, metadata.getColumnType(19));
            assertEquals(Types.OTHER, metadata.getColumnType(20));
            assertEquals(Types.OTHER, metadata.getColumnType(21));
            assertEquals(Types.OTHER, metadata.getColumnType(22));

            assertEquals("INTERVAL", metadata.getColumnTypeName(20));
            assertEquals("INTERVAL", metadata.getColumnTypeName(21));
            assertEquals("INTERVAL", metadata.getColumnTypeName(22));
            assertEquals(String.class.getName(), metadata.getColumnClassName(20));
            assertEquals(38, metadata.getPrecision(8));
            assertEquals(10, metadata.getScale(8));
            assertEquals(38, metadata.getPrecision(9));
            assertEquals(0, metadata.getScale(9));
            assertEquals(12, metadata.getPrecision(10));
            assertEquals(4000, metadata.getPrecision(11));
            assertEquals(12, metadata.getPrecision(12));
            assertEquals(4000, metadata.getPrecision(13));
            assertEquals(1000, metadata.getPrecision(19));
            assertEquals(ResultSetMetaData.columnNoNulls, metadata.isNullable(1));
            assertEquals(ResultSetMetaData.columnNullable, metadata.isNullable(2));
            assertTrue(metadata.isSigned(8));
            assertTrue(metadata.isSigned(9));
            assertFalse(metadata.isSigned(10));
            assertTrue(metadata.isCaseSensitive(10));
            assertTrue(metadata.isCaseSensitive(13));
        }
    }

    @Test
    @Timeout(90)
    void nullRowUsesJdbcDefaultsForEveryColumn() throws Exception {
        LiveTestSupport.execute(connection, "INSERT INTO " + TABLE + " (ID) VALUES (2)");
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID=2")) {
            assertTrue(result.next());
            assertEquals(0, result.getByte("B"));
            assertTrue(result.wasNull());
            assertEquals(0, result.getShort("S"));
            assertTrue(result.wasNull());
            assertEquals(0, result.getInt("I"));
            assertTrue(result.wasNull());
            assertEquals(0L, result.getLong("L"));
            assertTrue(result.wasNull());
            assertEquals(0f, result.getFloat("R"));
            assertTrue(result.wasNull());
            assertEquals(0d, result.getDouble("D"));
            assertTrue(result.wasNull());
            assertNull(result.getBigDecimal("N"));
            assertTrue(result.wasNull());
            assertNull(result.getBigDecimal("N0"));
            assertTrue(result.wasNull());
            assertNull(result.getString("C"));
            assertTrue(result.wasNull());
            assertNull(result.getString("V"));
            assertTrue(result.wasNull());
            assertNull(result.getNString("NC"));
            assertTrue(result.wasNull());
            assertNull(result.getNString("NV"));
            assertTrue(result.wasNull());
            assertNull(result.getDate("DT"));
            assertTrue(result.wasNull());
            assertNull(result.getTime("TM"));
            assertTrue(result.wasNull());
            assertNull(result.getObject("TZ"));
            assertTrue(result.wasNull());
            assertNull(result.getTimestamp("TS"));
            assertTrue(result.wasNull());
            assertFalse(result.getBoolean("BO"));
            assertTrue(result.wasNull());
            assertNull(result.getBytes("BIN"));
            assertTrue(result.wasNull());
            assertNull(result.getString("IV"));
            assertTrue(result.wasNull());
            assertNull(result.getString("IVYM"));
            assertTrue(result.wasNull());
            assertNull(result.getString("IVDS"));
            assertTrue(result.wasNull());
            for (int i = 2; i <= result.getMetaData().getColumnCount(); i++) {
                assertNull(result.getObject(i), "column " + i);
            }
        }
    }

    @Test
    @Timeout(90)
    void numericBoundariesAndScaleAreExact() throws Exception {
        BigDecimal max = new BigDecimal("9999999999999999999999999999.9999999999");
        BigDecimal min = new BigDecimal("-9999999999999999999999999999.9999999999");
        BigDecimal max0 = new BigDecimal("99999999999999999999999999999999999999");
        BigDecimal min0 = new BigDecimal("-99999999999999999999999999999999999999");
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (ID,N,N0) VALUES (?,?,?)")) {
            insert.setInt(1, 3);
            insert.setBigDecimal(2, max);
            insert.setBigDecimal(3, max0);
            insert.executeUpdate();
            insert.setInt(1, 4);
            insert.setBigDecimal(2, min);
            insert.setBigDecimal(3, min0);
            insert.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID IN (3,4) ORDER BY ID")) {
            assertTrue(result.next());
            assertEquals(max, result.getBigDecimal("N"));
            assertEquals(10, result.getBigDecimal("N").scale());
            assertEquals(max0, result.getBigDecimal("N0"));
            assertEquals(0, result.getBigDecimal("N0").scale());
            assertEquals(max.setScale(2, RoundingMode.HALF_UP), result.getBigDecimal("N", 2));
            assertEquals(max.toPlainString(), result.getString("N"));
            assertEquals(max.toPlainString(), result.getObject("N", String.class));

            assertTrue(result.next());
            assertEquals(min, result.getBigDecimal("N"));
            assertEquals(10, result.getBigDecimal("N").scale());
            assertEquals(min0, result.getBigDecimal("N0"));
            assertEquals(0, result.getBigDecimal("N0").scale());
        }
    }

    @Test
    @Timeout(90)
    void textEdgeCasesAndEscapingSurviveRoundTrip() throws Exception {
        String special = "O'Brien\\slash\nline2\tend %_";
        String unicode = "\u017b\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144 \u4e2d\u6587 \uD83D\uDE00";
        String combining = java.text.Normalizer.normalize("e\u0301", java.text.Normalizer.Form.NFC);
        String large = "x".repeat(4000);
        String largeN = "\u017c".repeat(4000);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (ID,C,V,NC,NV) VALUES (?,?,?,?,?)")) {
            insert.setInt(1, 5);
            insert.setString(2, "");
            insert.setString(3, "");
            insert.setNString(4, "");
            insert.setNString(5, "");
            insert.executeUpdate();

            insert.setInt(1, 6);
            insert.setString(2, "a");
            insert.setString(3, special);
            insert.setNString(4, "\u017b\u00f3\u0142\u0107");
            insert.setNString(5, unicode);
            insert.executeUpdate();

            insert.setInt(1, 7);
            insert.setString(2, "b");
            insert.setString(3, combining);
            insert.setNString(4, "c");
            insert.setNString(5, combining);
            insert.executeUpdate();

            insert.setInt(1, 8);
            insert.setString(2, "d");
            insert.setString(3, large);
            insert.setNString(4, "e");
            insert.setNString(5, largeN);
            insert.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID BETWEEN 5 AND 8 ORDER BY ID")) {
            assertTrue(result.next());
            assertEquals(" ".repeat(12), result.getString("C"));
            assertEquals("", result.getString("V"));
            assertFalse(result.wasNull());
            assertEquals("", result.getNString("NC"));
            assertEquals("", result.getNString("NV"));
            assertFalse(result.wasNull());

            assertTrue(result.next());
            assertEquals("a" + " ".repeat(11), result.getString("C"));
            assertEquals(special, result.getString("V"));
            assertEquals("\u017b\u00f3\u0142\u0107", result.getNString("NC"));
            assertEquals(unicode, result.getNString("NV"));
            assertEquals(unicode, new String(result.getBytes("NV"), StandardCharsets.UTF_8));

            assertTrue(result.next());
            assertEquals(combining, result.getString("V"));
            assertEquals(combining, result.getNString("NV"));

            assertTrue(result.next());
            assertEquals(large, result.getString("V"));
            assertEquals(4000, result.getString("V").length());
            assertEquals(largeN, result.getNString("NV"));
            assertEquals(4000, result.getNString("NV").length());
        }
    }

    @Test
    @Timeout(90)
    void binaryEdgeCasesSurviveRoundTrip() throws Exception {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) allBytes[i] = (byte) i;
        byte[] large = new byte[1000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
        byte[][] values = {new byte[0], new byte[] {0}, new byte[] {(byte) 0xFF}, allBytes, large};
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (ID,BIN) VALUES (?,?)")) {
            for (int i = 0; i < values.length; i++) {
                insert.setInt(1, 10 + i);
                insert.setBytes(2, values[i]);
                insert.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM " + TABLE + " WHERE ID BETWEEN 10 AND 14 ORDER BY ID")) {
            for (byte[] expected : values) {
                assertTrue(result.next());
                assertArrayEquals(expected, result.getBytes("BIN"));
                assertArrayEquals(expected, result.getObject("BIN", byte[].class));
                Blob blob = result.getBlob("BIN");
                assertNotNull(blob);
                assertEquals(expected.length, blob.length());
                assertArrayEquals(expected, blob.getBytes(1, expected.length));
                try (InputStream stream = result.getBinaryStream("BIN")) {
                    assertArrayEquals(expected, stream.readAllBytes());
                }
            }
        }
    }

    @Test
    @Timeout(90)
    void temporalBoundariesAndOffsetsArePreserved() throws Exception {
        Object[][] rows = {
                {20, LocalDate.of(1, 1, 1), LocalTime.of(0, 0), LocalDateTime.of(1, 1, 1, 0, 0),
                        OffsetTime.of(0, 0, 0, 1_000, ZoneOffset.ofHours(-12))},
                {21, LocalDate.of(9999, 12, 31), LocalTime.of(23, 59, 59),
                        LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000),
                        OffsetTime.of(23, 59, 59, 999_999_000, ZoneOffset.ofHours(13))},
                {22, LocalDate.of(2024, 2, 29), LocalTime.of(12, 34, 56),
                        LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000),
                        OffsetTime.of(12, 34, 56, 123_456_000, ZoneOffset.UTC)}
        };
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (ID,DT,TM,TZ,TS) VALUES (?,?,?,?,?)")) {
            for (Object[] row : rows) {
                insert.setInt(1, (Integer) row[0]);
                insert.setObject(2, row[1]);
                insert.setObject(3, row[2]);
                insert.setObject(4, row[4]);
                insert.setObject(5, row[3]);
                insert.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM " + TABLE + " WHERE ID BETWEEN 20 AND 22 ORDER BY ID")) {
            assertTrue(result.next());
            assertEquals(Date.valueOf("0001-01-01"), result.getDate("DT"));
            assertEquals(LocalTime.of(0, 0), result.getObject("TM", LocalTime.class));
            assertEquals("00:00:00", result.getString("TM"));
            assertEquals(OffsetTime.of(0, 0, 0, 1_000, ZoneOffset.ofHours(-12)), result.getObject("TZ"));
            assertEquals("00:00:00.000001-12", result.getString("TZ"));
            assertEquals(Timestamp.valueOf("0001-01-01 00:00:00"), result.getTimestamp("TS"));
            assertEquals("0001-01-01 00:00:00", result.getString("TS"));

            assertTrue(result.next());
            assertEquals(Date.valueOf("9999-12-31"), result.getDate("DT"));
            assertEquals(LocalTime.of(23, 59, 59), result.getObject("TM", LocalTime.class));
            assertEquals("23:59:59", result.getString("TM"));
            assertEquals(OffsetTime.of(23, 59, 59, 999_999_000, ZoneOffset.ofHours(13)), result.getObject("TZ"));
            assertEquals("23:59:59.999999+13", result.getString("TZ"));
            assertEquals(Timestamp.valueOf("9999-12-31 23:59:59.999999"), result.getTimestamp("TS"));
            assertEquals("9999-12-31 23:59:59.999999", result.getString("TS"));

            assertTrue(result.next());
            assertEquals(LocalDate.of(2024, 2, 29), result.getObject("DT", LocalDate.class));
            assertEquals(LocalTime.of(12, 34, 56), result.getObject("TM", LocalTime.class));
            assertEquals(OffsetTime.of(12, 34, 56, 123_456_000, ZoneOffset.UTC), result.getObject("TZ"));
            assertEquals("12:34:56.123456+00", result.getString("TZ"));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
            assertEquals("2024-02-29 12:34:56.123456", result.getString("TS"));
        }
    }

    @Test
    @Timeout(90)
    void intervalVariantsRoundTripAsCanonicalStrings() throws Exception {
        String[][] rows = {
                {"12", "0 days", "0 months", "0 seconds", "00:00:00", "00:00:00", "00:00:00"},
                {"13", "2 days 03:04:05", "14 months", "2 days 03:04:05.123456",
                        "2 days 03:04:05", "1 year 2 mons", "2 days 03:04:05.123456"},
                {"14", "-2 days -03:04:05", "-14 months", "-1 day +02:03:04",
                        "-2 days -03:04:05", "-1 year -2 mons", "-21:56:56"},
                {"15", "1 year 2 mons 3 days 04:05:06", "1 year 2 mons", "1 day 02:03:04.000005",
                        "1 year 2 mons 3 days 04:05:06", "1 year 2 mons", "1 day 02:03:04.000005"},
                {"16", "1 year -2 days", "13 months", "0.100000", "1 year -2 days", "1 year 1 mon", "00:00:00.1"},
                {"17", "-1 year 2 days", "24 months", "-0.100000", "-1 year +2 days", "2 years", "-00:00:00.1"}
        };
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (ID,IV,IVYM,IVDS) VALUES (?,?,?,?)")) {
            for (String[] row : rows) {
                insert.setInt(1, Integer.parseInt(row[0]));
                insert.setString(2, row[1]);
                insert.setString(3, row[2]);
                insert.setString(4, row[3]);
                insert.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM " + TABLE + " WHERE ID BETWEEN 12 AND 17 ORDER BY ID")) {
            for (String[] row : rows) {
                assertTrue(result.next());
                assertEquals(row[4], result.getString("IV"), "IV row " + row[0]);
                assertEquals(row[5], result.getString("IVYM"), "IVYM row " + row[0]);
                assertEquals(row[6], result.getString("IVDS"), "IVDS row " + row[0]);
                assertEquals(row[4], result.getObject("IV"));
                assertInstanceOf(String.class, result.getObject("IVYM"));
                assertInstanceOf(String.class, result.getObject("IVDS"));
                assertEquals(Types.OTHER, result.getMetaData().getColumnType(20));
                assertEquals("INTERVAL", result.getMetaData().getColumnTypeName(20));
                assertEquals(String.class.getName(), result.getMetaData().getColumnClassName(20));
            }
        }
    }

    @Test
    @Timeout(90)
    void databaseMetadataDescribesEveryFixtureColumn() throws Exception {
        insertBaseRow();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE)) {
            ResultSetMetaData rsmd = result.getMetaData();
            Map<String, Integer> types = new LinkedHashMap<>();
            Map<String, Integer> nullables = new LinkedHashMap<>();
            Map<String, Integer> sizes = new LinkedHashMap<>();
            Map<String, Integer> scales = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            DatabaseMetaData dbmd = connection.getMetaData();
            try (ResultSet columns = dbmd.getColumns(connection.getCatalog(), schema, TABLE, "%")) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    types.put(name, columns.getInt("DATA_TYPE"));
                    names.put(name, columns.getString("TYPE_NAME"));
                    sizes.put(name, columns.getInt("COLUMN_SIZE"));
                    Object digits = columns.getObject("DECIMAL_DIGITS");
                    scales.put(name, digits instanceof Number number ? number.intValue() : 0);
                    nullables.put(name, columns.getInt("NULLABLE"));
                }
            }
            assertEquals(rsmd.getColumnCount(), types.size());

            assertEquals(Types.INTEGER, types.get("ID"));
            assertEquals(Types.TINYINT, types.get("B"));
            assertEquals(Types.SMALLINT, types.get("S"));
            assertEquals(Types.INTEGER, types.get("I"));
            assertEquals(Types.BIGINT, types.get("L"));
            assertEquals(Types.REAL, types.get("R"));
            assertEquals(Types.DOUBLE, types.get("D"));
            assertEquals(Types.NUMERIC, types.get("N"));
            assertEquals(Types.NUMERIC, types.get("N0"));
            assertEquals(Types.CHAR, types.get("C"));
            assertEquals(Types.VARCHAR, types.get("V"));
            assertEquals(Types.NCHAR, types.get("NC"));
            assertEquals(Types.NVARCHAR, types.get("NV"));
            assertEquals(Types.DATE, types.get("DT"));
            assertEquals(Types.TIME, types.get("TM"));
            assertEquals(Types.TIME_WITH_TIMEZONE, types.get("TZ"));
            assertEquals(Types.TIMESTAMP, types.get("TS"));
            assertEquals(Types.BOOLEAN, types.get("BO"));
            assertEquals(Types.VARBINARY, types.get("BIN"));
            assertEquals(Types.OTHER, types.get("IV"));
            assertEquals(Types.OTHER, types.get("IVYM"));
            assertEquals(Types.OTHER, types.get("IVDS"));

            assertEquals("INTEGER", names.get("ID"));
            assertEquals("BYTEINT", names.get("B"));
            assertEquals("SMALLINT", names.get("S"));
            assertEquals("BIGINT", names.get("L"));
            assertEquals("REAL", names.get("R"));
            assertEquals("DOUBLE", names.get("D"));
            assertEquals("NUMERIC", names.get("N"));
            assertEquals("CHAR", names.get("C"));
            assertEquals("VARCHAR", names.get("V"));
            assertEquals("NCHAR", names.get("NC"));
            assertEquals("NVARCHAR", names.get("NV"));
            assertEquals("DATE", names.get("DT"));
            assertEquals("TIME", names.get("TM"));
            assertEquals("TIMETZ", names.get("TZ"));
            assertEquals("TIMESTAMP", names.get("TS"));
            assertEquals("BOOLEAN", names.get("BO"));
            assertEquals("VARBINARY", names.get("BIN"));
            assertEquals("INTERVAL", names.get("IV"));

            assertEquals(38, sizes.get("N"));
            assertEquals(10, scales.get("N"));
            assertEquals(38, sizes.get("N0"));
            assertEquals(0, scales.get("N0"));
            assertEquals(12, sizes.get("C"));
            assertEquals(4000, sizes.get("V"));
            assertEquals(12, sizes.get("NC"));
            assertEquals(4000, sizes.get("NV"));
            assertEquals(1000, sizes.get("BIN"));

            assertEquals(0, nullables.get("ID"));
            for (String name : types.keySet()) {
                if (!name.equals("ID")) assertEquals(1, nullables.get(name), name);
            }
            for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                String name = rsmd.getColumnName(i);
                assertEquals(types.get(name), rsmd.getColumnType(i), name);
                assertEquals(nullables.get(name) == DatabaseMetaData.columnNoNulls
                                ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable,
                        rsmd.isNullable(i), name);
            }
        }
    }

    @Test
    @Timeout(90)
    void parameterMetadataCoversEverySetterFamily() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?")) {
            statement.setByte(1, (byte) 1);
            statement.setShort(2, (short) 2);
            statement.setInt(3, 3);
            statement.setLong(4, 4L);
            statement.setFloat(5, 5.5f);
            statement.setDouble(6, 6.5d);
            statement.setBigDecimal(7, new BigDecimal("7.25"));
            statement.setString(8, "eight");
            statement.setNString(9, "nine");
            statement.setDate(10, Date.valueOf("2024-02-29"));
            statement.setTime(11, Time.valueOf("12:34:56"));
            statement.setTimestamp(12, Timestamp.valueOf("2024-02-29 12:34:56.123456"));
            statement.setObject(13, OffsetTime.of(12, 34, 56, 123_456_000, ZoneOffset.ofHours(2)));
            statement.setBoolean(14, true);
            statement.setBytes(15, new byte[] {1, 2});
            statement.setNull(16, Types.INTEGER);

            ParameterMetaData metadata = statement.getParameterMetaData();
            assertEquals(16, metadata.getParameterCount());
            int[] types = {Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL,
                    Types.DOUBLE, Types.NUMERIC, Types.VARCHAR, Types.NVARCHAR, Types.DATE, Types.TIME,
                    Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.BOOLEAN, Types.VARBINARY, Types.INTEGER};
            String[] classes = {Byte.class.getName(), Short.class.getName(), Integer.class.getName(),
                    Long.class.getName(), Float.class.getName(), Double.class.getName(),
                    BigDecimal.class.getName(), String.class.getName(), String.class.getName(),
                    Date.class.getName(), Time.class.getName(), Timestamp.class.getName(),
                    OffsetTime.class.getName(), Boolean.class.getName(), byte[].class.getName(),
                    Integer.class.getName()};
            String[] names = {"BYTEINT", "SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE", "NUMERIC",
                    "VARCHAR", "NVARCHAR", "DATE", "TIME", "TIMESTAMP", "TIMETZ", "BOOLEAN", "VARBINARY",
                    "INTEGER"};
            for (int i = 0; i < types.length; i++) {
                assertEquals(types[i], metadata.getParameterType(i + 1), "type " + (i + 1));
                assertEquals(classes[i], metadata.getParameterClassName(i + 1), "class " + (i + 1));
                assertEquals(names[i], metadata.getParameterTypeName(i + 1), "name " + (i + 1));
                assertEquals(ParameterMetaData.parameterModeIn, metadata.getParameterMode(i + 1));
            }
            assertTrue(metadata.isSigned(1));
            assertTrue(metadata.isSigned(7));
            assertFalse(metadata.isSigned(8));
            assertFalse(metadata.isSigned(13));
            assertTrue(metadata.isSigned(16));
        }
    }

    @Test
    @Timeout(90)
    void explicitClassConversionsWorkAcrossTheMatrix() throws Exception {
        insertBaseRow();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID=1")) {
            assertTrue(result.next());
            assertEquals(Byte.valueOf((byte) -128), result.getObject("B", Byte.class));
            assertEquals(Integer.valueOf(32767), result.getObject("S", Integer.class));
            assertEquals(Long.valueOf(Integer.MIN_VALUE), result.getObject("I", Long.class));
            assertEquals(new BigDecimal("1234567890123456789012345678.1234567890"),
                    result.getObject("N", BigDecimal.class));
            assertEquals("1234567890123456789012345678.1234567890", result.getObject("N", String.class));
            assertEquals(LocalDate.of(2024, 2, 29), result.getObject("DT", LocalDate.class));
            assertEquals(Timestamp.valueOf("2024-02-29 00:00:00"), result.getObject("DT", Timestamp.class));
            assertEquals(LocalTime.of(23, 59, 59), result.getObject("TM", LocalTime.class));
            assertEquals(Time.valueOf("23:59:59"), result.getObject("TM", Time.class));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
            assertEquals(Date.valueOf("2024-02-29"), result.getObject("TS", Date.class));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000)
                    .toInstant(ZoneOffset.UTC), result.getObject("TS", Instant.class));
            assertEquals(OffsetTime.of(3, 4, 5, 123_456_000, ZoneOffset.ofHoursMinutes(5, 30)),
                    result.getObject("TZ", OffsetTime.class));
            assertEquals(LocalTime.of(3, 4, 5, 123_456_000), result.getObject("TZ", LocalTime.class));
            assertEquals("2 days 03:04:05", result.getObject("IV", String.class));
            assertInstanceOf(Blob.class, result.getObject("BIN", Blob.class));
            assertInstanceOf(Clob.class, result.getObject("V", Clob.class));
            assertInstanceOf(NClob.class, result.getObject("NV", NClob.class));
        }
    }

    @Test
    @Timeout(90)
    void getObjectMatchesMetadataClassForEveryColumn() throws Exception {
        insertBaseRow();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + TABLE + " WHERE ID=1")) {
            assertTrue(result.next());
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                Object value = result.getObject(i);
                assertNotNull(value, "column " + metadata.getColumnName(i));
                Class<?> expected = Class.forName(metadata.getColumnClassName(i));
                assertTrue(expected.isInstance(value), "column " + metadata.getColumnName(i)
                        + " expected " + expected.getName() + " but was " + value.getClass().getName());
            }
        }
    }

    private void insertBaseRow() throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setInt(1, 1);
            insert.setByte(2, (byte) -128);
            insert.setShort(3, (short) 32767);
            insert.setInt(4, Integer.MIN_VALUE);
            insert.setLong(5, Long.MAX_VALUE);
            insert.setFloat(6, 3.25f);
            insert.setDouble(7, -2.5d);
            insert.setBigDecimal(8, new BigDecimal("1234567890123456789012345678.1234567890"));
            insert.setBigDecimal(9, new BigDecimal("99999999999999999999999999999999999999"));
            insert.setString(10, "chars");
            insert.setString(11, baseSpecial());
            insert.setNString(12, "\u017b\u00f3\u0142\u0107");
            insert.setNString(13, baseUnicode());
            insert.setObject(14, LocalDate.of(2024, 2, 29));
            insert.setObject(15, LocalTime.of(23, 59, 59));
            insert.setObject(16, baseTimetz());
            insert.setObject(17, LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000));
            insert.setBoolean(18, true);
            insert.setBytes(19, baseBinary());
            insert.setString(20, "2 days 03:04:05");
            insert.setString(21, "14 months");
            insert.setString(22, "2 days 03:04:05.123456");
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static byte[] baseBinary() {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        return binary;
    }

    private static String baseSpecial() {
        return "O'Brien\\line1\nline2\tend";
    }

    private static String baseUnicode() {
        return "\u017b\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144 \u4e2d\u6587 \uD83D\uDE00";
    }

    private static OffsetTime baseTimetz() {
        return OffsetTime.of(3, 4, 5, 123_456_000, ZoneOffset.ofHoursMinutes(5, 30));
    }

    private void dropTable() {
        try {
            LiveTestSupport.execute(connection, "DROP TABLE " + TABLE + " IF EXISTS");
        } catch (SQLException ignored) {
        }
    }
}
