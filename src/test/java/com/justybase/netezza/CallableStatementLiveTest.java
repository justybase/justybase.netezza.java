package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Positional CallableStatement support over Netezza CALL. */
@Tag("live")
class CallableStatementLiveTest {
    private Connection connection;
    private String schema;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        assumeTrue("1".equals(LiveTestSupport.value("NZ_DEV_ALLOW_FIXTURE_DDL")),
                "NZ_DEV_ALLOW_FIXTURE_DDL=1 is required for callable procedure tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        schema = LiveTestSupport.schema();
        dropProcedure();
        dropTimestampProcedure();
        LiveTestSupport.execute(connection, "CREATE OR REPLACE PROCEDURE " + schema + ".JAVA_CALL_ADD(INTEGER, INTEGER) " +
                "RETURNS INTEGER LANGUAGE NZPLSQL AS BEGIN_PROC " +
                "DECLARE a ALIAS FOR $1; b ALIAS FOR $2; BEGIN RETURN a + b; END; END_PROC");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null && !connection.isClosed()) {
                dropProcedure();
                dropTimestampProcedure();
            }
        } finally {
            if (connection != null) connection.close();
        }
    }

    @Test
    @Timeout(60)
    void callableStatementBindsInputsAndReadsReturnValue() throws Exception {
        try (CallableStatement statement = connection.prepareCall("{call " + schema + ".JAVA_CALL_ADD(?, ?)}")) {
            statement.setInt(1, 20);
            statement.setInt(2, 22);
            statement.registerOutParameter(1, Types.INTEGER);
            assertTrue(statement.execute());
            assertEquals(42, statement.getInt(1));
            assertFalse(statement.wasNull());
            assertEquals(42, statement.getObject(1, Integer.class));
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
            assertEquals(2, statement.getParameterMetaData().getParameterCount());
            assertEquals(Types.INTEGER, statement.getParameterMetaData().getParameterType(1));
        }
    }

    @Test
    @Timeout(60)
    void callableStatementAlsoSupportsPlainCallAndExecuteQuery() throws Exception {
        try (CallableStatement statement = connection.prepareCall("CALL " + schema + ".JAVA_CALL_ADD(?, ?)")) {
            statement.setInt(1, 19);
            statement.setInt(2, 23);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void outOfRangeAndNamedOutParametersAreRejected() throws Exception {
        try (CallableStatement statement = connection.prepareCall("{call " + schema + ".JAVA_CALL_ADD(?, ?)}")) {
            statement.setInt(1, 1);
            statement.setInt(2, 2);
            statement.registerOutParameter(2, Types.INTEGER);
            statement.execute();
            SQLException outOfRange = assertThrows(SQLException.class, () -> statement.getInt(2));
            assertEquals("07009", outOfRange.getSQLState());
            SQLException named = assertThrows(SQLException.class,
                    () -> statement.registerOutParameter("result", Types.INTEGER));
            assertEquals("0A000", named.getSQLState());
        }
    }

    @Test
    @Timeout(60)
    void calendarGettersInterpretOutputInTheRequestedZone() throws Exception {
        LiveTestSupport.execute(connection, "CREATE OR REPLACE PROCEDURE " + schema + ".JAVA_CALL_TS() " +
                "RETURNS TIMESTAMP LANGUAGE NZPLSQL AS BEGIN_PROC " +
                "BEGIN RETURN TIMESTAMP '2024-01-01 12:34:56.123456'; END; END_PROC");
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (CallableStatement statement = connection.prepareCall("{call " + schema + ".JAVA_CALL_TS()}")) {
            statement.registerOutParameter(1, Types.TIMESTAMP);
            statement.execute();
            assertEquals(LocalDateTime.of(2024, 1, 1, 12, 34, 56, 123_456_000).toInstant(ZoneOffset.UTC),
                    statement.getTimestamp(1, utc).toInstant());
            assertEquals(1704067200000L, statement.getDate(1, utc).getTime());
            assertEquals(45_296_123L, statement.getTime(1, utc).getTime());
            assertEquals(Timestamp.valueOf("2024-01-01 12:34:56.123456"), statement.getObject(1));
            assertEquals(LocalDateTime.of(2024, 1, 1, 12, 34, 56, 123_456_000),
                    statement.getObject(1, LocalDateTime.class));
        } finally {
            dropTimestampProcedure();
        }
    }

    private void dropProcedure() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP PROCEDURE " + schema + ".JAVA_CALL_ADD(INTEGER, INTEGER)");
        } catch (SQLException ignored) {
        }
    }

    private void dropTimestampProcedure() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP PROCEDURE " + schema + ".JAVA_CALL_TS()");
        } catch (SQLException ignored) {
        }
    }
}
