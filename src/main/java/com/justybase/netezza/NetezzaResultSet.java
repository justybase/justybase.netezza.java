/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

final class NetezzaResultSet implements InvocationHandler {
    final QueryResult q; final Connection connection; final ResultSet proxy;
    final int generation;
    boolean closed; int rowNum; int fetchSize;
    private Map<String,Integer> columnIndexes;
    private NetezzaResultSet(QueryResult q,Connection c){this.q=q;connection=c;generation=q.generation;fetchSize=q.statement==null?0:q.statement.fetchSize;proxy=(ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},this);}
    static ResultSet create(QueryResult q,Connection c){return new NetezzaResultSet(q,c).proxy;}
    static ResultSet empty(Connection c){final boolean[] closed={false};return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){case "toString"->"NetezzaResultSet[empty]";case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "next"->false;case "close"->{closed[0]=true;yield null;}case "isClosed"->closed[0];case "getStatement"->null;case "getMetaData"->Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),new Class[]{ResultSetMetaData.class},(x,mm,aa)->switch(mm.getName()){case "toString"->"NetezzaResultSetMetaData[columns=0]";case "hashCode"->System.identityHashCode(x);case "equals"->x==aa[0];case "getColumnCount"->0;default->throwUnsupported("ResultSetMetaData."+mm.getName());});case "getType"->ResultSet.TYPE_FORWARD_ONLY;case "getConcurrency"->ResultSet.CONCUR_READ_ONLY;case "getHoldability"->ResultSet.CLOSE_CURSORS_AT_COMMIT;case "unwrap"->throw new SQLException("Not a wrapper");case "isWrapperFor"->false;default->throw new SQLException("Empty result set has no current row","24000");});}
    private static Object throwUnsupported(String m)throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException(m);}
    @Override public Object invoke(Object p,Method m,Object[] a)throws Throwable{
         String n=m.getName();if(n.equals("toString"))return "NetezzaResultSet[row="+rowNum+"]";if(n.equals("hashCode"))return System.identityHashCode(p);if(n.equals("equals"))return p==a[0];
         if(n.equals("close")){close();return null;}if(n.equals("isClosed"))return closed||generation!=q.generation||q.rsClosed;check();
        return switch(n){
            case "next" -> {boolean b=q.next();rowNum=b?rowNum+1:rowNum;yield b;}
            case "getMetaData" -> meta();
            case "getStatement" -> q.statement==null?null:q.statement.proxy;
            case "getObject" -> object(a);
            case "getString","getNString" -> text(a[0]);
            case "getAsciiStream" -> asciiStream(a[0]);
            case "getBinaryStream" -> binaryStream(a[0]);
            case "getCharacterStream","getNCharacterStream" -> characterStream(a[0]);
            case "getBlob" -> blob(a[0]);
            case "getClob" -> clob(a[0]);
            case "getNClob" -> nclob(a[0]);
            case "getSQLXML" -> sqlxml(a[0]);
            case "getURL" -> url(a[0]);
            case "getUnicodeStream" -> unicodeStream(a[0]);
            case "getBoolean" -> {int i=index(a[0]);yield q.booleanValue(i);}
            case "getByte" -> {int i=index(a[0]);yield q.byteValue(i);}
            case "getShort" -> {int i=index(a[0]);yield q.shortValue(i);}
            case "getInt" -> {int i=index(a[0]);yield q.intValue(i);}
            case "getLong" -> {int i=index(a[0]);yield q.longValue(i);}
            case "getFloat" -> {int i=index(a[0]);yield q.floatValue(i);}
            case "getDouble" -> {int i=index(a[0]);yield q.doubleValue(i);}
            case "getBigDecimal" -> {
                int i=index(a[0]);BigDecimal value=q.bigDecimalValue(i);
                if(value!=null&&a.length>1&&a[1] instanceof Integer scale)value=value.setScale(scale,RoundingMode.HALF_UP);
                yield value;
            }
            case "getBytes" -> {int i=index(a[0]);yield q.bytesValue(i);}
            case "getDate" -> temporal(a[0],"date",calendar(a));case "getTime" -> temporal(a[0],"time",calendar(a));case "getTimestamp" -> temporal(a[0],"timestamp",calendar(a));
            case "wasNull" -> q.lastWasNull;
            case "findColumn" -> find((String)a[0]);
            case "getRow" -> rowNum;
            case "isBeforeFirst" -> rowNum==0&&!q.done&&!q.resultEnded;case "isAfterLast" -> rowNum>0&&(q.done||q.resultEnded);case "isFirst" -> rowNum==1;case "isLast" -> false;
            case "getType" -> ResultSet.TYPE_FORWARD_ONLY;case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;case "getFetchDirection" -> ResultSet.FETCH_FORWARD;case "setFetchDirection" -> {int d=(int)a[0];if(d!=ResultSet.FETCH_FORWARD)throw new SQLFeatureNotSupportedException("Netezza supports only FETCH_FORWARD","0A000");yield null;}case "getFetchSize" -> fetchSize;case "setFetchSize" -> {int size=(int)a[0];if(size<0)throw new SQLException("Fetch size must not be negative","HY092");fetchSize=size;yield null;}case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getCursorName" -> null;case "getWarnings" -> q.warnings();case "clearWarnings" -> {q.clearWarnings();yield null;}
            case "rowUpdated","rowInserted","rowDeleted" -> false;
            case "unwrap" -> {Class<?> c=(Class<?>)a[0];if(c.isInstance(proxy))yield proxy;throw new SQLException("Not a wrapper");}
            case "isWrapperFor" -> ((Class<?>)a[0]).isInstance(proxy);
            default -> {
                if(m.isDefault())yield InvocationHandler.invokeDefault(p,m,a);
                throw new SQLFeatureNotSupportedException("ResultSet."+n+" is not supported", "0A000");
            }
        };
    }
    private ResultSetMetaData meta(){return (ResultSetMetaData)Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),new Class[]{ResultSetMetaData.class},(p,m,a)->{
        String n=m.getName();
        if(n.equals("toString"))return "NetezzaResultSetMetaData[columns="+q.columns.size()+"]";
        if(n.equals("hashCode"))return System.identityHashCode(p);
        if(n.equals("equals"))return p==a[0];
        int i=a!=null&&a.length>0&&a[0] instanceof Integer?((Integer)a[0])-1:0;QueryResult.Column c=i>=0&&i<q.columns.size()?q.columns.get(i):null;
        return switch(n){case "getColumnCount"->q.columns.size();case "getColumnLabel","getColumnName"->c==null?"":c.name;case "getColumnType"->sqlType(c);case "getColumnTypeName"->typeName(c);case "getColumnClassName"->className(sqlType(c));case "getPrecision","getColumnDisplaySize"->precision(c);case "getScale"->scale(c);case "isNullable"->c==null||c.nullable==null?ResultSetMetaData.columnNullableUnknown:c.nullable?ResultSetMetaData.columnNullable:ResultSetMetaData.columnNoNulls;case "isAutoIncrement","isCurrency","isDefinitelyWritable","isWritable"->false;case "isReadOnly","isSearchable"->true;case "isCaseSensitive"->c!=null&&isTextType(sqlType(c));case "isSigned"->c!=null&&isNumericType(sqlType(c));case "getSchemaName","getTableName","getCatalogName"->"";case "unwrap"->throw new SQLException("Not a wrapper");case "isWrapperFor"->false;default->throw new SQLFeatureNotSupportedException("ResultSetMetaData."+n);};});}
     private static int precision(QueryResult.Column c){
         if(c==null)return 0;
         if(c.dbosType!=0)return c.dbosType==7?((c.size>>8)&0x7f):Math.max(0,c.size);
         if(c.modifier>16)return sqlType(c)==Types.NUMERIC?((c.modifier-16)>>16):(c.modifier-16);
         return Math.max(0,c.wireSize);
     }
     private static int scale(QueryResult.Column c){
         if(c==null)return 0;
         if(c.dbosType==7)return c.size&255;
         if(c.dbosType==0&&c.modifier>16&&sqlType(c)==Types.NUMERIC)return (c.modifier-16)&0xffff;
         return 0;
     }
     private static boolean isTextType(int t){return switch(t){case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR->true;default->false;};}
     private static boolean isNumericType(int t){return switch(t){case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.REAL,Types.FLOAT,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL->true;default->false;};}
     private static int sqlType(QueryResult.Column c){if(c==null)return Types.OTHER;if(c.dbosType!=0)return switch(c.dbosType){case 12->Types.BOOLEAN;case 20->Types.BIGINT;case 19->Types.SMALLINT;case 3->Types.INTEGER;case 13->Types.TINYINT;case 4->Types.REAL;case 2->Types.DOUBLE;case 7->Types.NUMERIC;case 6->Types.DATE;case 8->Types.TIME;case 11->Types.TIME_WITH_TIMEZONE;case 9->Types.TIMESTAMP;case 14,23->Types.VARBINARY;case 10->Types.OTHER;case 15->Types.CHAR;case 25->Types.NCHAR;case 16->Types.VARCHAR;case 26->Types.NVARCHAR;default->Types.VARCHAR;};if(c.oid!=0)return switch(c.oid){case 16->Types.BOOLEAN;case 20->Types.BIGINT;case 21->Types.SMALLINT;case 23->c.wireSize==1?Types.TINYINT:Types.INTEGER;case 26,28->Types.INTEGER;case 2500->Types.TINYINT;case 18,1042->Types.CHAR;case 19,25,1043->Types.VARCHAR;case 2522->Types.NCHAR;case 2530->Types.NVARCHAR;case 700->Types.REAL;case 701->Types.DOUBLE;case 1700->Types.NUMERIC;case 1082->Types.DATE;case 1083->Types.TIME;case 1114,1184->Types.TIMESTAMP;case 17,2568,2569->Types.VARBINARY;case 1186->Types.OTHER;case 1266->Types.TIME_WITH_TIMEZONE;default->Types.VARCHAR;};return Types.VARCHAR;}
     private static String typeName(QueryResult.Column c){if(c==null)return "UNKNOWN";if(c.dbosType!=0)return switch(c.dbosType){case 12->"BOOLEAN";case 20->"BIGINT";case 19->"SMALLINT";case 3->"INTEGER";case 13->"BYTEINT";case 4->"REAL";case 2->"DOUBLE";case 7->"NUMERIC";case 6->"DATE";case 8->"TIME";case 11->"TIMETZ";case 9->"TIMESTAMP";case 14,23->"VARBINARY";case 10->"INTERVAL";case 15->"CHAR";case 16->"VARCHAR";case 25->"NCHAR";case 26->"NVARCHAR";case 30->"JSON";case 31->"JSONB";case 32->"JSONPATH";case 22->"GEOMETRY";default->"VARCHAR";};if(c.oid!=0)return switch(c.oid){case 16->"BOOL";case 17->"BYTEA";case 18,1042->"CHAR";case 19,25->"TEXT";case 20->"INT8";case 21->"INT2";case 23->c.wireSize==1?"BYTEINT":"INT4";case 26,28->"INT4";case 2500->"BYTEINT";case 700->"FLOAT4";case 701->"FLOAT8";case 1043->"VARCHAR";case 1082->"DATE";case 1083->"TIME";case 1114,1184->"TIMESTAMP";case 1186->"INTERVAL";case 1266->"TIMETZ";case 1700->"NUMERIC";case 2522->"NCHAR";case 2530->"NVARCHAR";case 2568,2569->"VARBINARY";case 114->"JSON";case 3802->"JSONB";case 2950->"UUID";default->"VARCHAR";};return "VARCHAR";}
    private static String className(int t){return switch(t){case Types.BOOLEAN->Boolean.class.getName();case Types.TINYINT->Byte.class.getName();case Types.SMALLINT->Short.class.getName();case Types.INTEGER->Integer.class.getName();case Types.BIGINT->Long.class.getName();case Types.REAL->Float.class.getName();case Types.FLOAT,Types.DOUBLE->Double.class.getName();case Types.NUMERIC,Types.DECIMAL->BigDecimal.class.getName();case Types.DATE->java.sql.Date.class.getName();case Types.TIME->java.sql.Time.class.getName();case Types.TIME_WITH_TIMEZONE->java.time.OffsetTime.class.getName();case Types.TIMESTAMP->Timestamp.class.getName();case Types.TIMESTAMP_WITH_TIMEZONE->java.time.OffsetDateTime.class.getName();case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY->byte[].class.getName();case Types.BLOB->java.sql.Blob.class.getName();case Types.CLOB->java.sql.Clob.class.getName();case Types.NCLOB->java.sql.NClob.class.getName();case Types.SQLXML->java.sql.SQLXML.class.getName();default->String.class.getName();};}
    private Object value(Object key)throws SQLException{return q.value(index(key));}
    private int index(Object key)throws SQLException{int i=key instanceof Integer x?x-1:find((String)key)-1;if(i<0||i>=q.columns.size())throw new SQLException("Column index out of range: "+(i+1),"07009");return i;}
    private int find(String name)throws SQLException{
        if(name==null)throw new SQLException("Column name is null","S0022");
        Map<String,Integer> indexes=columnIndexes;
        if(indexes==null){
            indexes=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(int i=0;i<q.columns.size();i++)indexes.putIfAbsent(q.columns.get(i).name,i+1);
            columnIndexes=indexes;
        }
        Integer index=indexes.get(name);
        if(index==null)throw new SQLException("Column not found: "+name,"S0022");
        return index;
    }
    private Object object(Object[] a)throws SQLException{
        Object key=a[0];Class<?> type=null;
        for(int i=1;i<a.length;i++)if(a[i] instanceof Class<?> c)type=c;
        if(type==null)return objectValue(key);
        if(type==Blob.class)return blob(key);
        if(type==Clob.class)return clob(key);
        if(type==NClob.class)return nclob(key);
        if(type==SQLXML.class)return sqlxml(key);
        if(type==InputStream.class)return binaryStream(key);
        if(type==Reader.class)return characterStream(key);
        return JdbcTypeSupport.convert(value(key),type);
    }
    private String text(Object key)throws SQLException{
        return textOf(value(key));
    }
    private static String textOf(Object v)throws SQLException{return JdbcTypeSupport.textValue(v);}
    private static byte[] binary(Object v)throws SQLException{
        if(v==null)return null;
        if(v instanceof byte[] bytes)return bytes;
        if(v instanceof Blob blob)return blob.getBytes(1,(int)Math.min(Integer.MAX_VALUE,blob.length()));
        return v.toString().getBytes(StandardCharsets.UTF_8);
    }
    private InputStream asciiStream(Object key)throws SQLException{
        String value=text(key);
        return value==null?null:new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
    private InputStream binaryStream(Object key)throws SQLException{
        byte[] value=binary(value(key));
        return value==null?null:new ByteArrayInputStream(value);
    }
    private Reader characterStream(Object key)throws SQLException{
        String value=text(key);
        return value==null?null:new StringReader(value);
    }
    private InputStream unicodeStream(Object key)throws SQLException{
        String value=text(key);
        return value==null?null:new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    private java.net.URL url(Object key)throws SQLException{
        String value=text(key);
        if(value==null)return null;
        try{return new java.net.URL(value);}
        catch(java.net.MalformedURLException e){throw new SQLException("Invalid URL value: "+value,"22018",e);}
    }
    private Blob blob(Object key)throws SQLException{
        Object v=value(key);
        if(v==null)return null;
        if(v instanceof Blob blob)return blob;
        return new NetezzaBlob(binary(v));
    }
    private Clob clob(Object key)throws SQLException{
        Object v=value(key);
        if(v==null)return null;
        if(v instanceof Clob clob)return clob;
        return new NetezzaClob(textOf(v));
    }
    private NClob nclob(Object key)throws SQLException{
        Object v=value(key);
        if(v==null)return null;
        if(v instanceof NClob clob)return clob;
        return new NetezzaNClob(textOf(v));
    }
    private SQLXML sqlxml(Object key)throws SQLException{
        Object v=value(key);
        if(v==null)return null;
        if(v instanceof SQLXML xml)return xml;
        return new NetezzaSQLXML(textOf(v));
    }
    private static Calendar calendar(Object[] a){
        for(int i=1;i<a.length;i++)if(a[i] instanceof Calendar calendar)return calendar;
        return null;
    }
    private Object temporal(Object key,String type,Calendar calendar)throws SQLException{
        Object v=value(key);if(v==null)return null;
        try{
            if(calendar==null)return switch(type){
                case "date"->JdbcTypeSupport.convert(v,java.sql.Date.class);
                case "time"->JdbcTypeSupport.convert(v,java.sql.Time.class);
                default->JdbcTypeSupport.convert(v,Timestamp.class);
            };
            return switch(type){case "date"->dateWithCalendar(v,calendar);case "time"->timeWithCalendar(v,calendar);default->timestampWithCalendar(v,calendar);};
        }catch(Exception e){throw new SQLException("Cannot convert value to "+type+": "+v,"22007",e);}
    }
    static java.sql.Date dateWithCalendar(Object v,Calendar calendar)throws SQLException{
        LocalDate date=(LocalDate)JdbcTypeSupport.convert(v,LocalDate.class);
        Calendar cal=(Calendar)calendar.clone();cal.clear();
        cal.set(date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        return new java.sql.Date(cal.getTimeInMillis());
    }
    static Time timeWithCalendar(Object v,Calendar calendar)throws SQLException{
        LocalTime time=(LocalTime)JdbcTypeSupport.convert(v,LocalTime.class);
        Calendar cal=(Calendar)calendar.clone();cal.clear();
        cal.set(1970,Calendar.JANUARY,1,time.getHour(),time.getMinute(),time.getSecond());
        cal.set(Calendar.MILLISECOND,time.getNano()/1_000_000);
        return new Time(cal.getTimeInMillis());
    }
    static Timestamp timestampWithCalendar(Object v,Calendar calendar)throws SQLException{
        LocalDateTime dateTime=(LocalDateTime)JdbcTypeSupport.convert(v,LocalDateTime.class);
        Calendar cal=(Calendar)calendar.clone();cal.clear();
        cal.set(dateTime.getYear(),dateTime.getMonthValue()-1,dateTime.getDayOfMonth(),
                dateTime.getHour(),dateTime.getMinute(),dateTime.getSecond());
        cal.set(Calendar.MILLISECOND,dateTime.getNano()/1_000_000);
        Timestamp timestamp=new Timestamp(cal.getTimeInMillis());
        timestamp.setNanos(dateTime.getNano());
        return timestamp;
    }

    /** Converts a stored value to the JDBC class advertised by ResultSetMetaData. */
    static Object jdbcObjectValue(QueryResult.Column column,Object v)throws SQLException{
        if(v==null)return null;
        int t=sqlType(column);
        try{
            return switch(t){
                case Types.DATE->JdbcTypeSupport.convert(v,java.sql.Date.class);
                case Types.TIME->JdbcTypeSupport.convert(v,java.sql.Time.class);
                case Types.TIMESTAMP->JdbcTypeSupport.convert(v,Timestamp.class);
                case Types.TIME_WITH_TIMEZONE->JdbcTypeSupport.convert(v,java.time.OffsetTime.class);
                case Types.TIMESTAMP_WITH_TIMEZONE->JdbcTypeSupport.convert(v,java.time.OffsetDateTime.class);
                case Types.TINYINT->v instanceof Number n?Byte.valueOf(n.byteValue()):v;
                case Types.SMALLINT->v instanceof Number n?Short.valueOf(n.shortValue()):v;
                case Types.INTEGER->v instanceof Number n?Integer.valueOf(n.intValue()):v;
                case Types.BIGINT->v instanceof Number n?Long.valueOf(n.longValue()):v;
                case Types.REAL->v instanceof Number n?Float.valueOf(n.floatValue()):v;
                case Types.DOUBLE->v instanceof Number n?Double.valueOf(n.doubleValue()):v;
                case Types.NUMERIC,Types.DECIMAL->v instanceof BigDecimal b?b:new BigDecimal(v.toString());
                default->v;
            };
        }catch(RuntimeException e){return v;}
    }
    private Object objectValue(Object key)throws SQLException{
        int i=index(key);
        return jdbcObjectValue(q.columns.get(i),q.value(i));
    }

    private byte[] getBytes(int i)throws SQLException{Object v=value(i);return v==null?null:v instanceof byte[] b?b:v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
     private void check()throws SQLException{if(closed||generation!=q.generation||q.rsClosed)throw new SQLException("ResultSet is closed","24000");}
     private void close()throws SQLException{
         if(!closed){
             closed=true;
             if(generation==q.generation)q.close();
             NetezzaStatement statement=q.statement;
             if(statement!=null&&statement.closeOnCompletion)statement.closeAfterResultSet();
         }
     }

}
