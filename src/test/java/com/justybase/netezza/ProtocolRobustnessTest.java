package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** Malformed and boundary wire input must fail deterministically without corrupting later reads. */
class ProtocolRobustnessTest {
    @Test
    void fastInputHandlesPrimitivesAcrossTinyBufferBoundaries() throws Exception {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Protocol.FastInput input = new Protocol.FastInput(new ByteArrayInputStream(data), 3);
        assertEquals(0x0001, input.readShort());
        assertEquals(0x02030405, input.readInt());
        byte[] target = new byte[12];
        input.readExactInto(target, 2, 10);
        for (int i = 0; i < 10; i++) assertEquals((byte) (6 + i), target[2 + i]);
        assertEquals((byte) 16, (byte) input.readU8());
    }

    @Test
    void fastInputReportsShortStreamsInsteadOfReturningPartialData() {
        assertThrows(EOFException.class,
                () -> new Protocol.FastInput(new ByteArrayInputStream(new byte[0])).readU8());
        assertThrows(EOFException.class,
                () -> new Protocol.FastInput(new ByteArrayInputStream(new byte[]{1, 2}), 8).readInt());
        assertThrows(EOFException.class,
                () -> new Protocol.FastInput(new ByteArrayInputStream(new byte[]{1}), 8)
                        .readExactInto(new byte[4], 0, 4));
    }

    @Test
    void readMessageTypeSkipsLeadingZeroBytes() throws Exception {
        InputStream input = new ByteArrayInputStream(new byte[]{0, 0, 'Z', 0, 0, 0, 0});
        Protocol protocol = new Protocol(input);
        assertEquals('Z', protocol.readMessageType());
        assertEquals(0, protocol.readInt());
    }

    @Test
    void mapsStructuredErrorFieldsToStateAndReadableMessage() {
        byte[] payload = "C42P01\0Mrelation \"X\" does not exist\0Ddetail\0".getBytes(StandardCharsets.UTF_8);
        SQLException error = Protocol.serverError(payload);
        assertEquals("42P01", error.getSQLState());
        assertTrue(error.getMessage().contains("relation"), error.getMessage());
        assertTrue(error.getMessage().contains("C=42P01"), error.getMessage());
    }

    @Test
    void mapsEmptyAndUnstructuredErrorsWithoutDroppingText() {
        SQLException empty = Protocol.serverError(new byte[0]);
        assertEquals("HY000", empty.getSQLState());
        assertTrue(empty.getMessage().contains("Netezza server error"));

        SQLException fatal = Protocol.serverError("FATAL:  out of memory".getBytes(StandardCharsets.UTF_8));
        assertEquals("out of memory", fatal.getMessage());
        assertEquals("HY000", fatal.getSQLState());

        SQLException raw = Protocol.serverError("something unusual".getBytes(StandardCharsets.UTF_8));
        assertEquals("HY000", raw.getSQLState());
        assertTrue(raw.getMessage().contains("something unusual"));
    }

    @Test
    void mapsCancellationAndTimeoutTextTo57014RegardlessOfCase() {
        assertEquals("57014", Protocol.serverError("ERROR: Query was CANCELLED".getBytes(StandardCharsets.UTF_8))
                .getSQLState());
        assertEquals("57014", Protocol.serverError("ERROR: statement timeout".getBytes(StandardCharsets.UTF_8))
                .getSQLState());
    }

    @Test
    void wrapsIoFailuresAs08006WithoutLosingTheCause() {
        IOException cause = new IOException("socket reset");
        SQLException error = new Protocol(new ByteArrayInputStream(new byte[0])).io(cause);
        assertEquals("08006", error.getSQLState());
        assertSame(cause, error.getCause());
        assertTrue(error.getMessage().contains("socket reset"));
    }

    @Test
    void rejectsNegativeAndOversizedFrameLengthsAs08006() {
        for (int length : new int[]{-1, Protocol.MAX_MESSAGE + 1}) {
            byte[] stream = concat(new byte[]{'T'}, i32(0), i32(length));
            SQLException error = assertThrows(SQLException.class,
                    () -> new QueryResult(new Protocol(new ByteArrayInputStream(stream))));
            assertEquals("08006", error.getSQLState());
        }
    }

    @Test
    void rejectsEmptyStreamAs08006() {
        SQLException error = assertThrows(SQLException.class,
                () -> new QueryResult(new Protocol(new ByteArrayInputStream(new byte[0]))));
        assertEquals("08006", error.getSQLState());
    }

    @Test
    void parsesCommandCountsAndEmptyResults() throws Exception {
        QueryResult update = queryResult(message('C', "INSERT 0 5\0".getBytes(StandardCharsets.UTF_8)), ready());
        assertFalse(update.hasRows);
        assertEquals(5, update.initialUpdateCount);
        assertEquals(5, update.updateCount);
        assertFalse(update.next());

        QueryResult empty = queryResult(ready());
        assertFalse(empty.hasRows);
        assertTrue(empty.done);
        assertFalse(empty.next());
    }

    @Test
    void rejectsTruncatedDataRowShapes() throws Exception {
        assertQueryFails(rowDescription("V", 23, (short) 4, -1),
                message('D', new byte[0]));
        assertQueryFails(rowDescription("V", 23, (short) 4, -1),
                message('D', new byte[]{(byte) 0x80, 0, 0}));
        assertQueryFails(rowDescription("V", 23, (short) 4, -1),
                message('D', new byte[]{(byte) 0x80, 0, 0, 0, 3}));
    }

    @Test
    void rejectsDataRowsWithTrailingBytes() throws Exception {
        assertQueryFails(rowDescription("V", 23, (short) 4, -1),
                message('D', new byte[]{(byte) 0x80, 0, 0, 0, 4, (byte) '1', 99}));
    }

    @Test
    void rejectsTruncatedDbosDescriptorsAndInvalidFieldCounts() throws Exception {
        assertQueryFails(message('X', new byte[10]));
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(header);
        for (int i = 0; i < 8; i++) out.writeInt(0);
        out.writeInt(-1);
        for (int i = 0; i < 8; i++) out.writeInt(0);
        assertQueryFails(message('X', header.toByteArray()));
    }

    @Test
    void rejectsInvalidDbosRowLengths() throws Exception {
        byte[] descriptor = dbosDescriptor(1, 0,
                3, 4, 4, 4, 0, 0, 0, 1, 0);
        for (int length : new int[]{-1, Protocol.MAX_MESSAGE + 1}) {
            byte[] row = concat(i32(0), i32(length));
            assertQueryFails(descriptor, rawMessage('Y', row));
        }
    }

    @Test
    void decodesFixedDbosFieldsLazilyAndKeepsTheValueCached() throws Exception {
        byte[] descriptor = dbosDescriptor(1, 0,
                3, 4, 4, 3, 0, 0, 0, 1, 0);
        byte[] row = concat(i32(0), i32(7), new byte[]{0, 0, 0, 1, 2, 3, 4});
        QueryResult result = queryResult(descriptor, rawMessage('Y', row), command("SELECT 1"), ready());
        assertTrue(result.hasRows);
        assertTrue(result.next());
        assertEquals(0x04030201, result.value(0));
        assertEquals(0x04030201, result.value(0));
        assertEquals(0x04030201, result.intValue(0));
        assertFalse(result.lastWasNull);
    }

    @Test
    void rejectsDbosNullBitmapOutsideTheRowPayload() throws Exception {
        byte[] descriptor = dbosDescriptor(1, 0,
                3, 4, 4, 4, 64, 0, 0, 1, 0);
        byte[] row = concat(i32(0), i32(7), new byte[]{0, 0, 0, 1, 2, 3, 4});
        QueryResult result = queryResult(descriptor, rawMessage('Y', row), command("SELECT 1"), ready());
        assertTrue(result.next());
        SQLException error = assertThrows(SQLException.class, () -> result.value(0));
        assertEquals("HY000", error.getSQLState());
    }

    @Test
    void rejectsInvalidDbosVariableFieldLengthWhenValueIsRequested() throws Exception {
        byte[] descriptor = dbosDescriptor(1, 0,
                16, 10, 0, 0, 0, 0, 0, 0, 0);
        byte[] row = concat(i32(0), i32(4), new byte[]{0, 0, 0, 0});
        QueryResult result = queryResult(descriptor, rawMessage('Y', row), command("SELECT 1"), ready());
        assertTrue(result.next());
        SQLException error = assertThrows(SQLException.class, () -> result.value(0));
        assertEquals("HY000", error.getSQLState());
    }

    @Test
    void holdsLargeReusableDbosRowIntactUntilTheNextRow() throws Exception {
        QueryResult result = queryResult(
                dbosDescriptor(1, 3, 16, 20_000, 0, 0, 0, 0, 0, 0, 0),
                dbosTextRow(20_000), command("SELECT 1"), ready());
        assertTrue(result.next());
        String value = (String) result.value(0);
        assertEquals(19_995, value.length());
        assertEquals('a', value.charAt(0));
        assertEquals('a', value.charAt(value.length() - 1));
    }

    private static void assertQueryFails(byte[]... messages) {
        byte[] stream = concat(messages);
        SQLException error = assertThrows(SQLException.class,
                () -> new QueryResult(new Protocol(new ByteArrayInputStream(stream))));
        assertEquals("08006", error.getSQLState());
    }

    private static QueryResult queryResult(byte[]... messages) throws Exception {
        return new QueryResult(new Protocol(new ByteArrayInputStream(concat(messages))));
    }

    private static byte[] rowDescription(String name, int oid, short size, int modifier) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeShort(1);
        out.write(name.getBytes(StandardCharsets.UTF_8));
        out.writeByte(0);
        out.writeInt(oid);
        out.writeShort(size);
        out.writeInt(modifier);
        out.writeByte(0);
        return message('T', payload.toByteArray());
    }

    private static byte[] dbosDescriptor(int fieldCount, int fixedSize, int... fieldWords) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeInt(1);          // version
        out.writeInt(0);          // nulls
        out.writeInt(0);          // size word
        out.writeInt(0);          // size of
        out.writeInt(1);          // fixed count
        out.writeInt(fieldCount); // varying count
        out.writeInt(fixedSize);
        out.writeInt(0);
        out.writeInt(fieldCount);
        for (int word : fieldWords) out.writeInt(word);
        for (int i = 0; i < 8; i++) out.writeInt(0);
        return message('X', payload.toByteArray());
    }

    private static byte[] dbosTextRow(int length) throws IOException {
        byte[] data = new byte[length];
        java.util.Arrays.fill(data, (byte) 'a');
        // Null bitmap at 2 + phys/8; fixed fields end at byte 3.
        data[2] = 0;
        int fieldLength = length - 3;
        data[3] = (byte) fieldLength;
        data[4] = (byte) (fieldLength >>> 8);
        return rawMessage('Y', concat(i32(0), i32(length), data));
    }

    private static byte[] command(String tag) throws IOException {
        return message('C', (tag + "\0").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] ready() {
        return concat(new byte[]{'Z'}, i32(0));
    }

    private static byte[] message(char type, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type);
        out.writeInt(0);
        out.writeInt(payload.length);
        out.write(payload);
        return bytes.toByteArray();
    }

    /** Messages such as Y carry their body directly after the shared header. */
    private static byte[] rawMessage(char type, byte[] body) {
        return concat(new byte[]{(byte) type}, i32(0), body);
    }

    private static byte[] i32(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.writeBytes(chunk);
        return out.toByteArray();
    }
}
