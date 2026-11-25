package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DriverParsingTest {
    @Test
    void parsesDefaultsAndUrlOptions() throws Exception {
        NetezzaDriver.Endpoint e = NetezzaDriver.parseUrl(
                "jdbc:netezza://nz.example/ANALYTICS?sslmode=verify-full&connectTimeout=1200",
                new Properties());
        assertEquals("nz.example", e.host());
        assertEquals(5480, e.port());
        assertEquals("ANALYTICS", e.database());
        assertEquals("verify-full", e.options().getProperty("sslmode"));
        assertEquals("latin1", e.options().getProperty("char_varchar_encoding"));
        assertEquals("1200", e.options().getProperty("connectTimeout"));
        assertEquals("30000", e.options().getProperty("socketTimeout"));
        assertEquals("3", e.options().getProperty("clientType"));
        assertEquals("true", e.options().getProperty("bulkInsert"));
    }

    @Test
    void parsesIpv6AndDecodesEscapedParts() throws Exception {
        NetezzaDriver.Endpoint e = NetezzaDriver.parseUrl(
                "jdbc:netezza://[2001:db8::1]:5544/SALES%20DB;sslmode=require",
                new Properties());
        assertEquals("2001:db8::1", e.host());
        assertEquals(5544, e.port());
        assertEquals("SALES DB", e.database());
        assertEquals("require", e.options().getProperty("sslmode"));
    }

    @Test
    void explicitPropertiesOverrideUrlOptionsWithoutChangingThem() throws Exception {
        Properties supplied = new Properties();
        supplied.setProperty("sslmode", "disable");
        supplied.setProperty("user", "reader");
        NetezzaDriver.Endpoint e = NetezzaDriver.parseUrl(
                "jdbc:netezza://host:5481/DB?sslmode=require", supplied);
        assertEquals("disable", e.options().getProperty("sslmode"));
        assertEquals("require", NetezzaDriver.parseUrl(
                "jdbc:netezza://host:5481/DB?sslmode=require", new Properties())
                .options().getProperty("sslmode"));
        assertEquals("reader", supplied.getProperty("user"));
    }

    @Test
    void rejectsMalformedUrlsAndOptionsWithJdbcState() {
        for (String url : new String[] {
                "jdbc:netezza://host", "jdbc:netezza:///DB", "jdbc:netezza://host:0/DB",
                "jdbc:netezza://host:abc/DB", "jdbc:netezza://host/DB?sslmode=maybe",
                "jdbc:netezza://host/DB?connectTimeout=fast", "jdbc:netezza://host/DB?char_varchar_encoding=cp1252",
                "jdbc:netezza://host/DB?clientType=abc", "jdbc:netezza://host/DB?clientType=40000",
                "jdbc:netezza://host/DB?bulkInsert=maybe"
        }) {
            SQLException e = assertThrows(SQLException.class,
                    () -> NetezzaDriver.parseUrl(url, new Properties()), url);
            assertEquals("08001", e.getSQLState(), url);
        }
    }
}
