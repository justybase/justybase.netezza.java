package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
class DriverAliasLiveTest {
    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @Test
    @Timeout(30)
    void connectsThroughHistoricalDriverClass() throws Exception {
        LiveTestSupport.loadDriver();
        Driver alias = (Driver) Class.forName("org.netezza.Driver").getDeclaredConstructor().newInstance();
        assertNotNull(alias.connect(LiveTestSupport.url(), LiveTestSupport.connectionProperties()));
        try (Connection connection = alias.connect(LiveTestSupport.url(), LiveTestSupport.connectionProperties())) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertTrue(connection.getMetaData().getDriverName().contains("JustyBase"));
        }
    }
}
