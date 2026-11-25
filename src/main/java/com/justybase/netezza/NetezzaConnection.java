/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.DataOutputStream;

/** A single physical JDBC session. JDBC calls on a session are serialized. */
final class NetezzaConnection implements InvocationHandler {
    volatile Protocol protocol;
    final String host, user; final int port;
    volatile String database;
    final Properties options;
    private final String password;
    final Connection proxy;
    boolean closed, autoCommit=true, readOnly;
    int isolation=Connection.TRANSACTION_READ_COMMITTED, networkTimeout; boolean networkTimeoutSet;
    String schema;
    private String defaultSchema;
    NetezzaStatement lastStatement;
    final Set<NetezzaStatement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
    QueryResult activeResult;
    private SQLWarning firstWarning, lastWarning;
    private long warningSequence;
    private final ArrayDeque<WarningEntry> warningHistory = new ArrayDeque<>();
    private volatile NetezzaNoticeListener noticeListener;
    private final Properties clientInfo=new Properties();
    private String serverVersion;
    private int serverMajorVersion = 11, serverMinorVersion = 0;
    private static final int CANCEL_GRACE_MILLIS = 5_000;
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "netezza-jdbc-query-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long activeOperation = Long.MIN_VALUE;
    private volatile boolean operationTimedOut;
    private ScheduledFuture<?> timeoutFuture;

    private NetezzaConnection(String host,int port,String database,String user,String password,Properties options,Protocol protocol) {
        this.host=host;this.port=port;this.database=database;this.user=user;this.password=password;this.options=options;this.protocol=protocol;
        clientInfo.setProperty("ApplicationName",options.getProperty("applicationName",options.getProperty("clientApplicationName",System.getProperty("sun.java.command","NetezzaSQL"))));
        clientInfo.setProperty("ClientUser",options.getProperty("clientUser",user));
        String hostName=options.getProperty("clientHostName");
        if(hostName==null||hostName.isBlank())try{hostName=InetAddress.getLocalHost().getHostName();}catch(Exception ignored){hostName="localhost";}
        clientInfo.setProperty("ClientHostname",hostName);
        proxy=(Connection)Proxy.newProxyInstance(NetezzaConnectionExt.class.getClassLoader(),new Class[]{NetezzaConnectionExt.class},this);
    }
    static Connection open(String host,int port,String db,String user,String password,Properties p) throws SQLException {
        p.setProperty("port",Integer.toString(port));Protocol proto=new Protocol(host,port,db,user,password,p);
        NetezzaConnection c=new NetezzaConnection(host,port,db,user,password,p,proto);
        try {
            SessionInfo info=c.initializeSession(proto);
            c.schema=info.schema();
            c.defaultSchema=info.defaultSchema();
            c.networkTimeout=Integer.parseInt(p.getProperty("socketTimeout","30000"));
            c.networkTimeoutSet=true;
        }
        catch(SQLException e){try{proto.close();}catch(Exception ignored){}throw e;}
        return c.proxy;
    }
    private record SessionInfo(String schema,String defaultSchema){}
    /** Applies the session settings used by both the initial connect and a catalog reconnect. */
    private SessionInfo initializeSession(Protocol p)throws SQLException{
        try{
            executeOn(p,"set nz_encoding to 'utf8'");
            executeOn(p,"set DateStyle to 'ISO'");
            p.commandNumber=0;
            int timeout=networkTimeoutSet?networkTimeout:Integer.parseInt(options.getProperty("socketTimeout","30000"));
            p.socket.setSoTimeout(timeout);
            String serverDefault=readCurrentSchema(p);
            String active=serverDefault;
            String requested=options.getProperty("currentSchema");
            if(requested!=null&&!requested.isBlank()){
                executeOn(p,"SET SCHEMA \""+requested.replace("\"","\"\"")+"\"");
                active=requested;
            }
            if(isolation==Connection.TRANSACTION_SERIALIZABLE)
                executeOn(p,"SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            if(!autoCommit)executeOn(p,"BEGIN");
            return new SessionInfo(active,serverDefault);
        }
        catch(java.io.IOException e){
            throw p.io(e);
        }
    }
    private void executeOn(Protocol p,String sql)throws SQLException{
        try{p.query(sql);drain(p);}catch(java.io.IOException e){throw p.io(e);}
    }
    @Override public synchronized Object invoke(Object proxy,Method m,Object[] a) throws Throwable {
        String n=m.getName(); if(n.equals("toString"))return "NetezzaConnection["+host+"/"+database+"]";if(n.equals("hashCode"))return System.identityHashCode(proxy);if(n.equals("equals"))return proxy==a[0];
        if(n.equals("close")){close();return null;} if(n.equals("isClosed"))return closed;
        if(n.equals("isValid"))return isValid((int)a[0]);
        checkOpen();
        return switch(n){
            case "createStatement" -> {
                if(a!=null&&a.length>=2)checkResultSetOptions((int)a[0],(int)a[1]);
                if(a!=null&&a.length>=3)checkHoldability((int)a[2]);
                yield NetezzaStatement.create(this,null);
            }
            case "prepareStatement" -> {
                String sql=(String)a[0];
                if(a.length==2){
                    if(a[1] instanceof Integer autoKeys){
                        if(autoKeys!=Statement.NO_GENERATED_KEYS)throw new SQLFeatureNotSupportedException("Netezza does not return generated keys","0A000");
                    }else throw new SQLFeatureNotSupportedException("Netezza does not return generated keys","0A000");
                }else if(a.length==3){
                    checkResultSetOptions((int)a[1],(int)a[2]);
                }else if(a.length==4){
                    checkResultSetOptions((int)a[1],(int)a[2]);
                    checkHoldability((int)a[3]);
                }
                yield NetezzaStatement.create(this,sql);
            }
            case "prepareCall" -> {
                String sql=(String)a[0];
                if(a.length==3)checkResultSetOptions((int)a[1],(int)a[2]);
                else if(a.length==4){checkResultSetOptions((int)a[1],(int)a[2]);checkHoldability((int)a[3]);}
                yield NetezzaStatement.createCall(this,sql);
            }
            case "getAutoCommit" -> autoCommit;
            case "setAutoCommit" -> { boolean v=(boolean)a[0]; if(autoCommit!=v){transactionCommand(v?"COMMIT":"BEGIN");autoCommit=v;} yield null; }
            case "commit" -> { transactionCommand("COMMIT"); if(!autoCommit)transactionCommand("BEGIN"); yield null; }
            case "rollback" -> {if(a!=null&&a.length>0)throw new SQLFeatureNotSupportedException("Savepoints are not supported","0A000");transactionCommand("ROLLBACK");if(!autoCommit)transactionCommand("BEGIN");yield null;}
            case "setSavepoint", "releaseSavepoint" -> throw new SQLFeatureNotSupportedException("Savepoints are not supported","0A000");
            case "isReadOnly" -> readOnly;
            case "setReadOnly" -> {readOnly=(boolean)a[0];yield null;}
            case "getTransactionIsolation" -> isolation;
            case "setTransactionIsolation" -> {
                int level=(int)a[0];
                if(level!=Connection.TRANSACTION_READ_COMMITTED&&level!=Connection.TRANSACTION_SERIALIZABLE
                        &&level!=Connection.TRANSACTION_NONE)
                    throw new SQLFeatureNotSupportedException("Netezza supports READ_COMMITTED and SERIALIZABLE transactions","0A000");
                if(level!=isolation){
                    if(level==Connection.TRANSACTION_SERIALIZABLE)
                        executeInternal("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE");
                    else if(level==Connection.TRANSACTION_READ_COMMITTED)
                        executeInternal("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ COMMITTED");
                    isolation=level;
                }
                yield null;
            }
            case "getCatalog" -> database;
            case "setCatalog" -> {setCatalog(a[0]==null?null:(String)a[0]);yield null;}
            case "getSchema" -> schema;
            case "setSchema" -> {
                applySchema(a[0]==null?defaultSchema:(String)a[0]);
                yield null;
            }
            case "nativeSQL" -> JdbcEscapeProcessor.process((String)a[0]);
            case "getMetaData" -> NetezzaMetadata.create(this);
            case "getWarnings" -> warnings();
            case "clearWarnings" -> {clearWarnings();yield null;}
            case "setNoticeListener" -> {noticeListener=(NetezzaNoticeListener)a[0];yield null;}
            case "getNoticeListener" -> noticeListener;
            case "setClientInfo" -> {setClientInfo(a);yield null;}
            case "getClientInfo" -> a==null||a.length==0?clientInfoCopy():clientInfo.getProperty((String)a[0]);
            case "createBlob" -> new NetezzaBlob(new byte[0]);
            case "createClob" -> new NetezzaClob("");
            case "createNClob" -> new NetezzaNClob("");
            case "createSQLXML" -> new NetezzaSQLXML("");
            case "createArrayOf","createStruct" -> throw new SQLFeatureNotSupportedException("Netezza does not expose JDBC array or struct types","0A000");
            case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "setHoldability" -> {checkHoldability((int)a[0]);yield null;}
            case "getTypeMap" -> new HashMap<String,Class<?>>();
            case "setTypeMap" -> null;
            case "abort" -> {close();yield null;}
            case "setNetworkTimeout" -> {int ms=(int)a[1];if(ms<0)throw new SQLException("Network timeout must not be negative","HY092");if(a[0]==null)throw new SQLException("Executor must not be null","HY009");networkTimeout=ms;networkTimeoutSet=true;try{protocol.socket.setSoTimeout(ms);}catch(java.io.IOException e){throw protocol.io(e);}yield null;}
            case "getNetworkTimeout" -> networkTimeout;
            case "beginRequest", "endRequest" -> null;
            case "unwrap" -> unwrap((Class<?>)a[0],proxy);
            case "isWrapperFor" -> ((Class<?>)a[0]).isInstance(proxy);
            default -> {
                if(m.isDefault())yield InvocationHandler.invokeDefault(proxy,m,a);
                throw new SQLFeatureNotSupportedException("Connection."+n+" is not supported", "0A000");
            }
        };
    }
    private void applySchema(String requested)throws SQLException{
        if(requested==null||requested.isBlank())throw new SQLException("Schema name must not be null or blank","HY092");
        executeInternal("SET SCHEMA \""+requested.replace("\"","\"\"")+"\"");
        schema=requested;
    }
    /**
     * Changes the current database by opening a new physical session. The candidate session is fully
     * initialized before the old one is closed, so an unknown database leaves the current connection
     * usable instead of destroying it. A failed switch is deliberately silent and keeps the previous
     * catalog.
     */
    private void setCatalog(String catalog)throws SQLException{
        if(catalog==null||Objects.equals(database,catalog))return;
        Protocol candidate=null;
        SessionInfo info;
        try{
            candidate=new Protocol(host,port,catalog,user,password,options);
            info=initializeSession(candidate);
        }
        catch(SQLException|RuntimeException e){
            if(candidate!=null)try{candidate.close();}catch(Exception ignored){}
            return;
        }
        closeForReconnect();
        protocol=candidate;
        database=catalog;
        schema=info.schema();
        defaultSchema=info.defaultSchema();
        serverVersion=null;
        serverMajorVersion=11;
        serverMinorVersion=0;
    }
    private void closeForReconnect(){
        stopTimeout();
        boolean canRollback=!autoCommit;
        if(activeResult!=null){activeResult.abort();activeResult=null;canRollback=false;}
        if(canRollback)try{executeInternal("ROLLBACK");}catch(SQLException ignored){}
        for(NetezzaStatement statement:new ArrayList<>(statements))statement.markClosedFromConnection();
        statements.clear();
        lastStatement=null;
        try{protocol.close();}catch(Exception ignored){}
        closed=false;
    }
    private Object unwrap(Class<?> c,Object v)throws SQLException{if(c.isInstance(v))return v;throw new SQLException("Not a wrapper for "+c.getName());}
    private void setClientInfo(Object[] a)throws SQLException{
        if(a==null||a.length==0)return;
        if(a.length==1){
            if(a[0]==null){clientInfo.clear();return;}
            if(!(a[0] instanceof Properties properties))throw new SQLException("Invalid client info properties","HY092");
            clientInfo.clear();
            for(String name:properties.stringPropertyNames())clientInfo.setProperty(name,properties.getProperty(name));
            return;
        }
        if(a[0]==null)throw new SQLException("Client info name must not be null","HY009");
        String name=(String)a[0];
        if(a[1]==null)clientInfo.remove(name);else clientInfo.setProperty(name,(String)a[1]);
    }
    private Properties clientInfoCopy(){Properties copy=new Properties();copy.putAll(clientInfo);return copy;}
    private static void checkResultSetOptions(int type,int concurrency)throws SQLException{
        if(type!=ResultSet.TYPE_FORWARD_ONLY)throw new SQLFeatureNotSupportedException("Netezza supports only TYPE_FORWARD_ONLY result sets","0A000");
        if(concurrency!=ResultSet.CONCUR_READ_ONLY)throw new SQLFeatureNotSupportedException("Netezza supports only CONCUR_READ_ONLY result sets","0A000");
    }
    private static void checkHoldability(int holdability)throws SQLException{
        if(holdability!=ResultSet.CLOSE_CURSORS_AT_COMMIT)throw new SQLFeatureNotSupportedException("Netezza supports only CLOSE_CURSORS_AT_COMMIT holdability","0A000");
    }
    private void checkOpen()throws SQLException{if(closed)throw new SQLException("Connection is closed","08003");}
    synchronized void failProtocol(java.io.IOException cause){if(!closed){closed=true;stopTimeout();protocol.abort();}}
    private void stopTimeout(){
        activeOperation=Long.MIN_VALUE;
        operationTimedOut=false;
        if(timeoutFuture!=null){timeoutFuture.cancel(false);timeoutFuture=null;}
    }
    synchronized void close()throws SQLException{
        if(closed)return;
        stopTimeout();
        SQLException failure=null;
        boolean canRollback=!autoCommit;
        if(activeResult!=null){activeResult.abort();activeResult=null;canRollback=false;}
        for(NetezzaStatement statement:new ArrayList<>(statements))statement.markClosedFromConnection();
        statements.clear();
        lastStatement=null;
        if(canRollback){try{executeInternal("ROLLBACK");}catch(SQLException e){failure=e;}}
        try{protocol.close();}catch(Exception e){if(failure==null)failure=new SQLException("Error closing Netezza connection","08006",e);}
        finally{closed=true;}
        if(failure!=null)throw failure;
    }
    void register(NetezzaStatement statement){statements.add(statement);}
    void unregister(NetezzaStatement statement){statements.remove(statement);}
    void addWarning(SQLWarning warning){
        if(warning==null)return;
        SQLWarning copy=new SQLWarning(warning.getMessage(),warning.getSQLState(),warning.getErrorCode());
        NetezzaNoticeListener listener;
        synchronized(this){
            if(firstWarning==null)firstWarning=copy;else lastWarning.setNextWarning(copy);
            lastWarning=copy;
            warningHistory.addLast(new WarningEntry(++warningSequence,copy));
            listener=noticeListener;
        }
        if(listener!=null){
            try{listener.notice(copy);}
            catch(RuntimeException ignored){/* a listener must never break protocol processing */}
        }
    }
    synchronized SQLWarning warnings(){return firstWarning;}
    synchronized long warningSequence(){return warningSequence;}
    /** Copies the connection warning chain appended after the given sequence number. */
    synchronized SQLWarning warningsSince(long sequence){
        SQLWarning first=null,last=null;
        for(WarningEntry entry:warningHistory){
            if(entry.sequence()<=sequence)continue;
            SQLWarning copy=new SQLWarning(entry.warning().getMessage(),entry.warning().getSQLState(),entry.warning().getErrorCode());
            if(first==null)first=copy;else last.setNextWarning(copy);
            last=copy;
        }
        return first;
    }
    synchronized void clearWarnings(){firstWarning=null;lastWarning=null;warningHistory.clear();}
    private record WarningEntry(long sequence,SQLWarning warning){}
    synchronized void executeInternal(String sql)throws SQLException{checkOpen();if(activeResult!=null)activeResult.finish();try{protocol.query(sql);drain();}catch(java.io.IOException e){throw protocol.io(e);}}
    private void transactionCommand(String sql)throws SQLException{checkOpen();executeInternal(sql);}
    private void drain()throws SQLException,java.io.IOException{drain(protocol);}
    private void drain(Protocol p)throws SQLException,java.io.IOException{SQLException error=null;while(true){int t=p.readMessageType();int h=p.readInt();if(t=='Z'){if(error!=null)throw error;return;}if(t=='E'){SQLException e=Protocol.serverError(frame(p,t,h));if(error==null)error=e;}else if(t=='Y'){p.readInt();int n=p.readInt();p.readExact(n);}else if(t=='N'){addWarning(Protocol.noticeWarning(frame(p,t,h)));}else if(t=='C'||t=='T'||t=='D'||t=='X'||t=='I'||t=='P')frame(p,t,h);}}
    private byte[] frame(Protocol p,int t,int h)throws java.io.IOException{int n=p.readInt();if(n<0||n>Protocol.MAX_MESSAGE)throw new java.io.IOException("Invalid backend payload length "+n+" type="+(char)t+" header="+h);return p.readExact(n);}
    synchronized NetezzaStatement createStatement(String sql)throws SQLException{checkOpen();lastStatement=new NetezzaStatement(this,sql,false);return lastStatement;}
    private String readCurrentSchema(Protocol p)throws SQLException,java.io.IOException{
        p.query("SELECT CURRENT_SCHEMA");
        QueryResult result=new QueryResult(p);
        try{if(!result.next())return null;Object value=result.value(0);return value==null?null:value.toString();}
        finally{result.finish();}
    }
    /** Sends a cancel request without taking the session lock held by execute(). */
    void cancel(long operationToken)throws SQLException{
        if(operationToken!=activeOperation)return;
        try{sendCancelRequest();}
        catch(Exception e){throw new SQLException("Could not cancel Netezza query","08006",e);}
    }
    private void sendCancelRequest()throws java.io.IOException{
        int pid=protocol.pid,secret=protocol.secret;
        if(pid==0||protocol.closed)return;
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(host,port),2_000);
            socket.setSoTimeout(2_000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(16);
            out.writeInt(80877102);
            out.writeInt(pid);
            out.writeInt(secret);
            out.flush();
            try{socket.getInputStream().read();}catch(java.io.IOException ignored){}
        }
    }
    private void timeoutOperation(long operationToken){
        if(operationToken!=activeOperation)return;
        operationTimedOut=true;
        try{sendCancelRequest();}
        catch(Exception e){protocol.abort();}
    }
    void completeOperation(long operationToken){
        synchronized(this){
            if(operationToken!=activeOperation)return;
            activeOperation=Long.MIN_VALUE;
            operationTimedOut=false;
            if(timeoutFuture!=null){timeoutFuture.cancel(false);timeoutFuture=null;}
        }
    }
    boolean wasTimedOut(long operationToken){return operationToken==activeOperation&&operationTimedOut;}
    synchronized String databaseVersion()throws SQLException{
        if(serverVersion==null)loadServerVersion();
        return serverVersion;
    }
    synchronized int databaseMajorVersion()throws SQLException{
        if(serverVersion==null)loadServerVersion();
        return serverMajorVersion;
    }
    synchronized int databaseMinorVersion()throws SQLException{
        if(serverVersion==null)loadServerVersion();
        return serverMinorVersion;
    }
    private void loadServerVersion()throws SQLException{
        String value=null;
        QueryResult result=execute("SELECT VERSION()",0);
        try{
            if(result.next()){
                Object current=result.value(0);
                if(current!=null)value=current.toString();
            }
        }finally{
            result.finish();
        }
        serverVersion=value==null||value.isBlank()?"Netezza":value.trim();
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(serverVersion);
        if(matcher.find()){
            try{
                serverMajorVersion=Integer.parseInt(matcher.group(1));
                serverMinorVersion=Integer.parseInt(matcher.group(2));
            }catch(NumberFormatException ignored){
                serverMajorVersion=11;
                serverMinorVersion=0;
            }
        }
    }
    synchronized QueryResult execute(String sql,int timeoutSeconds)throws SQLException{return execute(sql,timeoutSeconds,0L);}
    synchronized QueryResult execute(String sql,int timeoutSeconds,long operationToken)throws SQLException{
        return execute(sql,timeoutSeconds,operationToken,null);
    }
    synchronized QueryResult execute(String sql,int timeoutSeconds,long operationToken,BulkInsertData bulkData)throws SQLException{
        checkOpen();
        if(activeResult!=null)activeResult.finish();else if(lastStatement!=null&&lastStatement.cursor!=null)lastStatement.cursor.finish();
        try {
            int base=networkTimeoutSet?networkTimeout:Integer.parseInt(options.getProperty("socketTimeout","30000"));
            int readTimeout=timeoutSeconds>0?Math.addExact(Math.multiplyExact(timeoutSeconds,1_000),CANCEL_GRACE_MILLIS):base;
            protocol.socket.setSoTimeout(readTimeout);
        }
        catch(Exception e){throw protocol.io(e instanceof java.io.IOException io?io:new java.io.IOException(e));}
        activeOperation=operationToken;
        operationTimedOut=false;
        if(timeoutFuture!=null)timeoutFuture.cancel(false);
        timeoutFuture=timeoutSeconds>0?TIMEOUT_SCHEDULER.schedule(()->timeoutOperation(operationToken),timeoutSeconds,TimeUnit.SECONDS):null;
        try{
            protocol.query(sql);
            activeResult=new QueryResult(this,operationToken,bulkData);
            return activeResult;
        }
        catch(SQLException e){
            if(operationToken==activeOperation&&(e.getSQLState()==null||!e.getSQLState().startsWith("08")))completeOperation(operationToken);
            if(e.getSQLState()!=null&&e.getSQLState().startsWith("08")){closed=true;protocol.abort();}
            throw e;
        }
        catch(RuntimeException|Error e){
            completeOperation(operationToken);
            throw e;
        }
    }
    private synchronized boolean isValid(int timeoutSeconds)throws SQLException{
        if(timeoutSeconds<0)throw new SQLException("isValid timeout must not be negative","HY092");
        if(closed||protocol.closed)return false;
        int previous;
        try{previous=protocol.socket.getSoTimeout();protocol.socket.setSoTimeout(timeoutSeconds>0?Math.multiplyExact(timeoutSeconds,1000):Integer.parseInt(options.getProperty("socketTimeout","30000")));}
        catch(Exception e){return false;}
        try{protocol.query("SELECT 1");drain();return true;}
        catch(java.io.IOException e){closed=true;protocol.abort();return false;}
        catch(SQLException e){if(e.getSQLState()!=null&&e.getSQLState().startsWith("08")){closed=true;protocol.abort();}return false;}
        finally{if(!closed&&!protocol.closed)try{protocol.socket.setSoTimeout(networkTimeoutSet?networkTimeout:previous);}catch(Exception ignored){}}
    }
}
