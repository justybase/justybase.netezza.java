/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.sql.Clob;
import java.sql.SQLException;

/** In-memory Clob used for JDBC LOB getters, setters and factory methods. */
class NetezzaClob implements Clob {
    private StringBuilder data;
    private boolean freed;

    NetezzaClob(String data) {
        this.data = new StringBuilder(data == null ? "" : data);
    }

    protected void check() throws SQLException {
        if (freed) throw new SQLException("Clob has been freed", "HY010");
    }

    @Override public long length() throws SQLException { check(); return data.length(); }

    @Override public String getSubString(long pos, int length) throws SQLException {
        check();
        if (pos < 1 || length < 0) throw new SQLException("Invalid Clob position or length", "22003");
        int start = (int) Math.min(Integer.MAX_VALUE, pos - 1);
        if (start >= data.length()) return "";
        int end = Math.min(data.length(), start + length);
        return data.substring(start, end);
    }

    @Override public Reader getCharacterStream() throws SQLException {
        check();
        return new StringReader(data.toString());
    }

    @Override public Reader getCharacterStream(long pos, long length) throws SQLException {
        return new StringReader(getSubString(pos, (int) Math.min(Integer.MAX_VALUE, length)));
    }

    @Override public InputStream getAsciiStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Override public long position(String search, long start) throws SQLException {
        check();
        if (search == null) throw new SQLException("Clob search string must not be null", "HY009");
        if (start < 1) throw new SQLException("Invalid Clob position", "22003");
        int found = data.indexOf(search, (int) Math.min(Integer.MAX_VALUE, start - 1));
        return found < 0 ? -1 : found + 1L;
    }

    @Override public long position(Clob search, long start) throws SQLException {
        if (search == null) throw new SQLException("Clob search value must not be null", "HY009");
        return position(search.getSubString(1, (int) search.length()), start);
    }

    @Override public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str == null ? 0 : str.length());
    }

    @Override public int setString(long pos, String str, int offset, int len) throws SQLException {
        check();
        if (str == null) throw new SQLException("Clob string must not be null", "HY009");
        if (pos < 1 || offset < 0 || len < 0 || offset + len > str.length())
            throw new SQLException("Invalid Clob position or range", "22003");
        int start = (int) Math.min(Integer.MAX_VALUE, pos - 1);
        while (data.length() < start) data.append('\0');
        int end = start + len;
        if (end > data.length()) data.setLength(end);
        data.replace(start, end, str.substring(offset, offset + len));
        return len;
    }

    @Override public OutputStream setAsciiStream(long pos) throws SQLException {
        check();
        if (pos < 1) throw new SQLException("Invalid Clob position", "22003");
        return new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                super.close();
                try { setString(pos, new String(toByteArray(), java.nio.charset.StandardCharsets.US_ASCII)); }
                catch (SQLException e) { throw new IOException(e); }
            }
        };
    }

    @Override public Writer setCharacterStream(long pos) throws SQLException {
        check();
        if (pos < 1) throw new SQLException("Invalid Clob position", "22003");
        return new StringWriter() {
            @Override public void close() throws IOException {
                super.close();
                try { setString(pos, toString()); }
                catch (SQLException e) { throw new IOException(e); }
            }
        };
    }

    @Override public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > data.length()) throw new SQLException("Invalid Clob length", "22003");
        data.setLength((int) len);
    }

    @Override public void free() {
        freed = true;
        data = null;
    }
}
