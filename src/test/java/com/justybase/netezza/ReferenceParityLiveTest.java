package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in value comparison against an external JDBC driver when the local parity jar is present.
 *
 * <p>The jar is local and intentionally not committed, so this test is skipped when it is absent.
 * Only values are asserted: external drivers can report different type metadata for several
 * Netezza types.</p>
 */
@Tag("parity")
class ReferenceParityLiveTest {
    private static final Path EXTERNAL_DRIVER_JAR = Path.of("orginal_reference", "nzjdbc-11.1.0.0.jar");
    private static final List<String> QUERIES = List.of(
            "SELECT 1::BYTEINT AS V",
            "SELECT 2::SMALLINT AS V",
            "SELECT 3::INTEGER AS V",
            "SELECT 4::BIGINT AS V",
            "SELECT 5.5::REAL AS V",
            "SELECT 6.5::DOUBLE PRECISION AS V",
            "SELECT 1234567890.1234567890::NUMERIC(38,10) AS V",
            "SELECT 9999999999999999999999999999.9999999999::NUMERIC(38,10) AS V",
            "SELECT -9999999999999999999999999999.9999999999::NUMERIC(38,10) AS V",
            "SELECT 'abc'::VARCHAR(8) AS V",
            "SELECT ''::VARCHAR(8) AS V",
            "SELECT 'abc'::NCHAR(8) AS V",
            "SELECT '\u017b\u00f3\u0142\u0107 \uD83D\uDE00'::NVARCHAR(100) AS V",
            "SELECT DATE '2024-02-29' AS V",
            "SELECT TIME '03:04:05' AS V",
            "SELECT TIMETZ '03:04:05.123456+05:30' AS V",
            "SELECT TIMETZ '23:59:59.999999-12' AS V",
            "SELECT TIMESTAMP '2024-02-29 12:34:56.123456' AS V",
            "SELECT TIMESTAMP '0001-01-01 00:00:00' AS V",
            "SELECT TIMESTAMP '9999-12-31 23:59:59.999999' AS V",
            "SELECT true::BOOLEAN AS V",
            "SELECT false::BOOLEAN AS V",
            "SELECT x'0102FF'::VARBINARY(8) AS V",
            "SELECT x''::VARBINARY(8) AS V",
            "SELECT INTERVAL '2 days 03:04:05' AS V",
            "SELECT INTERVAL '-2 days -03:04:05' AS V",
            "SELECT INTERVAL '1 year 2 months 3 days 04:05:06' AS V",
            "SELECT NULL::INTEGER AS V",
            "SELECT NULL::VARCHAR(8) AS V"
    );

    @BeforeAll
    static void requireLiveConfigurationAndExternalDriverJar() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
        assumeTrue(Files.isRegularFile(EXTERNAL_DRIVER_JAR), "external driver jar is not present: " + EXTERNAL_DRIVER_JAR);
    }

    @Test
    @Timeout(120)
    void resultValuesMatchExternalDriver() throws Exception {
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {EXTERNAL_DRIVER_JAR.toUri().toURL()}, parent)) {
            Driver externalDriver = (Driver) Class.forName("org.netezza.Driver", true, loader)
                    .getDeclaredConstructor().newInstance();
            Properties properties = LiveTestSupport.connectionProperties();
            try (Connection ours = LiveTestSupport.connect();
                 Connection external = externalDriver.connect(LiveTestSupport.url(), properties)) {
                assertNotNull(external, "external driver rejected the JDBC URL");
                List<String> differences = new ArrayList<>();
                for (String sql : QUERIES) {
                    List<String> oursValues = read(ours, sql);
                    List<String> externalValues = read(external, sql);
                    if (!oursValues.equals(externalValues)) {
                        differences.add(sql + System.lineSeparator()
                                + "  ours     = " + oursValues + System.lineSeparator()
                                + "  external = " + externalValues);
                    }
                }
                assertTrue(differences.isEmpty(),
                        "Value differences against the external driver:" + System.lineSeparator()
                                + String.join(System.lineSeparator(), differences));
            }
        }
    }

    private static List<String> read(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) values.add(normalize(result.getObject(1)));
        }
        return values;
    }

    private static String normalize(Object value) {
        if (value == null) return "<null>";
        if (value instanceof byte[] bytes) return "0x" + HexFormat.of().formatHex(bytes);
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof OffsetTime time) return canonicalOffsetTime(time);
        return value.toString();
    }

    /** Matches the server's canonical TIMETZ text: whole-hour offsets omit the minutes. */
    private static String canonicalOffsetTime(OffsetTime time) {
        String text = time.toLocalTime().toString();
        if (text.length() == 5) text += ":00";
        int total = time.getOffset().getTotalSeconds();
        int absolute = Math.abs(total);
        StringBuilder out = new StringBuilder(text).append(total < 0 ? '-' : '+')
                .append(String.format(java.util.Locale.ROOT, "%02d", absolute / 3600));
        int minutes = (absolute % 3600) / 60;
        int seconds = absolute % 60;
        if (minutes != 0 || seconds != 0)
            out.append(':').append(String.format(java.util.Locale.ROOT, "%02d", minutes));
        if (seconds != 0)
            out.append(':').append(String.format(java.util.Locale.ROOT, "%02d", seconds));
        return out.toString();
    }
}
