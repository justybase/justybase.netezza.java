package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringWriter;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** In-memory LOB and SQLXML wrappers. */
class LobUnitTest {
    @Test
    void blobSupportsReadWritePositionAndFree() throws Exception {
        Blob blob = new NetezzaBlob(new byte[] {1, 2, 3, 4});
        assertEquals(4, blob.length());
        assertArrayEquals(new byte[] {2, 3}, blob.getBytes(2, 2));
        assertEquals(3, blob.position(new byte[] {3, 4}, 1));
        blob.setBytes(2, new byte[] {9, 8});
        assertArrayEquals(new byte[] {1, 9, 8, 4}, blob.getBytes(1, 4));
        blob.truncate(2);
        assertEquals(2, blob.length());
        blob.free();
        assertThrows(SQLException.class, blob::length);
    }

    @Test
    void clobSupportsSubstringPositionAndWriters() throws Exception {
        Clob clob = new NetezzaClob("zażółć");
        assertEquals(6, clob.length());
        assertEquals("żół", clob.getSubString(3, 3));
        assertEquals(3, clob.position("żół", 1));
        StringWriter writer = new StringWriter();
        writer.write("abc");
        clob.setString(1, "abc");
        assertEquals("abc", clob.getSubString(1, 3));
        clob.truncate(2);
        assertEquals("ab", clob.getSubString(1, 10));
        clob.free();
        assertThrows(SQLException.class, clob::length);
    }

    @Test
    void sqlxmlSupportsStringStreamsAndResult() throws Exception {
        NetezzaSQLXML xml = new NetezzaSQLXML("<a/>");
        assertEquals("<a/>", xml.getString());
        xml.setString("<b>zażółć</b>");
        assertEquals("<b>zażółć</b>", new String(xml.getBinaryStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        StreamResult result = xml.setResult(StreamResult.class);
        result.getWriter().write("<c/>");
        assertEquals("<c/>", xml.getString());
        assertInstanceOf(StreamSource.class, xml.getSource(StreamSource.class));
        xml.free();
        assertThrows(SQLException.class, xml::getString);
    }
}
