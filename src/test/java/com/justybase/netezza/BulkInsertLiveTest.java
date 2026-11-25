package com.justybase.netezza;

import com.justybase.netezza.testutil.LiveTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live coverage for the external-table bulk path behind PreparedStatement batches. */
@Tag("live")
class BulkInsertLiveTest {
    private Connection connection;

    @BeforeAll
    static void requireLiveConfiguration() {
        assumeTrue(LiveTestSupport.configured(), "NZ_DEV_* live configuration is not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        LiveTestSupport.loadDriver();
        connection = LiveTestSupport.connect();
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE JAVA_BULK_INSERT IF EXISTS");
            statement.execute("CREATE TEMP TABLE JAVA_BULK_INSERT (" +
                    "ID INT, NAME NVARCHAR(200), AMOUNT NUMERIC(12,2), DT DATE, TS TIMESTAMP, FLAG BOOLEAN)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void preparedBatchUsesOneExternalTransferAndPreservesValues() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_BULK_INSERT VALUES (?, ?, ?, ?, ?, ?)")) {
            add(insert, 1, "one", new BigDecimal("12.50"),
                    LocalDate.of(2024, 2, 29), LocalDateTime.of(2024, 2, 29, 23, 59, 58, 123_456_000), true);
            add(insert, 2, "two\tlines\nand\\slash", null, null, null, false);
            add(insert, 3, "zażółć 😀", new BigDecimal("-0.01"),
                    LocalDate.of(1999, 12, 31), LocalDateTime.of(2000, 1, 1, 0, 0, 0), null);
            int[] counts = insert.executeBatch();
            assertEquals(3, counts.length);
            for (int count : counts) assertEquals(Statement.SUCCESS_NO_INFO, count);
        }

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT ID, NAME, AMOUNT, DT, TS, FLAG FROM JAVA_BULK_INSERT ORDER BY ID")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("one", rows.getString(2));
            assertEquals(new BigDecimal("12.50"), rows.getBigDecimal(3));
            assertEquals(LocalDate.of(2024, 2, 29), rows.getObject(4, LocalDate.class));
            assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 58, 123_456_000),
                    rows.getObject(5, LocalDateTime.class));
            assertTrue(rows.getBoolean(6));

            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertEquals("two\tlines\nand\\slash", rows.getString(2));
            assertNull(rows.getBigDecimal(3));
            assertNull(rows.getObject(4));
            assertNull(rows.getObject(5));
            assertFalse(rows.getBoolean(6));
            assertFalse(rows.wasNull());

            assertTrue(rows.next());
            assertEquals(3, rows.getInt(1));
            assertEquals("zażółć 😀", rows.getString(2));
            assertEquals(new BigDecimal("-0.01"), rows.getBigDecimal(3));
            assertNull(rows.getObject(6));
            assertTrue(rows.wasNull());
            assertFalse(rows.next());
        }
    }

    @Test
    void largeBatchIsStreamedInBlocks() throws Exception {
        int rows = 500;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_BULK_INSERT (ID, NAME, AMOUNT) VALUES (?, ?, ?)")) {
            for (int i = 0; i < rows; i++) {
                insert.setInt(1, i);
                insert.setString(2, "row-" + i);
                insert.setBigDecimal(3, BigDecimal.valueOf(i, 2));
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(rows, counts.length);
            for (int count : counts) assertEquals(Statement.SUCCESS_NO_INFO, count);
        }
        assertEquals(rows, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_BULK_INSERT"));
    }

    @Test
    void insertSelectFallsBackToRowByRowExecution() throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO JAVA_BULK_INSERT (ID, NAME) SELECT ?, ?")) {
            insert.setInt(1, 1);
            insert.setString(2, "a");
            insert.addBatch();
            insert.setInt(1, 2);
            insert.setString(2, "b");
            insert.addBatch();
            int[] counts = insert.executeBatch();
            assertEquals(2, counts.length);
            assertEquals(1, counts[0]);
            assertEquals(1, counts[1]);
        }
        assertEquals(2, LiveTestSupport.queryInt(connection, "SELECT COUNT(*) FROM JAVA_BULK_INSERT"));
    }

    @Test
    void binaryValuesFallBackWithoutLosingData() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE JAVA_BULK_BIN IF EXISTS");
            statement.execute("CREATE TEMP TABLE JAVA_BULK_BIN (ID INT, DATA VARBINARY(10))");
        }
        byte[] first = {0, 1, 2, 3, (byte) 255};
        byte[] second = {9, 8, 7};
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO JAVA_BULK_BIN VALUES (?, ?)")) {
            insert.setInt(1, 1);
            insert.setBytes(2, first);
            insert.addBatch();
            insert.setInt(1, 2);
            insert.setBytes(2, second);
            insert.addBatch();
            int[] counts = insert.executeBatch();
            assertEquals(2, counts.length);
            assertEquals(1, counts[0]);
            assertEquals(1, counts[1]);
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DATA FROM JAVA_BULK_BIN ORDER BY ID")) {
            assertTrue(rows.next());
            assertArrayEquals(first, rows.getBytes(1));
            assertTrue(rows.next());
            assertArrayEquals(second, rows.getBytes(1));
        }
    }

    @Test
    void bulkInsertCanBeDisabled() throws Exception {
        Properties properties = LiveTestSupport.connectionProperties();
        properties.setProperty("bulkInsert", "false");
        try (Connection plain = DriverManager.getConnection(LiveTestSupport.url(), properties);
             Statement statement = plain.createStatement()) {
            statement.execute("CREATE TEMP TABLE JAVA_BULK_OFF (ID INT)");
            try (PreparedStatement insert = plain.prepareStatement("INSERT INTO JAVA_BULK_OFF VALUES (?)")) {
                insert.setInt(1, 1);
                insert.addBatch();
                insert.setInt(1, 2);
                insert.addBatch();
                int[] counts = insert.executeBatch();
                assertEquals(2, counts.length);
                assertEquals(1, counts[0]);
                assertEquals(1, counts[1]);
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM JAVA_BULK_OFF")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    private static void add(PreparedStatement insert, int id, String name, BigDecimal amount,
                            LocalDate date, LocalDateTime timestamp, Boolean flag) throws Exception {
        insert.setInt(1, id);
        insert.setString(2, name);
        if (amount == null) insert.setNull(3, java.sql.Types.NUMERIC);
        else insert.setBigDecimal(3, amount);
        if (date == null) insert.setNull(4, java.sql.Types.DATE);
        else insert.setObject(4, date);
        if (timestamp == null) insert.setNull(5, java.sql.Types.TIMESTAMP);
        else insert.setObject(5, timestamp);
        if (flag == null) insert.setNull(6, java.sql.Types.BOOLEAN);
        else insert.setBoolean(6, flag);
        insert.addBatch();
    }
}
