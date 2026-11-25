package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Type matrix and typed getter behavior against a live appliance. */
@Tag("live")
class TypeGettersLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void integerBoundariesUseTheCorrectJdbcTypes() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT -128::BYTEINT AS BMIN, 127::BYTEINT AS BMAX, " +
                             "-32768::SMALLINT AS SMIN, 32767::SMALLINT AS SMAX, " +
                             "-2147483648::INTEGER AS IMIN, 2147483647::INTEGER AS IMAX, " +
                             "-9223372036854775808::BIGINT AS LMIN, 9223372036854775807::BIGINT AS LMAX")) {
            assertTrue(result.next());
            assertEquals((byte) -128, result.getByte("BMIN"));
            assertEquals((byte) 127, result.getByte("BMAX"));
            assertEquals((short) -32768, result.getShort("SMIN"));
            assertEquals((short) 32767, result.getShort("SMAX"));
            assertEquals(Integer.MIN_VALUE, result.getInt("IMIN"));
            assertEquals(Integer.MAX_VALUE, result.getInt("IMAX"));
            assertEquals(Long.MIN_VALUE, result.getLong("LMIN"));
            assertEquals(Long.MAX_VALUE, result.getLong("LMAX"));
            assertInstanceOf(Byte.class, result.getObject("BMIN"));
            assertInstanceOf(Short.class, result.getObject("SMAX"));
            assertInstanceOf(Integer.class, result.getObject("IMAX"));
            assertInstanceOf(Long.class, result.getObject("LMAX"));
            assertEquals("-2147483648", result.getString("IMIN"));
            assertEquals("9223372036854775807", result.getString("LMAX"));
        }
    }

    @Test
    @Timeout(60)
    void numericValuesPreservePrecisionScaleAndSign() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 12345678901234567890.123456::NUMERIC(30,6) AS BIG, " +
                             "-0.125::NUMERIC(10,3) AS NEG, 0.00::NUMERIC(5,2) AS ZERO, " +
                             "12345678901234567890123456789012345678::NUMERIC(38,0) AS MAX38")) {
            assertTrue(result.next());
            assertEquals(new BigDecimal("12345678901234567890.123456"), result.getBigDecimal("BIG"));
            assertEquals(6, result.getBigDecimal("BIG").scale());
            assertEquals(new BigDecimal("-0.125"), result.getBigDecimal("NEG"));
            assertEquals(3, result.getBigDecimal("NEG").scale());
            assertEquals(new BigDecimal("0.00"), result.getBigDecimal("ZERO"));
            assertEquals(new BigDecimal("12345678901234567890123456789012345678"), result.getBigDecimal("MAX38"));
            assertEquals(0, result.getBigDecimal("MAX38").scale());
            assertEquals("12345678901234567890.123456", result.getString("BIG"));
            assertInstanceOf(BigDecimal.class, result.getObject("BIG"));
            assertEquals(12345678901234567890.123456d, result.getDouble("BIG"), 1.0e18);
        }
    }

    @Test
    @Timeout(60)
    void floatingPointSpecialValuesRemainAvailable() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 3.25::REAL AS R, -3.25::DOUBLE PRECISION AS D, " +
                             "CAST('NaN' AS DOUBLE PRECISION) AS NAN, " +
                             "CAST('Infinity' AS DOUBLE PRECISION) AS INF, " +
                             "CAST('-Infinity' AS DOUBLE PRECISION) AS NINF")) {
            assertTrue(result.next());
            assertEquals(3.25f, result.getFloat("R"));
            assertEquals(-3.25d, result.getDouble("D"));
            assertTrue(Double.isNaN(result.getDouble("NAN")));
            assertTrue(Double.isInfinite(result.getDouble("INF")));
            assertEquals(Double.POSITIVE_INFINITY, result.getDouble("INF"));
            // The appliance normalizes the negative-infinity literal to +Infinity.
            assertTrue(Double.isInfinite(result.getDouble("NINF")));
            assertEquals("NaN", result.getString("NAN"));
            assertEquals("Infinity", result.getString("INF"));
        }
    }

    @Test
    @Timeout(60)
    void booleansAndNullsFollowJdbcDefaults() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TRUE::BOOLEAN AS T, FALSE::BOOLEAN AS F, NULL::BOOLEAN AS N")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean("T"));
            assertFalse(result.getBoolean("F"));
            assertFalse(result.getBoolean("N"));
            assertTrue(result.wasNull());
            assertEquals("true", result.getString("T"));
            assertEquals("false", result.getString("F"));
            assertNull(result.getObject("N"));
            assertInstanceOf(Boolean.class, result.getObject("T"));
        }
    }

    @Test
    @Timeout(60)
    void textTypesPreservePaddingAndUnicode() throws Exception {
        String unicode = "\u017b\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 'x'::CHAR(5) AS C, 'x'::VARCHAR(5) AS V, " +
                             "'\u017b\u00f3\u0142\u0107'::NCHAR(10) AS NC, " +
                             "'" + unicode + "'::NVARCHAR(40) AS NV")) {
            assertTrue(result.next());
            assertEquals(5, result.getString("C").length());
            assertEquals("x    ", result.getString("C"));
            assertEquals("x", result.getString("V"));
            assertEquals(10, result.getNString("NC").length());
            assertEquals(unicode, result.getNString("NV").trim());
            assertEquals(unicode, result.getString("NV").trim());
            assertEquals(Types.CHAR, result.getMetaData().getColumnType(1));
            assertEquals(Types.VARCHAR, result.getMetaData().getColumnType(2));
            assertEquals(Types.NCHAR, result.getMetaData().getColumnType(3));
            assertEquals(Types.NVARCHAR, result.getMetaData().getColumnType(4));
            assertTrue(result.getMetaData().isCaseSensitive(1));
        }
    }

    @Test
    @Timeout(60)
    void temporalBoundariesAndSpecialTypesAreReadable() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DATE '0001-01-01' AS DMIN, DATE '9999-12-31' AS DMAX, " +
                             "TIME '03:04:05.123456' AS TM, " +
                             "TIMESTAMP '2024-02-29 12:34:56.123456' AS TS, " +
                             "'03:04:05+02:00'::TIMETZ AS TZ, " +
                             "INTERVAL '2 days 03:04:05' AS IV")) {
            assertTrue(result.next());
            assertEquals(Date.valueOf("0001-01-01"), result.getDate("DMIN"));
            assertEquals(Date.valueOf("9999-12-31"), result.getDate("DMAX"));
            assertEquals("03:04:05", result.getTime("TM").toString());
            assertEquals(123, result.getTime("TM").getTime() % 1000);
            assertEquals("03:04:05.123456", result.getString("TM"));
            assertEquals(123_456_000, result.getObject("TM", LocalTime.class).getNano());
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"), result.getTimestamp("TS"));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
            assertTrue(result.getString("TZ").startsWith("03:04:05+02"), result.getString("TZ"));
            assertEquals("2 days 03:04:05", result.getString("IV"));
            OffsetTime timetz = OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(2));
            assertEquals(timetz, result.getObject("TZ"));
            assertEquals(timetz, result.getObject("TZ", OffsetTime.class));
            assertEquals(LocalTime.of(3, 4, 5), result.getObject("TZ", LocalTime.class));
            assertInstanceOf(OffsetTime.class, result.getObject("TZ"));
            assertEquals(Date.valueOf("1970-01-01"), result.getDate("TZ"));
            assertEquals(Timestamp.valueOf("1970-01-01 03:04:05"), result.getTimestamp("TZ"));
            assertEquals(Types.TIME_WITH_TIMEZONE, result.getMetaData().getColumnType(5));
            assertEquals(OffsetTime.class.getName(), result.getMetaData().getColumnClassName(5));
            assertInstanceOf(String.class, result.getObject("IV"));
        }
    }

    @Test
    @Timeout(60)
    void temporalGettersConvertAcrossRelatedTypes() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TIMESTAMP '2024-01-02 03:04:05.123456' AS TS, " +
                             "DATE '2024-01-02' AS D, TIME '03:04:05.123456' AS T")) {
            assertTrue(result.next());
            assertEquals(Date.valueOf("2024-01-02"), result.getDate("TS"));
            assertEquals("03:04:05", result.getTime("TS").toString());
            assertEquals(123, result.getTime("TS").getTime() % 1000);
            assertEquals(Timestamp.valueOf("2024-01-02 00:00:00"), result.getTimestamp("D"));
            assertEquals(Time.valueOf("00:00:00"), result.getTime("D"));
            assertEquals(LocalDateTime.of(1970, 1, 1, 3, 4, 5, 123_456_000),
                    result.getTimestamp("T").toLocalDateTime());
            assertEquals(Date.valueOf("1970-01-01"), result.getDate("T"));
            assertEquals(LocalDate.of(2024, 1, 2), result.getObject("TS", LocalDate.class));
            assertEquals(LocalTime.of(3, 4, 5, 123_456_000), result.getObject("TS", LocalTime.class));
            assertEquals(LocalDateTime.of(2024, 1, 2, 0, 0), result.getObject("D", LocalDateTime.class));
            assertEquals(LocalDateTime.of(1970, 1, 1, 3, 4, 5, 123_456_000),
                    result.getObject("T", LocalDateTime.class));
        }
    }

    @Test
    @Timeout(60)
    void binaryValuesAndEmptyPayloadsAreSupported() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT x'00ff' AS B")) {
            assertTrue(result.next());
            assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.getBytes("B"));
            assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.getObject("B", byte[].class));
            assertEquals(Types.VARBINARY, result.getMetaData().getColumnType(1));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT x'' AS E")) {
            assertTrue(result.next());
            assertArrayEquals(new byte[0], result.getBytes("E"));
        }
    }

    @Test
    @Timeout(60)
    void nullMatrixReturnsDefaultsAndTracksWasNull() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT NULL::BYTEINT AS B, NULL::SMALLINT AS S, NULL::INTEGER AS I, " +
                             "NULL::BIGINT AS L, NULL::REAL AS F, NULL::DOUBLE PRECISION AS D, " +
                             "NULL::NUMERIC(10,2) AS N, NULL::BOOLEAN AS BO, NULL::CHAR(5) AS C, " +
                             "NULL::VARCHAR(5) AS V, NULL::NCHAR(5) AS NC, NULL::NVARCHAR(5) AS NV, " +
                             "NULL::DATE AS DT, NULL::TIME AS TM, NULL::TIMESTAMP AS TS, " +
                             "NULL::VARBINARY(5) AS BIN, NULL::INTERVAL AS IV")) {
            assertTrue(result.next());
            assertEquals(0, result.getByte("B"));
            assertTrue(result.wasNull());
            assertEquals(0, result.getShort("S"));
            assertTrue(result.wasNull());
            assertEquals(0, result.getInt("I"));
            assertTrue(result.wasNull());
            assertEquals(0L, result.getLong("L"));
            assertTrue(result.wasNull());
            assertEquals(0f, result.getFloat("F"));
            assertTrue(result.wasNull());
            assertEquals(0d, result.getDouble("D"));
            assertTrue(result.wasNull());
            assertNull(result.getBigDecimal("N"));
            assertTrue(result.wasNull());
            assertFalse(result.getBoolean("BO"));
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
            assertNull(result.getTimestamp("TS"));
            assertTrue(result.wasNull());
            assertNull(result.getBytes("BIN"));
            assertTrue(result.wasNull());
            assertNull(result.getString("IV"));
            assertTrue(result.wasNull());
        }
    }

    @Test
    @Timeout(60)
    void crossTypeConversionsFollowJdbcSemantics() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 42::INTEGER AS I, 42.75::NUMERIC(10,2) AS N, '17' AS S, 1 AS ONE, '1' AS SONE, " +
                             "DATE '2024-01-02' AS D, TIMESTAMP '2024-01-02 03:04:05' AS TS, " +
                             "TRUE::BOOLEAN AS B, '2024-01-02 03:04:05' AS ST")) {
            assertTrue(result.next());
            assertEquals(42, result.getInt("N"));
            assertEquals(17, result.getInt("S"));
            assertEquals(new BigDecimal("17"), result.getBigDecimal("S"));
            assertEquals(42L, result.getLong("N"));
            assertTrue(result.getBoolean("ONE"));
            assertTrue(result.getBoolean("SONE"));
            assertEquals(Boolean.TRUE, result.getObject("ONE", Boolean.class));
            assertEquals(Boolean.TRUE, result.getObject("SONE", Boolean.class));
            assertEquals(42, result.getObject("N", Integer.class));
            assertEquals(42L, result.getObject("N", Long.class));
            assertEquals("2024-01-02", result.getString("D"));
            assertTrue(result.getString("TS").contains("2024-01-02"));
            assertEquals("true", result.getString("B"));
            assertEquals(Timestamp.valueOf("2024-01-02 03:04:05"), result.getTimestamp("ST"));
        }
    }

    @Test
    @Timeout(60)
    void metadataCoversTheWholeTypeMatrix() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1::BYTEINT AS BI, 2::SMALLINT AS SI, 3::INTEGER AS I, 4::BIGINT AS L, " +
                             "1.5::REAL AS R, 2.5::DOUBLE PRECISION AS D, 3.50::NUMERIC(8,2) AS N, " +
                             "'x'::CHAR(3) AS C, 'y'::VARCHAR(3) AS V, 'z'::NCHAR(3) AS NC, " +
                             "'w'::NVARCHAR(3) AS NV, DATE '2024-01-02' AS DT, TIME '03:04:05' AS TM, " +
                             "TIMESTAMP '2024-01-02 03:04:05' AS TS, '03:04:05+02:00'::TIMETZ AS TZ, " +
                             "TRUE::BOOLEAN AS B, x'00ff' AS BIN, " +
                             "INTERVAL '1 day' AS IV")) {
            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(18, metadata.getColumnCount());
            int[] expected = {Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL,
                    Types.DOUBLE, Types.NUMERIC, Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.BOOLEAN,
                    Types.VARBINARY, Types.OTHER};
            for (int i = 1; i <= expected.length; i++) {
                assertEquals(expected[i - 1], metadata.getColumnType(i), "column " + i);
                assertNotNull(metadata.getColumnTypeName(i));
                assertFalse(metadata.getColumnTypeName(i).isBlank());
                assertNotNull(metadata.getColumnClassName(i));
            }
            assertEquals(OffsetTime.class.getName(), metadata.getColumnClassName(15));
            assertInstanceOf(Byte.class, result.getObject("BI"));
            assertInstanceOf(Short.class, result.getObject("SI"));
            assertInstanceOf(Integer.class, result.getObject("I"));
            assertInstanceOf(Long.class, result.getObject("L"));
            assertInstanceOf(Float.class, result.getObject("R"));
            assertInstanceOf(Double.class, result.getObject("D"));
            assertInstanceOf(BigDecimal.class, result.getObject("N"));
            assertInstanceOf(String.class, result.getObject("C"));
            assertInstanceOf(Date.class, result.getObject("DT"));
            assertInstanceOf(Time.class, result.getObject("TM"));
            assertInstanceOf(Timestamp.class, result.getObject("TS"));
            assertInstanceOf(OffsetTime.class, result.getObject("TZ"));
            assertInstanceOf(Boolean.class, result.getObject("B"));
            assertInstanceOf(byte[].class, result.getObject("BIN"));
        }
    }

    @Test
    @Timeout(90)
    void largeTextAndNumericValuesSurviveRoundTrip() throws Exception {
        String large = "x".repeat(5_000);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT '" + large + "'::VARCHAR(10000) AS V, " +
                             "99999999999999999999999999999999999999::NUMERIC(38,0) AS N")) {
            assertTrue(result.next());
            assertEquals(5_000, result.getString("V").length());
            assertEquals(large, result.getString("V"));
            assertEquals(new BigDecimal("99999999999999999999999999999999999999"),
                    result.getBigDecimal("N"));
        }
    }

    @Test
    @Timeout(60)
    void localDateTimeTargetClassesPreserveValues() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DATE '2024-01-02' AS D, TIME '03:04:05.123456' AS T, " +
                             "TIMESTAMP '2024-01-02 03:04:05.123456' AS TS")) {
            assertTrue(result.next());
            assertEquals(LocalDate.of(2024, 1, 2), result.getObject("D", LocalDate.class));
            assertEquals(LocalTime.of(3, 4, 5, 123_456_000), result.getObject("T", LocalTime.class));
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
        }
    }
}
