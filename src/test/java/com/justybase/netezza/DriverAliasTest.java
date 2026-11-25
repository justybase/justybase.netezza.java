package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverAliasTest {
    @Test
    void aliasAcceptsNetezzaUrlsAndRegistersWithDriverManager() throws Exception {
        Driver driver = (Driver) Class.forName("org.netezza.Driver").getDeclaredConstructor().newInstance();
        assertTrue(driver.acceptsURL("jdbc:netezza://localhost:5480/JUST_DATA"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/JUST_DATA"));
        assertEquals(0, driver.getMajorVersion());
        assertEquals(1, driver.getMinorVersion());
        assertFalse(driver.jdbcCompliant());
        assertNotNull(driver.getParentLogger());
        assertNotNull(DriverManager.getDriver("jdbc:netezza://localhost:5480/JUST_DATA"));
    }
}
