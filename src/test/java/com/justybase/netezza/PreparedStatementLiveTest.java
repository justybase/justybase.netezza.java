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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PreparedStatement binding, batching, validation and safety against a live appliance. */
@Tag("live")
class PreparedStatementLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_PS IF EXISTS");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_PS (ID INTEGER, NAME VARCHAR(80), AMOUNT NUMERIC(12,3), " +
                        "FLAG BOOLEAN, D DATE, T TIME, TS TIMESTAMP, BIN VARBINARY(16), NOTE NVARCHAR(80))");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @Timeout(60)
    void bindsEverySupportedScalarTypeAndReadsItBack() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setInt(1, 7);
            insert.setString(2, "O'Brien");
            insert.setBigDecimal(3, new BigDecimal("12.340"));
            insert.setBoolean(4, true);
            insert.setDate(5, Date.valueOf("2024-01-02"));
            insert.setTime(6, Time.valueOf("03:04:05"));
            insert.setTimestamp(7, Timestamp.valueOf("2024-01-02 03:04:05.123456"));
            insert.setBytes(8, new byte[]{0, 1, (byte) 0xFF});
            insert.setNString(9, "Za\u017c\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144");
            assertEquals(1, insert.executeUpdate());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM JAVA_PS WHERE ID=7")) {
            assertTrue(result.next());
            assertEquals(7, result.getInt("ID"));
            assertEquals("O'Brien", result.getString("NAME"));
            assertEquals(new BigDecimal("12.340"), result.getBigDecimal("AMOUNT"));
            assertTrue(result.getBoolean("FLAG"));
            assertEquals(Date.valueOf("2024-01-02"), result.getDate("D"));
            assertEquals("03:04:05", result.getTime("T").toString());
            assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123456"), result.getTimestamp("TS"));
            assertArrayEquals(new byte[]{0, 1, (byte) 0xFF}, result.getBytes("BIN"));
            assertEquals("Za\u017c\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144", result.getNString("NOTE"));
        }
    }

    @Test
    @Timeout(60)
    void nullsAreBoundExplicitlyAndReportedByWasNull() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setInt(1, 8);
            insert.setNull(2, Types.VARCHAR);
            insert.setNull(3, Types.NUMERIC);
            insert.setNull(4, Types.BOOLEAN);
            insert.setNull(5, Types.DATE);
            insert.setNull(6, Types.TIME);
            insert.setNull(7, Types.TIMESTAMP);
            insert.setNull(8, Types.VARBINARY);
            insert.setNull(9, Types.NVARCHAR);
            assertEquals(1, insert.executeUpdate());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM JAVA_PS WHERE ID=8")) {
            assertTrue(result.next());
            assertNull(result.getString("NAME"));
            assertTrue(result.wasNull());
            assertNull(result.getBigDecimal("AMOUNT"));
            assertTrue(result.wasNull());
            assertFalse(result.getBoolean("FLAG"));
            assertTrue(result.wasNull());
            assertNull(result.getDate("D"));
            assertTrue(result.wasNull());
            assertNull(result.getTime("T"));
            assertTrue(result.wasNull());
            assertNull(result.getTimestamp("TS"));
            assertTrue(result.wasNull());
            assertNull(result.getBytes("BIN"));
            assertTrue(result.wasNull());
            assertNull(result.getNString("NOTE"));
            assertTrue(result.wasNull());
        }
    }

    @Test
    @Timeout(60)
    void setObjectSupportsJdbcAndJavaTimeValues() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setObject(1, 9);
            insert.setObject(2, "object");
            insert.setObject(3, new BigDecimal("9.125"), Types.NUMERIC);
            insert.setObject(4, Boolean.FALSE);
            insert.setObject(5, LocalDate.of(2024, 2, 29));
            insert.setObject(6, LocalTime.of(12, 34, 56));
            insert.setObject(7, LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000));
            insert.setObject(8, new byte[]{9, 8, 7});
            insert.setObject(9, "unicode-\u017c\u00f3\u0142\u0107");
            assertEquals(1, insert.executeUpdate());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM JAVA_PS WHERE ID=9")) {
            assertTrue(result.next());
            assertEquals("object", result.getString("NAME"));
            assertEquals(new BigDecimal("9.125"), result.getBigDecimal("AMOUNT"));
            assertFalse(result.getBoolean("FLAG"));
            assertEquals(Date.valueOf("2024-02-29"), result.getDate("D"));
            assertEquals("12:34:56", result.getTime("T").toString());
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.789"), result.getTimestamp("TS"));
            assertArrayEquals(new byte[]{9, 8, 7}, result.getBytes("BIN"));
            assertEquals("unicode-\u017c\u00f3\u0142\u0107", result.getNString("NOTE"));
        }
    }

    @Test
    @Timeout(60)
    void timestampWithTimezoneParametersAreRejectedWithClearSqlState() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
            OffsetDateTime offset = OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, java.time.ZoneOffset.ofHours(2));
            SQLException offsetError = assertThrows(SQLException.class, () -> statement.setObject(1, offset));
            assertEquals("0A000", offsetError.getSQLState());
            SQLException instantError = assertThrows(SQLException.class,
                    () -> statement.setObject(1, Instant.parse("2024-01-02T03:04:05Z")));
            assertEquals("0A000", instantError.getSQLState());
            statement.setObject(1, offset, Types.TIMESTAMP);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertNotNull(result.getTimestamp(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void clearParametersAllowsRebindingAndDetectsMissingValues() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS (ID, NAME) VALUES (?,?)")) {
            insert.setInt(1, 1);
            insert.setString(2, "one");
            insert.clearParameters();
            insert.setInt(1, 2);
            insert.setString(2, "two");
            assertEquals(1, insert.executeUpdate());

            insert.clearParameters();
            SQLException missing = assertThrows(SQLException.class, insert::executeUpdate);
            assertEquals("07001", missing.getSQLState());
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS WHERE ID=2"));
    }

    @Test
    @Timeout(90)
    void reusesOnePreparedStatementForManyRows() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS (ID, NAME) VALUES (?,?)")) {
            for (int i = 100; i < 125; i++) {
                insert.setInt(1, i);
                insert.setString(2, "row-" + i);
                assertEquals(1, insert.executeUpdate());
            }
        }
        assertEquals(25, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS WHERE ID>=100"));
    }

    @Test
    @Timeout(60)
    void batchesReturnCountsAndCanBeCleared() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS (ID, NAME) VALUES (?,?)")) {
            for (int i = 200; i < 203; i++) {
                insert.setInt(1, i);
                insert.setString(2, "batch-" + i);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(3, counts.length);
            for (int count : counts) assertEquals(Statement.SUCCESS_NO_INFO, count);
            assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS WHERE ID>=200"));

            insert.setInt(1, 203);
            insert.setString(2, "large-batch");
            insert.addBatch();
            long[] large = insert.executeLargeBatch();
            assertEquals(1, large.length);
            assertEquals(1L, large[0]);

            insert.setInt(1, 204);
            insert.setString(2, "cleared");
            insert.addBatch();
            insert.clearBatch();
            assertEquals(0, insert.executeBatch().length);
            assertEquals(4, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS WHERE ID>=200"));
        }
    }

    @Test
    @Timeout(60)
    void parameterCountMismatchesUseSqlState07001() throws Exception {
        try (PreparedStatement select = connection.prepareStatement("SELECT ? AS V")) {
            SQLException missing = assertThrows(SQLException.class, select::executeQuery);
            assertEquals("07001", missing.getSQLState());

            select.setInt(1, 1);
            select.setInt(2, 2);
            SQLException extra = assertThrows(SQLException.class, select::executeQuery);
            assertEquals("07001", extra.getSQLState());
        }
        try (PreparedStatement noMarkers = connection.prepareStatement("SELECT 1")) {
            noMarkers.setInt(1, 1);
            SQLException extra = assertThrows(SQLException.class, noMarkers::executeQuery);
            assertEquals("07001", extra.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void boundTextCannotEscapeItsLiteralOrInjectSql() throws Exception {
        String hostile = "x' OR 1=1 -- ?\nsecond line";
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_PS (ID, NAME) VALUES (?,?)")) {
            insert.setInt(1, 300);
            insert.setString(2, hostile);
            assertEquals(1, insert.executeUpdate());
        }
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS WHERE ID=300"));
        try (PreparedStatement select = connection.prepareStatement("SELECT NAME FROM JAVA_PS WHERE ID=?")) {
            select.setInt(1, 300);
            try (ResultSet result = select.executeQuery()) {
                assertTrue(result.next());
                assertEquals(hostile, result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    @Timeout(60)
    void questionMarksInsideQuotesAndCommentsStayLiteral() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ? AS V, '?' AS Q /* ? */ -- ?\n")) {
            statement.setInt(1, 7);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(7, result.getInt("V"));
                assertEquals("?", result.getString("Q"));
            }
        }
    }

    @Test
    @Timeout(60)
    void temporalParametersPreserveMidnightAndMicroseconds() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_PS (ID, D, T, TS) VALUES (?,?,?,?)")) {
            insert.setInt(1, 400);
            insert.setDate(2, Date.valueOf("2024-02-29"));
            insert.setTime(3, Time.valueOf("00:00:00"));
            insert.setTimestamp(4, Timestamp.valueOf("2024-01-03 00:00:00"));
            assertEquals(1, insert.executeUpdate());
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT D, T, TS FROM JAVA_PS WHERE ID=?")) {
            select.setInt(1, 400);
            try (ResultSet result = select.executeQuery()) {
                assertTrue(result.next());
                assertEquals(Date.valueOf("2024-02-29"), result.getDate(1));
                assertEquals("00:00:00", result.getTime(2).toString());
                assertEquals(Timestamp.valueOf("2024-01-03 00:00:00"), result.getTimestamp(3));
                assertEquals(LocalDateTime.of(2024, 1, 3, 0, 0), result.getObject(3, LocalDateTime.class));
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_PS (ID, TS) VALUES (?,?)")) {
            insert.setInt(1, 401);
            insert.setTimestamp(2, Timestamp.valueOf("2024-01-02 03:04:05.123456"));
            assertEquals(1, insert.executeUpdate());
        }
        try (PreparedStatement verify = connection.prepareStatement("SELECT TS FROM JAVA_PS WHERE ID=?")) {
            verify.setInt(1, 401);
            try (ResultSet result = verify.executeQuery()) {
                assertTrue(result.next());
                assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123456"), result.getTimestamp(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void binaryAndUnicodeParametersRoundTrip() throws Exception {
        byte[] binary = {0, 1, 2, 3, (byte) 0xFE, (byte) 0xFF};
        String unicode = "\u017b\u00f3\u0142\u0107 \u4f60\u597d";
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_PS (ID, BIN, NOTE) VALUES (?,?,?)")) {
            insert.setInt(1, 500);
            insert.setBytes(2, binary);
            insert.setNString(3, unicode);
            assertEquals(1, insert.executeUpdate());
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT BIN, NOTE FROM JAVA_PS WHERE ID=?")) {
            select.setInt(1, 500);
            try (ResultSet result = select.executeQuery()) {
                assertTrue(result.next());
                assertArrayEquals(binary, result.getBytes(1));
                assertEquals(unicode, result.getNString(2));
            }
        }
    }

    @Test
    @Timeout(60)
    void preparedUpdatesAndDeletesReportAffectedRows() throws Exception {
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_PS (ID, NAME) SELECT 1,'a' UNION ALL SELECT 2,'b' UNION ALL SELECT 3,'c'");
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE JAVA_PS SET NAME=? WHERE ID>=?")) {
            update.setString(1, "updated");
            update.setInt(2, 2);
            assertEquals(2, update.executeUpdate());
            assertEquals(2, update.executeLargeUpdate());
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM JAVA_PS WHERE ID=?")) {
            delete.setInt(1, 3);
            assertEquals(1, delete.executeUpdate());
        }
        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_PS"));
    }

    @Test
    @Timeout(60)
    void preparedSelectUsesParametersAndExposesMetadata() throws Exception {
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_PS (ID, NAME, AMOUNT) SELECT 600,'target',6.50");
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT ID, NAME, AMOUNT FROM JAVA_PS WHERE ID=?")) {
            select.setInt(1, 600);
            try (ResultSet result = select.executeQuery()) {
                assertEquals(3, result.getMetaData().getColumnCount());
                assertEquals("ID", result.getMetaData().getColumnLabel(1));
                assertTrue(result.next());
                assertEquals(600, result.getInt("ID"));
                assertEquals("target", result.getString("NAME"));
                assertEquals(new BigDecimal("6.500"), result.getBigDecimal("AMOUNT"));
                assertFalse(result.next());
            }
        }
    }

    @Test
    @Timeout(60)
    void unsupportedParameterApisFailWith0A000() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            assertUnsupported(() -> statement.setArray(1, null));
            assertUnsupported(() -> statement.setRef(1, null));
            assertUnsupported(() -> statement.setRowId(1, null));
            assertEquals(0, statement.getParameterMetaData().getParameterCount());
            statement.setBlob(1, (java.sql.Blob) null);
            statement.setClob(1, (java.sql.Clob) null);
            statement.setNClob(1, (java.sql.NClob) null);
            statement.setCharacterStream(1, (java.io.Reader) null);
            statement.setAsciiStream(1, (java.io.InputStream) null);
            statement.setBinaryStream(1, (java.io.InputStream) null);
            statement.setSQLXML(1, null);
            statement.setNCharacterStream(1, (java.io.Reader) null);
        }
    }

    @Test
    @Timeout(60)
    void closedPreparedStatementRejectsBindingAndExecution() throws Exception {
        PreparedStatement statement = connection.prepareStatement("SELECT ?");
        statement.close();
        assertTrue(statement.isClosed());
        SQLException bind = assertThrows(SQLException.class, () -> statement.setInt(1, 1));
        assertEquals("07000", bind.getSQLState());
        SQLException execute = assertThrows(SQLException.class, statement::executeQuery);
        assertEquals("07000", execute.getSQLState());
        statement.close();
    }

    @Test
    @Timeout(60)
    void supportsCalendarOverloadsAndStatementIdentity() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO JAVA_PS (ID, D, TS) VALUES (?,?,?)")) {
            assertSame(connection, statement.getConnection());
            assertSame(statement, statement.unwrap(PreparedStatement.class));
            assertTrue(statement.isWrapperFor(PreparedStatement.class));
            statement.setInt(1, 700);
            statement.setDate(2, Date.valueOf("2024-05-06"), Calendar.getInstance());
            statement.setTimestamp(3, Timestamp.valueOf("2024-05-06 07:08:09"), Calendar.getInstance());
            assertEquals(1, statement.executeUpdate());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT D, TS FROM JAVA_PS WHERE ID=700")) {
            assertTrue(result.next());
            assertEquals(Date.valueOf("2024-05-06"), result.getDate(1));
            assertEquals(Timestamp.valueOf("2024-05-06 07:08:09"), result.getTimestamp(2));
        }
    }

    private static void assertUnsupported(SqlCall call) {
        SQLException error = assertThrows(SQLException.class, call::run);
        assertEquals("0A000", error.getSQLState(), error.getMessage());
    }

    @FunctionalInterface
    private interface SqlCall {
        void run() throws Exception;
    }
}
