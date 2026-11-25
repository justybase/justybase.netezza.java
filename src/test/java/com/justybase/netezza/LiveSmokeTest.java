package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
class LiveSmokeTest {
    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @Test
    void connectsAndExecutesAQueryWithStandardJdbcCalls() throws Exception {
        LiveTestSupport.loadDriver();
        try (Connection connection = LiveTestSupport.connect()) {
            assertTrue(connection.isValid(5));
            assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1::INTEGER"));
            DatabaseMetaData metadata = connection.getMetaData();
            assertNotNull(metadata.getDatabaseProductName());
            assertNotNull(metadata.getDriverName());
            assertFalse(connection.isClosed());
        }
    }
}
