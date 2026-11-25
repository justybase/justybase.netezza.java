/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Network and wire codec. All wire reads are exact and all lengths are bounded. */
final class Protocol implements Closeable {
    static final int MAX_MESSAGE = 256 * 1024 * 1024;
    Socket socket;
    FastInput in;
    OutputStream out;
    volatile int pid, secret;
    int commandNumber=-1;
    volatile boolean closed;
    private final String host;

    /** Input-only protocol instance used by offline wire-fixture tests. */
    Protocol(InputStream input) {
        this(input, OutputStream.nullOutputStream());
    }

    /** Wire-fixture instance that captures the frontend bytes for assertions. */
    Protocol(InputStream input, OutputStream output) {
        host = "<fixture>";
        in = new FastInput(input);
        out = output;
    }

    Protocol(String host, int port, String database, String user, String password, Properties options) throws SQLException {
        this.host=host;
        try {
            socket = new Socket(); socket.connect(new InetSocketAddress(host, port), Integer.parseInt(options.getProperty("connectTimeout", "15000")));
            socket.setTcpNoDelay(true); socket.setKeepAlive(true); socket.setSoTimeout(Integer.parseInt(options.getProperty("socketTimeout", "30000")));
            int socketBuffer=Integer.parseInt(options.getProperty("socketBufferSize","0"));
            if(socketBuffer>0){socket.setReceiveBufferSize(socketBuffer);socket.setSendBufferSize(socketBuffer);}
            in = new FastInput(socket.getInputStream()); out = socket.getOutputStream();
            startup(database, user, password, options);
        } catch (SQLException e) { throw e; }
        catch (Exception e) { throw new SQLException("Unable to connect to Netezza at " + host + ":" + port + ": " + e.getMessage(), "08001", e); }
    }

    private void startup(String database, String user, String password, Properties options) throws Exception {
        int hs = 6;
        while (true) {
            writeInt(8); writeShort(1); writeShort(hs); out.flush();
            int response = readU8();
            if (response == 'N') break;
            if (response == 'M') { int v=readU8(); hs=v-'0'; if(hs<2||hs>5) throw new IOException("Unsupported Netezza handshake version " + v); }
            else throw new IOException("Netezza handshake rejected ("+(char)response+")");
        }
        requestString(2, database);
        String sslmode=options.getProperty("sslmode","prefer").toLowerCase(java.util.Locale.ROOT);
        int sec = switch(sslmode) { case "require", "verify-ca", "verify-full" -> 3; case "disable" -> 1; default -> 0; };
        writeInt(10); writeShort(11); writeInt(sec); out.flush();
        int ssl=readU8();
        if (ssl == 'S') {
            if (sslmode.equals("disable")) throw new IOException("Server requires TLS but sslmode=disable");
            writeInt(6); writeShort(12); out.flush();
            javax.net.ssl.SSLContext context=TlsSupport.context(options);
            SSLSocketFactory factory=context==null?(SSLSocketFactory)SSLSocketFactory.getDefault():context.getSocketFactory();
            SSLSocket tls=(SSLSocket)factory.createSocket(socket, host, socket.getPort(), true);
            tls.setUseClientMode(true);
            if (sslmode.equals("verify-full")) { javax.net.ssl.SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(params); }
            tls.startHandshake(); socket=tls; in=new FastInput(tls.getInputStream()); out=tls.getOutputStream();
            ssl=readU8();
        }
        if (ssl == 'N' && (sslmode.equals("require")||sslmode.equals("verify-ca")||sslmode.equals("verify-full")))
            throw new IOException("Server refused TLS while sslmode=" + sslmode);
        if (ssl != 'N') throw new IOException("Netezza SSL negotiation failed ("+(char)ssl+")");
        requestString(3, user);
        handshakeField(13, options.getProperty("clientApplicationName",
                options.getProperty("applicationName", System.getProperty("sun.java.command", "NetezzaSQL"))));
        handshakeField(14, System.getProperty("os.name", "Java"));
        String clientHostName=options.getProperty("clientHostName");
        if(clientHostName==null||clientHostName.isBlank())clientHostName=InetAddress.getLocalHost().getHostName();
        handshakeField(15, clientHostName);
        handshakeField(16, options.getProperty("clientUser", System.getProperty("user.name", "java")));
        writeInt(10); writeShort(9); writeShort(3); writeShort(5); out.flush(); readN();
        writeInt(10); writeShort(6); writeInt((int)ProcessHandle.current().pid()); out.flush(); readN();
        writeInt(8); writeShort(8); writeShort(Short.parseShort(options.getProperty("clientType", "3"))); out.flush(); readN();
        if (hs >= 5) { writeInt(8); writeShort(17); writeShort(1); out.flush(); readN(); }
        writeInt(6); writeShort(1000); out.flush();
        authenticate(password);
        boolean ready=false;
        while(!ready) {
            int type=readU8();
            if(type==0) continue;
            if(type=='Z') { readExact(4); ready=true; }
            else if(type=='K') { byte[] p=readExact(16); pid=i32(p,8);secret=i32(p,12); }
            else if(type=='R') { int method=readInt(); if(method!=0) authChallenge(method, readExact((method==5||method==6)?2:0), password); }
            else if(type=='N') { readExact(4); int n=readInt(); if(n>0)readExact(n); }
            else if(type=='E') throw new SQLException("Netezza authentication failed (server error)","28000");
            else readExact(8);
        }
    }
    private void authenticate(String password) throws Exception {
        int t=readU8(); if(t=='E') throw serverError(readFrame()); if(t!='R') throw new IOException("Expected authentication request, got "+(char)t);
        int method=readInt(); if(method==0) return; authChallenge(method,readExact((method==5||method==6)?2:0),password);
    }
    private void authChallenge(int method, byte[] salt, String password) throws Exception {
        byte[] answer=authenticationResponse(method,salt,password);
        if(answer==null)return;
        writeInt(answer.length+5); out.write(answer); out.write(0); out.flush();
    }

    /** Computes the password response selected by the server, without touching the network. */
    static byte[] authenticationResponse(int method, byte[] salt, String password) throws SQLException {
        if(method==0)return null;
        byte[] pw=password.getBytes(StandardCharsets.UTF_8);
        if(method==3)return pw;
        if(method!=5&&method!=6)
            throw new SQLException("Unsupported Netezza authentication method "+method+" (Kerberos/GSSAPI is not implemented)","0A000");
        if(salt==null||salt.length<2)throw new SQLException("Missing password authentication salt","08006");
        byte[] input=new byte[salt.length+pw.length];
        System.arraycopy(salt,0,input,0,salt.length);
        System.arraycopy(pw,0,input,salt.length,pw.length);
        try {
            byte[] hash=MessageDigest.getInstance(method==5?"MD5":"SHA-256").digest(input);
            return Base64.getEncoder().withoutPadding().encode(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new SQLException("Required password digest is unavailable","08006",e);
        }
    }
    private void requestString(int op, String value) throws IOException, SQLException { byte[] b=value.getBytes(StandardCharsets.UTF_8); writeInt(2+b.length+1+4); writeShort(op); out.write(b); out.write(0); out.flush(); readN(); }
    private void handshakeField(int op,String value) throws IOException, SQLException { requestString(op,value); }
    private void readN() throws IOException, SQLException { int b=readU8(); if(b=='N') return; if(b=='E') throw serverError(readFrame()); throw new IOException("Unexpected handshake response: "+(char)b); }
    void query(String sql) throws SQLException {
        try {
            byte[] b=sql.getBytes(StandardCharsets.UTF_8);
            int command;
            if(commandNumber<0)command=-1;
            else{commandNumber++;if(commandNumber>100000)commandNumber=1;command=commandNumber;}
            byte[] packet=new byte[5+b.length+1];
            packet[0]='P';
            packet[1]=(byte)(command>>>24);packet[2]=(byte)(command>>>16);packet[3]=(byte)(command>>>8);packet[4]=(byte)command;
            System.arraycopy(b,0,packet,5,b.length);
            out.write(packet);
            out.flush();
        } catch(IOException e){throw io(e);}
    }
    int readU8() throws IOException { return in.readU8(); }
    int readMessageType() throws IOException { int b; do { b=readU8(); } while(b==0); return b; }
    int readInt() throws IOException { return in.readInt(); }
    int readShort() throws IOException { return in.readShort(); }
    byte[] readExact(int n) throws IOException { validateProtocolLength(n); return in.readExact(n); }
    private byte[] reusableFrame = new byte[0];
    private int reusableLength;
    /** Reads into a reusable buffer; the returned bytes are valid until the next call. */
    byte[] readExactReusable(int n) throws IOException { validateProtocolLength(n); if(reusableFrame.length<n) reusableFrame=new byte[Math.max(n,8192)]; in.readExactInto(reusableFrame,0,n); reusableLength=n; return reusableFrame; }
    int lastReusableLength() { return reusableLength; }
    byte[] readFrame() throws IOException { int len=readInt(); validateProtocolLength(len); return readExact(len); }
    /** Skips a length-prefixed frame without allocating its payload. */
    void skipFrame() throws IOException { int len=readInt(); validateProtocolLength(len); in.skip(len); }
    void skip(int n) throws IOException { validateProtocolLength(n); in.skip(n); }
    static void validateProtocolLength(int n) throws IOException { if(n<0||n>MAX_MESSAGE) throw new IOException("Invalid Netezza protocol length: "+n); }
    void writeInt(int v) throws IOException { out.write((v>>>24)&255);out.write((v>>>16)&255);out.write((v>>>8)&255);out.write(v&255); }
    void writeShort(int v) throws IOException { out.write((v>>>8)&255);out.write(v&255); }
    static int i32(byte[] a,int i){return (a[i]&255)<<24|(a[i+1]&255)<<16|(a[i+2]&255)<<8|(a[i+3]&255);}
    static SQLException serverError(byte[] p) {
        String raw=new String(p,StandardCharsets.UTF_8).trim();
        if(raw.startsWith("ERROR:")||raw.startsWith("FATAL:")){
            String message=raw.substring(raw.indexOf(':')+1).trim();
            return new SQLException(message,stateForMessage(message));
        }
        String state="HY000",msg="Netezza server error";StringBuilder all=new StringBuilder();int i=0;boolean hasMessage=false;
        while(i<p.length){
            int code=p[i++]&255;if(code==0)break;int s=i;
            while(i<p.length&&p[i]!=0)i++;
            String v=new String(p,s,i-s,StandardCharsets.UTF_8);
            if(i<p.length)i++;
            if(code=='C')state=v;
            else if(code=='M'){msg=v;hasMessage=true;}
            all.append((char)code).append('=').append(v).append(' ');
        }
        if(!hasMessage&&!raw.isEmpty())msg=raw;
        return new SQLException(msg+(all.isEmpty()?"":" ["+all.toString().trim()+"]"),state);
    }
    /** Parses a PostgreSQL/Netezza NoticeResponse payload into a SQLWarning. */
    static SQLWarning noticeWarning(byte[] p) {
        String rawText = new String(p, StandardCharsets.UTF_8).trim();
        if (hasSeverityPrefix(rawText)) {
            return new SQLWarning(rawText, "01000", 0);
        }
        String severity = null, state = null, message = null, detail = null;
        int i = 0;
        boolean fields = false;
        while (i < p.length && p[i] != 0) {
            int code = p[i++] & 255;
            int start = i;
            while (i < p.length && p[i] != 0) i++;
            if (i >= p.length) break;
            String value = new String(p, start, i - start, StandardCharsets.UTF_8);
            i++;
            fields = true;
            switch (code) {
                case 'S', 'V' -> severity = value;
                case 'C' -> state = value;
                case 'M' -> message = value;
                case 'D' -> detail = value;
            }
        }
        if (!fields) {
            String raw = new String(p, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) raw = "Netezza notice";
            String text = hasSeverityPrefix(raw) ? raw : "NOTICE: " + raw;
            return new SQLWarning(text, state == null || state.isBlank() ? "01000" : state, 0);
        }
        if (message == null || message.isBlank()) message = new String(p, StandardCharsets.UTF_8).trim();
        if (message.isBlank()) message = "Netezza notice";
        String normalized = message;
        if (severity != null && !severity.isBlank()) {
            String prefix = severity + ":";
            if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
                normalized = normalized.substring(prefix.length()).trim();
            }
        }
        if (normalized.isBlank()) normalized = message;
        String text = (severity == null || severity.isBlank() ? "NOTICE" : severity) + ": " + normalized;
        if (detail != null && !detail.isBlank()) text += " (" + detail + ")";
        return new SQLWarning(text, state == null || state.isBlank() ? "01000" : state, 0);
    }
    private static boolean hasSeverityPrefix(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0) return false;
        String severity = text.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        return Set.of("NOTICE", "WARNING", "INFO", "DEBUG", "LOG", "ERROR", "FATAL", "PANIC").contains(severity);
    }
    private static String stateForMessage(String message){
        String lower=message.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("cancel")||lower.contains("rolled back by client")||lower.contains("terminated by user"))return "57014";
        if(lower.contains("timed out")||lower.contains("timeout"))return "57014";
        if(lower.contains("syntax error")||lower.contains("expecting a keyword")||lower.contains("unexpected token"))return "42601";
        if(lower.contains("schema")&&(lower.contains("not found")||lower.contains("does not exist")||lower.contains("unknown")))return "3F000";
        if(lower.contains("database")&&(lower.contains("not found")||lower.contains("does not exist")||lower.contains("unknown")))return "3D000";
        if(lower.contains("relation does not exist")||lower.contains("table does not exist")||lower.contains("no such table"))return "42P01";
        if((lower.contains("attribute '")&&lower.contains("not found"))||(lower.contains("column")&&lower.contains("does not exist")))return "42703";
        if(lower.contains("divide by 0")||lower.contains("division by zero"))return "22012";
        if(lower.contains("field cannot contain null")||lower.contains("not null")||lower.contains("null value in column"))return "23502";
        if(lower.contains("duplicate key")||lower.contains("unique constraint")||lower.contains("violates unique"))return "23505";
        if(lower.contains("permission denied")||lower.contains("access denied"))return "42501";
        if(lower.contains("right truncation")||lower.contains("string data")&&lower.contains("truncat"))return "22001";
        if(lower.contains("numeric overflow")||lower.contains("out of range")||lower.contains("overflow"))return "22003";
        if(lower.contains("ambiguous"))return "42702";
        if(lower.contains("can't parse")||lower.contains("invalid input syntax")||lower.contains("invalid cast")||lower.contains("cannot cast type")||lower.contains("invalid value")||lower.contains("invalid date")||lower.contains("invalid time"))return "22P02";
        return "HY000";
    }
    SQLException io(IOException e){return new SQLException("Netezza protocol I/O failed: "+e.getMessage(),"08006",e);}
    void abort(){closed=true;try{if(socket!=null)socket.close();}catch(IOException ignored){}}
    @Override public void close() throws IOException { if(!closed){closed=true;try{out.write('X');writeInt(4);out.flush();}catch(Exception ignored){}socket.close();} }

    /** Small single-threaded buffered reader optimized for the Netezza frame layout. */
    static final class FastInput {
        private final InputStream source;
        private final byte[] buffer;
        private int position, limit;

        FastInput(InputStream source) {
            this(source, 64 * 1024);
        }

        FastInput(InputStream source, int bufferSize) {
            this.source = source;
            this.buffer = new byte[bufferSize];
        }

        int readU8() throws IOException {
            if (position >= limit) fill();
            return buffer[position++] & 255;
        }

        int readShort() throws IOException {
            if (limit - position >= 2) {
                int p = position;
                position = p + 2;
                return ((buffer[p] & 255) << 8) | (buffer[p + 1] & 255);
            }
            return (readU8() << 8) | readU8();
        }

        int readInt() throws IOException {
            if (limit - position >= 4) {
                int p = position;
                position = p + 4;
                return ((buffer[p] & 255) << 24)
                        | ((buffer[p + 1] & 255) << 16)
                        | ((buffer[p + 2] & 255) << 8)
                        | (buffer[p + 3] & 255);
            }
            return (readU8() << 24) | (readU8() << 16) | (readU8() << 8) | readU8();
        }

        void readExactInto(byte[] target, int offset, int length) throws IOException {
            int remaining = length;
            while (remaining > 0) {
                if (position >= limit) fill();
                int chunk = Math.min(remaining, limit - position);
                System.arraycopy(buffer, position, target, offset, chunk);
                position += chunk;
                offset += chunk;
                remaining -= chunk;
            }
        }

        byte[] readExact(int length) throws IOException {
            byte[] bytes = new byte[length];
            readExactInto(bytes, 0, length);
            return bytes;
        }

        /** Consumes bytes without copying them into a payload array. */
        void skip(long length) throws IOException {
            long remaining=length;
            while(remaining>0){
                if(position>=limit)fill();
                int chunk=(int)Math.min(remaining,limit-position);
                position+=chunk;
                remaining-=chunk;
            }
        }

        private void fill() throws IOException {
            position = 0;
            limit = source.read(buffer, 0, buffer.length);
            if (limit <= 0) {
                limit = 0;
                throw new EOFException("Netezza closed the connection");
            }
        }
    }
}
