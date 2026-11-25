package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Driver, URL and DataSource contract details that do not require a live appliance. */
class DriverContractTest {
    @Test
    void acceptsOnlyNetezzaUrls() {
        NetezzaDriver driver = new NetezzaDriver();
        assertTrue(driver.acceptsURL("jdbc:netezza://host/DB"));
        assertTrue(driver.acceptsURL("jdbc:netezza:"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://host/DB"));
        assertFalse(driver.acceptsURL(null));
        assertNull(assertDoesNotThrow(() -> driver.connect("jdbc:postgresql://host/DB", new Properties())));
    }

    @Test
    void isRegisteredWithDriverManagerAndReportsJdbc43() throws Exception {
        NetezzaDriver driver = new NetezzaDriver();
        assertTrue(DriverManager.getDriver("jdbc:netezza://host/DB") instanceof NetezzaDriver);
        assertEquals(0, driver.getMajorVersion());
        assertEquals(1, driver.getMinorVersion());
        assertFalse(driver.jdbcCompliant());
        assertNotNull(driver.getParentLogger());
        assertNotNull(DriverManager.getDriver("jdbc:netezza://host/DB").getParentLogger());

        DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:netezza://host/DB", new Properties());
        assertTrue(info.length >= 3);
        assertEquals("user", info[0].name);
        assertEquals("password", info[1].name);
        assertEquals("sslmode", info[2].name);
    }

    @Test
    void dataSourceExposesNoticeListenerConfiguration() {
        NetezzaDataSource dataSource = new NetezzaDataSource();
        assertNull(dataSource.getNoticeListener());
        NetezzaNoticeListener listener = warning -> { };
        dataSource.setNoticeListener(listener);
        assertSame(listener, dataSource.getNoticeListener());
        dataSource.setNoticeListener(null);
        assertNull(dataSource.getNoticeListener());
    }

    @Test
    void dataSourceExposesConnectionProperties() {
        NetezzaDataSource dataSource = new NetezzaDataSource();
        dataSource.setUrl("jdbc:netezza://host:5480/DB");
        dataSource.setCurrentSchema("ANALYTICS");
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(false);
        dataSource.setBulkInsert(false);
        dataSource.setClientApplicationName("app");
        dataSource.setClientUser("reader");
        dataSource.setClientHostName("client-1");
        dataSource.setSocketBufferSize(65536);
        dataSource.setConnectTimeout(1000);
        dataSource.setSocketTimeout(2000);
        dataSource.setCharVarcharEncoding("utf8");
        dataSource.setClientType("7");
        Properties properties = new Properties();
        properties.setProperty("applicationName", "unit");
        dataSource.setProperties(properties);

        assertEquals("jdbc:netezza://host:5480/DB", dataSource.getUrl());
        assertEquals("ANALYTICS", dataSource.getCurrentSchema());
        assertEquals(Boolean.TRUE, dataSource.getReadOnly());
        assertEquals(Boolean.FALSE, dataSource.getAutoCommit());
        assertEquals(Boolean.FALSE, dataSource.getBulkInsert());
        assertEquals("app", dataSource.getClientApplicationName());
        assertEquals("reader", dataSource.getClientUser());
        assertEquals("client-1", dataSource.getClientHostName());
        assertEquals(65536, dataSource.getSocketBufferSize());
        assertEquals(1000, dataSource.getConnectTimeout());
        assertEquals(2000, dataSource.getSocketTimeout());
        assertEquals("utf8", dataSource.getCharVarcharEncoding());
        assertEquals("7", dataSource.getClientType());
        assertEquals("unit", dataSource.getProperties().getProperty("applicationName"));
    }

    @Test
    void driverPropertyInfoCoversConnectionAndTlsOptions() {
        DriverPropertyInfo[] info = new NetezzaDriver().getPropertyInfo("jdbc:netezza://host/DB", new Properties());
        java.util.Map<String, DriverPropertyInfo> byName = new java.util.LinkedHashMap<>();
        for (DriverPropertyInfo property : info) byName.put(property.name, property);
        assertTrue(byName.get("user").required);
        assertArrayEquals(new String[]{"disable", "allow", "prefer", "require", "verify-ca", "verify-full"},
                byName.get("sslmode").choices);
        for (String name : new String[]{"connectTimeout", "socketTimeout", "clientType", "char_varchar_encoding",
                "currentSchema", "applicationName", "clientApplicationName", "clientUser", "clientHostName",
                "socketBufferSize", "maxStreamBytes", "bulkInsert", "caCertificate", "trustStore", "trustStorePassword",
                "trustStoreType", "clientCertificate", "clientKey", "clientKeyPassword", "keyStore",
                "keyStorePassword", "keyStoreType"}) {
            assertNotNull(byName.get(name), name);
        }
    }

    @Test
    void requiresAUserBeforeOpeningASocket() {
        SQLException error = assertThrows(SQLException.class,
                () -> new NetezzaDriver().connect("jdbc:netezza://127.0.0.1:1/DB", new Properties()));
        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("user"));
    }

    @Test
    void acceptsEveryDocumentedSslModeCaseInsensitively() throws Exception {
        for (String mode : new String[]{"disable", "allow", "prefer", "require", "verify-ca", "verify-full",
                "DISABLE", "Require"}) {
            NetezzaDriver.Endpoint endpoint = NetezzaDriver.parseUrl(
                    "jdbc:netezza://host/DB?sslmode=" + mode, new Properties());
            assertEquals(mode.toLowerCase(java.util.Locale.ROOT), endpoint.options().getProperty("sslmode"));
        }
    }

    @Test
    void normalizesEncodingAliasesAndKeepsExplicitValues() throws Exception {
        assertEquals("latin1", NetezzaDriver.parseUrl("jdbc:netezza://host/DB?char_varchar_encoding=ISO-8859-1",
                new Properties()).options().getProperty("char_varchar_encoding"));
        assertEquals("latin1", NetezzaDriver.parseUrl("jdbc:netezza://host/DB?char_varchar_encoding=LATIN",
                new Properties()).options().getProperty("char_varchar_encoding"));
        assertEquals("utf8", NetezzaDriver.parseUrl("jdbc:netezza://host/DB?char_varchar_encoding=UTF-8",
                new Properties()).options().getProperty("char_varchar_encoding"));
    }

    @Test
    void parsesIpv6WithoutPortAndWithQueryOptions() throws Exception {
        NetezzaDriver.Endpoint endpoint = NetezzaDriver.parseUrl(
                "jdbc:netezza://[::1]/DB;clientType=7&socketTimeout=0", new Properties());
        assertEquals("::1", endpoint.host());
        assertEquals(5480, endpoint.port());
        assertEquals("DB", endpoint.database());
        assertEquals("7", endpoint.options().getProperty("clientType"));
        assertEquals("0", endpoint.options().getProperty("socketTimeout"));
    }

    @Test
    void decodesHostDatabaseAndOptionNames() throws Exception {
        NetezzaDriver.Endpoint endpoint = NetezzaDriver.parseUrl(
                "jdbc:netezza://nz%2Eexample/SALES%2FDB?user%20name=reader&password=a%26b", new Properties());
        assertEquals("nz.example", endpoint.host());
        assertEquals("SALES/DB", endpoint.database());
        Properties supplied = new Properties();
        supplied.setProperty("applicationName", "unit test");
        assertEquals("unit test", NetezzaDriver.parseUrl("jdbc:netezza://host/DB", supplied)
                .options().getProperty("applicationName"));
    }

    @Test
    void rejectsNullBlankAndMalformedValuesWith08001() {
        for (String url : new String[]{
                null,
                "",
                "jdbc:netezza:",
                "jdbc:netezza:/host/DB",
                "jdbc:netezza://",
                "jdbc:netezza:///DB",
                "jdbc:netezza://host/",
                "jdbc:netezza://host/DB?sslmode=",
                "jdbc:netezza://host/DB?sslmode=trust",
                "jdbc:netezza://host/DB?connectTimeout=-1",
                "jdbc:netezza://host/DB?socketTimeout=soon",
                "jdbc:netezza://host/DB?clientType=-1",
                "jdbc:netezza://host/DB?clientType=32768",
                "jdbc:netezza://host:65536/DB",
                "jdbc:netezza://host:-1/DB",
                "jdbc:netezza://host/DB?novalue",
                "jdbc:netezza://host/DB?=value",
                "jdbc:netezza://host/DB?bad=%ZZ",
                "jdbc:netezza://[::1/DB",
                "jdbc:netezza://[::1]x/DB"
        }) {
            SQLException error = assertThrows(SQLException.class,
                    () -> NetezzaDriver.parseUrl(url, new Properties()), String.valueOf(url));
            assertEquals("08001", error.getSQLState(), String.valueOf(url));
        }
    }

    @Test
    void dataSourceExposesPropertiesAndRequiresADatabase() throws Exception {
        NetezzaDataSource dataSource = new NetezzaDataSource();
        dataSource.setServerName("nz.example");
        dataSource.setPortNumber(5544);
        dataSource.setDatabaseName("ANALYTICS");
        dataSource.setUser("reader");
        dataSource.setPassword("secret");
        dataSource.setSslmode("require");
        dataSource.setLoginTimeout(7);
        assertEquals("nz.example", dataSource.getServerName());
        assertEquals(5544, dataSource.getPortNumber());
        assertEquals("ANALYTICS", dataSource.getDatabaseName());
        assertEquals("reader", dataSource.getUser());
        assertEquals("secret", dataSource.getPassword());
        assertEquals("require", dataSource.getSslmode());
        assertEquals(7, dataSource.getLoginTimeout());

        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        dataSource.setLogWriter(writer);
        assertSame(writer, dataSource.getLogWriter());
        assertNotNull(dataSource.getParentLogger());
        assertSame(dataSource, dataSource.unwrap(NetezzaDataSource.class));
        assertTrue(dataSource.isWrapperFor(NetezzaDataSource.class));
        assertFalse(dataSource.isWrapperFor(javax.sql.ConnectionPoolDataSource.class));
        assertThrows(SQLException.class, () -> dataSource.unwrap(javax.sql.ConnectionPoolDataSource.class));

        dataSource.setLoginTimeout(-5);
        assertEquals(0, dataSource.getLoginTimeout());

        NetezzaDataSource noDatabase = new NetezzaDataSource();
        noDatabase.setServerName("nz.example");
        SQLException error = assertThrows(SQLException.class, noDatabase::getConnection);
        assertEquals("08001", error.getSQLState());
    }

    @Test
    void dataSourceConnectionOverloadRejectsMissingDatabaseBeforeNetworking() {
        NetezzaDataSource dataSource = new NetezzaDataSource("127.0.0.1", 1, " ", "reader", "secret");
        SQLException error = assertThrows(SQLException.class, () -> dataSource.getConnection("reader", "secret"));
        assertEquals("08001", error.getSQLState());
    }

    @Test
    void exposesDriverIdentityWithoutOpeningAConnection() {
        NetezzaDriver driver = new NetezzaDriver();
        assertEquals("jdbc:netezza:", NetezzaDriver.PREFIX);
        assertTrue(driver.toString().contains("NetezzaDriver"));
    }
}
