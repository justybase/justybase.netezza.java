package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolUnitTest {
    @Test
    void supportsPlainMd5AndSha256Responses() throws Exception {
        byte[] salt = {1, 2};
        assertArrayEquals("päss".getBytes(StandardCharsets.UTF_8),
                Protocol.authenticationResponse(3, salt, "päss"));
        assertEquals("9z87pEoxiDspxv/TLjE4Aw",
                new String(Protocol.authenticationResponse(5, salt, "s3cret"), StandardCharsets.US_ASCII));
        assertEquals("8jioWuHFSLbd7CdoLu/bSEBTeZCkZFAO9Icyy2MSHmQ",
                new String(Protocol.authenticationResponse(6, salt, "s3cret"), StandardCharsets.US_ASCII));
        assertNull(Protocol.authenticationResponse(0, new byte[0], ""));
    }

    @Test
    void rejectsGssAndMalformedPasswordChallengesExplicitly() {
        SQLException unsupported = assertThrows(SQLException.class,
                () -> Protocol.authenticationResponse(7, new byte[0], "secret"));
        assertEquals("0A000", unsupported.getSQLState());
        SQLException missingSalt = assertThrows(SQLException.class,
                () -> Protocol.authenticationResponse(5, new byte[] {1}, "secret"));
        assertEquals("08006", missingSalt.getSQLState());
    }

    @Test
    void boundsProtocolFramesBeforeAllocation() throws Exception {
        assertDoesNotThrow(() -> Protocol.validateProtocolLength(0));
        assertDoesNotThrow(() -> Protocol.validateProtocolLength(Protocol.MAX_MESSAGE));
        assertThrows(IOException.class, () -> Protocol.validateProtocolLength(-1));
        assertThrows(IOException.class, () -> Protocol.validateProtocolLength(Protocol.MAX_MESSAGE + 1));
    }

    @Test
    void readsBigEndianIntegersWithoutChangingWireOrder() throws Exception {
        Protocol protocol = new Protocol(new ByteArrayInputStream(new byte[] {
                (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef, 0x12, 0x34
        }));
        assertEquals(0x89abcdef, protocol.readInt());
        assertEquals(0x1234, protocol.readShort());
    }

    @Test
    void skipsPayloadsAcrossBufferRefillsWithoutCopying() throws Exception {
        byte[] bytes = new byte[200_000];
        bytes[0] = 1;
        bytes[70_000] = 2;
        bytes[199_999] = 3;
        Protocol protocol = new Protocol(new ByteArrayInputStream(bytes));
        protocol.skip(70_000);
        assertEquals(2, protocol.readU8());
        protocol.skip(129_998);
        assertEquals(3, protocol.readU8());
    }

    @Test
    void skipsLengthPrefixedFramesAndLeavesTheNextFrameAtTheCursor() throws Exception {
        Protocol protocol = new Protocol(new ByteArrayInputStream(new byte[] {
                0, 0, 0, 3, 10, 20, 30, 0, 0, 0, 2, 40, 50
        }));
        protocol.skipFrame();
        assertEquals(2, protocol.readInt());
        assertEquals(40, protocol.readU8());
        assertEquals(50, protocol.readU8());
    }

    @Test
    void writesTheWholeQueryAsASingleFrontendPacket() throws Exception {
        // Splitting the packet into one write per byte cost ~40 ms per query on the
        // appliance because of delayed ACKs; keep the frontend packet contiguous.
        CountingStream sink = new CountingStream();
        Protocol protocol = new Protocol(new ByteArrayInputStream(new byte[0]), sink);
        protocol.query("SELECT 1");
        assertEquals(1, sink.writes, "query must reach the socket in a single write");
        byte[] packet = sink.toByteArray();
        assertEquals('P', packet[0]);
        assertEquals(-1, Protocol.i32(packet, 1));
        assertEquals("SELECT 1", new String(packet, 5, packet.length - 6, StandardCharsets.UTF_8));
        assertEquals(0, packet[packet.length - 1]);
    }

    private static final class CountingStream extends ByteArrayOutputStream {
        int writes;

        @Override public synchronized void write(int b) {
            writes++;
            super.write(b);
        }

        @Override public synchronized void write(byte[] b, int off, int len) {
            writes++;
            super.write(b, off, len);
        }
    }

    @Test
    void preservesApplianceErrorTextWithoutDroppingItsFirstCharacter() {
        SQLException error = Protocol.serverError("ERROR: syntax error near FROM".getBytes(StandardCharsets.UTF_8));
        assertEquals("syntax error near FROM", error.getMessage());
        assertEquals("42601", error.getSQLState());
    }

    @Test
    void mapsCommonApplianceErrorsToStandardSqlStates() {
        assertEquals("42P01", Protocol.serverError(
                "ERROR: relation does not exist JUST_DATA.ADMIN.NO_SUCH".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("42703", Protocol.serverError(
                "ERROR: Attribute 'NO_SUCH' not found".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("22012", Protocol.serverError(
                "ERROR: Divide by 0".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("23502", Protocol.serverError(
                "ERROR: Column 1 : Field cannot contain null values".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("23505", Protocol.serverError(
                "ERROR: duplicate key value violates unique constraint".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("42501", Protocol.serverError(
                "ERROR: permission denied for relation X".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("22003", Protocol.serverError(
                "ERROR: numeric overflow".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("22001", Protocol.serverError(
                "ERROR: string data right truncation".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("22P02", Protocol.serverError(
                "ERROR: pg_atoi: error in \"abc\": can't parse \"abc\"".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("22P02", Protocol.serverError(
                "ERROR: Cannot cast type 'UNKNOWN' to 'VARBINARY'".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("42702", Protocol.serverError(
                "ERROR: column reference \"ID\" is ambiguous".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("3F000", Protocol.serverError(
                "ERROR: SET SCHEMA: DB \"X\" schema \"NOPE\" not found".getBytes(StandardCharsets.UTF_8)).getSQLState());
        assertEquals("HY000", Protocol.serverError(
                "ERROR: something appliance specific".getBytes(StandardCharsets.UTF_8)).getSQLState());
    }

    @Test
    void mapsApplianceCancellationMessagesToSqlState57014() {
        for (String text : new String[] {
                "ERROR: Query was cancelled.",
                "ERROR: Transaction rolled back by client",
                "ERROR: terminated by user"
        }) {
            SQLException error = Protocol.serverError(text.getBytes(StandardCharsets.UTF_8));
            assertEquals("57014", error.getSQLState(), text);
        }
    }
}
