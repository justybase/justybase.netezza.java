/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;

/**
 * Handles Netezza external-table file transfer messages that are interleaved
 * with normal query responses. All payload lengths are bounded before use.
 */
final class ExternalTableHandler implements Closeable {
    private static final int DATA = 1;
    private static final int ERROR = 2;
    private static final int DONE = 3;
    private static final int MAX_FILE_NAME = 1 << 20;
    private static final int MAX_CHUNK = 32 * 1024 * 1024;

    private final Protocol protocol;
    private final boolean bulkMode;
    private BulkInsertData bulkData;
    private OutputStream exportStream;
    private SQLException deferredError;

    ExternalTableHandler(Protocol protocol) {
        this(protocol, null);
    }

    ExternalTableHandler(Protocol protocol, BulkInsertData bulkData) {
        this.protocol = protocol;
        this.bulkData = bulkData;
        this.bulkMode = bulkData != null;
    }

    void handle(int type, int header) throws IOException, SQLException {
        switch (type) {
            case 'u' -> startExport();
            case 'U' -> receiveExportData();
            case 'l' -> importFile();
            case 'e' -> receiveLog();
            case 'x' -> protocol.readExact(4);
            default -> throw new IOException("Unexpected external-table message " + (char) type);
        }
    }

    SQLException deferredError() {
        return deferredError;
    }

    private void startExport() throws IOException {
        protocol.readExact(10);
        protocol.readExact(16);
        String fileName = readString(protocol.readInt());
        try {
            exportStream = Files.newOutputStream(Path.of(fileName),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            writeStatus(0);
        } catch (Exception e) {
            closeExport();
            writeStatus(1);
            deferredError = new SQLException("Could not open external export file: " + fileName, "HY000", e);
        }
    }

    private void receiveExportData() throws IOException, SQLException {
        protocol.readExact(4);
        while (true) {
            int status = protocol.readInt();
            if (status == DATA) {
                int length = protocol.readInt();
                validateLength(length);
                byte[] data = protocol.readExactReusable(length);
                if (exportStream != null) {
                    try {
                        exportStream.write(data, 0, length);
                    } catch (IOException e) {
                        closeExport();
                        deferredError = new SQLException("Could not write external export file", "HY000", e);
                    }
                }
                continue;
            }
            if (status == DONE) {
                closeExport();
                return;
            }
            if (status == ERROR) {
                String message = readShortString();
                String object = readShortString();
                closeExport();
                deferredError = new SQLException("External export failed: " + message +
                        (object.isEmpty() ? "" : " (object: " + object + ")"), "HY000");
                return;
            }
            closeExport();
            deferredError = new SQLException("Unknown external export status " + status, "HY000");
            return;
        }
    }

    private void importFile() throws IOException {
        protocol.readExact(4);
        String fileName = readCString();
        protocol.readInt(); // host version
        writeStatus(1);    // client version
        protocol.readInt(); // format
        int blockSize = protocol.readInt();
        validateLength(blockSize);
        if (blockSize == 0) blockSize = 64 * 1024;

        if (bulkMode) {
            BulkInsertData data = bulkData;
            bulkData = null;
            if (data == null) {
                sendImportError(new IOException("Bulk insert data was already consumed"));
                deferredError = new SQLException("Bulk insert data was already consumed", "HY000");
                return;
            }
            try {
                data.writeRows(protocol.out, blockSize);
            } catch (Exception e) {
                sendImportError(e);
                deferredError = new SQLException("Could not stream bulk insert rows", "HY000", e);
            }
            return;
        }

        Path path = Path.of(fileName);
        if (!Files.isRegularFile(path)) {
            writeStatus(ERROR);
            deferredError = new SQLException("External import file does not exist: " + fileName, "HY000");
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            // Read directly into the packet payload so each chunk leaves as one
            // socket write instead of a status/length/data sequence of small writes.
            byte[] packet = new byte[8 + Math.min(blockSize, MAX_CHUNK)];
            int read;
            while ((read = input.read(packet, 8, packet.length - 8)) >= 0) {
                if (read == 0) continue;
                putInt(packet, 0, DATA);
                putInt(packet, 4, read);
                protocol.out.write(packet, 0, 8 + read);
                protocol.out.flush();
            }
            writeStatus(DONE);
        } catch (Exception e) {
            sendImportError(e);
            deferredError = new SQLException("Could not read external import file: " + fileName, "HY000", e);
        }
    }

    private void sendImportError(Exception failure) throws IOException {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, 0x7fff);
        byte[] packet = new byte[6 + length];
        putInt(packet, 0, ERROR);
        packet[4] = (byte) (length >>> 8);
        packet[5] = (byte) length;
        System.arraycopy(bytes, 0, packet, 6, length);
        protocol.out.write(packet);
        protocol.out.flush();
    }

    private void receiveLog() throws IOException {
        int directoryLength = protocol.readInt();
        validateLength(directoryLength);
        if (directoryLength < 1) throw new IOException("Invalid external log directory length " + directoryLength);
        String directory = readString(directoryLength - 1);
        protocol.readU8(); // NUL terminator
        String fileName = readCString();
        int logType = protocol.readInt();
        if (bulkMode) {
            // The server may send load logs when a bulk insert fails. The
            // statement error is reported through the normal error frame, so
            // drain the payload instead of writing appliance paths locally.
            while (true) {
                int length = protocol.readInt();
                if (length == 0) break;
                validateLength(length);
                protocol.readExactReusable(length);
            }
            return;
        }
        String extension = switch (logType) {
            case 1 -> ".nzlog";
            case 2 -> ".nzbad";
            case 3 -> ".nzstats";
            default -> ".log";
        };
        Path target = Path.of(directory, fileName + extension);
        OutputStream output = null;
        try {
            Files.createDirectories(target.getParent());
            output = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            deferredError = new SQLException("Could not create external log file: " + target, "HY000", e);
        }
        try {
            while (true) {
                int length = protocol.readInt();
                if (length == 0) break;
                validateLength(length);
                byte[] data = protocol.readExactReusable(length);
                if (output != null) {
                    try {
                        output.write(data, 0, length);
                    } catch (IOException e) {
                        if (deferredError == null)
                            deferredError = new SQLException("Could not write external log file: " + target, "HY000", e);
                    }
                }
            }
        } finally {
            closeQuietly(output);
        }
    }

    private String readCString() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = protocol.readU8();
            if (value == 0) return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= MAX_FILE_NAME) throw new IOException("External-table filename is too long");
            bytes.write(value);
        }
    }

    private String readShortString() throws IOException {
        int length = protocol.readShort();
        validateLength(length);
        return new String(protocol.readExact(length), StandardCharsets.UTF_8);
    }

    private String readString(int length) throws IOException {
        validateLength(length);
        if (length > MAX_FILE_NAME) throw new IOException("External-table filename is too long");
        return new String(protocol.readExact(length), StandardCharsets.UTF_8);
    }

    private void writeStatus(int status) throws IOException {
        byte[] bytes = {
                (byte) (status >>> 24), (byte) (status >>> 16), (byte) (status >>> 8), (byte) status
        };
        protocol.out.write(bytes);
        protocol.out.flush();
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void validateLength(int length) throws IOException {
        if (length < 0 || length > MAX_CHUNK) throw new IOException("Invalid external-table payload length " + length);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private void closeExport() {
        closeQuietly(exportStream);
        exportStream = null;
    }

    @Override
    public void close() {
        closeExport();
    }
}
