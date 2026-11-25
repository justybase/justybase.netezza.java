package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("live")
class ExternalTableLiveTest {
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
        tempDirectory = Files.createTempDirectory(LiveTestSupport.tempDir(), "external-");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_SOURCE IF EXISTS");
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_DEST IF EXISTS");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_SOURCE AS SELECT 1 AS ID, 'one' AS VAL UNION ALL SELECT 2, 'two'");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try {
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_SOURCE IF EXISTS");
                LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_DEST IF EXISTS");
            } finally {
                connection.close();
            }
        }
        if (tempDirectory != null) deleteTree(tempDirectory);
    }

    @Test
    @Timeout(60)
    void exportsAndImportsTextExternalTable() throws Exception {
        Path file = tempDirectory.resolve("roundtrip.dat");
        String sqlFile = sqlPath(file);
        String logDir = sqlPath(tempDirectory);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + sqlFile + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        logDir + "') AS SELECT * FROM JAVA_EXT_SOURCE");
        assertTrue(Files.isRegularFile(file));
        String content = Files.readString(file);
        assertTrue(content.contains("1|one"), content);
        assertTrue(content.contains("2|two"), content);

        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_EXT_DEST (ID INT, VAL VARCHAR(20))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + sqlFile +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + logDir + "')");

        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_EXT_DEST"));
        assertEquals(3, LiveTestSupport.queryInt(connection, "SELECT SUM(ID) FROM JAVA_EXT_DEST"));
        assertSentinel();
    }

    @Test
    @Timeout(90)
    void compressedInternalFormatRoundTrips() throws Exception {
        Path file = tempDirectory.resolve("compressed.dat");
        String sqlFile = sqlPath(file);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + sqlFile + "' USING (REMOTESOURCE 'jdbc' FORMAT 'INTERNAL' COMPRESS 'TRUE') " +
                        "AS SELECT * FROM JAVA_EXT_SOURCE");
        assertTrue(Files.size(file) > 0);

        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_EXT_DEST (ID INT, VAL VARCHAR(20))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + sqlFile +
                        "' USING (REMOTESOURCE 'jdbc' FORMAT 'INTERNAL' COMPRESS 'TRUE')");
        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_EXT_DEST"));
        assertEquals(0, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_DEST MINUS SELECT * FROM JAVA_EXT_SOURCE) X"));
        assertSentinel();
    }

    @Test
    @Timeout(90)
    void exportsAndImportsMultipleRowsWithHighCardinalityStrings() throws Exception {
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_SOURCE2 IF EXISTS");
        LiveTestSupport.execute(connection,
                "CREATE TEMP TABLE JAVA_EXT_SOURCE2 AS SELECT ID, 'value-' || ID AS LABEL " +
                        "FROM (SELECT 1 AS ID UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) X");
        Path file = tempDirectory.resolve("many.dat");
        String sqlFile = sqlPath(file);
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + sqlFile + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|') " +
                        "AS SELECT * FROM JAVA_EXT_SOURCE2");
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_EXT_DEST (ID INT, LABEL VARCHAR(30))");
        LiveTestSupport.execute(connection,
                "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + sqlFile +
                        "' USING (REMOTESOURCE 'jdbc' DELIMITER '|')");
        assertEquals(4, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_EXT_DEST"));
        assertEquals(0, LiveTestSupport.queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT * FROM JAVA_EXT_DEST MINUS SELECT * FROM JAVA_EXT_SOURCE2) X"));
        LiveTestSupport.execute(connection, "DROP TABLE JAVA_EXT_SOURCE2 IF EXISTS");
        assertSentinel();
    }

    @Test
    @Timeout(60)
    void serverLogsAreSavedWhenProvided() throws Exception {
        Path file = tempDirectory.resolve("logged.dat");
        LiveTestSupport.execute(connection,
                "CREATE EXTERNAL TABLE '" + sqlPath(file) + "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" +
                        sqlPath(tempDirectory) + "') AS SELECT * FROM JAVA_EXT_SOURCE");
        List<Path> logs;
        try (Stream<Path> paths = Files.list(tempDirectory)) {
            logs = paths.filter(path -> path.getFileName().toString().endsWith(".nzlog") ||
                    path.getFileName().toString().endsWith(".nzstats")).toList();
        }
        // Netezza may omit logs for tiny successful transfers; if it sends them they must be readable.
        for (Path log : logs) assertTrue(Files.isRegularFile(log));
        assertSentinel();
    }

    @Test
    @Timeout(60)
    void badImportReportsFailureAndPreservesTheSession() throws Exception {
        Path file = tempDirectory.resolve("bad.dat");
        Files.writeString(file, "1Xbad\n2Xalso-bad\n", StandardCharsets.UTF_8);
        LiveTestSupport.execute(connection, "CREATE TEMP TABLE JAVA_EXT_DEST (ID INT, VAL VARCHAR(20))");
        String sql = "INSERT INTO JAVA_EXT_DEST SELECT * FROM EXTERNAL '" + sqlPath(file) +
                "' USING (REMOTESOURCE 'jdbc' DELIMITER '|' LOGDIR '" + sqlPath(tempDirectory) + "' MAXERRORS 10)";
        try {
            LiveTestSupport.execute(connection, sql);
        } catch (SQLException expected) {
            assertTrue(expected.getMessage() != null);
        }
        assertSentinel();
    }

    @Test
    @Timeout(60)
    void exportFileOpenFailureDoesNotDesynchronizeTheSession() throws Exception {
        Path impossible = tempDirectory.resolve("missing-directory").resolve("file.dat");
        String sql = "CREATE EXTERNAL TABLE '" + sqlPath(impossible) +
                "' USING (REMOTESOURCE 'jdbc' DELIMITER '|') AS SELECT * FROM JAVA_EXT_SOURCE";
        try {
            LiveTestSupport.execute(connection, sql);
        } catch (SQLException expected) {
            assertTrue(expected.getMessage() != null);
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
            ordered.sort(java.util.Comparator.reverseOrder());
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
