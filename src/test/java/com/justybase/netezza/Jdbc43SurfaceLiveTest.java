package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** JDBC 4.3 metadata, standard overloads, DataSource and capability limits. */
@Tag("live")
class Jdbc43SurfaceLiveTest {
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
    void parameterMetadataAndPreparedResultMetadata() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ?::INTEGER AS N, 'x'::VARCHAR(5) AS V")) {
            statement.setInt(1, 42);
            ParameterMetaData parameters = statement.getParameterMetaData();
            assertEquals(1, parameters.getParameterCount());
            assertEquals(Types.INTEGER, parameters.getParameterType(1));
            assertEquals("INTEGER", parameters.getParameterTypeName(1));
            assertEquals(Integer.class.getName(), parameters.getParameterClassName(1));
            assertEquals(ParameterMetaData.parameterModeIn, parameters.getParameterMode(1));
            assertEquals(ParameterMetaData.parameterNullableUnknown, parameters.isNullable(1));
            assertTrue(parameters.isSigned(1));
            SQLException outOfRange = assertThrows(SQLException.class, () -> parameters.getParameterType(2));
            assertEquals("07009", outOfRange.getSQLState());
            assertTrue(parameters.toString().contains("NetezzaParameterMetaData"));

            ResultSetMetaData metadata = statement.getMetaData();
            assertNotNull(metadata);
            assertTrue(metadata.toString().contains("NetezzaResultSetMetaData"));
            assertEquals(2, metadata.getColumnCount());
            assertEquals("N", metadata.getColumnLabel(1));
            assertEquals(Types.INTEGER, metadata.getColumnType(1));
            assertEquals("V", metadata.getColumnLabel(2));
            assertEquals(Types.VARCHAR, metadata.getColumnType(2));

            statement.clearParameters();
            assertEquals(Types.OTHER, statement.getParameterMetaData().getParameterType(1));
            statement.setObject(1, "7", JDBCType.INTEGER);
            assertEquals(Types.INTEGER, statement.getParameterMetaData().getParameterType(1));
        }
    }

    @Test
    @Timeout(60)
    void standardOverloadsHonorCalendarScaleMapAndSqlType() throws Exception {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1.234::NUMERIC(10,3) AS N, DATE '2024-01-01' AS D, " +
                             "TIME '12:34:56' AS T, TIMESTAMP '2024-01-01 12:34:56.123456' AS TS")) {
            assertTrue(result.next());
            assertEquals(new BigDecimal("1.2"), result.getBigDecimal("N", 1));
            assertEquals(LocalDate.of(2024, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    result.getDate("D", utc).getTime());
            assertEquals(LocalDateTime.of(2024, 1, 1, 12, 34, 56, 123_456_000)
                            .toInstant(ZoneOffset.UTC),
                    result.getTimestamp("TS", utc).toInstant());
            assertEquals(new BigDecimal("1.234"), result.getObject("N", Map.of()));
            assertEquals(new BigDecimal("1.234"), result.getObject("N", BigDecimal.class));
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ?::DATE AS D, ?::INTEGER AS I, ?::NUMERIC(10,2) AS N, ?::VARCHAR(5) AS V")) {
            statement.setObject(1, "2024-02-29", Types.DATE);
            statement.setObject(2, 7, JDBCType.INTEGER);
            statement.setObject(3, new BigDecimal("1.235"), Types.NUMERIC, 2);
            statement.setNull(4, Types.VARCHAR);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(Date.valueOf("2024-02-29"), result.getDate(1));
                assertEquals(7, result.getInt(2));
                assertEquals(new BigDecimal("1.24"), result.getBigDecimal(3));
                assertNull(result.getString(4));
            }
        }
    }

    @Test
    @Timeout(60)
    void dataSourceAppliesConnectionProperties() throws Exception {
        NetezzaDataSource dataSource = new NetezzaDataSource();
        dataSource.setUrl(LiveTestSupport.url());
        dataSource.setUser(LiveTestSupport.required("NZ_DEV_USER"));
        dataSource.setPassword(System.getenv("NZ_DEV_PASSWORD"));
        dataSource.setCurrentSchema(LiveTestSupport.schema());
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(false);
        dataSource.setClientApplicationName("jdbc43-live");
        dataSource.setClientUser("jdbc43-user");
        dataSource.setClientHostName("jdbc43-host");
        dataSource.setSocketBufferSize(65536);
        dataSource.setSocketTimeout(120000);
        try (Connection dataSourceConnection = dataSource.getConnection()) {
            assertEquals(LiveTestSupport.schema(), dataSourceConnection.getSchema());
            assertTrue(dataSourceConnection.isReadOnly());
            assertFalse(dataSourceConnection.getAutoCommit());
            assertEquals("jdbc43-live", dataSourceConnection.getClientInfo("ApplicationName"));
            assertEquals("jdbc43-user", dataSourceConnection.getClientInfo("ClientUser"));
            assertEquals("jdbc43-host", dataSourceConnection.getClientInfo("ClientHostname"));
            try (Statement statement = dataSourceConnection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1::INTEGER")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            dataSourceConnection.setAutoCommit(true);
        }
        assertEquals(LiveTestSupport.url(), dataSource.getUrl());
    }

    @Test
    @Timeout(60)
    void metadataLimitsAndCapabilitiesAreConsistent() throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        assertEquals(128, metadata.getMaxTableNameLength());
        assertEquals(128, metadata.getMaxColumnNameLength());
        assertEquals(128, metadata.getMaxSchemaNameLength());
        assertEquals(1600, metadata.getMaxColumnsInTable());
        assertEquals(0, metadata.getMaxConnections());
        assertEquals(0, metadata.getMaxStatementLength());
        assertTrue(metadata.supportsStoredProcedures());
        assertTrue(metadata.supportsBatchUpdates());
        assertTrue(metadata.supportsTransactions());
        assertFalse(metadata.supportsSavepoints());
        assertFalse(metadata.supportsGetGeneratedKeys());
    }

    @Test
    @Timeout(60)
    void clientInfoRoundTripsAndCanBeCleared() throws Exception {
        connection.setClientInfo("ApplicationName", "unit-test");
        assertEquals("unit-test", connection.getClientInfo("ApplicationName"));
        Properties all = connection.getClientInfo();
        assertEquals("unit-test", all.getProperty("ApplicationName"));

        Properties replacement = new Properties();
        replacement.setProperty("ClientUser", "tester");
        connection.setClientInfo(replacement);
        assertEquals("tester", connection.getClientInfo("ClientUser"));
        assertNull(connection.getClientInfo("ApplicationName"));
    }

    @Test
    @Timeout(60)
    void nonSelectPreparedMetadataIsUnknownRatherThanGuessed() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE CONFORMANCE.JAVA_METADATA_MISSING SET VALUE = 1")) {
            assertNull(statement.getMetaData());
        }
    }
}
