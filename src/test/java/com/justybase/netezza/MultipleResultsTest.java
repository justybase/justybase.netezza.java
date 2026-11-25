package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MultipleResultsTest {
    @Test
    void advancesOneStreamingResultAtATime() throws Exception {
        QueryResult q = new QueryResult(new Protocol(new ByteArrayInputStream(twoSelects())));

        assertTrue(q.hasRows);
        assertEquals(1, q.columns.size());
        java.sql.ResultSet firstResult = q.resultSet();
        assertTrue(q.next());
        assertEquals(1, q.value(0));
        assertFalse(q.next()); // stops at the first CommandComplete
        assertFalse(q.done);   // ReadyForQuery and later results remain unread

        assertTrue(q.nextResult());
        assertTrue(firstResult.isClosed());
        assertThrows(java.sql.SQLException.class, firstResult::next);
        assertEquals(1, q.columns.size());
        assertTrue(q.next());
        assertEquals(2, q.value(0));
        assertFalse(q.next());
        assertFalse(q.nextResult());
        assertTrue(q.done);
    }

    @Test
    void representsAnUpdateCountBeforeTheFollowingRowSet() throws Exception {
        byte[] wire = concat(command("UPDATE 1"), select(7), ready());
        QueryResult q = new QueryResult(new Protocol(new ByteArrayInputStream(wire)));
        assertFalse(q.hasRows);
        assertEquals(1, q.initialUpdateCount);
        assertTrue(q.nextResult());
        assertTrue(q.next());
        assertEquals(7, q.value(0));
        assertFalse(q.next());
        assertFalse(q.nextResult());
        assertTrue(q.done);
    }

    @Test
    void finishDrainsAllUnconsumedResultsForSessionReuse() throws Exception {
        QueryResult q = new QueryResult(new Protocol(new ByteArrayInputStream(twoSelects())));
        q.finish();
        assertTrue(q.done);
    }

    private static byte[] twoSelects() throws IOException {
        return concat(select(1), command("SELECT 1"), select(2), command("SELECT 1"), ready());
    }

    private static byte[] select(int value) throws IOException {
        ByteArrayOutputStream rowDescription = new ByteArrayOutputStream();
        DataOutputStream rd = new DataOutputStream(rowDescription);
        rd.writeShort(1);
        rd.write("n\0".getBytes(StandardCharsets.UTF_8));
        rd.writeInt(23); // int4
        rd.writeShort(4);
        rd.writeInt(-1);
        rd.writeByte(0);

        ByteArrayOutputStream row = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(row);
        data.writeByte(0x80); // one non-NULL column
        byte[] text = Integer.toString(value).getBytes(StandardCharsets.US_ASCII);
        data.writeInt(text.length + 4);
        data.write(text);
        return concat(message('T', rowDescription.toByteArray()), message('D', row.toByteArray()));
    }

    private static byte[] command(String tag) throws IOException {
        return message('C', (tag + "\0").getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] message(char type, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type);
        out.writeInt(0); // shared backend header
        out.writeInt(payload.length);
        out.write(payload);
        return bytes.toByteArray();
    }

    private static byte[] ready() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte('Z');
        out.writeInt(0);
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
}
