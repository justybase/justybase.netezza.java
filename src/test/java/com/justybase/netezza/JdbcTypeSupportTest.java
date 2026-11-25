package com.justybase.netezza;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC type mapping used by ParameterMetaData and client-side binding. */
class JdbcTypeSupportTest {
    @Test
    void mapsJavaValuesToJdbcTypes() {
        assertEquals(Types.BOOLEAN, JdbcTypeSupport.typeOf(true));
        assertEquals(Types.INTEGER, JdbcTypeSupport.typeOf(1));
        assertEquals(Types.BIGINT, JdbcTypeSupport.typeOf(1L));
        assertEquals(Types.NUMERIC, JdbcTypeSupport.typeOf(new BigDecimal("1.2")));
        assertEquals(Types.DATE, JdbcTypeSupport.typeOf(LocalDate.of(2024, 1, 1)));
        assertEquals(Types.TIMESTAMP, JdbcTypeSupport.typeOf(LocalDateTime.of(2024, 1, 1, 12, 0)));
        assertEquals(Types.VARBINARY, JdbcTypeSupport.typeOf(new byte[] {1}));
        assertEquals(Types.VARCHAR, JdbcTypeSupport.typeOf("x"));
        assertEquals(Types.TIMESTAMP, JdbcTypeSupport.typeOf(new java.util.Date(0)));
        assertEquals(Types.OTHER, JdbcTypeSupport.typeOf(new Object()));
    }

    @Test
    void exposesTypeNamesClassesAndSignedness() {
        assertEquals("INTEGER", JdbcTypeSupport.typeName(Types.INTEGER));
        assertEquals("VARCHAR", JdbcTypeSupport.typeName(Types.VARCHAR));
        assertEquals(Integer.class.getName(), JdbcTypeSupport.className(Types.INTEGER));
        assertEquals(java.sql.Blob.class.getName(), JdbcTypeSupport.className(Types.BLOB));
        assertEquals(java.sql.Clob.class.getName(), JdbcTypeSupport.className(Types.CLOB));
        assertEquals(java.sql.NClob.class.getName(), JdbcTypeSupport.className(Types.NCLOB));
        assertEquals(java.sql.SQLXML.class.getName(), JdbcTypeSupport.className(Types.SQLXML));
        assertEquals(OffsetTime.class.getName(), JdbcTypeSupport.className(Types.TIME_WITH_TIMEZONE));
        assertEquals(OffsetDateTime.class.getName(), JdbcTypeSupport.className(Types.TIMESTAMP_WITH_TIMEZONE));
        assertEquals(String.class.getName(), JdbcTypeSupport.className(Types.OTHER));
        assertTrue(JdbcTypeSupport.isSigned(Types.NUMERIC));
        assertFalse(JdbcTypeSupport.isSigned(Types.VARCHAR));
    }

    @Test
    void coercesStringsNumbersDatesAndBinaryValues() throws Exception {
        assertEquals(42, JdbcTypeSupport.coerce("42", Types.INTEGER, null));
        assertEquals(new BigDecimal("1.24"), JdbcTypeSupport.coerce(new BigDecimal("1.235"), Types.NUMERIC, 2));
        assertEquals(java.sql.Date.valueOf("2024-02-29"), JdbcTypeSupport.coerce("2024-02-29", Types.DATE, null));
        assertEquals("123", JdbcTypeSupport.coerce(123, Types.VARCHAR, null));
        assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (byte[]) JdbcTypeSupport.coerce("abc", Types.VARBINARY, null));
        assertNull(JdbcTypeSupport.coerce(null, Types.INTEGER, null));
    }

    @Test
    void convertsExplicitClasses() throws Exception {
        assertEquals(LocalDate.of(2024, 2, 29),
                JdbcTypeSupport.convert(java.sql.Date.valueOf("2024-02-29"), LocalDate.class));
        assertEquals("42", JdbcTypeSupport.convert(42, String.class));
        assertInstanceOf(NetezzaBlob.class, JdbcTypeSupport.convert(new byte[] {1, 2}, java.sql.Blob.class));
        assertInstanceOf(NetezzaClob.class, JdbcTypeSupport.convert("text", java.sql.Clob.class));
    }

    @Test
    void convertsTemporalValuesAcrossRelatedTypes() throws Exception {
        Timestamp timestamp = Timestamp.valueOf("2024-01-02 03:04:05.123456");
        assertEquals(LocalDate.of(2024, 1, 2), JdbcTypeSupport.convert(timestamp, LocalDate.class));
        assertEquals(LocalTime.of(3, 4, 5, 123_456_000), JdbcTypeSupport.convert(timestamp, LocalTime.class));
        java.sql.Time convertedTime = (java.sql.Time) JdbcTypeSupport.convert(timestamp, java.sql.Time.class);
        assertEquals("03:04:05", convertedTime.toString());
        assertEquals(123, convertedTime.getTime() % 1000);
        assertEquals(Timestamp.valueOf("2024-01-02 00:00:00"),
                JdbcTypeSupport.convert(java.sql.Date.valueOf("2024-01-02"), Timestamp.class));
        assertEquals(LocalDateTime.of(2024, 1, 2, 0, 0),
                JdbcTypeSupport.convert(java.sql.Date.valueOf("2024-01-02"), LocalDateTime.class));
        assertEquals(java.sql.Date.valueOf("1970-01-01"),
                JdbcTypeSupport.convert(java.sql.Time.valueOf("03:04:05"), java.sql.Date.class));
        assertEquals(Timestamp.valueOf("1970-01-01 03:04:05"),
                JdbcTypeSupport.convert(java.sql.Time.valueOf("03:04:05"), Timestamp.class));
        assertEquals(java.sql.Date.valueOf("1970-01-01"),
                JdbcTypeSupport.convert("03:04:05+02:00", java.sql.Date.class));
        assertEquals(Timestamp.valueOf("1970-01-01 03:04:05"),
                JdbcTypeSupport.convert("03:04:05+02:00", Timestamp.class));
        assertEquals(LocalDate.of(1970, 1, 1),
                JdbcTypeSupport.convert("03:04:05+02:00", LocalDate.class));
        assertEquals(LocalDateTime.of(1970, 1, 1, 3, 4, 5),
                JdbcTypeSupport.convert("03:04:05+02:00", LocalDateTime.class));
    }

    @Test
    void convertsOffsetsBooleanAndNumericClasses() throws Exception {
        OffsetTime time = OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(2));
        assertEquals(time, JdbcTypeSupport.convert("03:04:05+02", OffsetTime.class));
        assertEquals(time, JdbcTypeSupport.convert("03:04:05+02:00", OffsetTime.class));
        assertEquals(LocalTime.of(3, 4, 5), JdbcTypeSupport.convert("03:04:05+02", LocalTime.class));
        OffsetDateTime dateTime = OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2));
        assertEquals(dateTime, JdbcTypeSupport.convert("2024-01-02 03:04:05+02", OffsetDateTime.class));
        assertEquals(dateTime.toInstant(), JdbcTypeSupport.convert("2024-01-02 03:04:05+02", java.time.Instant.class));
        assertEquals(java.time.Instant.parse("2024-01-02T00:00:00Z"),
                JdbcTypeSupport.convert(java.sql.Date.valueOf("2024-01-02"), java.time.Instant.class));
        assertEquals(java.time.Instant.parse("1970-01-01T03:04:05Z"),
                JdbcTypeSupport.convert(java.sql.Time.valueOf("03:04:05"), java.time.Instant.class));
        assertEquals(Boolean.TRUE, JdbcTypeSupport.convert("1", Boolean.class));
        assertEquals(Boolean.TRUE, JdbcTypeSupport.convert("t", Boolean.class));
        assertEquals(42, JdbcTypeSupport.convert(new BigDecimal("42.75"), Integer.class));
    }

    @Test
    void rejectsUnknownTargetTypes() {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> JdbcTypeSupport.coerce("x", Types.ARRAY, null));
        assertEquals("0A000", error.getSQLState());
    }
}
