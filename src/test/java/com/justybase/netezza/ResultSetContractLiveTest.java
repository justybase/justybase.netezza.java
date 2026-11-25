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
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Broad JDBC ResultSet contract against DBOS and text protocol rows. */
@Tag("live")
class ResultSetContractLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_RS IF EXISTS");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_RS (I INTEGER, D DOUBLE PRECISION, S VARCHAR(30), N INTEGER, " +
                        "DT DATE, TM TIME, TS TIMESTAMP, B BOOLEAN, BI BYTEINT, SI SMALLINT, L BIGINT, " +
                        "NUM NUMERIC(12,3), BIN VARBINARY(10))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_RS SELECT 1, 2.5, 'text', NULL, DATE '2024-01-02', " +
                        "TIME '03:04:05.123456', TIMESTAMP '2024-01-02 03:04:05.123456', TRUE, 7, 8, 9, " +
                        "1234.500, x'00ff' " +
                        "UNION ALL SELECT 2, -1.25, NULL, 42, NULL, NULL, NULL, FALSE, -7, -8, -9, -0.125, NULL");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void forwardNavigationTracksRowNumbersAndBoundaryFlags() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT ID FROM (SELECT 1 AS ID UNION ALL SELECT 2 UNION ALL SELECT 3) T ORDER BY ID")) {
            assertEquals(0, result.getRow());
            assertTrue(result.isBeforeFirst());
            assertFalse(result.isAfterLast());
            assertFalse(result.isFirst());
            assertFalse(result.isLast());

            assertTrue(result.next());
            assertEquals(1, result.getRow());
            assertTrue(result.isFirst());
            assertFalse(result.isBeforeFirst());

            assertTrue(result.next());
            assertEquals(2, result.getRow());
            assertFalse(result.isFirst());

            assertTrue(result.next());
            assertEquals(3, result.getRow());

            assertFalse(result.next());
            assertEquals(3, result.getRow());
            assertTrue(result.isAfterLast());
            assertFalse(result.isBeforeFirst());
            assertFalse(result.isLast());
            assertFalse(result.next());
        }
    }

    @Test
    @Timeout(60)
    void findColumnIsCaseInsensitiveAndFirstDuplicateLabelWins() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 AS \"Mixed Label\", 2 AS snake_case, 3 AS \"with space\", 4 AS X, 5 AS X")) {
            assertEquals(1, result.findColumn("Mixed Label"));
            assertEquals(1, result.findColumn("mixed label"));
            assertEquals(2, result.findColumn("SNAKE_CASE"));
            assertEquals(3, result.findColumn("with space"));
            assertEquals(4, result.getInt("X"));
            assertEquals(4, result.findColumn("x"));

            SQLException missing = assertThrows(SQLException.class, () -> result.findColumn("NO_SUCH_COLUMN"));
            assertEquals("S0022", missing.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void wasNullTracksEveryGetterAndResetsOnTheNextValue() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT CAST(NULL AS INTEGER) AS N, 7 AS V, CAST(NULL AS VARCHAR(5)) AS S, " +
                             "CAST(NULL AS DATE) AS D, CAST(NULL AS TIMESTAMP) AS TS")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt("N"));
            assertTrue(result.wasNull());
            assertEquals(0L, result.getLong("N"));
            assertTrue(result.wasNull());
            assertFalse(result.getBoolean("N"));
            assertTrue(result.wasNull());
            assertNull(result.getString("N"));
            assertTrue(result.wasNull());
            assertNull(result.getBigDecimal("N"));
            assertTrue(result.wasNull());
            assertNull(result.getDate("N"));
            assertTrue(result.wasNull());
            assertNull(result.getTimestamp("N"));
            assertTrue(result.wasNull());
            assertNull(result.getObject("N"));
            assertTrue(result.wasNull());
            assertNull(result.getObject("N", Integer.class));
            assertTrue(result.wasNull());
            assertNull(result.getString("S"));
            assertTrue(result.wasNull());
            assertNull(result.getDate("D"));
            assertTrue(result.wasNull());
            assertNull(result.getTimestamp("TS"));
            assertTrue(result.wasNull());

            assertEquals(7, result.getInt("V"));
            assertFalse(result.wasNull());
        }
    }

    @Test
    @Timeout(60)
    void typedGettersConvertNumbersStringsAndBooleans() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 42 AS I, 1234567890123 AS L, 1.5 AS D, 2.50::NUMERIC(5,2) AS NUM, TRUE AS B, '17' AS S, 1 AS ONE")) {
            assertTrue(result.next());
            assertEquals(42, result.getInt("I"));
            assertEquals(42L, result.getLong("I"));
            assertEquals((short) 42, result.getShort("I"));
            assertEquals((byte) 42, result.getByte("I"));
            assertEquals(42.0f, result.getFloat("I"));
            assertEquals(42.0d, result.getDouble("I"));
            assertEquals("42", result.getString("I"));
            assertEquals(1234567890123L, result.getLong("L"));
            assertEquals(1.5d, result.getDouble("D"), 0.000001);
            assertEquals(new BigDecimal("2.50"), result.getBigDecimal("NUM"));
            assertTrue(result.getBoolean("B"));
            assertTrue(result.getBoolean("ONE"));
            assertEquals(17, result.getInt("S"));
            assertEquals(Integer.valueOf(42), result.getObject("I"));
            assertFalse(result.wasNull());
        }
    }

    @Test
    @Timeout(60)
    void objectClassesMatchTheDeclaredJdbcTypes() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT I, D, S, N, DT, TM, TS, B, BI, SI, L, NUM, BIN FROM JAVA_RS WHERE I=1")) {
            assertTrue(result.next());
            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(Types.INTEGER, metadata.getColumnType(1));
            assertEquals(Types.DOUBLE, metadata.getColumnType(2));
            assertEquals(Types.VARCHAR, metadata.getColumnType(3));
            assertEquals(Types.INTEGER, metadata.getColumnType(4));
            assertEquals(Types.DATE, metadata.getColumnType(5));
            assertEquals(Types.TIME, metadata.getColumnType(6));
            assertEquals(Types.TIMESTAMP, metadata.getColumnType(7));
            assertEquals(Types.BOOLEAN, metadata.getColumnType(8));
            assertEquals(Types.TINYINT, metadata.getColumnType(9));
            assertEquals(Types.SMALLINT, metadata.getColumnType(10));
            assertEquals(Types.BIGINT, metadata.getColumnType(11));
            assertEquals(Types.NUMERIC, metadata.getColumnType(12));
            assertEquals(Types.VARBINARY, metadata.getColumnType(13));

            assertInstanceOf(Integer.class, result.getObject("I"));
            assertInstanceOf(Double.class, result.getObject("D"));
            assertInstanceOf(String.class, result.getObject("S"));
            assertNull(result.getObject("N"));
            assertInstanceOf(Date.class, result.getObject("DT"));
            assertInstanceOf(Time.class, result.getObject("TM"));
            assertInstanceOf(Timestamp.class, result.getObject("TS"));
            assertInstanceOf(Boolean.class, result.getObject("B"));
            assertInstanceOf(Byte.class, result.getObject("BI"));
            assertInstanceOf(Short.class, result.getObject("SI"));
            assertInstanceOf(Long.class, result.getObject("L"));
            assertInstanceOf(BigDecimal.class, result.getObject("NUM"));
            assertInstanceOf(byte[].class, result.getObject("BIN"));

            assertEquals("java.lang.Integer", metadata.getColumnClassName(1));
            assertEquals("java.sql.Timestamp", metadata.getColumnClassName(7));
            assertEquals("java.lang.Byte", metadata.getColumnClassName(9));
            assertEquals("java.math.BigDecimal", metadata.getColumnClassName(12));
            assertEquals("[B", metadata.getColumnClassName(13));
        }
    }

    @Test
    @Timeout(60)
    void temporalGettersPreserveMidnightAndMicroseconds() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DATE '2024-01-02' AS D, TIME '03:04:05.123456' AS T, " +
                             "TIMESTAMP '2024-01-02 03:04:05.123456' AS TS, " +
                             "TIMESTAMP '2024-01-03 00:00:00' AS MIDNIGHT")) {
            assertTrue(result.next());
            assertEquals(Date.valueOf("2024-01-02"), result.getDate("D"));
            assertEquals(LocalDate.of(2024, 1, 2), result.getObject("D", LocalDate.class));
            assertEquals("03:04:05", result.getTime("T").toString());
            assertEquals(123_456_000, result.getObject("T", LocalTime.class).getNano());
            assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123456"), result.getTimestamp("TS"));
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_000),
                    result.getObject("TS", LocalDateTime.class));
            assertEquals(Timestamp.valueOf("2024-01-03 00:00:00"), result.getTimestamp("MIDNIGHT"));
            assertInstanceOf(Timestamp.class, result.getObject("TS"));
        }
    }

    @Test
    @Timeout(60)
    void binaryValuesRoundTripAndTextBytesUseUtf8() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT x'00ff' AS B, 'abc' AS S")) {
            assertTrue(result.next());
            assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.getBytes("B"));
            assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.getObject("B", byte[].class));
            assertArrayEquals(new byte[]{97, 98, 99}, result.getBytes("S"));
            assertEquals(Types.VARBINARY, result.getMetaData().getColumnType(1));
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT BIN FROM JAVA_RS WHERE I=1")) {
            assertTrue(result.next());
            assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.getBytes(1));
        }
    }

    @Test
    @Timeout(60)
    void objectWithTargetClassConvertsAcrossTypes() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 42 AS I, 2.50::NUMERIC(5,2) AS N, 'abc' AS S, DATE '2024-01-02' AS D, " +
                             "TIME '03:04:05' AS T, TIMESTAMP '2024-01-02 03:04:05' AS TS, TRUE AS B")) {
            assertTrue(result.next());
            assertEquals(42L, result.getObject("I", Long.class));
            assertEquals(42, result.getObject("I", Integer.class));
            assertEquals("42", result.getObject("I", String.class));
            assertEquals(new BigDecimal("2.50"), result.getObject("N", BigDecimal.class));
            assertEquals(LocalDate.of(2024, 1, 2), result.getObject("D", LocalDate.class));
            assertEquals(LocalTime.of(3, 4, 5), result.getObject("T", LocalTime.class));
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), result.getObject("TS", LocalDateTime.class));
            assertEquals(Timestamp.valueOf("2024-01-02 03:04:05"), result.getObject("TS", Timestamp.class));
            assertTrue(result.getObject("B", Boolean.class));

            SQLException invalid = assertThrows(SQLException.class,
                    () -> result.getObject("S", Integer.class));
            assertEquals("22018", invalid.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void metadataDescribesTypesNamesAndFlags() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT I AS A, NUM AS B, S AS C, N AS D, B AS E, BIN AS F FROM JAVA_RS WHERE I=1")) {
            ResultSetMetaData metadata = result.getMetaData();
            assertEquals(6, metadata.getColumnCount());
            assertEquals("A", metadata.getColumnLabel(1));
            assertEquals(Types.INTEGER, metadata.getColumnType(1));
            assertEquals(Types.NUMERIC, metadata.getColumnType(2));
            assertEquals(Types.VARCHAR, metadata.getColumnType(3));
            assertEquals(Types.INTEGER, metadata.getColumnType(4));
            assertEquals(Types.BOOLEAN, metadata.getColumnType(5));
            assertEquals(Types.VARBINARY, metadata.getColumnType(6));

            assertTrue(metadata.isSigned(1));
            assertTrue(metadata.isSigned(2));
            assertFalse(metadata.isSigned(3));
            assertFalse(metadata.isSigned(5));
            assertTrue(metadata.isCaseSensitive(3));
            assertFalse(metadata.isCaseSensitive(1));
            assertTrue(metadata.isReadOnly(1));
            assertTrue(metadata.isSearchable(1));
            assertFalse(metadata.isWritable(1));
            assertFalse(metadata.isDefinitelyWritable(1));
            assertFalse(metadata.isAutoIncrement(1));
            assertFalse(metadata.isCurrency(1));
            assertEquals(3, metadata.getScale(2));
            assertEquals(ResultSetMetaData.columnNullable, metadata.isNullable(4));
        }
    }

    @Test
    @Timeout(60)
    void emptyResultKeepsColumnMetadata() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 AS A, 'x' AS B WHERE 1=0")) {
            assertEquals(2, result.getMetaData().getColumnCount());
            assertEquals("A", result.getMetaData().getColumnLabel(1));
            assertFalse(result.next());
            assertEquals(0, result.getRow());
            assertFalse(result.isBeforeFirst());
            assertFalse(result.isAfterLast());
            assertFalse(result.next());
        }
    }

    @Test
    @Timeout(60)
    void unsupportedNavigationAndUpdateMethodsFailCleanly() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 AS ID")) {
            for (String method : new String[]{"previous", "beforeFirst", "afterLast", "moveToInsertRow",
                    "moveToCurrentRow", "updateRow", "insertRow", "deleteRow", "refreshRow", "cancelRowUpdates"}) {
                SQLException error = assertThrows(SQLException.class, () -> invokeNoArgs(result, method), method);
                assertTrue(error instanceof SQLFeatureNotSupportedException, method);
                assertEquals("0A000", error.getSQLState(), method);
            }
            assertThrows(SQLFeatureNotSupportedException.class, () -> result.absolute(1));
            assertThrows(SQLFeatureNotSupportedException.class, () -> result.relative(1));
            assertFalse(result.rowUpdated());
            assertFalse(result.rowInserted());
            assertFalse(result.rowDeleted());
            assertNull(result.getCursorName());
            assertNull(result.getWarnings());
            result.clearWarnings();
            assertEquals(ResultSet.FETCH_FORWARD, result.getFetchDirection());
            result.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> result.setFetchDirection(ResultSet.FETCH_REVERSE));
            assertEquals(0, result.getFetchSize());
            result.setFetchSize(100);
            assertEquals(100, result.getFetchSize());
            assertThrows(SQLException.class, () -> result.setFetchSize(-1));
        }
    }

    @Test
    @Timeout(60)
    void closeSemanticsCloseResultSetsAndRejectFurtherReads() throws Exception {
        try (Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery("SELECT 1 AS ID");
            assertSame(statement, result.getStatement());
            assertSame(result, result.unwrap(ResultSet.class));
            assertTrue(result.isWrapperFor(ResultSet.class));
            assertFalse(result.isClosed());
            result.close();
            assertTrue(result.isClosed());
            SQLException closed = assertThrows(SQLException.class, result::next);
            assertEquals("24000", closed.getSQLState());
            result.close();

            ResultSet other = statement.executeQuery("SELECT 2 AS ID");
            statement.close();
            assertTrue(other.isClosed());
        }
    }

    @Test
    @Timeout(60)
    void getStringFormatsScalarValues() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 42 AS I, 2.50::NUMERIC(5,2) AS N, 'abc' AS S, TRUE AS B")) {
            assertTrue(result.next());
            assertEquals("42", result.getString("I"));
            assertEquals("2.50", result.getString("N"));
            assertEquals("abc", result.getString("S"));
            assertEquals("true", result.getString("B"));
        }
    }

    private static void invokeNoArgs(Object target, String method) throws Exception {
        try {
            target.getClass().getMethod(method).invoke(target);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }
}
