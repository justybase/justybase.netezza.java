/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

final class NetezzaStatement implements InvocationHandler {
    final NetezzaConnection connection; final String template; final boolean prepared; final boolean callable;
    final Statement proxy; final Map<Integer,Object> params=new HashMap<>(); final Map<Integer,Integer> paramTypes=new HashMap<>();
    final Set<Integer> outParameters=new TreeSet<>(); final Map<Integer,Integer> outTypes=new HashMap<>();
    /** Prepared batches store the processed SQL template; plain statements store the final SQL. */
    final List<String> batch=new ArrayList<>();
    final List<Object[]> batchValues=new ArrayList<>();
    int bulkSequence;
    private int parameterCountCache=-1;
    private String processedTemplateCache;
    private boolean processedTemplateCachedEscape;
    QueryResult cursor; SQLWarning failedWarnings; ResultSetMetaData preparedMetadata;
    int queryTimeout,fetchSize,maxRows,maxFieldSize=32768,updateCount=-1; boolean closed,poolable,closeOnCompletion,escapeProcessing=true,executed;
    long operationSequence, activeOperation;
    static Statement create(NetezzaConnection c,String sql){NetezzaStatement h=new NetezzaStatement(c,sql,false);c.lastStatement=h;return h.proxy;}
    static CallableStatement createCall(NetezzaConnection c,String sql){NetezzaStatement h=new NetezzaStatement(c,sql,true);c.lastStatement=h;return (CallableStatement)h.proxy;}
    NetezzaStatement(NetezzaConnection c,String sql,boolean callable){
        connection=c;template=sql;prepared=sql!=null;this.callable=callable;
        Class<?> type=callable?CallableStatement.class:prepared?PreparedStatement.class:Statement.class;
        proxy=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{type},this);
        c.register(this);
    }
    private String sql(Object[] a)throws SQLException{
        if(prepared){
            String source=escapeProcessing?JdbcEscapeProcessor.process(template):template;
            return substitute(source,params);
        }
        if(a==null||a.length==0||!(a[0] instanceof String s))throw new SQLException("SQL text is required");
        return escapeProcessing?JdbcEscapeProcessor.process(s):s;
    }
    private boolean executeSql(String sql)throws SQLException{return executeSql(sql,null);}
    private boolean executeSql(String sql,BulkInsertData bulkData)throws SQLException{
        if(cursor!=null){cursor.finish();cursor=null;}
        executed=false;
        failedWarnings=null;
        long warningStart=connection.warningSequence();
        long token=++operationSequence;
        activeOperation=token;
        try{
            cursor=connection.execute(sql,queryTimeout,token,bulkData);
            cursor.statement=this;
            connection.lastStatement=this;
            cursor.maxRows=maxRows;
            updateCount=cursor.initialUpdateCount;
            executed=true;
            return cursor.hasRows;
        }catch(SQLException e){
            failedWarnings=connection.warningsSince(warningStart);
            activeOperation=0;
            throw e;
        }
    }
    @Override public Object invoke(Object p,Method m,Object[] a)throws Throwable{
        String n=m.getName();if(n.equals("toString"))return "NetezzaStatement["+template+"]";if(n.equals("hashCode"))return System.identityHashCode(p);if(n.equals("equals"))return p==a[0];
        if(n.equals("close")){close();return null;} if(n.equals("isClosed"))return closed; check();
        if(n.startsWith("set")&&prepared&&a!=null&&a.length>=2&&a[0] instanceof Integer ix){
            if(ix<1)throw new SQLException("Parameter index out of range: "+ix,"07009");
            if(n.equals("setNull")){
                params.put(ix,null);
                paramTypes.put(ix,a.length>1&&a[1] instanceof Integer type?type:Types.NULL);
                return null;
            }
            if(n.equals("setObject")){
                Object value=a[1];
                if(!JdbcTypeSupport.isLiteralValue(value))
                    throw new SQLFeatureNotSupportedException("Unsupported setObject value type: "
                            +value.getClass().getName()+"; use a typed setter","0A000");
                int target=JdbcTypeSupport.typeOf(value);
                Integer scale=null;
                if(a.length>=3){
                    if(a[2] instanceof Integer type)target=type;
                    else if(a[2] instanceof SQLType type)target=JdbcTypeSupport.vendorType(type);
                    else throw new SQLException("Invalid setObject target type","HY092");
                }
                if(a.length>=4){
                    if(a[3] instanceof Integer valueScale)scale=valueScale;
                    else throw new SQLException("Invalid setObject scale","HY092");
                }
                Object coerced=JdbcTypeSupport.coerce(value,target,scale);
                if(coerced instanceof java.time.OffsetDateTime||coerced instanceof java.time.Instant)
                    throw new SQLFeatureNotSupportedException("Netezza has no TIMESTAMP WITH TIME ZONE literal; use LocalDateTime or Timestamp","0A000");
                params.put(ix,coerced);
                paramTypes.put(ix,target);
                return null;
            }
            if(n.equals("setAsciiStream")){
                byte[] bytes=readBytes((InputStream)a[1],streamLength(a));
                params.put(ix,bytes==null?null:new String(bytes,StandardCharsets.US_ASCII));paramTypes.put(ix,Types.VARCHAR);return null;
            }
            if(n.equals("setUnicodeStream")){
                byte[] bytes=readBytes((InputStream)a[1],streamLength(a));
                params.put(ix,bytes==null?null:new String(bytes,StandardCharsets.UTF_8));paramTypes.put(ix,Types.VARCHAR);return null;
            }
            if(n.equals("setBinaryStream")){
                params.put(ix,readBytes((InputStream)a[1],streamLength(a)));paramTypes.put(ix,Types.VARBINARY);return null;
            }
            if(n.equals("setCharacterStream")||n.equals("setNCharacterStream")){
                params.put(ix,readText((Reader)a[1],streamLength(a)));paramTypes.put(ix,n.equals("setNCharacterStream")?Types.NVARCHAR:Types.VARCHAR);return null;
            }
            if(n.equals("setBlob")){
                byte[] bytes;
                if(a[1] instanceof Blob blob)bytes=blob==null?null:blob.getBytes(1,(int)Math.min(Integer.MAX_VALUE,blob.length()));
                else bytes=readBytes((InputStream)a[1],streamLength(a));
                params.put(ix,bytes);paramTypes.put(ix,Types.BLOB);return null;
            }
            if(n.equals("setClob")||n.equals("setNClob")){
                String text;
                if(a[1] instanceof Clob clob)text=clob==null?null:clob.getSubString(1,(int)Math.min(Integer.MAX_VALUE,clob.length()));
                else text=readText((Reader)a[1],streamLength(a));
                params.put(ix,text);paramTypes.put(ix,n.equals("setNClob")?Types.NCLOB:Types.CLOB);return null;
            }
            if(n.equals("setSQLXML")){
                SQLXML xml=(SQLXML)a[1];
                params.put(ix,xml==null?null:xml.getString());paramTypes.put(ix,Types.SQLXML);return null;
            }
            int type=switch(n){
                case "setBoolean"->Types.BOOLEAN;case "setByte"->Types.TINYINT;case "setShort"->Types.SMALLINT;
                case "setInt"->Types.INTEGER;case "setLong"->Types.BIGINT;case "setFloat"->Types.REAL;
                case "setDouble"->Types.DOUBLE;case "setBigDecimal"->Types.NUMERIC;case "setString"->Types.VARCHAR;
                case "setNString"->Types.NVARCHAR;case "setBytes"->Types.VARBINARY;case "setDate"->Types.DATE;
                case "setTime"->Types.TIME;case "setTimestamp"->Types.TIMESTAMP;case "setURL"->Types.VARCHAR;
                default->Integer.MIN_VALUE;
            };
            if(type==Integer.MIN_VALUE)throw new SQLFeatureNotSupportedException("PreparedStatement."+n+" is not supported", "0A000");
            Object value=a[1];
            if(n.equals("setURL"))value=value==null?null:value.toString();
            if(n.equals("setDate")&&a.length>2&&a[2] instanceof Calendar calendar)value=dateInCalendar((java.sql.Date)value,calendar);
            if(n.equals("setTime")&&a.length>2&&a[2] instanceof Calendar calendar)value=timeInCalendar((Time)value,calendar);
            if(n.equals("setTimestamp")&&a.length>2&&a[2] instanceof Calendar calendar)value=timestampInCalendar((Timestamp)value,calendar);
            params.put(ix,value);paramTypes.put(ix,type);return null;
        }
        return switch(n){
            case "executeQuery" -> {if(executeSql(sql(a))==false)throw new SQLException("Statement did not produce a result set","02000");yield cursor.resultSet();}
            case "execute" -> executeSql(sql(a));
            case "executeUpdate" -> {executeSql(sql(a));if(cursor.hasRows){cursor.finish();throw new SQLException("Query returned rows; use executeQuery");}yield Math.max(updateCount,0);}
            case "executeLargeUpdate" -> {executeSql(sql(a));if(cursor.hasRows){cursor.finish();throw new SQLException("Query returned rows; use executeQuery");}yield (long)Math.max(updateCount,0);}
            case "getResultSet" -> cursor==null||!cursor.hasRows?null:cursor.resultSet();
            case "getUpdateCount" -> cursor!=null&&cursor.hasRows?-1:updateCount;
            case "getLargeUpdateCount" -> (long)(cursor!=null&&cursor.hasRows?-1:updateCount);
            case "getMoreResults" -> {
                int mode=a==null||a.length==0?Statement.CLOSE_CURRENT_RESULT:(int)a[0];
                if(mode!=Statement.CLOSE_CURRENT_RESULT&&mode!=Statement.CLOSE_ALL_RESULTS&&mode!=Statement.KEEP_CURRENT_RESULT)
                    throw new SQLException("Invalid getMoreResults disposition","HY092");
                if(mode==Statement.KEEP_CURRENT_RESULT)
                    throw new SQLFeatureNotSupportedException("Keeping multiple open result sets is not supported","0A000");
                boolean more=cursor!=null&&cursor.nextResult();
                updateCount=cursor==null?-1:cursor.updateCount;
                yield more;
            }
            case "getConnection" -> connection.proxy;
            case "setQueryTimeout" -> {
                int seconds=(int)a[0];
                if(seconds<0)throw new SQLException("Query timeout must not be negative","HY092");
                queryTimeout=seconds;
                yield null;
            }
            case "getQueryTimeout" -> queryTimeout;
            case "setFetchSize" -> {fetchSize=(int)a[0];yield null;}
            case "getFetchSize" -> fetchSize;
            case "setFetchDirection" -> {int d=(int)a[0];if(d!=ResultSet.FETCH_FORWARD)throw new SQLFeatureNotSupportedException("Netezza supports only FETCH_FORWARD","0A000");yield null;}
            case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
            case "setMaxRows" -> {maxRows=(int)a[0];yield null;}
            case "getMaxRows" -> maxRows;
            case "getMaxFieldSize" -> maxFieldSize;
            case "setMaxFieldSize" -> {
                int size=(int)a[0];
                if(size<0)throw new SQLException("Max field size must not be negative","HY092");
                maxFieldSize=size;
                yield null;
            }
            case "setLargeMaxRows" -> {maxRows=(int)Math.min((long)a[0],Integer.MAX_VALUE);yield null;}
            case "getLargeMaxRows" -> (long)maxRows;
            case "cancel" -> {connection.cancel(activeOperation);yield null;}
            case "clearParameters" -> {params.clear();paramTypes.clear();yield null;}
            case "clearBatch" -> {batch.clear();batchValues.clear();yield null;}
            case "addBatch" -> {
                if(prepared){
                    // Snapshot the values and defer SQL rendering. The bulk path never
                    // needs the rendered literals, so rendering them here would be pure
                    // waste; the row-by-row fallback renders on demand from this snapshot.
                    int count=templateParameterCount();
                    for(int i=1;i<=count;i++)if(!params.containsKey(i))
                        throw new SQLException("No value assigned for parameter "+i,"07001");
                    if(params.size()>count)
                        throw new SQLException("Too many parameters set: expected "+count+", got "+params.size(),"07001");
                    Object[] values=new Object[count];
                    for(int i=1;i<=count;i++)values[i-1]=snapshotValue(params.get(i));
                    batch.add(processedTemplate());
                    batchValues.add(values);
                }else{
                    String text=(String)a[0];
                    batch.add(escapeProcessing?JdbcEscapeProcessor.process(text):text);
                    batchValues.add(null);
                }
                yield null;
            }
            case "executeBatch" -> executeBatch();
            case "executeLargeBatch" -> {int[] r=executeBatch();long[] l=new long[r.length];for(int i=0;i<r.length;i++)l[i]=r[i];yield l;}
            case "clearWarnings" -> {if(cursor!=null)cursor.clearWarnings();failedWarnings=null;yield null;}
            case "getWarnings" -> cursor!=null?cursor.warnings():failedWarnings;
            case "setCursorName" -> null;
            case "setEscapeProcessing" -> {escapeProcessing=(boolean)a[0];yield null;}
            case "setPoolable" -> {poolable=(boolean)a[0];yield null;}
            case "closeOnCompletion" -> {closeOnCompletion=true;yield null;}
            case "isPoolable" -> poolable;
            case "isCloseOnCompletion" -> closeOnCompletion;
            case "getResultSetType" -> ResultSet.TYPE_FORWARD_ONLY;
            case "getResultSetConcurrency" -> ResultSet.CONCUR_READ_ONLY;
            case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getGeneratedKeys" -> NetezzaResultSet.empty(connection.proxy);
            case "getParameterMetaData" -> parameterMetaData();
            case "getMetaData" -> preparedMetaData();
            case "registerOutParameter" -> {registerOutParameter(a);yield null;}
            case "getObject","getString","getNString","getBoolean","getByte","getShort","getInt","getLong",
                    "getFloat","getDouble","getBigDecimal","getBytes","getDate","getTime","getTimestamp",
                    "getBlob","getClob","getNClob","getSQLXML","getURL","getCharacterStream",
                    "getNCharacterStream","getAsciiStream","getBinaryStream","wasNull" -> callableGet(n,a);
            case "unwrap" -> unwrap((Class<?>)a[0]);
            case "isWrapperFor" -> ((Class<?>)a[0]).isInstance(proxy);
            default -> {
                if(m.isDefault())yield InvocationHandler.invokeDefault(p,m,a);
                throw new SQLFeatureNotSupportedException("Statement."+n+" is not supported", "0A000");
            }
        };
    }
    private void registerOutParameter(Object[] a)throws SQLException{
        if(a==null||a.length<2||!(a[0] instanceof Integer))
            throw new SQLFeatureNotSupportedException("Named CallableStatement parameters are not supported","0A000");
        int index=(Integer)a[0];
        if(index<1)throw new SQLException("Parameter index out of range: "+index,"07009");
        outParameters.add(index);
        outTypes.put(index,a[1] instanceof Integer type?type:Types.OTHER);
    }
    private int outColumn(int index)throws SQLException{
        if(!executed||cursor==null||!cursor.hasRows)
            throw new SQLException("No output parameters are available","24000");
        if(index<1||index>cursor.columns.size())
            throw new SQLException("Output parameter index out of range: "+index,"07009");
        return index-1;
    }
    private Object callableGet(String name,Object[] a)throws SQLException{
        if(name.equals("wasNull")&&(a==null||a.length==0))return cursor!=null&&cursor.lastWasNull;
        if(a==null||a.length==0||!(a[0] instanceof Integer))
            throw new SQLFeatureNotSupportedException("Named CallableStatement parameters are not supported","0A000");
        int index=(Integer)a[0];
        int column=outColumn(index);
        Calendar calendar=null;
        for(int i=1;i<a.length;i++)if(a[i] instanceof Calendar value)calendar=value;
        return switch(name){
            case "wasNull" -> cursor.lastWasNull;
            case "getObject" -> {
                Object value=cursor.value(column);
                if(a.length>=3&&a[2] instanceof Class<?> type)yield JdbcTypeSupport.convert(value,type);
                if(a.length>=2&&a[1] instanceof Class<?> type)yield JdbcTypeSupport.convert(value,type);
                yield NetezzaResultSet.jdbcObjectValue(cursor.columns.get(column),value);
            }
            case "getString","getNString" -> textValue(cursor.value(column));
            case "getBoolean" -> cursor.booleanValue(column);
            case "getByte" -> cursor.byteValue(column);
            case "getShort" -> cursor.shortValue(column);
            case "getInt" -> cursor.intValue(column);
            case "getLong" -> cursor.longValue(column);
            case "getFloat" -> cursor.floatValue(column);
            case "getDouble" -> cursor.doubleValue(column);
            case "getBigDecimal" -> {
                BigDecimal value=cursor.bigDecimalValue(column);
                if(value!=null&&a.length>1&&a[1] instanceof Integer scale)value=value.setScale(scale,RoundingMode.HALF_UP);
                yield value;
            }
            case "getBytes" -> cursor.bytesValue(column);
            case "getDate" -> calendar==null
                    ?JdbcTypeSupport.convert(cursor.value(column),java.sql.Date.class)
                    :NetezzaResultSet.dateWithCalendar(cursor.value(column),calendar);
            case "getTime" -> calendar==null
                    ?JdbcTypeSupport.convert(cursor.value(column),java.sql.Time.class)
                    :NetezzaResultSet.timeWithCalendar(cursor.value(column),calendar);
            case "getTimestamp" -> calendar==null
                    ?JdbcTypeSupport.convert(cursor.value(column),Timestamp.class)
                    :NetezzaResultSet.timestampWithCalendar(cursor.value(column),calendar);
            case "getBlob" -> {
                Object value=cursor.value(column);
                if(value==null)yield null;
                if(value instanceof Blob blob)yield blob;
                yield new NetezzaBlob(cursor.bytesValue(column));
            }
            case "getClob" -> {
                Object value=cursor.value(column);
                if(value==null)yield null;
                if(value instanceof Clob clob)yield clob;
                yield new NetezzaClob(textValue(value));
            }
            case "getNClob" -> {
                Object value=cursor.value(column);
                if(value==null)yield null;
                if(value instanceof NClob clob)yield clob;
                yield new NetezzaNClob(textValue(value));
            }
            case "getSQLXML" -> {
                Object value=cursor.value(column);
                if(value==null)yield null;
                if(value instanceof SQLXML xml)yield xml;
                yield new NetezzaSQLXML(textValue(value));
            }
            case "getURL" -> {
                String value=textValue(cursor.value(column));
                if(value==null)yield null;
                try{yield new java.net.URL(value);}catch(java.net.MalformedURLException e){throw new SQLException("Invalid URL value", "22018", e);}
            }
            case "getCharacterStream","getNCharacterStream" -> {
                String value=textValue(cursor.value(column));
                yield value==null?null:new StringReader(value);
            }
            case "getAsciiStream" -> {
                String value=textValue(cursor.value(column));
                yield value==null?null:new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
            }
            case "getBinaryStream" -> {
                byte[] value=cursor.bytesValue(column);
                yield value==null?null:new ByteArrayInputStream(value);
            }
            default -> throw new SQLFeatureNotSupportedException("CallableStatement."+name+" is not supported","0A000");
        };
    }
    private static String textValue(Object value)throws SQLException{return JdbcTypeSupport.textValue(value);}
    private ParameterMetaData parameterMetaData()throws SQLException{
        if(!prepared)throw new SQLFeatureNotSupportedException("Parameter metadata requires a prepared statement","0A000");
        int count=parameterCount(template);
        return (ParameterMetaData)Proxy.newProxyInstance(ParameterMetaData.class.getClassLoader(),new Class[]{ParameterMetaData.class},(p,m,a)->{
            String name=m.getName();
            if(name.equals("toString"))return "NetezzaParameterMetaData[count="+count+"]";
            if(name.equals("hashCode"))return System.identityHashCode(p);
            if(name.equals("equals"))return p==a[0];
            if(name.equals("getParameterCount"))return count;
            if(name.equals("unwrap")){Class<?> c=(Class<?>)a[0];if(c.isInstance(p))return p;throw new SQLException("Not a wrapper for "+c.getName());}
            if(name.equals("isWrapperFor"))return ((Class<?>)a[0]).isInstance(p);
            int index=(Integer)a[0];
            if(index<1||index>count)throw new SQLException("Parameter index out of range: "+index,"07009");
            int type=paramTypes.getOrDefault(index,Types.OTHER);
            return switch(name){
                case "isNullable"->ParameterMetaData.parameterNullableUnknown;
                case "isSigned"->JdbcTypeSupport.isSigned(type);
                case "getPrecision","getScale"->0;
                case "getParameterType"->type;
                case "getParameterTypeName"->JdbcTypeSupport.typeName(type);
                case "getParameterClassName"->JdbcTypeSupport.className(type);
                case "getParameterMode"->ParameterMetaData.parameterModeIn;
                default->throw new SQLFeatureNotSupportedException("ParameterMetaData."+name);
            };
        });
    }
    /** Best-effort metadata for a SELECT/WITH statement without executing its rows. */
    private ResultSetMetaData preparedMetaData()throws SQLException{
        if(!prepared)throw new SQLFeatureNotSupportedException("Prepared metadata requires a prepared statement","0A000");
        if(preparedMetadata!=null)return preparedMetadata;
        if(cursor!=null)return cursor.hasRows?cursor.resultSet().getMetaData():null;
        String source=escapeProcessing?JdbcEscapeProcessor.process(template):template;
        String upper=source.stripLeading().toUpperCase(Locale.ROOT);
        if(!upper.startsWith("SELECT")&&!upper.startsWith("WITH"))return null;
        int count=parameterCount(source);
        Map<Integer,Object> metadataParams=new HashMap<>();
        for(int i=1;i<=count;i++)metadataParams.put(i,params.get(i));
        String rendered;
        try{rendered=substitute(source,metadataParams);}catch(SQLException e){return null;}
        rendered=rendered.trim();
        while(rendered.endsWith(";"))rendered=rendered.substring(0,rendered.length()-1).trim();
        if(rendered.isEmpty())return null;
        try{
            QueryResult result=connection.execute("SELECT * FROM ("+rendered+") NZ_METADATA WHERE 1=0",queryTimeout,++operationSequence);
            try{preparedMetadata=result.resultSet().getMetaData();return preparedMetadata;}
            finally{result.finish();}
        }catch(SQLException e){return null;}
    }
    private static java.sql.Date dateInCalendar(java.sql.Date value,Calendar calendar){
        if(value==null)return null;
        Calendar cal=(Calendar)calendar.clone();cal.setTime(value);
        return java.sql.Date.valueOf(LocalDate.of(cal.get(Calendar.YEAR),cal.get(Calendar.MONTH)+1,cal.get(Calendar.DAY_OF_MONTH)));
    }
    private static Time timeInCalendar(Time value,Calendar calendar){
        if(value==null)return null;
        Calendar cal=(Calendar)calendar.clone();cal.setTime(value);
        return Time.valueOf(LocalTime.of(cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),cal.get(Calendar.SECOND)));
    }
    private static LocalDateTime timestampInCalendar(Timestamp value,Calendar calendar){
        if(value==null)return null;
        Calendar cal=(Calendar)calendar.clone();cal.setTimeInMillis(value.getTime());
        return LocalDateTime.of(cal.get(Calendar.YEAR),cal.get(Calendar.MONTH)+1,cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),cal.get(Calendar.SECOND),value.getNanos());
    }
    static int parameterCount(String sql){
        return SqlScanner.parameterCount(sql);
    }
    private int[] executeBatch()throws SQLException{
        try{
            if(prepared&&batch.size()>=2&&bulkInsertEnabled()){
                String source=processedTemplate();
                BulkInsertPlan plan=BulkInsertPlan.parse(source,parameterCount(source));
                if(plan!=null){
                    BulkInsertData data=BulkInsertData.create(plan.columns(),batchValues);
                    if(data!=null){
                        String marker="NZ_BULK_"+connection.protocol.pid+"_"+(bulkSequence++);
                        try{
                            executeSql(plan.sql(marker,data),data);
                        }catch(SQLException e){
                            throw new BatchUpdateException(e.getMessage(),e.getSQLState(),e.getErrorCode(),
                                    new int[0],e);
                        }
                        int[] counts=new int[batch.size()];
                        Arrays.fill(counts,Statement.SUCCESS_NO_INFO);
                        return counts;
                    }
                }
            }
            return executeBatchRows();
        }finally{
            batch.clear();
            batchValues.clear();
        }
    }
    private int[] executeBatchRows()throws SQLException{
        int[] counts=new int[batch.size()];int completed=0;
        try{
            for(int i=0;i<counts.length;i++){
                String sql=batch.get(i);
                if(prepared)sql=substitute(sql,batchValues.get(i));
                executeSql(sql);
                counts[i]=Math.max(updateCount,0);
                completed++;
            }
        }catch(SQLException e){
            throw new BatchUpdateException(e.getMessage(),e.getSQLState(),e.getErrorCode(),
                    Arrays.copyOf(counts,completed),e);
        }
        return counts;
    }
    private int templateParameterCount(){
        int count=parameterCountCache;
        if(count<0){count=parameterCount(template);parameterCountCache=count;}
        return count;
    }
    private String processedTemplate(){
        boolean escape=escapeProcessing;
        String cached=processedTemplateCache;
        if(cached==null||processedTemplateCachedEscape!=escape){
            cached=escape?JdbcEscapeProcessor.process(template):template;
            processedTemplateCache=cached;
            processedTemplateCachedEscape=escape;
        }
        return cached;
    }
    private boolean bulkInsertEnabled(){
        return Boolean.parseBoolean(connection.options.getProperty("bulkInsert","true"));
    }
    private Object unwrap(Class<?> c)throws SQLException{if(c.isInstance(proxy))return proxy;throw new SQLException("Not a wrapper for "+c.getName());}
    private void check()throws SQLException{if(closed)throw new SQLException("Statement is closed","07000");}
    private void close()throws SQLException{
        if(!closed){
            closed=true;
            try{if(cursor!=null)cursor.finish();}
            finally{connection.unregister(this);}
        }
    }
    void markClosedFromConnection(){if(cursor!=null)cursor.abort();closed=true;cursor=null;}
    /** Invoked when the last dependent result set is closed and closeOnCompletion is set. */
    void closeAfterResultSet()throws SQLException{close();}

    private static long streamLength(Object[] a){
        return a.length>=3&&a[2] instanceof Number number?number.longValue():-1;
    }
    private int maxStreamBytes(){
        try{return Math.max(0,Integer.parseInt(connection.options.getProperty("maxStreamBytes","16777216")));}
        catch(NumberFormatException e){return 16777216;}
    }
    private byte[] readBytes(InputStream input,long declared)throws SQLException{
        if(input==null)return null;
        int limit=maxStreamBytes();
        if(declared>limit)throw new SQLException("Stream exceeds maxStreamBytes ("+limit+")","54000");
        long remaining=declared>=0?declared:Long.MAX_VALUE;
        try{
            ByteArrayOutputStream output=new ByteArrayOutputStream((int)Math.min(limit,8192));
            byte[] buffer=new byte[8192];
            while(remaining>0){
                int read=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(read<0)break;
                output.write(buffer,0,read);
                remaining-=read;
                if(output.size()>limit)throw new SQLException("Stream exceeds maxStreamBytes ("+limit+")","54000");
            }
            return output.toByteArray();
        }catch(IOException e){throw new SQLException("Cannot read binary stream","HY000",e);}
    }
    private String readText(Reader reader,long declared)throws SQLException{
        if(reader==null)return null;
        int limit=maxStreamBytes();
        if(declared>limit)throw new SQLException("Stream exceeds maxStreamBytes ("+limit+")","54000");
        long remaining=declared>=0?declared:Long.MAX_VALUE;
        try{
            StringBuilder text=new StringBuilder();
            char[] buffer=new char[4096];
            while(remaining>0){
                int read=reader.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(read<0)break;
                text.append(buffer,0,read);
                remaining-=read;
                if(text.length()>limit)throw new SQLException("Stream exceeds maxStreamBytes ("+limit+")","54000");
            }
            return text.toString();
        }catch(IOException e){throw new SQLException("Cannot read character stream","HY000",e);}
    }
    static String substitute(String sql,Map<Integer,Object> values)throws SQLException{
        return SqlScanner.substitute(sql,values,index->literal(values.get(index)));
    }
    static String substitute(String sql,Object[] values)throws SQLException{
        return SqlScanner.substitute(sql,values.length,index->literal(values[index-1]));
    }
    /**
     * Captures the value as it was when {@code addBatch()} was called. Immutable
     * values are shared; mutable payloads are copied so a reused buffer cannot
     * change a row that was already added to the batch.
     */
    static Object snapshotValue(Object value)throws SQLException{
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean
                ||value instanceof Character)return value;
        if(value instanceof byte[] bytes)return bytes.clone();
        if(value instanceof Timestamp t){
            Timestamp copy=new Timestamp(t.getTime());
            copy.setNanos(t.getNanos());
            return copy;
        }
        if(value instanceof java.sql.Date d)return new java.sql.Date(d.getTime());
        if(value instanceof Time t)return new Time(t.getTime());
        if(value instanceof java.util.Date d)return new java.util.Date(d.getTime());
        if(value instanceof Blob b)return b.getBytes(1,(int)Math.min(Integer.MAX_VALUE,b.length()));
        if(value instanceof Clob c)return c.getSubString(1,(int)Math.min(Integer.MAX_VALUE,c.length()));
        if(value instanceof SQLXML x)return x.getString();
        return value;
    }
    static String literal(Object v)throws SQLException{
        if(v==null)return "NULL";if(v instanceof Boolean b)return b?"TRUE":"FALSE";if(v instanceof Number n)return n.toString();
        if(v instanceof byte[] b)return "x'"+HexFormat.of().formatHex(b)+"'";
        if(v instanceof Blob b)return "x'"+HexFormat.of().formatHex(b.getBytes(1,(int)Math.min(Integer.MAX_VALUE,b.length())))+"'";
        if(v instanceof Clob c)return quote(c.getSubString(1,(int)Math.min(Integer.MAX_VALUE,c.length())));
        if(v instanceof SQLXML x)return quote(x.getString());
        if(v instanceof java.net.URL u)return quote(u.toString());
        if(v instanceof java.time.LocalDate d)return "DATE '"+d+"'";
        if(v instanceof java.time.LocalTime t)return "TIME '"+JdbcTypeSupport.timeText(t)+"'";
        if(v instanceof java.time.LocalDateTime t)return "TIMESTAMP '"+JdbcTypeSupport.timestampText(t)+"'";
        if(v instanceof java.time.OffsetTime t)return "TIMETZ '"+JdbcTypeSupport.offsetTimeText(t)+"'";
        if(v instanceof java.time.OffsetDateTime||v instanceof java.time.Instant)
            throw new SQLFeatureNotSupportedException("Netezza has no TIMESTAMP WITH TIME ZONE literal; use LocalDateTime or Timestamp","0A000");
        if(v instanceof java.sql.Date d)return "DATE '"+d+"'";if(v instanceof java.sql.Time t)return "TIME '"+t+"'";if(v instanceof java.sql.Timestamp t)return "TIMESTAMP '"+JdbcTypeSupport.timestampText(t.toLocalDateTime())+"'";
        if(v instanceof java.util.Date d)return "TIMESTAMP '"+JdbcTypeSupport.timestampText(new java.sql.Timestamp(d.getTime()).toLocalDateTime())+"'";
        if(v instanceof CharSequence||v instanceof Character||v instanceof java.util.UUID)return quote(v.toString());
        throw new SQLFeatureNotSupportedException("Unsupported parameter value type: "+v.getClass().getName(),"0A000");
    }
    private static String quote(String value){return "'"+value.replace("'","''")+"'";}
}
