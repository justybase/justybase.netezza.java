package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** JDBC escape processing against a live appliance. */
@Tag("live")
class EscapeProcessingLiveTest {
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
    void dateTimeTimestampAndFunctionEscapesExecute() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT {fn UCASE('abc')} AS U, {d '2024-02-29'} AS D, " +
                             "{t '12:34:56'} AS T, {ts '2024-02-29 12:34:56.123456'} AS TS")) {
            assertTrue(result.next());
            assertEquals("ABC", result.getString("U"));
            assertEquals(Date.valueOf("2024-02-29"), result.getDate("D"));
            assertEquals(Time.valueOf("12:34:56"), result.getTime("T"));
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"), result.getTimestamp("TS"));
        }
    }

    @Test
    @Timeout(60)
    void likeEscapeAndNativeSqlTranslationWork() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 'a_b' LIKE 'a!_%' {escape '!'} AS MATCH")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean("MATCH"));
        }
        assertEquals("DATE '2024-01-01'", connection.nativeSQL("{d '2024-01-01'}"));
    }

    @Test
    @Timeout(60)
    void setEscapeProcessingFalseLeavesJdbcBracesForTheServer() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setEscapeProcessing(false);
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT {fn UCASE('abc')}"));
            assertNotNull(error.getSQLState(), error.getMessage());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1::INTEGER")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}
