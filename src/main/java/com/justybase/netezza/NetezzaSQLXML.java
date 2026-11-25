/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import javax.xml.transform.Source;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/** In-memory SQLXML value with string and stream access. */
final class NetezzaSQLXML implements SQLXML {
    private String data;
    private StringWriter resultWriter;
    private boolean freed;

    NetezzaSQLXML(String data) {
        this.data = data == null ? "" : data;
    }

    private void check() throws SQLException {
        if (freed) throw new SQLException("SQLXML has been freed", "HY010");
    }

    private String text() {
        return resultWriter == null ? data : resultWriter.toString();
    }

    @Override public void free() {
        freed = true;
        data = null;
        resultWriter = null;
    }

    @Override public InputStream getBinaryStream() throws SQLException {
        check();
        return new ByteArrayInputStream(text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override public OutputStream setBinaryStream() throws SQLException {
        check();
        return new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                super.close();
                data = new String(toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                resultWriter = null;
            }
        };
    }

    @Override public Reader getCharacterStream() throws SQLException {
        check();
        return new StringReader(text());
    }

    @Override public Writer setCharacterStream() throws SQLException {
        check();
        return new StringWriter() {
            @Override public void close() throws IOException {
                super.close();
                data = toString();
                resultWriter = null;
            }
        };
    }

    @Override public String getString() throws SQLException {
        check();
        return text();
    }

    @Override public void setString(String value) throws SQLException {
        check();
        if (value == null) throw new SQLException("SQLXML value must not be null", "HY009");
        data = value;
        resultWriter = null;
    }

    @SuppressWarnings("unchecked")
    @Override public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        check();
        if (sourceClass == null || sourceClass.isAssignableFrom(StreamSource.class)) {
            StreamSource source = new StreamSource(new StringReader(text()));
            return sourceClass == null ? (T) source : sourceClass.cast(source);
        }
        throw new SQLFeatureNotSupportedException("SQLXML source " + sourceClass.getName() + " is not supported", "0A000");
    }

    @SuppressWarnings("unchecked")
    @Override public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        check();
        if (resultClass == null || resultClass.isAssignableFrom(StreamResult.class)) {
            resultWriter = new StringWriter();
            StreamResult result = new StreamResult(resultWriter);
            return resultClass == null ? (T) result : resultClass.cast(result);
        }
        throw new SQLFeatureNotSupportedException("SQLXML result " + resultClass.getName() + " is not supported", "0A000");
    }

}
