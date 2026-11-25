package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ExternalTableHandlerTest {
    @Test
    void exportsChunksToTheFileNamedByTheServer() throws Exception {
        Path directory = Files.createTempDirectory("nz-external-export");
        Path file = directory.resolve("export.dat");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.write(new byte[10]);
            out.write(new byte[16]);
            writeString(out, file.toString());
            out.writeInt(0); // 'U' frame header
            out.writeInt(1); // DATA
            byte[] payload = "1|one\n2|two\n".getBytes(StandardCharsets.UTF_8);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeInt(3); // DONE
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('u', 0);
        handler.handle('U', 0);
        handler.close();

        assertEquals("1|one\n2|two\n", Files.readString(file));
        assertNull(handler.deferredError());
        deleteTree(directory);
    }

    @Test
    void importsTheFileAndSendsDataThenDone() throws Exception {
        Path directory = Files.createTempDirectory("nz-external-import");
        Path file = directory.resolve("import.dat");
        byte[] payload = "1|one\n2|two\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(0); // 'l' frame header
            writeCString(out, file.toString());
            out.writeInt(99); // host version
            out.writeInt(0);  // format
            out.writeInt(8);  // block size
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        protocol.out = new ByteArrayOutputStream();
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('l', 0);
        handler.close();

        ByteArrayOutputStream written = (ByteArrayOutputStream) protocol.out;
        var input = new java.io.DataInputStream(new ByteArrayInputStream(written.toByteArray()));
        assertEquals(1, input.readInt()); // client version
        ByteArrayOutputStream imported = new ByteArrayOutputStream();
        while (true) {
            int status = input.readInt();
            if (status == 3) break;
            assertEquals(1, status);
            int length = input.readInt();
            imported.write(input.readNBytes(length));
        }
        assertArrayEquals(payload, imported.toByteArray());
        assertNull(handler.deferredError());
        deleteTree(directory);
    }

    @Test
    void importsEachChunkInOneSocketWrite() throws Exception {
        Path directory = Files.createTempDirectory("nz-external-import-batch");
        Path file = directory.resolve("import.dat");
        byte[] payload = "1|one\n2|two\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(0);
            writeCString(out, file.toString());
            out.writeInt(99);
            out.writeInt(0);
            out.writeInt(64);
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        CountingStream sink = new CountingStream();
        protocol.out = sink;
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('l', 0);
        handler.close();

        assertEquals(1, sink.chunkWrites, "each import chunk must be one socket write");
        var input = new java.io.DataInputStream(new ByteArrayInputStream(sink.toByteArray()));
        assertEquals(1, input.readInt()); // client version
        assertEquals(1, input.readInt()); // DATA
        assertEquals(payload.length, input.readInt());
        assertArrayEquals(payload, input.readNBytes(payload.length));
        assertEquals(3, input.readInt()); // DONE
        assertNull(handler.deferredError());
        deleteTree(directory);
    }

    private static final class CountingStream extends ByteArrayOutputStream {
        int chunkWrites;

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > 8) chunkWrites++;
            super.write(bytes, offset, length);
        }
    }

    @Test
    void missingImportFileSendsAnErrorStatus() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(0);
            writeCString(out, Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist-nz.dat").toString());
            out.writeInt(99);
            out.writeInt(0);
            out.writeInt(8);
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        protocol.out = new ByteArrayOutputStream();
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('l', 0);

        var input = new java.io.DataInputStream(new ByteArrayInputStream(((ByteArrayOutputStream) protocol.out).toByteArray()));
        assertEquals(1, input.readInt());
        assertEquals(2, input.readInt());
        assertNotNull(handler.deferredError());
        assertTrue(handler.deferredError().getMessage().contains("does not exist"));
    }

    @Test
    void savesServerLogChunksWithTheCorrectExtension() throws Exception {
        Path directory = Files.createTempDirectory("nz-external-log");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(directory.toString().getBytes(StandardCharsets.UTF_8).length + 1);
            out.write(directory.toString().getBytes(StandardCharsets.UTF_8));
            out.writeByte(0);
            writeCString(out, "bad");
            out.writeInt(2); // .nzbad
            byte[] payload = "bad data".getBytes(StandardCharsets.UTF_8);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeInt(0);
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('e', 0);

        Path log = directory.resolve("bad.nzbad");
        assertTrue(Files.isRegularFile(log));
        assertEquals("bad data", Files.readString(log));
        assertNull(handler.deferredError());
        deleteTree(directory);
    }

    @Test
    void exportErrorStatusIsDeferredForTheQueryLoop() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(0); // 'U' frame header
            out.writeInt(2); // ERROR
            writeShortString(out, "bad row");
            writeShortString(out, "object");
        }
        Protocol protocol = new Protocol(new ByteArrayInputStream(body.toByteArray()));
        ExternalTableHandler handler = new ExternalTableHandler(protocol);
        handler.handle('U', 0);
        assertNotNull(handler.deferredError());
        assertTrue(handler.deferredError().getMessage().contains("bad row"));
        assertTrue(handler.deferredError().getMessage().contains("object"));
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeCString(DataOutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeByte(0);
    }

    private static void writeShortString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
