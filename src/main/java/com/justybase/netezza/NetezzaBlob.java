/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/** In-memory Blob used for JDBC LOB getters, setters and factory methods. */
final class NetezzaBlob implements Blob {
    private byte[] data;
    private boolean freed;

    NetezzaBlob(byte[] data) {
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }

    private void check() throws SQLException {
        if (freed) throw new SQLException("Blob has been freed", "HY010");
    }

    @Override public long length() throws SQLException { check(); return data.length; }

    @Override public byte[] getBytes(long pos, int length) throws SQLException {
        check();
        if (pos < 1 || length < 0) throw new SQLException("Invalid Blob position or length", "22003");
        int start = (int) Math.min(Integer.MAX_VALUE, pos - 1);
        if (start >= data.length) return new byte[0];
        int end = Math.min(data.length, start + length);
        return Arrays.copyOfRange(data, start, end);
    }

    @Override public InputStream getBinaryStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data);
    }

    @Override public InputStream getBinaryStream(long pos, long length) throws SQLException {
        return new ByteArrayInputStream(getBytes(pos, (int) Math.min(Integer.MAX_VALUE, length)));
    }

    @Override public long position(byte[] pattern, long start) throws SQLException {
        check();
        if (pattern == null) throw new SQLException("Blob pattern must not be null", "HY009");
        if (start < 1) throw new SQLException("Invalid Blob position", "22003");
        int from = (int) Math.min(Integer.MAX_VALUE, start - 1);
        outer:
        for (int i = from; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i + 1L;
        }
        return -1;
    }

    @Override public long position(Blob pattern, long start) throws SQLException {
        if (pattern == null) throw new SQLException("Blob pattern must not be null", "HY009");
        return position(pattern.getBytes(1, (int) pattern.length()), start);
    }

    @Override public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes == null ? 0 : bytes.length);
    }

    @Override public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        check();
        if (bytes == null) throw new SQLException("Blob bytes must not be null", "HY009");
        if (pos < 1 || offset < 0 || len < 0 || offset + len > bytes.length)
            throw new SQLException("Invalid Blob position or range", "22003");
        int start = (int) Math.min(Integer.MAX_VALUE, pos - 1);
        int end = start + len;
        if (end > data.length) data = Arrays.copyOf(data, end);
        System.arraycopy(bytes, offset, data, start, len);
        return len;
    }

    @Override public OutputStream setBinaryStream(long pos) throws SQLException {
        check();
        if (pos < 1) throw new SQLException("Invalid Blob position", "22003");
        return new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                super.close();
                try { setBytes(pos, toByteArray()); }
                catch (SQLException e) { throw new IOException(e); }
            }
        };
    }

    @Override public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > data.length) throw new SQLException("Invalid Blob length", "22003");
        data = Arrays.copyOf(data, (int) len);
    }

    @Override public void free() {
        freed = true;
        data = null;
    }
}
