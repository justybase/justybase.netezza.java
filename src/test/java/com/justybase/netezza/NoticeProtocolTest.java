package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLWarning;

import static org.junit.jupiter.api.Assertions.*;

/** NoticeResponse parsing and interleaving with command completion. */
class NoticeProtocolTest {
    @Test
    void rawNoticeIsNormalizedWithoutDuplicatePrefix() {
        SQLWarning warning = Protocol.noticeWarning("NOTICE:  hello\n\0".getBytes(StandardCharsets.UTF_8));
        assertEquals("01000", warning.getSQLState());
        assertTrue(warning.getMessage().contains("hello"));
        assertFalse(warning.getMessage().startsWith("NOTICE: NOTICE:"));
    }

    @Test
    void multilineRawNoticeKeepsItsSeverityPrefixExactlyOnce() {
        SQLWarning warning = Protocol.noticeWarning(
                "WARNING: first line\nsecond line\0".getBytes(StandardCharsets.UTF_8));
        assertTrue(warning.getMessage().startsWith("WARNING: first line"), warning.getMessage());
        assertTrue(warning.getMessage().contains("second line"), warning.getMessage());
        assertFalse(warning.getMessage().startsWith("WARNING: WARNING:"), warning.getMessage());
    }

    @Test
    void fieldListNoticeCarriesStateAndDetail() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        field(payload, 'S', "NOTICE");
        field(payload, 'C', "01000");
        field(payload, 'M', "hello");
        field(payload, 'D', "detail");
        payload.write(0);
        SQLWarning warning = Protocol.noticeWarning(payload.toByteArray());
        assertEquals("01000", warning.getSQLState());
        assertTrue(warning.getMessage().contains("hello"));
        assertTrue(warning.getMessage().contains("detail"));
    }

    @Test
    void queryResultCollectsNoticeBeforeCommandComplete() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        frame(stream, 'N', "NOTICE:  interleaved\n\0".getBytes(StandardCharsets.UTF_8));
        frame(stream, 'C', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
        QueryResult result = new QueryResult(new Protocol(new ByteArrayInputStream(stream.toByteArray())));
        assertNotNull(result.warnings());
        assertTrue(result.warnings().getMessage().contains("interleaved"));
        result.clearWarnings();
        assertNull(result.warnings());
    }

    private static void field(ByteArrayOutputStream out, char code, String value) {
        out.write(code);
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void frame(ByteArrayOutputStream out, char type, byte[] payload) {
        out.write(type);
        out.write(0); // shared backend header
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(payload.length >>> 24);
        out.write(payload.length >>> 16);
        out.write(payload.length >>> 8);
        out.write(payload.length);
        out.writeBytes(payload);
    }
}
