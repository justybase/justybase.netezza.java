/*
 * Copyright Krzysztof Duśko
 * SPDX-License-Identifier: Apache-2.0
 * Original copyright notices are retained in LICENSE.
 */
package com.justybase.netezza;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** Builds an SSLContext from custom CA, trust store or client certificate options. */
final class TlsSupport {
    private TlsSupport() {}

    static SSLContext context(Properties options) throws SQLException {
        String caCertificate=trim(options.getProperty("caCertificate"));
        String trustStore=trim(options.getProperty("trustStore"));
        String keyStore=trim(options.getProperty("keyStore"));
        String clientCertificate=trim(options.getProperty("clientCertificate"));
        String clientKey=trim(options.getProperty("clientKey"));
        if(caCertificate==null&&trustStore==null&&keyStore==null&&clientCertificate==null&&clientKey==null)return null;
        try{
            KeyStore trust=null;
            if(trustStore!=null)trust=loadStore(trustStore,options.getProperty("trustStoreType"),options.getProperty("trustStorePassword"),"trust store");
            if(caCertificate!=null){
                if(trust==null){trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);}
                addCertificates(trust,caCertificate);
            }
            TrustManagerFactory trustManagers=null;
            if(trust!=null){
                trustManagers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trust);
            }
            KeyManagerFactory keyManagers=null;
            char[] keyPassword=password(options.getProperty("clientKeyPassword",options.getProperty("keyStorePassword")));
            if(keyStore!=null){
                KeyStore keys=loadStore(keyStore,options.getProperty("keyStoreType"),options.getProperty("keyStorePassword"),"key store");
                keyManagers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keys,keyPassword);
            }else if(clientCertificate!=null||clientKey!=null){
                if(clientCertificate==null||clientKey==null)
                    throw new SQLException("Both clientCertificate and clientKey are required for a PEM client certificate","08001");
                KeyStore keys=KeyStore.getInstance("PKCS12");
                keys.load(null,null);
                List<Certificate> chain=readCertificates(clientCertificate);
                PrivateKey key=readPrivateKey(clientKey,options.getProperty("clientKeyPassword"));
                keys.setKeyEntry("client",key,keyPassword,chain.toArray(new Certificate[0]));
                keyManagers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keys,keyPassword);
            }
            SSLContext context=SSLContext.getInstance("TLS");
            context.init(keyManagers==null?null:keyManagers.getKeyManagers(),
                    trustManagers==null?null:trustManagers.getTrustManagers(),null);
            return context;
        }catch(SQLException e){throw e;}
        catch(Exception e){throw new SQLException("Unable to initialize TLS: "+e.getMessage(),"08001",e);}
    }

    private static KeyStore loadStore(String path,String type,String password,String description)throws Exception{
        String storeType=type==null||type.isBlank()?storeType(path):type;
        KeyStore store=KeyStore.getInstance(storeType);
        try(InputStream input=Files.newInputStream(Path.of(path))){
            store.load(input,password(password));
        }catch(Exception e){
            throw new SQLException("Unable to load TLS "+description+" '"+path+"': "+e.getMessage(),"08001",e);
        }
        return store;
    }

    private static void addCertificates(KeyStore store,String path)throws Exception{
        List<Certificate> certificates=readCertificates(path);
        if(certificates.isEmpty())throw new SQLException("CA certificate file contains no certificates: "+path,"08001");
        for(int i=0;i<certificates.size();i++)store.setCertificateEntry("ca-"+i,certificates.get(i));
    }

    private static List<Certificate> readCertificates(String path)throws Exception{
        try(InputStream input=Files.newInputStream(Path.of(path))){
            List<Certificate> certificates=new ArrayList<>();
            for(Certificate certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))certificates.add(certificate);
            return certificates;
        }catch(Exception e){
            throw new SQLException("Unable to read certificate '"+path+"': "+e.getMessage(),"08001",e);
        }
    }

    private static PrivateKey readPrivateKey(String path,String password)throws Exception{
        String pem=Files.readString(Path.of(path),StandardCharsets.US_ASCII);
        byte[] encoded=decodePem(pem);
        if(pem.contains("ENCRYPTED PRIVATE KEY")){
            if(password==null)throw new SQLException("clientKeyPassword is required for an encrypted private key","08001");
            EncryptedPrivateKeyInfo info=new EncryptedPrivateKeyInfo(encoded);
            SecretKeyFactory factory=SecretKeyFactory.getInstance(info.getAlgName());
            Cipher cipher=Cipher.getInstance(info.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE,factory.generateSecret(new PBEKeySpec(password.toCharArray())));
            encoded=info.getKeySpec(cipher).getEncoded();
        }
        for(String algorithm:List.of("RSA","EC","DSA","Ed25519","Ed448")){
            try{return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));}
            catch(GeneralSecurityException ignored){}
        }
        throw new SQLException("Unsupported private key format in '"+path+"' (PKCS#8 is required)","08001");
    }

    private static byte[] decodePem(String pem)throws SQLException{
        int begin=pem.indexOf("-----BEGIN ");
        int end=pem.indexOf("-----END ");
        if(begin<0||end<0)throw new SQLException("Invalid PEM private key","08001");
        int headerEnd=pem.indexOf("-----",begin+11);
        if(headerEnd<0)throw new SQLException("Invalid PEM private key","08001");
        String base64=pem.substring(headerEnd+5,end).replaceAll("\\s","");
        try{return Base64.getDecoder().decode(base64);}
        catch(IllegalArgumentException e){throw new SQLException("Invalid PEM private key encoding","08001",e);}
    }

    private static String storeType(String path){
        String lower=path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".p12")||lower.endsWith(".pfx")||lower.endsWith(".pkcs12")?"PKCS12":"JKS";
    }

    private static char[] password(String value){return value==null?null:value.toCharArray();}
    private static String trim(String value){return value==null||value.isBlank()?null:value;}
}
