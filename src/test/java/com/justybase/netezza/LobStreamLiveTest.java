package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** LOB and stream getters/setters against a live appliance. */
@Tag("live")
class LobStreamLiveTest {
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
    void resultSetExposesBlobClobAndStreams() throws Exception {
        String unicode = "zażółć gęślą jaźń";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT x'0102ff'::VARBINARY(3) AS B, '" + unicode + "'::NVARCHAR(40) AS T")) {
            assertTrue(result.next());
            assertArrayEquals(new byte[] {1, 2, (byte) 0xff}, result.getBlob("B").getBytes(1, 3));
            assertArrayEquals(new byte[] {1, 2, (byte) 0xff}, result.getBinaryStream("B").readAllBytes());
            assertEquals(unicode, result.getClob("T").getSubString(1, 100));
            assertEquals(unicode, result.getNClob("T").getSubString(1, 100));
            assertEquals(unicode, read(result.getCharacterStream("T")));
            assertEquals(unicode, read(result.getNCharacterStream("T")));
            assertEquals(unicode, result.getSQLXML("T").getString());
            assertNotNull(result.getAsciiStream("T"));
        }
    }

    @Test
    @Timeout(60)
    void preparedStatementBindsStreamsLobsAndSqlXml() throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 0xff};
        String text = "zażółć";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ?::VARBINARY(20) AS B, ?::NVARCHAR(40) AS T, ?::NVARCHAR(40) AS C, " +
                        "?::NVARCHAR(40) AS N, ?::NVARCHAR(40) AS X, ?::VARCHAR(10) AS A")) {
            statement.setBinaryStream(1, new ByteArrayInputStream(bytes), bytes.length);
            statement.setCharacterStream(2, new StringReader(text), text.length());
            Clob clob = connection.createClob();
            clob.setString(1, text);
            statement.setClob(3, clob);
            NClob nclob = connection.createNClob();
            nclob.setString(1, text);
            statement.setNClob(4, nclob);
            SQLXML xml = connection.createSQLXML();
            xml.setString(text);
            statement.setSQLXML(5, xml);
            statement.setAsciiStream(6, new ByteArrayInputStream("ascii".getBytes(StandardCharsets.US_ASCII)), 5);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertArrayEquals(bytes, result.getBytes(1));
                assertEquals(text, result.getString(2));
                assertEquals(text, result.getString(3));
                assertEquals(text, result.getString(4));
                assertEquals(text, result.getString(5));
                assertEquals("ascii", result.getString(6));
            }
        }
    }

    @Test
    @Timeout(60)
    void connectionFactoriesCreateLobsAndRejectArrays() throws Exception {
        assertNotNull(connection.createBlob());
        assertNotNull(connection.createClob());
        assertNotNull(connection.createNClob());
        assertNotNull(connection.createSQLXML());
        SQLException arrays = assertThrows(SQLException.class,
                () -> connection.createArrayOf("INTEGER", new Object[] {1, 2}));
        assertEquals("0A000", arrays.getSQLState());
    }

    @Test
    @Timeout(60)
    void streamLimitIsEnforced() throws Exception {
        Properties properties = LiveTestSupport.connectionProperties();
        properties.setProperty("maxStreamBytes", "4");
        try (Connection limited = DriverManager.getConnection(LiveTestSupport.url(), properties);
             PreparedStatement statement = limited.prepareStatement("SELECT ?::VARBINARY(20)")) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.setBinaryStream(1, new ByteArrayInputStream(new byte[5]), 5));
            assertEquals("54000", error.getSQLState());
        }
    }

    private static String read(java.io.Reader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[1024];
        int read;
        while ((read = reader.read(buffer)) >= 0) text.append(buffer, 0, read);
        return text.toString();
    }
}
