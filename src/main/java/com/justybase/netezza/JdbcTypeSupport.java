/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;

/** Shared JDBC type mapping and value coercion for client-side parameter binding. */
final class JdbcTypeSupport {
    private JdbcTypeSupport() {}

    static int typeOf(Object value) {
        if (value == null) return Types.NULL;
        if (value instanceof Boolean) return Types.BOOLEAN;
        if (value instanceof Byte) return Types.TINYINT;
        if (value instanceof Short) return Types.SMALLINT;
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof Long) return Types.BIGINT;
        if (value instanceof Float) return Types.REAL;
        if (value instanceof Double) return Types.DOUBLE;
        if (value instanceof BigDecimal) return Types.NUMERIC;
        if (value instanceof byte[]) return Types.VARBINARY;
        if (value instanceof java.sql.Date || value instanceof LocalDate) return Types.DATE;
        if (value instanceof java.sql.Time || value instanceof LocalTime) return Types.TIME;
        if (value instanceof Timestamp || value instanceof LocalDateTime) return Types.TIMESTAMP;
        if (value instanceof java.util.Date) return Types.TIMESTAMP;
        if (value instanceof OffsetTime) return Types.TIME_WITH_TIMEZONE;
        if (value instanceof OffsetDateTime || value instanceof Instant) return Types.TIMESTAMP_WITH_TIMEZONE;
        if (value instanceof Blob) return Types.BLOB;
        if (value instanceof NClob) return Types.NCLOB;
        if (value instanceof Clob) return Types.CLOB;
        if (value instanceof SQLXML) return Types.SQLXML;
        if (value instanceof String) return Types.VARCHAR;
        return Types.OTHER;
    }

    static boolean isLiteralValue(Object value) {
        return value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof byte[]
                || value instanceof java.util.UUID
                || value instanceof java.util.Date
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetTime
                || value instanceof OffsetDateTime
                || value instanceof Instant
                || value instanceof Blob
                || value instanceof Clob
                || value instanceof SQLXML
                || value instanceof java.net.URL;
    }

    static String typeName(int type) {
        return switch (type) {
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.TINYINT -> "BYTEINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL -> "REAL";
            case Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            case Types.NUMERIC, Types.DECIMAL -> "NUMERIC";
            case Types.CHAR -> "CHAR";
            case Types.VARCHAR -> "VARCHAR";
            case Types.LONGVARCHAR -> "LONGVARCHAR";
            case Types.NCHAR -> "NCHAR";
            case Types.NVARCHAR -> "NVARCHAR";
            case Types.LONGNVARCHAR -> "LONGNVARCHAR";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIME_WITH_TIMEZONE -> "TIMETZ";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMPTZ";
            case Types.BINARY -> "BINARY";
            case Types.VARBINARY -> "VARBINARY";
            case Types.LONGVARBINARY -> "LONGVARBINARY";
            case Types.BLOB -> "BLOB";
            case Types.CLOB -> "CLOB";
            case Types.NCLOB -> "NCLOB";
            case Types.SQLXML -> "SQLXML";
            case Types.NULL -> "NULL";
            default -> "OTHER";
        };
    }

    static String className(int type) {
        return switch (type) {
            case Types.BOOLEAN, Types.BIT -> Boolean.class.getName();
            case Types.TINYINT -> Byte.class.getName();
            case Types.SMALLINT -> Short.class.getName();
            case Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.REAL -> Float.class.getName();
            case Types.FLOAT, Types.DOUBLE -> Double.class.getName();
            case Types.NUMERIC, Types.DECIMAL -> BigDecimal.class.getName();
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR -> String.class.getName();
            case Types.CLOB -> Clob.class.getName();
            case Types.NCLOB -> NClob.class.getName();
            case Types.SQLXML -> SQLXML.class.getName();
            case Types.DATE -> java.sql.Date.class.getName();
            case Types.TIME -> java.sql.Time.class.getName();
            case Types.TIME_WITH_TIMEZONE -> OffsetTime.class.getName();
            case Types.TIMESTAMP -> Timestamp.class.getName();
            case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.class.getName();
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> byte[].class.getName();
            case Types.BLOB -> Blob.class.getName();
            default -> String.class.getName();
        };
    }

    static boolean isSigned(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT,
                    Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    static boolean isCharacterType(int type) {
        return switch (type) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> true;
            default -> false;
        };
    }

    static boolean isBinaryType(int type) {
        return switch (type) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true;
            default -> false;
        };
    }

    static int vendorType(SQLType type) {
        return type == null || type.getVendorTypeNumber() == null ? Types.OTHER : type.getVendorTypeNumber();
    }

    /** Coerces a value to the requested JDBC target type, preserving NULL. */
    static Object coerce(Object value, int targetType, Integer scale) throws SQLException {
        if (value == null || targetType == Types.NULL) return null;
        if (targetType == Types.OTHER) return value;
        try {
            if (targetType == Types.BOOLEAN || targetType == Types.BIT) {
                if (value instanceof Boolean b) return b;
                if (value instanceof Number n) return n.intValue() != 0;
                String text = value.toString().trim();
                return "1".equals(text) || "true".equalsIgnoreCase(text) || "t".equalsIgnoreCase(text);
            }
            if (targetType == Types.TINYINT) return number(value).byteValue();
            if (targetType == Types.SMALLINT) return number(value).shortValue();
            if (targetType == Types.INTEGER) return number(value).intValue();
            if (targetType == Types.BIGINT) return number(value).longValue();
            if (targetType == Types.REAL) return number(value).floatValue();
            if (targetType == Types.FLOAT || targetType == Types.DOUBLE) return number(value).doubleValue();
            if (targetType == Types.NUMERIC || targetType == Types.DECIMAL) {
                BigDecimal decimal = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
                return scale == null ? decimal : decimal.setScale(scale, RoundingMode.HALF_UP);
            }
            if (isCharacterType(targetType)) {
                if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return value.toString();
            }
            if (targetType == Types.DATE) {
                if (value instanceof java.sql.Date d) return d;
                if (value instanceof LocalDate d) return java.sql.Date.valueOf(d);
                if (value instanceof Timestamp t) return java.sql.Date.valueOf(t.toLocalDateTime().toLocalDate());
                if (value instanceof LocalDateTime dt) return java.sql.Date.valueOf(dt.toLocalDate());
                if (value instanceof java.sql.Time || value instanceof LocalTime) return java.sql.Date.valueOf(LocalDate.of(1970, 1, 1));
                String text = value.toString().trim();
                if (isTimeOnly(text)) return java.sql.Date.valueOf(LocalDate.of(1970, 1, 1));
                return java.sql.Date.valueOf(text.substring(0, Math.min(10, text.length())));
            }
            if (targetType == Types.TIME) {
                if (value instanceof java.sql.Time t) return t;
                if (value instanceof LocalTime t) return sqlTime(t);
                if (value instanceof Timestamp t) return new java.sql.Time(t.getTime());
                if (value instanceof LocalDateTime dt) return sqlTime(dt.toLocalTime());
                if (value instanceof java.sql.Date || value instanceof LocalDate) return java.sql.Time.valueOf(LocalTime.MIDNIGHT);
                return sqlTime(parseLocalTime(value.toString()));
            }
            if (targetType == Types.TIMESTAMP) {
                if (value instanceof Timestamp t) return t;
                if (value instanceof LocalDateTime t) return Timestamp.valueOf(t);
                if (value instanceof java.sql.Date d) return Timestamp.valueOf(d.toLocalDate().atStartOfDay());
                if (value instanceof LocalDate d) return Timestamp.valueOf(d.atStartOfDay());
                if (value instanceof java.sql.Time t) return Timestamp.valueOf(LocalDate.of(1970, 1, 1).atTime(t.toLocalTime()));
                if (value instanceof LocalTime t) return Timestamp.valueOf(LocalDate.of(1970, 1, 1).atTime(t));
                if (value instanceof OffsetDateTime t) return Timestamp.from(t.toInstant());
                if (value instanceof Instant t) return Timestamp.from(t);
                String text = value.toString().trim();
                if (isTimeOnly(text)) return Timestamp.valueOf(LocalDate.of(1970, 1, 1).atTime(parseLocalTime(text)));
                text = text.replace('T', ' ');
                if (text.length() == 10) text += " 00:00:00";
                if (text.length() == 16) text += ":00";
                return Timestamp.valueOf(text);
            }
            if (targetType == Types.TIME_WITH_TIMEZONE) {
                if (value instanceof OffsetTime t) return t;
                if (value instanceof java.sql.Time t) return t.toLocalTime().atOffset(ZoneOffset.UTC);
                if (value instanceof LocalTime t) return t.atOffset(ZoneOffset.UTC);
                if (value instanceof OffsetDateTime t) return t.toOffsetTime();
                return parseOffsetTime(value.toString());
            }
            if (targetType == Types.TIMESTAMP_WITH_TIMEZONE) {
                if (value instanceof OffsetDateTime t) return t;
                if (value instanceof Instant t) return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
                if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
                if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
                if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
                if (value instanceof LocalDate d) return d.atStartOfDay().atOffset(ZoneOffset.UTC);
                return parseOffsetDateTime(value.toString());
            }
            if (isBinaryType(targetType)) {
                if (value instanceof byte[] bytes) return bytes;
                if (value instanceof Blob blob) return blob.getBytes(1, (int) blob.length());
                return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (targetType == Types.BLOB) {
                if (value instanceof Blob blob) return blob;
                if (value instanceof byte[] bytes) return new NetezzaBlob(bytes);
                return new NetezzaBlob(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (targetType == Types.CLOB) {
                if (value instanceof Clob clob) return clob;
                return new NetezzaClob(value.toString());
            }
            if (targetType == Types.NCLOB) {
                if (value instanceof NClob clob) return clob;
                return new NetezzaNClob(value.toString());
            }
            if (targetType == Types.SQLXML) {
                if (value instanceof SQLXML xml) return xml;
                return new NetezzaSQLXML(value.toString());
            }
        } catch (RuntimeException e) {
            throw new SQLException("Cannot convert value to JDBC type " + typeName(targetType), "22018", e);
        }
        throw new SQLFeatureNotSupportedException("setObject target type " + typeName(targetType) + " is not supported", "0A000");
    }

    /** Converts a stored value to an explicitly requested Java class. */
    static Object convert(Object value, Class<?> target) throws SQLException {
        if (value == null) return null;
        if (target == null || target == Object.class || target.isInstance(value)) return value;
        if (target == String.class) {
            if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (value instanceof Clob clob) return clob.getSubString(1, (int) Math.min(Integer.MAX_VALUE, clob.length()));
            return value.toString();
        }
        if (target == java.net.URL.class) {
            try {
                return new java.net.URL(value.toString());
            } catch (java.net.MalformedURLException e) {
                throw new SQLException("Cannot convert value to URL: " + value, "22018", e);
            }
        }
        if (target == Integer.class || target == int.class) return coerce(value, Types.INTEGER, null);
        if (target == Long.class || target == long.class) return coerce(value, Types.BIGINT, null);
        if (target == Short.class || target == short.class) return coerce(value, Types.SMALLINT, null);
        if (target == Byte.class || target == byte.class) return coerce(value, Types.TINYINT, null);
        if (target == Double.class || target == double.class) return coerce(value, Types.DOUBLE, null);
        if (target == Float.class || target == float.class) return coerce(value, Types.REAL, null);
        if (target == Boolean.class || target == boolean.class) return coerce(value, Types.BOOLEAN, null);
        if (target == BigDecimal.class) return coerce(value, Types.NUMERIC, null);
        if (target == byte[].class) return coerce(value, Types.VARBINARY, null);
        if (target == java.sql.Date.class) return coerce(value, Types.DATE, null);
        if (target == java.sql.Time.class) return coerce(value, Types.TIME, null);
        if (target == Timestamp.class) return coerce(value, Types.TIMESTAMP, null);
        if (target == LocalDate.class) {
            if (value instanceof java.sql.Date date) return date.toLocalDate();
            if (value instanceof LocalDate date) return date;
            if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
            if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
            if (value instanceof java.sql.Time || value instanceof LocalTime) return LocalDate.of(1970, 1, 1);
            String text = value.toString().trim();
            if (isTimeOnly(text)) return LocalDate.of(1970, 1, 1);
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        }
        if (target == LocalTime.class) {
            if (value instanceof java.sql.Time time) return time.toLocalTime();
            if (value instanceof LocalTime time) return time;
            if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalTime();
            if (value instanceof LocalDateTime dateTime) return dateTime.toLocalTime();
            if (value instanceof OffsetTime offsetTime) return offsetTime.toLocalTime();
            return parseLocalTime(value.toString());
        }
        if (target == LocalDateTime.class) {
            if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
            if (value instanceof LocalDateTime dateTime) return dateTime;
            if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay();
            if (value instanceof LocalDate date) return date.atStartOfDay();
            if (value instanceof java.sql.Time time) return LocalDate.of(1970, 1, 1).atTime(time.toLocalTime());
            if (value instanceof LocalTime time) return LocalDate.of(1970, 1, 1).atTime(time);
            if (value instanceof OffsetDateTime dateTime) return dateTime.toLocalDateTime();
            String text = value.toString().trim();
            if (isTimeOnly(text)) return LocalDate.of(1970, 1, 1).atTime(parseLocalTime(text));
            text = text.replace('T', ' ');
            if (text.length() == 10) text += " 00:00:00";
            if (text.length() == 16) text += ":00";
            return LocalDateTime.parse(text.replace(' ', 'T'));
        }
        if (target == OffsetTime.class) return coerce(value, Types.TIME_WITH_TIMEZONE, null);
        if (target == OffsetDateTime.class) return coerce(value, Types.TIMESTAMP_WITH_TIMEZONE, null);
        if (target == Instant.class) {
            if (value instanceof Timestamp timestamp) return timestamp.toInstant();
            if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
            if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
            if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            if (value instanceof LocalDate date) return date.atStartOfDay().toInstant(ZoneOffset.UTC);
            if (value instanceof java.sql.Time time) return LocalDate.of(1970, 1, 1).atTime(time.toLocalTime()).toInstant(ZoneOffset.UTC);
            if (value instanceof LocalTime time) return LocalDate.of(1970, 1, 1).atTime(time).toInstant(ZoneOffset.UTC);
            if (value instanceof Instant instant) return instant;
            String text = value.toString().trim();
            if (isTimeOnly(text)) return LocalDate.of(1970, 1, 1).atTime(parseLocalTime(text)).toInstant(ZoneOffset.UTC);
            if (text.indexOf(':') < 0) return LocalDate.parse(text.substring(0, Math.min(10, text.length()))).atStartOfDay().toInstant(ZoneOffset.UTC);
            return OffsetDateTime.parse(normalizeOffset(text.replace(' ', 'T'))).toInstant();
        }
        if (target == Blob.class) {
            if (value instanceof Blob blob) return blob;
            return new NetezzaBlob(coerce(value, Types.VARBINARY, null) instanceof byte[] bytes ? bytes : value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (target == Clob.class) {
            if (value instanceof Clob clob) return clob;
            return new NetezzaClob(value.toString());
        }
        if (target == NClob.class) {
            if (value instanceof NClob clob) return clob;
            return new NetezzaNClob(value.toString());
        }
        if (target == SQLXML.class) {
            if (value instanceof SQLXML xml) return xml;
            return new NetezzaSQLXML(value.toString());
        }
        if (target == InputStream.class) {
            byte[] bytes = coerce(value, Types.VARBINARY, null) instanceof byte[] data ? data : value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new ByteArrayInputStream(bytes);
        }
        if (target == Reader.class) return new StringReader(value.toString());
        throw new SQLException("Unsupported conversion to " + target.getName(), "22018");
    }

    static String textValue(Object value)throws SQLException{
        if(value==null)return null;
        if(value instanceof byte[] bytes)return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        if(value instanceof Clob clob)return clob.getSubString(1,(int)Math.min(Integer.MAX_VALUE,clob.length()));
        if(value instanceof LocalDateTime t)return timestampText(t);
        if(value instanceof LocalTime t)return timeText(t);
        if(value instanceof OffsetTime t)return offsetTimeText(t);
        return value.toString();
    }
    static String timestampText(LocalDateTime t){String s=t.toString().replace('T',' ');if(s.length()==16)s+=":00";return s;}
    static String timeText(LocalTime t){String s=t.toString();if(s.length()==5)s+=":00";return s;}
    static String offsetTimeText(OffsetTime t){
        LocalTime time=t.toLocalTime().withNano((t.getNano()/1000)*1000);
        int total=t.getOffset().getTotalSeconds(),abs=Math.abs(total);
        return timeText(time)+(total<0?"-":"+")+String.format(java.util.Locale.ROOT,"%02d:%02d",abs/3600,(abs%3600)/60);
    }

    private static java.sql.Time sqlTime(LocalTime value) {
        java.sql.Time time = java.sql.Time.valueOf(value.withNano(0));
        if (value.getNano() != 0) time.setTime(time.getTime() + value.getNano() / 1_000_000);
        return time;
    }

    private static boolean isTimeOnly(String value) {
        int colon = value.indexOf(':');
        if (colon < 0) return false;
        int dash = value.indexOf('-');
        return dash < 0 || dash > colon;
    }

    private static LocalTime parseLocalTime(String text) {
        String value = text.trim();
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(space + 1);
        else if (value.indexOf('T') >= 0) value = value.substring(value.indexOf('T') + 1);
        int offset = offsetStart(value);
        if (offset > 0) value = value.substring(0, offset);
        return LocalTime.parse(value);
    }

    private static OffsetTime parseOffsetTime(String text) {
        String value = text.trim();
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(space + 1);
        else if (value.indexOf('T') >= 0) value = value.substring(value.indexOf('T') + 1);
        if (offsetStart(value) < 0) return LocalTime.parse(value).atOffset(ZoneOffset.UTC);
        return OffsetTime.parse(normalizeOffset(value));
    }

    private static OffsetDateTime parseOffsetDateTime(String text) {
        String value = text.trim().replace(' ', 'T');
        if (offsetStart(value) < 0) return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(normalizeOffset(value));
    }

    private static String normalizeOffset(String value) {
        if (value.endsWith("Z") || value.endsWith("z"))
            return value.substring(0, value.length() - 1) + "+00:00";
        if (value.matches(".*[+-]\\d{2}$")) return value + ":00";
        if (value.matches(".*[+-]\\d{4}$"))
            return value.substring(0, value.length() - 2) + ":" + value.substring(value.length() - 2);
        return value;
    }

    private static int offsetStart(String value) {
        for (int i = value.length() - 1; i > 0; i--) {
            char c = value.charAt(i);
            if (c == '+' || c == '-' || c == 'Z' || c == 'z') return i;
            if (c == ':' || c == '.' || Character.isDigit(c)) continue;
            break;
        }
        return -1;
    }

    private static Number number(Object value) {
        if (value instanceof Number n) return n;
        return new BigDecimal(value.toString().trim());
    }
}
