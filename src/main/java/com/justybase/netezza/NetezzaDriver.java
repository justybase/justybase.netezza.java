/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.sql.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/** JDBC entry point for NetezzaSQL. */
public final class NetezzaDriver implements Driver {
    /** JDBC URL prefix accepted by this driver. */
    public static final String PREFIX = "jdbc:netezza:";
    /** Creates a driver instance; registration with {@link java.sql.DriverManager} happens in the static initializer. */
    public NetezzaDriver() {}
    static { try { DriverManager.registerDriver(new NetezzaDriver()); } catch (SQLException e) { throw new ExceptionInInitializerError(e); } }

    @Override public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        Endpoint endpoint = parseUrl(url, info);
        String user = endpoint.options().getProperty("user", endpoint.options().getProperty("username", ""));
        String password = endpoint.options().getProperty("password", "");
        if (user.isBlank()) throw new SQLException("Netezza JDBC property 'user' is required", "08001");
        return NetezzaConnection.open(endpoint.host(), endpoint.port(), endpoint.database(), user, password, endpoint.options());
    }

    /** Parsed URL value is package-private to keep URL handling testable without opening a socket. */
    record Endpoint(String host, int port, String database, Properties options) {}

    static Endpoint parseUrl(String url, Properties supplied) throws SQLException {
        if (url == null || !url.startsWith(PREFIX + "//"))
            throw new SQLException("URL must be jdbc:netezza://host:port/database", "08001");
        String tail = url.substring(PREFIX.length() + 2);
        int slash = tail.indexOf('/');
        if (slash < 0) throw new SQLException("Database name is missing from Netezza JDBC URL", "08001");
        String authority = tail.substring(0, slash);
        String host;
        int port = 5480;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) throw new SQLException("Invalid IPv6 host in Netezza JDBC URL", "08001");
            host = authority.substring(1, close);
            String suffix = authority.substring(close + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) throw new SQLException("Invalid authority in Netezza JDBC URL", "08001");
                port = parsePort(suffix.substring(1));
            }
        } else {
            int colon = authority.lastIndexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
            if (colon >= 0) port = parsePort(authority.substring(colon + 1));
        }
        if (host.isBlank()) throw new SQLException("Netezza JDBC URL host is missing", "08001");
        String pathAndOptions = tail.substring(slash + 1);
        int queryStart = firstOptionSeparator(pathAndOptions);
        String database = decode(queryStart < 0 ? pathAndOptions : pathAndOptions.substring(0, queryStart));
        if (database.isBlank()) throw new SQLException("Database name is missing from Netezza JDBC URL", "08001");
        Properties options = new Properties();
        if (queryStart >= 0) parseOptions(pathAndOptions.substring(queryStart + 1), options);
        if (supplied != null) {
            for (String name : supplied.stringPropertyNames()) options.setProperty(name, supplied.getProperty(name));
        }
        String sslmode = options.getProperty("sslmode", "prefer").toLowerCase(Locale.ROOT);
        if (!Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full").contains(sslmode))
            throw new SQLException("Invalid sslmode '" + sslmode + "'", "08001");
        options.setProperty("sslmode", sslmode);
        String textEncoding = options.getProperty("char_varchar_encoding", "latin1").toLowerCase(Locale.ROOT);
        if (Set.of("latin", "latin1", "iso-8859-1").contains(textEncoding)) textEncoding = "latin1";
        else if (Set.of("utf8", "utf-8").contains(textEncoding)) textEncoding = "utf8";
        else throw new SQLException("Invalid char_varchar_encoding '" + textEncoding + "' (expected latin1 or utf8)", "08001");
        options.setProperty("char_varchar_encoding", textEncoding);
        validateTimeout(options, "connectTimeout", 15000);
        validateTimeout(options, "socketTimeout", 30000);
        validateNonNegative(options, "socketBufferSize", 0);
        validateNonNegative(options, "maxStreamBytes", 16777216);
        validateBoolean(options, "bulkInsert", true);
        validateStoreType(options, "trustStoreType");
        validateStoreType(options, "keyStoreType");
        validateClientType(options);
        options.putIfAbsent("connectTimeout", "15000");
        options.putIfAbsent("socketTimeout", "30000");
        options.putIfAbsent("clientType", "3");
        options.putIfAbsent("bulkInsert", "true");
        return new Endpoint(decode(host), port, database, options);
    }

    private static void parseOptions(String part, Properties options) throws SQLException {
        for (String kv : part.split("[&;]")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            if (eq <= 0) throw new SQLException("Invalid Netezza URL option", "08001");
            options.setProperty(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
        }
    }

    private static int firstOptionSeparator(String s) {
        int q = s.indexOf('?'), semi = s.indexOf(';');
        if (q < 0) return semi;
        if (semi < 0) return q;
        return Math.min(q, semi);
    }

    private static int parsePort(String text) throws SQLException {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new NumberFormatException("out of range");
            return port;
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid port in Netezza JDBC URL", "08001", e);
        }
    }

    private static void validateTimeout(Properties options, String name, int defaultValue) throws SQLException {
        String value = options.getProperty(name, Integer.toString(defaultValue));
        try {
            if (Integer.parseInt(value) < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid " + name + " value", "08001", e);
        }
    }

    private static void validateNonNegative(Properties options, String name, int defaultValue) throws SQLException {
        String value = options.getProperty(name, Integer.toString(defaultValue));
        try {
            if (Integer.parseInt(value) < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid " + name + " value", "08001", e);
        }
    }

    private static void validateBoolean(Properties options, String name, boolean defaultValue) throws SQLException {
        String value = options.getProperty(name, Boolean.toString(defaultValue));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new SQLException("Invalid " + name + " value (expected true or false)", "08001");
        options.setProperty(name, Boolean.toString(Boolean.parseBoolean(value)));
    }

    private static void validateStoreType(Properties options, String name) throws SQLException {
        String value = options.getProperty(name);
        if (value == null || value.isBlank()) return;
        if (!value.equalsIgnoreCase("JKS") && !value.equalsIgnoreCase("PKCS12"))
            throw new SQLException("Invalid " + name + " value (expected JKS or PKCS12)", "08001");
        options.setProperty(name, value.toUpperCase(Locale.ROOT));
    }

    private static void validateClientType(Properties options) throws SQLException {
        String value = options.getProperty("clientType", "3");
        try {
            int type = Integer.parseInt(value);
            if (type < 0 || type > 32767) throw new NumberFormatException("out of range");
            options.setProperty("clientType", Integer.toString(type));
        } catch (NumberFormatException e) {
            throw new SQLException("Invalid clientType value", "08001", e);
        }
    }

    private static String decode(String value) throws SQLException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid percent-encoding in Netezza JDBC URL", "08001", e);
        }
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties options=new Properties();
        if(info!=null)options.putAll(info);
        if(url!=null&&acceptsURL(url))try{options.putAll(parseUrl(url,options).options());}catch(SQLException ignored){}
        List<DriverPropertyInfo> properties=new ArrayList<>();
        properties.add(property("user",options.getProperty("user"),"Netezza user name",true));
        properties.add(property("password",null,"Netezza password",false));
        properties.add(choice("sslmode",options.getProperty("sslmode","prefer"),"TLS negotiation mode",false,
                new String[]{"disable","allow","prefer","require","verify-ca","verify-full"}));
        properties.add(property("connectTimeout",options.getProperty("connectTimeout","15000"),"TCP connect timeout in milliseconds",false));
        properties.add(property("socketTimeout",options.getProperty("socketTimeout","30000"),"Socket read timeout in milliseconds",false));
        properties.add(property("clientType",options.getProperty("clientType","3"),"Netezza handshake client type",false));
        properties.add(choice("char_varchar_encoding",options.getProperty("char_varchar_encoding","latin1"),
                "Encoding for CHAR/VARCHAR values",false,new String[]{"latin1","utf8"}));
        properties.add(property("currentSchema",options.getProperty("currentSchema"),"Schema set after connecting",false));
        properties.add(property("applicationName",options.getProperty("applicationName"),"Application name sent during handshake",false));
        properties.add(property("clientApplicationName",options.getProperty("clientApplicationName"),"JDBC client application name",false));
        properties.add(property("clientUser",options.getProperty("clientUser"),"JDBC client user name",false));
        properties.add(property("clientHostName",options.getProperty("clientHostName"),"JDBC client host name",false));
        properties.add(property("socketBufferSize",options.getProperty("socketBufferSize","0"),"Socket send/receive buffer size; 0 uses the OS default",false));
        properties.add(property("maxStreamBytes",options.getProperty("maxStreamBytes","16777216"),"Maximum bytes/chars materialized from a stream setter",false));
        properties.add(choice("bulkInsert",options.getProperty("bulkInsert","true"),"Use one external-table transfer for prepared batches of two or more INSERT rows",false,new String[]{"true","false"}));
        properties.add(property("caCertificate",options.getProperty("caCertificate"),"PEM CA certificate path for TLS verification",false));
        properties.add(property("trustStore",options.getProperty("trustStore"),"TLS trust store path",false));
        properties.add(property("trustStorePassword",null,"TLS trust store password",false));
        properties.add(choice("trustStoreType",options.getProperty("trustStoreType","JKS"),"TLS trust store type",false,new String[]{"JKS","PKCS12"}));
        properties.add(property("clientCertificate",options.getProperty("clientCertificate"),"PEM client certificate chain path",false));
        properties.add(property("clientKey",options.getProperty("clientKey"),"PEM PKCS#8 client private key path",false));
        properties.add(property("clientKeyPassword",null,"Client private key password",false));
        properties.add(property("keyStore",options.getProperty("keyStore"),"Client key store path",false));
        properties.add(property("keyStorePassword",null,"Client key store password",false));
        properties.add(choice("keyStoreType",options.getProperty("keyStoreType","JKS"),"Client key store type",false,new String[]{"JKS","PKCS12"}));
        return properties.toArray(new DriverPropertyInfo[0]);
    }
    private static DriverPropertyInfo property(String name,String value,String description,boolean required){
        DriverPropertyInfo property=new DriverPropertyInfo(name,value);
        property.description=description;property.required=required;return property;
    }
    private static DriverPropertyInfo choice(String name,String value,String description,boolean required,String[] choices){
        DriverPropertyInfo property=property(name,value,description,required);property.choices=choices;return property;
    }
    @Override public int getMajorVersion() { return 0; }
    @Override public int getMinorVersion() { return 1; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.justybase.netezza"); }
}
