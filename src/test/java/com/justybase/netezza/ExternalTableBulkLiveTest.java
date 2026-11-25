package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Large, compressed, unicode and null-preserving external-table transfers. */
@Tag("live")
class ExternalTableBulkLiveTest {
    private Connection connection;
    private Path tempDirectory;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        tempDirectory = Files.createTempDirectory(LiveTestSupport.tempDir(), "external-bulk-");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_BULK IF EXISTS");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_BULK_DEST IF EXISTS");
        // 20,000 deterministic rows from the system vector index (0..1023 per dimension).
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_BULK AS " +
                        "SELECT CAST(A.IDX * 1000 + B.IDX + 1 AS INTEGER) AS ID, " +
                        "'row-' || (A.IDX * 1000 + B.IDX + 1) AS LABEL " +
                        "FROM _v_vector_idx A, _v_vector_idx B " +
                        "WHERE A.IDX < 20 AND B.IDX < 1000");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try {
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_BULK IF EXISTS");
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_BULK_DEST IF EXISTS");
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_UNICODE IF EXISTS");
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_NULLS IF EXISTS");
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_DEST IF EXISTS");
            } finally {
                connection.close();
            }
        }
        if (tempDirectory != null) deleteTree(tempDirectory);
    }

    @Test
    @Timeout(180)
    void twentyThousandRowsRoundTripThroughTextExternalTable() throws Exception {
        Path file = tempDirectory.resolve("bulk.dat");
        String path = sqlPath(file);
        String log = sqlPath(tempDirectory);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        log + "') AS SELECT * FROM JAVA_EXT_BULK");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.size(file) > 100_000, "bulk export should span multiple chunks");

        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_BULK_DEST (ID INTEGER, LABEL VARCHAR(80))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_BULK_DEST SELECT * FROM EXTERNAL '" + path +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + log + "')");
        assertEquals(20_000, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM JAVA_EXT_BULK_DEST"));
        assertEquals(0, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_BULK_DEST MINUS SELECT * FROM JAVA_EXT_BULK) X"));
        assertSentinel();
    }

    @Test
    @Timeout(180)
    void twentyThousandRowsRoundTripThroughCompressedInternalFormat() throws Exception {
        Path file = tempDirectory.resolve("bulk-internal.dat");
        String path = sqlPath(file);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' FORMAT 'INTERNAL' COMPRESS 'TRUE') " +
                        "AS SELECT * FROM JAVA_EXT_BULK");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.size(file) > 0);

        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_BULK_DEST (ID INTEGER, LABEL VARCHAR(80))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_BULK_DEST SELECT * FROM EXTERNAL '" + path +
                        "' USING (REMOTESOURCE 'jdbc' FORMAT 'INTERNAL' COMPRESS 'TRUE')");
        assertEquals(20_000, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM JAVA_EXT_BULK_DEST"));
        assertEquals(0, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_BULK_DEST MINUS SELECT * FROM JAVA_EXT_BULK) X"));
        assertSentinel();
    }

    @Test
    @Timeout(120)
    void unicodeNvarcharRoundTripsThroughTextExternalTable() throws Exception {
        String polish = "\u017b\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144";
        String chinese = "\u4f60\u597d\u4e16\u754c";
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_UNICODE AS " +
                        "SELECT 1 AS ID, '" + polish + "'::NVARCHAR(60) AS VAL " +
                        "UNION ALL SELECT 2, '" + chinese + "'::NVARCHAR(60)");
        Path file = tempDirectory.resolve("unicode.dat");
        String path = sqlPath(file);
        String log = sqlPath(tempDirectory);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        log + "') AS SELECT * FROM JAVA_EXT_UNICODE");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_DEST (ID INTEGER, VAL NVARCHAR(60))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + path +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + log + "')");
        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_EXT_DEST"));
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT ID, VAL FROM JAVA_EXT_DEST ORDER BY ID")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertEquals(polish, result.getNString(2).trim());
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            assertEquals(chinese, result.getNString(2).trim());
            assertFalse(result.next());
        }
        assertSentinel();
    }

    @Test
    @Timeout(120)
    void nullsRoundTripThroughTextExternalTable() throws Exception {
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_NULLS AS " +
                        "SELECT 1 AS ID, CAST(NULL AS VARCHAR(10)) AS VAL " +
                        "UNION ALL SELECT 2, 'present'");
        Path file = tempDirectory.resolve("nulls.dat");
        String path = sqlPath(file);
        String log = sqlPath(tempDirectory);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        log + "') AS SELECT * FROM JAVA_EXT_NULLS");
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_EXT_DEST (ID INTEGER, VAL VARCHAR(20))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + path +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + log + "')");
        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_EXT_DEST"));
        assertEquals(1, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM JAVA_EXT_DEST WHERE VAL IS NULL"));
        assertEquals("present", LiveTestSupport.queryString(connection,
                "SELECT VAL FROM JAVA_EXT_DEST WHERE ID=2"));
        assertSentinel();
    }

    @Test
    @Timeout(120)
    void pathsWithSpacesRoundTripCorrectly() throws Exception {
        Path spacedDirectory = Files.createDirectories(tempDirectory.resolve("with space"));
        Path file = spacedDirectory.resolve("bulk file.dat");
        String path = sqlPath(file);
        String log = sqlPath(spacedDirectory);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        log + "') AS SELECT ID, LABEL FROM JAVA_EXT_BULK WHERE ID <= 1000");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_BULK_DEST (ID INTEGER, LABEL VARCHAR(80))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_BULK_DEST SELECT * FROM EXTERNAL '" + path +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + log + "')");
        assertEquals(1_000, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM JAVA_EXT_BULK_DEST"));
        assertEquals(0, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_BULK_DEST MINUS " +
                        "(SELECT ID, LABEL FROM JAVA_EXT_BULK WHERE ID <= 1000)) X"));
        assertSentinel();
    }

    @Test
    @Timeout(90)
    void alternateDelimitersRoundTrip() throws Exception {
        for (String delimiter : new String[]{",", ";"}) {
            String suffix = delimiter.equals(",") ? "comma" : "semicolon";
            Path file = tempDirectory.resolve("delimiter-" + suffix + ".dat");
            String path = sqlPath(file);
            String log = sqlPath(tempDirectory);
            LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_BULK_DEST IF EXISTS");
            LiveTestSupport.execute(connection,
                    "CREATE EXTERNAL TABLE '" + path + "' USING (REMOTESOURCE 'jdbc' DELIMITER '" +
                            delimiter + "' LOGDIR '" + log + "') AS " +
                            "SELECT ID, LABEL FROM JAVA_EXT_BULK WHERE ID <= 5");
            assertTrue(Files.isRegularFile(file));
            LiveTestSupport.execute(connection,
                    "CREATE TEMP TABLE JAVA_EXT_BULK_DEST (ID INTEGER, LABEL VARCHAR(80))");
            LiveTestSupport.execute(connection,
                    "INSERT INTO JAVA_EXT_BULK_DEST SELECT * FROM EXTERNAL '" + path +
                            "' USING (REMOTESOURCE 'jdbc' DELIMITER '" + delimiter + "' LOGDIR '" + log + "')");
            assertEquals(5, LiveTestSupport.queryInt(connection,
                    "SELECT COUNT(*) FROM JAVA_EXT_BULK_DEST"));
            assertEquals(0, LiveTestSupport.queryInt(connection,
                    "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_BULK_DEST MINUS " +
                            "(SELECT ID, LABEL FROM JAVA_EXT_BULK WHERE ID <= 5)) X"));
        }
        assertSentinel();
    }

    private void assertSentinel() throws Exception {
        assertEquals(1, LiveTestSupport.queryInt(connection, "SELECT 1"));
        assertTrue(connection.isValid(5));
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> ordered = new ArrayList<>(paths.toList());
            ordered.sort(Comparator.reverseOrder());
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}
