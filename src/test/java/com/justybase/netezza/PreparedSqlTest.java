package com.justybase.netezza;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PreparedSqlTest {
    @Test void substitutesLiteralsWithoutTouchingQuotedQuestionMarks() throws Exception {
        assertEquals("SELECT 7, 'O''Brien', '?' -- ?\n", NetezzaStatement.substitute("SELECT ?, ?, '?' -- ?\n", Map.of(1, 7, 2, "O'Brien")));
    }

    @Test void reportsUnboundParameters() {
        SQLException e = assertThrows(SQLException.class, () -> NetezzaStatement.substitute("SELECT ?", Map.of()));
        assertEquals("07001", e.getSQLState());
    }

    @Test void rejectsExtraParameterValues() {
        SQLException e = assertThrows(SQLException.class, () -> NetezzaStatement.substitute("SELECT 1", Map.of(1, 5)));
        assertEquals("07001", e.getSQLState());
    }

    @Test void substitutesDeferredBatchSnapshotsWithoutAMap() throws Exception {
        // addBatch stores a value snapshot and renders only if the bulk plan falls back.
        assertEquals("SELECT 7, 'O''Brien', NULL",
                NetezzaStatement.substitute("SELECT ?, ?, ?", new Object[] {7, "O'Brien", null}));
        SQLException missing = assertThrows(SQLException.class,
                () -> NetezzaStatement.substitute("SELECT ?, ?", new Object[] {1}));
        assertEquals("07001", missing.getSQLState());
        SQLException extra = assertThrows(SQLException.class,
                () -> NetezzaStatement.substitute("SELECT ?", new Object[] {1, 2}));
        assertEquals("07001", extra.getSQLState());
    }

    @Test void addBatchSnapshotsMutableValues() throws Exception {
        byte[] bytes = {1, 2, 3};
        Object bytesSnapshot = NetezzaStatement.snapshotValue(bytes);
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) bytesSnapshot);

        Timestamp timestamp = Timestamp.valueOf("2024-02-29 12:34:56.123456789");
        Object timestampSnapshot = NetezzaStatement.snapshotValue(timestamp);
        timestamp.setNanos(1);
        assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456789"), timestampSnapshot);

        String text = "immutable";
        assertSame(text, NetezzaStatement.snapshotValue(text));
    }
}
