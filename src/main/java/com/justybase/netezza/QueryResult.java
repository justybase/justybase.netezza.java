/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import java.lang.reflect.Proxy;
import java.nio.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Incremental DBOS/PostgreSQL response reader; row payloads are decoded only as requested. */
final class QueryResult {
    final NetezzaConnection connection; final Protocol p; final List<Column> columns=new ArrayList<>(); final Charset charVarcharCharset;
    boolean hasRows,done,rsClosed,resultEnded; int initialUpdateCount=-1,updateCount=-1,maxRows,rows,generation;
    long operationToken;
    private ExternalTableHandler externalTable;
    private final BulkInsertData bulkData;
    private int dbosVariableCount;
    private int[] dbosVariableOffsets=new int[0];
    private int dbosColumnCount, dbosFixedFieldsSize;
    private int[] dbosNullByte=new int[0], dbosColumnOffset=new int[0], dbosColumnSize=new int[0],
            dbosColumnTrueSize=new int[0], dbosColumnType=new int[0], dbosVariableIndex=new int[0];
    private byte[] dbosNullMask=new byte[0];
    private boolean[] dbosFixed=new boolean[0];
    private int[] columnOids=new int[0];
    private boolean lazyRow, lazyDbos, rowReady;
    private boolean lazyVariableOffsetsReady;
    private byte[] lazyData;
    private int lazyEnd;
    private int[] lazyOffsets=new int[0], lazyLengths=new int[0];
    private boolean[] lazyNulls=new boolean[0], lazyDecoded=new boolean[0], lazyResolved=new boolean[0];
    private Object[] lazyValues=new Object[0];
    boolean lastWasNull;
    private SQLWarning firstWarning,lastWarning;
    ResultSet rs; SQLException terminalError; NetezzaStatement statement;
    QueryResult(NetezzaConnection c)throws SQLException{this(c,0,null);}
    QueryResult(NetezzaConnection c,long operationToken)throws SQLException{this(c,operationToken,null);}
    QueryResult(NetezzaConnection c,long operationToken,BulkInsertData bulkData)throws SQLException{this(c,c.protocol,configuredCharset(c),operationToken,bulkData);}
    QueryResult(Protocol protocol)throws SQLException{this(null,protocol,StandardCharsets.UTF_8,0,null);}
    private QueryResult(NetezzaConnection c,Protocol protocol,Charset textCharset,long operationToken,BulkInsertData bulkData)throws SQLException{connection=c;p=protocol;charVarcharCharset=textCharset;this.operationToken=operationToken;this.bulkData=bulkData;try{prime();}catch(java.io.IOException e){fail(e);throw p.io(e);}catch(SQLException e){if(externalTable!=null)externalTable.close();throw e;}}
    private static Charset configuredCharset(NetezzaConnection c){String name=c.options.getProperty("char_varchar_encoding","latin1");return name.equalsIgnoreCase("utf8")?StandardCharsets.UTF_8:StandardCharsets.ISO_8859_1;}
    private void prime()throws java.io.IOException,SQLException{
        while(!done){
            int t=p.readMessageType(),header=p.readInt();
            switch(t){
                case 'T'->{parseRowDescription(frame(t,header));hasRows=true;}
                case 'X'->{parseDbosDescription(frame(t,header));hasRows=true;}
                case 'Y'->{readLazyDbosRow(true);rowReady=true;hasRows=true;return;}
                case 'D'->{parseLazyDataRow(frame(t,header));rowReady=true;hasRows=true;return;}
                case 'C'->{updateCount=parseCommand(frame(t,header));initialUpdateCount=updateCount;resultEnded=true;completeOperation();return;}
                case 'Z'->{done=true;resultEnded=true;doneMessage();return;}
                case 'E'->{boolean timedOut=wasTimedOut();SQLException e=Protocol.serverError(frame(t,header));drainAfterError();throw timeoutError(e,timedOut);}
                case 'u','U','l','e','x'->handleExternal(t,header);
                case 'N'->addWarning(Protocol.noticeWarning(frame(t,header)));case 'I','P'->frame(t,header);
                default->{ /* ignore asynchronous/extension messages */ }
            }
        }
    }
    private void drainAfterError()throws java.io.IOException,SQLException{
        while(true){
            int t=p.readMessageType(),h=p.readInt();
            if(t=='Z'){done=true;resultEnded=true;doneMessage();return;}
            if(t=='Y'){p.readInt();p.skipFrame();}
            else if(t=='D')p.skipFrame();
            else if(t=='u'||t=='U'||t=='l'||t=='e'||t=='x')handleExternal(t,h);
            else if(t=='N')addWarning(Protocol.noticeWarning(p.readFrame()));
            else p.readFrame();
        }
    }
    ResultSet resultSet(){if(rs==null)rs=NetezzaResultSet.create(this,connection==null?null:connection.proxy);return rs;}
    boolean next()throws SQLException{
        if(rsClosed||resultEnded||done)return false;
        if(maxRows>0&&rows>=maxRows){drainCurrentResult();return false;}
        if(rowReady){rowReady=false;rows++;return true;}
        try{
            while(!done&&!resultEnded){
                int t=p.readMessageType(),h=p.readInt();
                switch(t){
                    case 'Y'->{readLazyDbosRow(false);rows++;return true;}
                    case 'D'->{parseLazyDataRow(frameReusable(t,h),p.lastReusableLength());rows++;return true;}
                    case 'C'->{updateCount=parseCommand(frame(t,h));resultEnded=true;completeOperation();return false;}
                    case 'Z'->{done=true;resultEnded=true;doneMessage();return false;}
                    case 'E'->{boolean timedOut=wasTimedOut();SQLException e=Protocol.serverError(frame(t,h));drainAfterError();throw timeoutError(e,timedOut);}
                    case 'u','U','l','e','x'->handleExternal(t,h);
                    case 'N'->addWarning(Protocol.noticeWarning(frame(t,h)));case 'I','P'->frame(t,h);
                    case 'T'->{parseRowDescription(frame(t,h));hasRows=true;}
                    case 'X'->{parseDbosDescription(frame(t,h));hasRows=true;}
                    default->{}
                }
            }
            return false;
        }catch(java.io.IOException e){fail(e);throw p.io(e);}
    }
    /** Close only the current result set, preserving the protocol position for getMoreResults(). */
    void close()throws SQLException{if(rsClosed)return;rsClosed=true;drainCurrentResult();}
    /** Marks the result closed without touching the socket; used when the connection is closing. */
    void abort(){rsClosed=true;done=true;resultEnded=true;if(externalTable!=null){externalTable.close();externalTable=null;}}
    private void drainCurrentResult()throws SQLException{
        if(connection!=null&&connection.closed){done=true;resultEnded=true;return;}
        if(done||resultEnded)return;
        try{
            while(!done&&!resultEnded){
                int t=p.readMessageType(),h=p.readInt();
                switch(t){
                    case 'C'->{updateCount=parseCommand(frame(t,h));resultEnded=true;completeOperation();}
                    case 'Z'->{done=true;resultEnded=true;doneMessage();}
                    case 'Y'->{p.readInt();p.skipFrame();}
                    case 'D'->p.skipFrame();
                    case 'E'->{boolean timedOut=wasTimedOut();SQLException e=Protocol.serverError(frame(t,h));drainAfterError();throw timeoutError(e,timedOut);}
                    case 'u','U','l','e','x'->handleExternal(t,h);
                    case 'N'->addWarning(Protocol.noticeWarning(frame(t,h)));default->p.readFrame();
                }
            }
        }catch(SQLException e){throw e;}catch(java.io.IOException e){fail(e);throw new SQLException("Could not drain result set","08006",e);}
    }
    /** Drain every remaining result before reusing or closing the physical session. */
    void finish()throws SQLException{
        rsClosed=true;
        if(connection!=null&&connection.closed){done=true;resultEnded=true;return;}
        if(done)return;
        try{
            while(!done){
                int t=p.readMessageType(),h=p.readInt();
                switch(t){
                    case 'Z'->{done=true;resultEnded=true;doneMessage();}
                    case 'Y'->{p.readInt();p.skipFrame();}
                    case 'D'->p.skipFrame();
                    case 'E'->{boolean timedOut=wasTimedOut();SQLException e=Protocol.serverError(frame(t,h));drainAfterError();throw timeoutError(e,timedOut);}
                    case 'u','U','l','e','x'->handleExternal(t,h);
                    case 'N'->addWarning(Protocol.noticeWarning(frame(t,h)));default->p.readFrame();
                }
            }
        }catch(SQLException e){throw e;}catch(java.io.IOException e){fail(e);throw new SQLException("Could not finish query response","08006",e);}
        finally{if(externalTable!=null)externalTable.close();}
    }
    /** Advance to exactly one following JDBC result, leaving later results unread. */
    boolean nextResult()throws SQLException{
        close();
        clearWarnings();
        rs=null;generation++;rsClosed=false;rowReady=false;lazyRow=false;lazyDbos=false;lazyData=null;rows=0;maxRows=0;columns.clear();
        hasRows=false;initialUpdateCount=-1;updateCount=-1;resultEnded=false;
        if(connection!=null&&connection.closed){done=true;resultEnded=true;return false;}
        if(done)return false;
        try{
            while(!done){
                int t=p.readMessageType(),h=p.readInt();
                switch(t){
                    case 'T'->{parseRowDescription(frame(t,h));hasRows=true;}
                    case 'X'->{parseDbosDescription(frame(t,h));hasRows=true;}
                    case 'D'->{parseLazyDataRow(frame(t,h));rowReady=true;hasRows=true;return true;}
                    case 'Y'->{readLazyDbosRow(true);rowReady=true;hasRows=true;return true;}
                    case 'C'->{updateCount=parseCommand(frame(t,h));initialUpdateCount=updateCount;resultEnded=true;completeOperation();return hasRows;}
                    case 'Z'->{done=true;resultEnded=true;doneMessage();return false;}
                    case 'E'->{boolean timedOut=wasTimedOut();SQLException e=Protocol.serverError(frame(t,h));drainAfterError();throw timeoutError(e,timedOut);}
                    case 'u','U','l','e','x'->handleExternal(t,h);
                    case 'N'->addWarning(Protocol.noticeWarning(frame(t,h)));case 'I','P'->frame(t,h);
                    default->{}
                }
            }
            return false;
        }catch(SQLException e){throw e;}catch(java.io.IOException e){fail(e);throw p.io(e);}
    }
    private void fail(java.io.IOException cause){if(connection!=null)connection.failProtocol(cause);}
    void addWarning(SQLWarning warning){
        if(warning==null)return;
        if(firstWarning==null)firstWarning=warning;else lastWarning.setNextWarning(warning);
        lastWarning=warning;
        if(connection!=null)connection.addWarning(warning);
    }
    SQLWarning warnings(){return firstWarning;}
    void clearWarnings(){firstWarning=null;lastWarning=null;}
    private void handleExternal(int type,int header)throws java.io.IOException,SQLException{
        if(externalTable==null)externalTable=new ExternalTableHandler(p,bulkData);
        externalTable.handle(type,header);
    }
    private void doneMessage()throws SQLException{
        completeOperation();
        if(externalTable!=null&&externalTable.deferredError()!=null)throw externalTable.deferredError();
    }
    private void completeOperation(){if(connection!=null)connection.completeOperation(operationToken);}
    private boolean wasTimedOut(){return connection!=null&&connection.wasTimedOut(operationToken);}
    private static SQLException timeoutError(SQLException error,boolean timedOut){
        if(!timedOut)return error;
        String state=error.getSQLState();
        if(state!=null&&state.startsWith("08"))return error;
        return new SQLTimeoutException(error.getMessage(),"57014",error);
    }
    private void parseRowDescription(byte[] b)throws java.io.IOException{if(b.length<2)return;ByteBuffer x=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);int n=x.getShort()&65535;columns.clear();columnOids=new int[n];for(int i=0;i<n;i++){String name=cstring(x);int oid=x.getInt();short size=x.getShort();int mod=x.getInt();int format=x.get()&255;columnOids[i]=oid;columns.add(new Column(name,oid,size,mod,format));}hasRows=true;}
    private byte[] frame(int type,int header)throws java.io.IOException{int len=p.readInt();if(len<0||len>Protocol.MAX_MESSAGE)throw new java.io.IOException("Invalid backend payload length "+len+" (type="+(char)type+", header="+header+")");return p.readExact(len);}
    private byte[] frameReusable(int type,int header)throws java.io.IOException{int len=p.readInt();if(len<0||len>Protocol.MAX_MESSAGE)throw new java.io.IOException("Invalid backend payload length "+len+" (type="+(char)type+", header="+header+")");return p.readExactReusable(len);}
    private void parseDbosDescription(byte[] b)throws java.io.IOException{
        if(b.length<44)throw new java.io.IOException("Truncated DBOS tuple descriptor");
        ByteBuffer x=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        int version=x.getInt(),nulls=x.getInt();
        int sizeWord=x.getInt(),sizeOf=x.getInt(),fixedCount=x.getInt(),varyingCount=x.getInt(),fixedSize=x.getInt(),max=x.getInt(),n=x.getInt();
        if(n<0||n>100000||b.length<36+n*36+8)throw new java.io.IOException("Invalid DBOS field count "+n);
        while(columns.size()<n)columns.add(new Column("column"+(columns.size()+1),0,(short)0,0,0));
        ensureDbosCapacity(n);
        dbosColumnCount=n;dbosFixedFieldsSize=fixedSize;
        int variableCount=0;
        for(int i=0;i<n;i++){
            int type=x.getInt(),size=x.getInt(),trueSize=x.getInt(),offset=x.getInt(),phys=x.getInt(),log=x.getInt(),nullable=x.getInt(),fixed=x.getInt(),spring=x.getInt();
            Column c=columns.get(i);
            c.dbosType=type;c.size=size;c.trueSize=trueSize;c.offset=offset;c.phys=phys;c.nullable=nullable!=0;c.fixedSize=fixed;c.varyingCount=varyingCount;c.fixedFieldsSize=fixedSize;
            dbosColumnType[i]=type;dbosColumnSize[i]=size;dbosColumnTrueSize[i]=trueSize;dbosColumnOffset[i]=offset;
            dbosNullByte[i]=2+phys/8;dbosNullMask[i]=(byte)(1<<(phys&7));
            dbosFixed[i]=fixed!=0;
            dbosVariableIndex[i]=fixed==0?variableCount++:-1;
        }
        dbosVariableCount=variableCount;
        dbosVariableOffsets=new int[variableCount];
        hasRows=true;
    }
    private void ensureDbosCapacity(int n){
        if(dbosColumnType.length>=n)return;
        int size=Math.max(n,8);
        dbosNullByte=new int[size];dbosColumnOffset=new int[size];dbosColumnSize=new int[size];dbosColumnTrueSize=new int[size];dbosColumnType=new int[size];dbosVariableIndex=new int[size];
        dbosNullMask=new byte[size];dbosFixed=new boolean[size];
    }
    private void readLazyDbosRow(boolean retain)throws java.io.IOException{
        p.readInt();
        int n=p.readInt();
        if(n<0||n>Protocol.MAX_MESSAGE)throw new java.io.IOException("Invalid DBOS tuple length "+n);
        byte[] data=retain?p.readExact(n):p.readExactReusable(n);
        int end=retain?data.length:n;
        ensureLazyCapacity(dbosColumnCount);
        lazyData=data;lazyEnd=end;lazyDbos=true;lazyRow=true;
        lazyVariableOffsetsReady=dbosVariableCount==0;
        java.util.Arrays.fill(lazyResolved,0,dbosColumnCount,false);
        java.util.Arrays.fill(lazyDecoded,0,dbosColumnCount,false);
    }
    private void resolveVariableOffsets()throws SQLException{
        if(lazyVariableOffsetsReady)return;
        int[] offsets=dbosVariableOffsets;
        int pos=dbosFixedFieldsSize;
        for(int i=0;i<dbosVariableCount;i++){
            if(pos+2>lazyEnd)throw new SQLException("Truncated DBOS variable field","HY000");
            offsets[i]=pos;
            int len=(lazyData[pos]&255)|((lazyData[pos+1]&255)<<8);
            if(len<2||pos+len>lazyEnd)throw new SQLException("Invalid DBOS variable field length","HY000");
            pos+=len+(len&1);
        }
        lazyVariableOffsetsReady=true;
    }
    private void resolveLazyDbos(int i)throws SQLException{
        if(lazyResolved[i])return;
        if(i<0||i>=dbosColumnCount)throw new SQLException("Column index out of range: "+(i+1),"07009");
        byte[] data=lazyData;
        int nullByte=dbosNullByte[i];
        if(nullByte<0||nullByte>=lazyEnd)throw new SQLException("Invalid DBOS null bitmap","HY000");
        if((data[nullByte]&dbosNullMask[i])!=0){
            lazyNulls[i]=true;lazyOffsets[i]=-1;lazyLengths[i]=0;lazyResolved[i]=true;return;
        }
        if(!dbosFixed[i])resolveVariableOffsets();
        int off=dbosFixed[i]?dbosColumnOffset[i]:(dbosVariableIndex[i]>=0&&dbosVariableIndex[i]<dbosVariableOffsets.length?dbosVariableOffsets[dbosVariableIndex[i]]:-1);
        if(off<0||off>=lazyEnd)throw new SQLException("Invalid DBOS field offset for column "+(i+1),"HY000");
        int len=dbosFixed[i]?(dbosColumnType[i]==7?dbosColumnTrueSize[i]:dbosColumnSize[i]):((data[off]&255)|((data[off+1]&255)<<8))-2;
        int start=off+(dbosFixed[i]?0:2);
        if(len<0||start+len>lazyEnd)throw new SQLException("Invalid DBOS field length for column "+(i+1),"HY000");
        lazyNulls[i]=false;lazyOffsets[i]=start;lazyLengths[i]=len;lazyResolved[i]=true;
    }
    private Object decodeDbos(int i)throws SQLException{
        Column c=columns.get(i);
        try{return decode(c.dbosType,c,lazyData,lazyOffsets[i],lazyLengths[i]);}
        catch(java.io.IOException e){throw new SQLException("Invalid DBOS value for column "+c.name,"HY000",e);}
    }
    private Object decode(int t,Column c,byte[] a,int off,int len)throws java.io.IOException{return switch(t){
        case 2->Double.longBitsToDouble(littleLong(a,off));
        case 3->littleInt(a,off);
        case 4->Float.intBitsToFloat(littleInt(a,off));
        case 6->java.time.LocalDate.of(2000,1,1).plusDays(littleInt(a,off));
        case 7->decodeNumeric(c,a,off,len);
        case 8->java.time.LocalTime.ofNanoOfDay(littleLong(a,off)*1000L);
        case 9->decodeTimestamp(a,off);
        case 10->decodeInterval(a,off,len);
        case 11->decodeTimeTz(a,off,len);
        case 12->a[off]!=0;
        case 13->(byte)a[off];
        case 14,23->Arrays.copyOfRange(a,off,off+len);
        case 15,16,18,21,22,30,31,32->new String(a,off,len,charVarcharCharset);
        case 17->new String(a,off,len,StandardCharsets.UTF_8);
        case 19->(short)littleShort(a,off);
        case 20->littleLong(a,off);
        case 25,26->new String(a,off,len,StandardCharsets.UTF_8);
        default->new String(a,off,len,StandardCharsets.UTF_8);
    };}
    private static LocalDateTime decodeTimestamp(byte[] a,int off){
        long micros=littleLong(a,off);
        long seconds=Math.floorDiv(micros,1_000_000L);
        int nanos=(int)Math.floorMod(micros,1_000_000L)*1000;
        return LocalDateTime.of(2000,1,1,0,0).plusSeconds(seconds).plusNanos(nanos);
    }
    private static String decodeInterval(byte[] a,int off,int len)throws java.io.IOException{
        if(len<12)throw new java.io.IOException("Truncated DBOS INTERVAL value");
        long micros=littleLong(a,off);
        int months=littleInt(a,off+8);
        StringBuilder out=new StringBuilder();
        if(months!=0){
            int years=months/12, mons=months%12;
            if(years!=0)out.append(years).append(unit(years," year"," years"));
            if(mons!=0){
                if(out.length()>0)out.append(' ');
                out.append(mons).append(unit(mons," mon"," mons"));
            }
        }
        if(micros!=0){
            if(out.length()>0)out.append(' ');
            appendIntervalTime(out,micros,months<0);
        }else if(out.length()==0){
            out.append("00:00:00");
        }
        return out.toString();
    }
    private static void appendIntervalTime(StringBuilder out,long micros,boolean negativeMonths){
        long days=micros/86_400_000_000L;
        long rest=micros%86_400_000_000L;
        if(days!=0){
            if(negativeMonths&&days>0)out.append('+');
            out.append(days).append(unit(days," day"," days"));
            if(rest!=0){
                out.append(' ');
                appendSignedTime(out,rest,negativeMonths);
            }
        }else{
            appendSignedTime(out,rest,negativeMonths);
        }
    }
    private static void appendSignedTime(StringBuilder out,long micros,boolean negativeMonths){
        if(micros<0)out.append('-');
        else if(micros>0&&negativeMonths)out.append('+');
        long abs=Math.abs(micros);
        long hours=abs/3_600_000_000L;
        long minutes=(abs%3_600_000_000L)/60_000_000L;
        long seconds=(abs%60_000_000L)/1_000_000L;
        long fraction=abs%1_000_000L;
        out.append(String.format(Locale.ROOT,"%02d:%02d:%02d",hours,minutes,seconds));
        if(fraction!=0){
            String digits=String.format(Locale.ROOT,"%06d",fraction);
            int end=digits.length();
            while(end>0&&digits.charAt(end-1)=='0')end--;
            out.append('.').append(digits,0,end);
        }
    }
    private static String unit(long value,String singular,String plural){
        return Math.abs(value)==1?singular:plural;
    }
    private static String decodeTimeTz(byte[] a,int off,int len)throws java.io.IOException{
        if(len<12)throw new java.io.IOException("Truncated DBOS TIMETZ value");
        long micros=littleLong(a,off);
        int zone=littleInt(a,off+len-4);
        StringBuilder out=new StringBuilder();
        appendSignedTime(out,micros,false);
        int offset=-zone;
        int abs=Math.abs(offset);
        out.append(offset<0?'-':'+').append(String.format(Locale.ROOT,"%02d",abs/3600));
        int minutes=(abs%3600)/60, seconds=abs%60;
        if(minutes!=0||seconds!=0)out.append(':').append(String.format(Locale.ROOT,"%02d",minutes));
        if(seconds!=0)out.append(':').append(String.format(Locale.ROOT,"%02d",seconds));
        return out.toString();
    }
    private static int littleShort(byte[] a,int p){return (a[p]&255)|(a[p+1]<<8);}
    private static int littleInt(byte[] a,int p){return (a[p]&255)|((a[p+1]&255)<<8)|((a[p+2]&255)<<16)|(a[p+3]<<24);}
    private static long littleLong(byte[] a,int p){return ((long)a[p]&255L)|(((long)a[p+1]&255L)<<8)|(((long)a[p+2]&255L)<<16)|(((long)a[p+3]&255L)<<24)|(((long)a[p+4]&255L)<<32)|(((long)a[p+5]&255L)<<40)|(((long)a[p+6]&255L)<<48)|((long)a[p+7]<<56);}
    static java.math.BigDecimal decodeNumeric(Column c,byte[] a,int off,int len)throws java.io.IOException{int precision=(c.size>>8)&0x7f,scale=c.size&255,words=c.trueSize/4;if(words<=0)words=precision<=9?1:precision<=18?2:4;if(words>len/4)throw new java.io.IOException("Truncated DBOS NUMERIC value");if(words==1)return java.math.BigDecimal.valueOf(littleInt(a,off),scale);if(words==2){long unscaled=((long)littleInt(a,off)<<32)|(littleInt(a,off+4)&0xffffffffL);return java.math.BigDecimal.valueOf(unscaled,scale);}byte[] be=new byte[words*4];for(int w=0;w<words;w++){int src=off+w*4,dst=w*4;be[dst]=a[src+3];be[dst+1]=a[src+2];be[dst+2]=a[src+1];be[dst+3]=a[src];}return new java.math.BigDecimal(new java.math.BigInteger(be),scale);}
    private void parseLazyDataRow(byte[] b)throws java.io.IOException{parseLazyDataRow(b,b.length);}
    private void parseLazyDataRow(byte[] b,int length)throws java.io.IOException{
        int n=columns.size(),bitmap=(n+7)/8;
        if(length<bitmap||length>b.length)throw new java.io.IOException("Truncated DataRow null bitmap");
        ensureLazyCapacity(n);
        int pos=bitmap;
        for(int i=0;i<n;i++){
            boolean present=(b[i/8]&(1<<(7-(i&7))))!=0;
            if(!present){lazyNulls[i]=true;lazyOffsets[i]=-1;lazyLengths[i]=0;continue;}
            if(pos+4>length)throw new java.io.IOException("Truncated DataRow field length");
            int len=Protocol.i32(b,pos);pos+=4;
            if(len<4||len-4>length-pos)throw new java.io.IOException("Invalid DataRow field length "+len);
            lazyNulls[i]=false;lazyOffsets[i]=pos;lazyLengths[i]=len-4;pos+=len-4;
        }
        if(pos!=length)throw new java.io.IOException("DataRow length mismatch: consumed "+pos+" of "+length+" bytes");
        lazyData=b;lazyEnd=length;lazyDbos=false;lazyRow=true;
        for(int i=0;i<n;i++)lazyDecoded[i]=false;
    }
    private void ensureLazyCapacity(int n){
        if(lazyOffsets.length>=n)return;
        int size=Math.max(n,8);
        lazyOffsets=new int[size];lazyLengths=new int[size];lazyNulls=new boolean[size];lazyDecoded=new boolean[size];lazyResolved=new boolean[size];lazyValues=new Object[size];
    }
    Object value(int i)throws SQLException{
        if(i<0||i>=columns.size())throw new SQLException("Column index out of range: "+(i+1),"07009");
        if(!lazyRow)throw new SQLException("Cursor is not positioned on a row","24000");
        if(lazyDbos)resolveLazyDbos(i);
        lastWasNull=lazyNulls[i];
        if(lastWasNull)return null;
        if(lazyDecoded[i])return lazyValues[i];
        Object v=lazyDbos?decodeDbos(i):decodePg(columnOids[i],lazyData,lazyOffsets[i],lazyLengths[i],charVarcharCharset);
        lazyValues[i]=v;lazyDecoded[i]=true;return v;
    }
    boolean isNull(int i)throws SQLException{
        if(i<0||i>=columns.size())throw new SQLException("Column index out of range: "+(i+1),"07009");
        if(!lazyRow)throw new SQLException("Cursor is not positioned on a row","24000");
        if(lazyDbos)resolveLazyDbos(i);
        lastWasNull=lazyNulls[i];
        return lastWasNull;
    }
    int intValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==3)return littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==20)return (int)littleLong(lazyData,lazyOffsets[i]);
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).intValue();
                    if(t==12)return lazyData[lazyOffsets[i]]!=0?1:0;
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==23||oid==26||oid==28||oid==2500)return parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==21)return (short)parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==20)return (int)parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.intValue();
    }
    long longValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==20)return littleLong(lazyData,lazyOffsets[i]);
                    if(t==3)return littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).longValue();
                    if(t==12)return lazyData[lazyOffsets[i]]!=0?1:0;
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==20)return parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==23||oid==26||oid==28||oid==2500||oid==21)return parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.longValue();
    }
    short shortValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==3)return (short)littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return (short)littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==20)return (short)littleLong(lazyData,lazyOffsets[i]);
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).shortValue();
                    if(t==12)return (short)(lazyData[lazyOffsets[i]]!=0?1:0);
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==20)return (short)parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==23||oid==26||oid==28||oid==2500||oid==21)return (short)parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.shortValue();
    }
    byte byteValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==3)return (byte)littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return (byte)littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==20)return (byte)littleLong(lazyData,lazyOffsets[i]);
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).byteValue();
                    if(t==12)return (byte)(lazyData[lazyOffsets[i]]!=0?1:0);
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==20)return (byte)parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==23||oid==26||oid==28||oid==2500||oid==21)return (byte)parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.byteValue();
    }
    float floatValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==4)return Float.intBitsToFloat(littleInt(lazyData,lazyOffsets[i]));
                    if(t==2)return (float)Double.longBitsToDouble(littleLong(lazyData,lazyOffsets[i]));
                    if(t==3)return littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==20)return littleLong(lazyData,lazyOffsets[i]);
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).floatValue();
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==700)return parseFloat(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==701)return (float)parseDouble(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==20)return parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==23||oid==26||oid==28||oid==2500||oid==21)return parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==1700)return parseDecimal(lazyData,lazyOffsets[i],lazyLengths[i]).floatValue();
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.floatValue();
    }
    double doubleValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return 0;
            if(lazyDbos){
                Column c=columns.get(i);int t=c.dbosType;
                try{
                    if(t==2)return Double.longBitsToDouble(littleLong(lazyData,lazyOffsets[i]));
                    if(t==4)return Float.intBitsToFloat(littleInt(lazyData,lazyOffsets[i]));
                    if(t==3)return littleInt(lazyData,lazyOffsets[i]);
                    if(t==19)return littleShort(lazyData,lazyOffsets[i]);
                    if(t==13)return lazyData[lazyOffsets[i]];
                    if(t==20)return littleLong(lazyData,lazyOffsets[i]);
                    if(t==7)return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]).doubleValue();
                }catch(java.io.IOException|RuntimeException ignored){}
            }else{
                int oid=columnOids[i];
                try{
                    if(oid==701)return parseDouble(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==700)return parseFloat(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==20)return parseLong(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==23||oid==26||oid==28||oid==2500||oid==21)return parseInt(lazyData,lazyOffsets[i],lazyLengths[i]);
                    if(oid==1700)return parseDecimal(lazyData,lazyOffsets[i],lazyLengths[i]).doubleValue();
                }catch(RuntimeException ignored){}
            }
        }
        Number n=numberValue(i);
        return n==null?0:n.doubleValue();
    }
    boolean booleanValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return false;
        }
        if(lazyRow&&lazyDbos&&columns.get(i).dbosType==12)return lazyData[lazyOffsets[i]]!=0;
        if(lazyRow&&!lazyDbos&&columnOids[i]==16){byte b=lazyData[lazyOffsets[i]];return b=='t'||b=='T'||b=='1';}
        Object v=value(i);
        if(v==null)return false;
        if(v instanceof Boolean b)return b;
        if(v instanceof Number n)return n.intValue()!=0;
        String s=v.toString();
        return s.equalsIgnoreCase("true")||s.equals("1")||s.equalsIgnoreCase("t");
    }
    java.math.BigDecimal bigDecimalValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow){
            lastWasNull=lazyNulls[i];
            if(lastWasNull)return null;
            if(lazyDbos){
                Column c=columns.get(i);
                if(c.dbosType==7){try{return decodeNumeric(c,lazyData,lazyOffsets[i],lazyLengths[i]);}catch(java.io.IOException ignored){}}
            }else if(columnOids[i]==1700){
                try{return parseDecimal(lazyData,lazyOffsets[i],lazyLengths[i]);}catch(RuntimeException ignored){}
            }
        }
        Object v=value(i);
        if(v==null)return null;
        if(v instanceof java.math.BigDecimal b)return b;
        try{return new java.math.BigDecimal(v.toString());}catch(NumberFormatException e){throw new SQLException("Value is not numeric: "+v,"22018",e);}
    }
    byte[] bytesValue(int i)throws SQLException{
        if(lazyRow&&lazyDbos)resolveLazyDbos(i);
        if(lazyRow)lastWasNull=lazyNulls[i];
        if(lazyRow&&!lazyNulls[i]&&lazyDbos){
            int t=columns.get(i).dbosType;
            if(t==14||t==23)return Arrays.copyOfRange(lazyData,lazyOffsets[i],lazyOffsets[i]+lazyLengths[i]);
        }
        Object v=value(i);
        if(v==null)return null;
        if(v instanceof byte[] b)return b;
        return v.toString().getBytes(StandardCharsets.UTF_8);
    }
    private Number numberValue(int i)throws SQLException{
        Object v=value(i);
        if(v==null)return null;
        if(v instanceof Number n)return n;
        try{return new java.math.BigDecimal(v.toString());}catch(NumberFormatException e){throw new SQLException("Value is not numeric: "+v,"22018",e);}
    }
    static Object[] decodeDataRow(List<Column> columns,byte[] b)throws java.io.IOException{return decodeDataRow(columns,b,StandardCharsets.UTF_8);}
    static Object[] decodeDataRow(List<Column> columns,byte[] b,Charset textCharset)throws java.io.IOException{Object[] out=new Object[columns.size()];decodeDataRow(columns,b,textCharset,out,null);return out;}
    static void decodeDataRow(List<Column> columns,byte[] b,Charset textCharset,Object[] out,int[] oids)throws java.io.IOException{int n=columns.size(),bitmap=(n+7)/8;if(b.length<bitmap)throw new java.io.IOException("Truncated DataRow null bitmap");int pos=bitmap;for(int i=0;i<n;i++){boolean present=(b[i/8]&(1<<(7-(i&7))))!=0;if(!present){out[i]=null;continue;}if(pos+4>b.length)throw new java.io.IOException("Truncated DataRow field length");int len=Protocol.i32(b,pos);pos+=4;if(len<4||len-4>b.length-pos)throw new java.io.IOException("Invalid DataRow field length "+len);int oid=oids!=null&&i<oids.length?oids[i]:columns.get(i).oid;out[i]=decodePg(oid,b,pos,len-4,textCharset);pos+=len-4;}if(pos!=b.length)throw new java.io.IOException("DataRow length mismatch: consumed "+pos+" of "+b.length+" bytes");}
    static Object decodePg(int oid,byte[] v,int off,int len){return decodePg(oid,v,off,len,StandardCharsets.UTF_8);}
    static Object decodePg(int oid,byte[] v,int off,int len,Charset textCharset){
        try{
            return switch(oid){
                case 16->len>0&&(v[off]=='t'||v[off]=='T'||v[off]=='1');
                case 20->Long.valueOf(parseLong(v,off,len));
                case 21->Short.valueOf((short)parseInt(v,off,len));
                case 23,26,28,2500->Integer.valueOf(parseInt(v,off,len));
                case 700->Float.valueOf(parseFloat(v,off,len));
                case 701->Double.valueOf(parseDouble(v,off,len));
                case 1700->parseDecimal(v,off,len);
                case 1082->parseDate(v,off,len);
                case 1083->java.time.LocalTime.parse(new String(v,off,len,StandardCharsets.UTF_8));
                case 1114->Timestamp.valueOf(new String(v,off,len,StandardCharsets.UTF_8).replace('T',' '));
                case 17,2568,2569->decodeBytes(new String(v,off,len,StandardCharsets.UTF_8));
                case 18,25,1042,1043->new String(v,off,len,textCharset);
                default->new String(v,off,len,StandardCharsets.UTF_8);
            };
        }catch(Exception e){
            return new String(v,off,len,(oid==18||oid==25||oid==1042||oid==1043)?textCharset:StandardCharsets.UTF_8);
        }
    }
    private static long parseLong(byte[] v,int off,int len){
        int i=off,end=off+len;
        while(i<end&&v[i]==' ')i++;
        while(end>i&&v[end-1]==' ')end--;
        boolean negative=false;
        if(i<end&&(v[i]=='-'||v[i]=='+')){negative=v[i]=='-';i++;}
        if(i>=end)throw new NumberFormatException();
        long value=0;
        for(;i<end;i++){
            int digit=v[i]-'0';
            if(digit<0||digit>9)throw new NumberFormatException();
            if(value>(Long.MAX_VALUE-digit)/10)throw new NumberFormatException();
            value=value*10+digit;
        }
        return negative?-value:value;
    }
    private static int parseInt(byte[] v,int off,int len){
        long value=parseLong(v,off,len);
        if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new NumberFormatException();
        return (int)value;
    }
    private static float parseFloat(byte[] v,int off,int len){return Float.parseFloat(new String(v,off,len,StandardCharsets.US_ASCII));}
    private static double parseDouble(byte[] v,int off,int len){return Double.parseDouble(new String(v,off,len,StandardCharsets.US_ASCII));}
    private static java.math.BigDecimal parseDecimal(byte[] v,int off,int len){
        int i=off,end=off+len;
        while(i<end&&v[i]==' ')i++;
        while(end>i&&v[end-1]==' ')end--;
        int numberStart=i;
        boolean negative=false;
        if(i<end&&(v[i]=='-'||v[i]=='+')){negative=v[i]=='-';i++;}
        long unscaled=0;int digits=0,scale=0;boolean dot=false;
        for(;i<end;i++){
            byte b=v[i];
            if(b=='.'){if(dot)throw new NumberFormatException();dot=true;continue;}
            int digit=b-'0';
            if(digit<0||digit>9)throw new NumberFormatException();
            if(digits>=18)return new java.math.BigDecimal(new String(v,numberStart,end-numberStart,StandardCharsets.US_ASCII));
            unscaled=unscaled*10+digit;digits++;
            if(dot)scale++;
        }
        if(digits==0)throw new NumberFormatException();
        return java.math.BigDecimal.valueOf(negative?-unscaled:unscaled,scale);
    }
    private static java.sql.Date parseDate(byte[] v,int off,int len){
        if(len<10)throw new NumberFormatException();
        int year=parseInt(v,off,4),month=parseInt(v,off+5,2),day=parseInt(v,off+8,2);
        return java.sql.Date.valueOf(java.time.LocalDate.of(year,month,day));
    }
    static String decodeText(int dbosType,byte[] bytes,int offset,int length,Charset charVarcharCharset){return new String(bytes,offset,length,(dbosType==15||dbosType==16)?charVarcharCharset:StandardCharsets.UTF_8);}
    private static byte[] decodeBytes(String s){try{if(s.startsWith("\\x"))return HexFormat.of().parseHex(s.substring(2));if(s.length()>2&&(s.charAt(0)=='x'||s.charAt(0)=='X')&&s.charAt(1)=='\'')return HexFormat.of().parseHex(s.substring(2,s.length()-1));}catch(Exception ignored){}return s.getBytes(StandardCharsets.UTF_8);}
    private int parseCommand(byte[] b){String s=new String(b,StandardCharsets.UTF_8).trim();String[] p=s.split(" ");try{return Integer.parseInt(p[p.length-1]);}catch(Exception e){return 0;}}
    private String cstring(ByteBuffer x)throws java.io.IOException{int s=x.position();while(x.hasRemaining()&&x.get()!=0){}if(!x.hasRemaining()&&x.get(x.limit()-1)!=0)throw new java.io.IOException("Unterminated protocol string");int e=x.position()-1;byte[] v=new byte[e-s];for(int i=0;i<v.length;i++)v[i]=x.get(s+i);return new String(v,StandardCharsets.UTF_8);}
    static final class Column{String name;int oid;short wireSize;int modifier,format,dbosType,size,trueSize,offset,phys,fixedSize,fixedFieldsSize,varyingCount;Boolean nullable;Column(String n,int o,int s,int m,int f){name=n;oid=o;wireSize=(short)s;modifier=m;format=f;}}
}
