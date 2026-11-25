/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.util.List;

/**
 * Rendered, tab-separated rows for one bulk insert. Values use the external
 * table text format: {@code \N} for NULL, and a backslash escape before the
 * delimiter, backslash, CR and LF. No temporary files are involved.
 *
 * <p>The batch stores the unescaped text values only. Widths are measured while
 * the batch is created, and rows are escaped and encoded straight into one
 * reusable packet buffer while streaming, so the steady-state write path does
 * not allocate per field, per row or per DATA frame.</p>
 */
final class BulkInsertData {
    static final String USING_CLAUSE =
            "REMOTESOURCE 'jdbc' DELIMITER '\t' ESCAPECHAR '\\' NULLVALUE '\\N' ENCODING 'UTF8'";

    private static final int MAX_NVARCHAR = 16_000;
    private static final int MAX_ROW_BYTES = 64_000;
    /** Match the C# driver: never let a server-advertised block size inflate the packet buffer without bound. */
    private static final int MAX_BLOCK_BYTES = 1 << 20;
    private static final int DEFAULT_BLOCK_BYTES = 64 * 1024;

    private final String[][] rows;
    private final String columnDefinitions;
    private final int maxLineBytes;

    private BulkInsertData(String[][] rows, String columnDefinitions, int maxLineBytes) {
        this.rows = rows;
        this.columnDefinitions = columnDefinitions;
        this.maxLineBytes = maxLineBytes;
    }

    String columnDefinitions() {
        return columnDefinitions;
    }

    /**
     * Renders the batch, or returns {@code null} when a value cannot be
     * represented safely; the caller then falls back to row-by-row execution.
     */
    static BulkInsertData create(int columns, List<Object[]> rows) throws SQLException {
        if (columns < 1 || rows == null || rows.isEmpty()) return null;
        int[] maxChars = new int[columns];
        int[] maxBytes = new int[columns];
        String[][] textRows = new String[rows.size()][];
        int maxLine = 1;
        int rowIndex = 0;
        try {
            for (Object[] row : rows) {
                String[] values = new String[columns];
                // (columns - 1) delimiters plus the trailing newline.
                int lineBytes = columns;
                for (int i = 0; i < columns; i++) {
                    String text = text(row == null || i >= row.length ? null : row[i]);
                    values[i] = text;
                    if (text == null) {
                        lineBytes += 2; // \N
                    } else {
                        long measured = measure(text);
                        int chars = text.length();
                        int bytes = (int) measured;
                        int escaped = (int) (measured >>> 32);
                        if (chars > maxChars[i]) maxChars[i] = chars;
                        if (bytes > maxBytes[i]) maxBytes[i] = bytes;
                        lineBytes += escaped;
                    }
                }
                if (lineBytes > maxLine) maxLine = lineBytes;
                textRows[rowIndex++] = values;
            }
        } catch (UnsupportedValue e) {
            return null;
        }

        StringBuilder definitions = new StringBuilder(columns * 24);
        long rowBytes = 0;
        for (int i = 0; i < columns; i++) {
            int chars = Math.max(1, maxChars[i]);
            int bytes = Math.max(1, maxBytes[i]);
            String type;
            if (chars <= MAX_NVARCHAR) {
                type = "nvarchar(" + chars + ")";
                rowBytes += bytes;
            } else if (bytes <= MAX_ROW_BYTES) {
                type = "varchar(" + bytes + ")";
                rowBytes += bytes;
            } else {
                return null;
            }
            if (rowBytes > MAX_ROW_BYTES) return null;
            if (i > 0) definitions.append(", ");
            definitions.append('c').append(i).append(' ').append(type);
        }
        return new BulkInsertData(textRows, definitions.toString(), maxLine);
    }

    /** Streams rows as external-table DATA frames and finishes with DONE. */
    void writeRows(OutputStream out, int blockSize) throws IOException {
        int block = blockSize > 0 ? Math.min(blockSize, MAX_BLOCK_BYTES) : DEFAULT_BLOCK_BYTES;
        // Payload is encoded at offset 8 so each DATA frame leaves as one socket write.
        byte[] packet = new byte[8 + block];
        byte[] line = new byte[Math.max(64, maxLineBytes)];
        int used = 0;
        for (String[] values : rows) {
            int length = encodeLine(values, line);
            int offset = 0;
            while (offset < length) {
                int count = Math.min(block - used, length - offset);
                System.arraycopy(line, offset, packet, 8 + used, count);
                used += count;
                offset += count;
                if (used == block) {
                    sendChunk(out, packet, used);
                    used = 0;
                }
            }
        }
        if (used > 0) sendChunk(out, packet, used);
        writeInt(out, 3); // DONE
        out.flush();
    }

    private static void sendChunk(OutputStream out, byte[] packet, int length) throws IOException {
        putInt(packet, 0, 1); // DATA
        putInt(packet, 4, length);
        out.write(packet, 0, 8 + length);
        out.flush();
    }

    /**
     * Measures one value in a single pass without allocating: the low 32 bits
     * hold the unescaped UTF-8 byte length, the high 32 bits the escaped length.
     * Control characters that cannot be represented safely are rejected here,
     * before any bytes reach the socket.
     */
    private static long measure(String text) throws UnsupportedValue {
        int bytes = 0;
        int escaped = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (c < 0x20) {
                    if (c != '\t' && c != '\n' && c != '\r') throw new UnsupportedValue();
                    bytes++;
                    escaped += 2;
                } else {
                    bytes++;
                    escaped += c == '\\' ? 2 : 1;
                }
                continue;
            }
            int length;
            if (c < 0x800) {
                length = 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    bytes += 4;
                    escaped += 4;
                    continue;
                }
                length = 1; // String.getBytes(UTF_8) replaces an unpaired surrogate with '?'
            } else if (Character.isLowSurrogate(c)) {
                length = 1;
            } else {
                length = 3;
            }
            bytes += length;
            escaped += length;
        }
        return ((long) escaped << 32) | (bytes & 0xFFFFFFFFL);
    }

    private int encodeLine(String[] values, byte[] target) {
        int position = 0;
        for (int i = 0; i < values.length; i++) {
            if (i > 0) target[position++] = '\t';
            String text = values[i];
            if (text == null) {
                target[position++] = '\\';
                target[position++] = 'N';
            } else {
                position = encodeText(target, position, text);
            }
        }
        target[position++] = '\n';
        return position;
    }

    /** UTF-8 encoding with external-table escaping; capacity is guaranteed by {@link #measure}. */
    private static int encodeText(byte[] target, int position, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (c < 0x20) { // measure() has already rejected anything but \t, \n and \r
                    target[position++] = '\\';
                    target[position++] = (byte) c;
                } else if (c == '\\') {
                    target[position++] = '\\';
                    target[position++] = '\\';
                } else {
                    target[position++] = (byte) c;
                }
                continue;
            }
            if (c < 0x800) {
                target[position++] = (byte) (0xC0 | (c >> 6));
                target[position++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, text.charAt(++i));
                    target[position++] = (byte) (0xF0 | (codePoint >> 18));
                    target[position++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                    target[position++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    target[position++] = (byte) (0x80 | (codePoint & 0x3F));
                } else {
                    target[position++] = '?';
                }
            } else if (Character.isLowSurrogate(c)) {
                target[position++] = '?';
            } else {
                target[position++] = (byte) (0xE0 | (c >> 12));
                target[position++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                target[position++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        return position;
    }

    private static String text(Object value) throws SQLException, UnsupportedValue {
        if (value == null) return null;
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) throw new UnsupportedValue();
            return f.toString();
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) throw new UnsupportedValue();
            return d.toString();
        }
        if (value instanceof Number n) return n.toString();
        if (value instanceof Character c) return c.toString();
        if (value instanceof java.util.UUID u) return u.toString();
        if (value instanceof String s) return s;
        if (value instanceof LocalDate d) return d.toString();
        if (value instanceof LocalTime t) return JdbcTypeSupport.timeText(t);
        if (value instanceof LocalDateTime t) return JdbcTypeSupport.timestampText(t);
        if (value instanceof OffsetTime t) return JdbcTypeSupport.offsetTimeText(t);
        if (value instanceof java.sql.Date d) return d.toString();
        if (value instanceof Time t) return t.toString();
        if (value instanceof Timestamp t) return JdbcTypeSupport.timestampText(t.toLocalDateTime());
        if (value instanceof java.util.Date d)
            return JdbcTypeSupport.timestampText(new Timestamp(d.getTime()).toLocalDateTime());
        if (value instanceof Clob c)
            return c.getSubString(1, (int) Math.min(Integer.MAX_VALUE, c.length()));
        if (value instanceof SQLXML xml) return xml.getString();
        if (value instanceof URL url) return url.toString();
        throw new UnsupportedValue();
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 255);
        out.write((value >>> 16) & 255);
        out.write((value >>> 8) & 255);
        out.write(value & 255);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static final class UnsupportedValue extends Exception {
    }
}
