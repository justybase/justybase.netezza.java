/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Standard JDBC DataSource; pooling is intentionally delegated to pool libraries. */
public final class NetezzaDataSource implements DataSource {
    private String serverName="localhost",databaseName,user,password,sslmode="prefer";
    private String url,currentSchema,clientApplicationName,clientUser,clientHostName;
    private String charVarcharEncoding="latin1",clientType="3";
    private int portNumber=5480,loginTimeout,socketBufferSize,connectTimeout=15000,socketTimeout=30000;
    private Boolean readOnly,autoCommit,bulkInsert;
    private PrintWriter logWriter;
    private NetezzaNoticeListener noticeListener;
    private final Properties properties=new Properties();

    /** Creates an empty data source; configure it before calling {@link #getConnection()}. */
    public NetezzaDataSource(){}

    /**
     * Creates a data source for a fixed endpoint.
     *
     * @param serverName server host name
     * @param portNumber server port, normally 5480
     * @param databaseName database name
     * @param user user name
     * @param password password
     */
    public NetezzaDataSource(String serverName,int portNumber,String databaseName,String user,String password){this.serverName=serverName;this.portNumber=portNumber;this.databaseName=databaseName;this.user=user;this.password=password;}

    /**
     * Returns the server host name.
     * @return host name, {@code localhost} by default
     */
    public String getServerName(){return serverName;}
    /**
     * Sets the server host name.
     * @param s host name
     */
    public void setServerName(String s){serverName=s;}

    /**
     * Returns the server port.
     * @return port number, 5480 by default
     */
    public int getPortNumber(){return portNumber;}
    /**
     * Sets the server port.
     * @param n port number
     */
    public void setPortNumber(int n){portNumber=n;}

    /**
     * Returns the database name.
     * @return database name, or {@code null}
     */
    public String getDatabaseName(){return databaseName;}
    /**
     * Sets the database name. Required unless a full JDBC URL is configured.
     * @param s database name
     */
    public void setDatabaseName(String s){databaseName=s;}

    /**
     * Returns the default user name.
     * @return user name, or {@code null}
     */
    public String getUser(){return user;}
    /**
     * Sets the default user name.
     * @param s user name
     */
    public void setUser(String s){user=s;}

    /**
     * Returns the configured password.
     * @return password, or {@code null}
     */
    public String getPassword(){return password;}
    /**
     * Sets the password used by {@link #getConnection()}.
     * @param s password
     */
    public void setPassword(String s){password=s;}

    /**
     * Returns the TLS negotiation mode.
     * @return sslmode value, {@code prefer} by default
     */
    public String getSslmode(){return sslmode;}
    /**
     * Sets the TLS negotiation mode.
     * @param s one of the documented {@code sslmode} values
     */
    public void setSslmode(String s){sslmode=s;}

    /**
     * Returns the explicit JDBC URL, building one from the endpoint when not set.
     * @return JDBC URL
     */
    public String getUrl(){return url==null?buildUrl():url;}
    /**
     * Sets an explicit JDBC URL; URL options override the individual properties.
     * @param value JDBC URL
     */
    public void setUrl(String value){url=value;}

    /**
     * Returns the schema applied after connecting.
     * @return schema name, or {@code null}
     */
    public String getCurrentSchema(){return currentSchema;}
    /**
     * Sets the schema applied after connecting.
     * @param value schema name
     */
    public void setCurrentSchema(String value){currentSchema=value;}

    /**
     * Returns the requested read-only state.
     * @return read-only flag, or {@code null} when not set
     */
    public Boolean getReadOnly(){return readOnly;}
    /**
     * Sets the read-only state applied after connecting.
     * @param value read-only flag
     */
    public void setReadOnly(Boolean value){readOnly=value;}

    /**
     * Returns the requested auto-commit state.
     * @return auto-commit flag, or {@code null} when not set
     */
    public Boolean getAutoCommit(){return autoCommit;}
    /**
     * Sets the auto-commit state applied after connecting.
     * @param value auto-commit flag
     */
    public void setAutoCommit(Boolean value){autoCommit=value;}

    /**
     * Returns the bulk-insert preference.
     * @return bulk flag, or {@code null} to use the driver default
     */
    public Boolean getBulkInsert(){return bulkInsert;}
    /**
     * Enables or disables the bulk prepared-batch path.
     * @param value bulk flag
     */
    public void setBulkInsert(Boolean value){bulkInsert=value;}

    /**
     * Returns the application name sent during the handshake.
     * @return application name, or {@code null}
     */
    public String getClientApplicationName(){return clientApplicationName;}
    /**
     * Sets the application name sent during the handshake.
     * @param value application name
     */
    public void setClientApplicationName(String value){clientApplicationName=value;}

    /**
     * Returns the client user reported to the server.
     * @return client user, or {@code null}
     */
    public String getClientUser(){return clientUser;}
    /**
     * Sets the client user reported to the server.
     * @param value client user
     */
    public void setClientUser(String value){clientUser=value;}

    /**
     * Returns the client host name reported to the server.
     * @return client host name, or {@code null}
     */
    public String getClientHostName(){return clientHostName;}
    /**
     * Sets the client host name reported to the server.
     * @param value client host name
     */
    public void setClientHostName(String value){clientHostName=value;}

    /**
     * Returns the socket buffer size.
     * @return buffer size in bytes, or 0 for the OS default
     */
    public int getSocketBufferSize(){return socketBufferSize;}
    /**
     * Sets the socket buffer size.
     * @param value buffer size in bytes; 0 uses the OS default
     */
    public void setSocketBufferSize(int value){socketBufferSize=value;}

    /**
     * Returns the TCP connect timeout.
     * @return timeout in milliseconds
     */
    public int getConnectTimeout(){return connectTimeout;}
    /**
     * Sets the TCP connect timeout.
     * @param value timeout in milliseconds
     */
    public void setConnectTimeout(int value){connectTimeout=value;}

    /**
     * Returns the socket read timeout.
     * @return timeout in milliseconds
     */
    public int getSocketTimeout(){return socketTimeout;}
    /**
     * Sets the socket read timeout.
     * @param value timeout in milliseconds
     */
    public void setSocketTimeout(int value){socketTimeout=value;}

    /**
     * Returns the encoding used for CHAR/VARCHAR values.
     * @return {@code latin1} or {@code utf8}
     */
    public String getCharVarcharEncoding(){return charVarcharEncoding;}
    /**
     * Sets the encoding used for CHAR/VARCHAR values.
     * @param value {@code latin1} or {@code utf8}
     */
    public void setCharVarcharEncoding(String value){charVarcharEncoding=value;}

    /**
     * Returns the Netezza handshake client type.
     * @return client type as text
     */
    public String getClientType(){return clientType;}
    /**
     * Sets the Netezza handshake client type.
     * @param value client type as text
     */
    public void setClientType(String value){clientType=value;}

    /**
     * Returns a copy of the additional connection properties.
     * @return copy of the properties
     */
    public Properties getProperties(){Properties copy=new Properties();copy.putAll(properties);return copy;}
    /**
     * Replaces the additional connection properties.
     * @param value properties to copy, or {@code null} to clear them
     */
    public void setProperties(Properties value){properties.clear();if(value!=null)properties.putAll(value);}

    /**
     * Returns the notice listener installed on every connection created by this data source.
     * @return the listener, or {@code null}
     */
    public NetezzaNoticeListener getNoticeListener(){return noticeListener;}
    /**
     * Installs a notice listener on every connection created by this data source.
     * @param listener notice callback, or {@code null} to remove it
     */
    public void setNoticeListener(NetezzaNoticeListener listener){noticeListener=listener;}

    @Override public Connection getConnection()throws SQLException{return connect(user,password);}
    @Override public Connection getConnection(String u,String p)throws SQLException{return connect(u,p);}

    private String buildUrl(){
        return "jdbc:netezza://"+serverName+":"+portNumber+"/"+(databaseName==null?"":databaseName);
    }

    private Connection connect(String u,String p)throws SQLException{
        if((url==null||url.isBlank())&&(databaseName==null||databaseName.isBlank()))
            throw new SQLException("NetezzaDataSource databaseName is required","08001");
        Properties props=new Properties();
        props.putAll(properties);
        if(u!=null)props.setProperty("user",u);
        if(p!=null)props.setProperty("password",p);
        if(sslmode!=null)props.setProperty("sslmode",sslmode);
        if(connectTimeout>=0)props.setProperty("connectTimeout",Integer.toString(connectTimeout));
        if(socketTimeout>=0)props.setProperty("socketTimeout",Integer.toString(socketTimeout));
        if(socketBufferSize>0)props.setProperty("socketBufferSize",Integer.toString(socketBufferSize));
        if(charVarcharEncoding!=null)props.setProperty("char_varchar_encoding",charVarcharEncoding);
        if(clientType!=null)props.setProperty("clientType",clientType);
        if(bulkInsert!=null)props.setProperty("bulkInsert",Boolean.toString(bulkInsert));
        if(clientApplicationName!=null)props.setProperty("clientApplicationName",clientApplicationName);
        if(clientUser!=null)props.setProperty("clientUser",clientUser);
        if(clientHostName!=null)props.setProperty("clientHostName",clientHostName);
        if(loginTimeout>0)props.setProperty("connectTimeout",Integer.toString(loginTimeout*1000));
        Connection connection=new NetezzaDriver().connect(url==null||url.isBlank()?buildUrl():url,props);
        if(connection==null)throw new SQLException("Netezza driver rejected the data source URL","08001");
        try{
            if(currentSchema!=null&&!currentSchema.isBlank())connection.setSchema(currentSchema);
            if(readOnly!=null)connection.setReadOnly(readOnly);
            if(autoCommit!=null)connection.setAutoCommit(autoCommit);
            if(noticeListener!=null&&connection instanceof NetezzaConnectionExt ext)ext.setNoticeListener(noticeListener);
            return connection;
        }catch(SQLException|RuntimeException e){
            try{connection.close();}catch(SQLException ignored){}
            throw e;
        }
    }

    @Override public PrintWriter getLogWriter(){return logWriter;}
    @Override public void setLogWriter(PrintWriter out){logWriter=out;}
    @Override public void setLoginTimeout(int seconds){loginTimeout=Math.max(0,seconds);}
    @Override public int getLoginTimeout(){return loginTimeout;}
    @Override public Logger getParentLogger(){return Logger.getLogger("com.justybase.netezza");}
    @Override public <T>T unwrap(Class<T> iface)throws SQLException{if(iface.isInstance(this))return iface.cast(this);throw new SQLException("Not a wrapper for "+iface.getName());}
    @Override public boolean isWrapperFor(Class<?> iface){return iface.isInstance(this);}
}
