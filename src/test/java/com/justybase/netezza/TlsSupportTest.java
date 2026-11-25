package com.justybase.netezza;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Custom trust stores, CA certificates and client key stores without a live appliance. */
class TlsSupportTest {
    @Test
    void trustStoreCaCertificateAndClientKeyStoreBuildContexts(@TempDir Path directory) throws Exception {
        Path store = createStore(directory);
        Path certificate = exportCertificate(directory, store);

        Properties trustStore = new Properties();
        trustStore.setProperty("trustStore", store.toString());
        trustStore.setProperty("trustStorePassword", "changeit");
        trustStore.setProperty("trustStoreType", "PKCS12");
        assertNotNull(TlsSupport.context(trustStore));

        Properties ca = new Properties();
        ca.setProperty("caCertificate", certificate.toString());
        assertNotNull(TlsSupport.context(ca));

        Properties keys = new Properties();
        keys.setProperty("keyStore", store.toString());
        keys.setProperty("keyStorePassword", "changeit");
        keys.setProperty("keyStoreType", "PKCS12");
        assertNotNull(TlsSupport.context(keys));

        Path privateKey = exportPrivateKey(directory, store);
        Properties pemClient = new Properties();
        pemClient.setProperty("clientCertificate", certificate.toString());
        pemClient.setProperty("clientKey", privateKey.toString());
        assertNotNull(TlsSupport.context(pemClient));
    }

    @Test
    void invalidAndIncompleteTlsConfigurationFailsWith08001(@TempDir Path directory) throws Exception {
        Path store = createStore(directory);
        Path certificate = exportCertificate(directory, store);

        Properties missing = new Properties();
        missing.setProperty("trustStore", directory.resolve("missing.p12").toString());
        missing.setProperty("trustStorePassword", "changeit");
        SQLException missingError = assertThrows(SQLException.class, () -> TlsSupport.context(missing));
        assertEquals("08001", missingError.getSQLState());

        Properties incomplete = new Properties();
        incomplete.setProperty("clientCertificate", certificate.toString());
        SQLException incompleteError = assertThrows(SQLException.class, () -> TlsSupport.context(incomplete));
        assertEquals("08001", incompleteError.getSQLState());
    }

    @Test
    void noCustomTlsOptionsUseThePlatformDefault() throws Exception {
        assertNull(TlsSupport.context(new Properties()));
    }

    private static Path createStore(Path directory) throws Exception {
        Path tool = keytool();
        Path store = directory.resolve("store.p12");
        run(tool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit", "-noprompt");
        return store;
    }

    private static Path exportCertificate(Path directory, Path store) throws Exception {
        Path certificate = directory.resolve("cert.pem");
        run(keytool(), "-exportcert", "-rfc", "-alias", "test", "-keystore", store.toString(),
                "-storepass", "changeit", "-file", certificate.toString());
        return certificate;
    }

    private static Path exportPrivateKey(Path directory, Path store) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream input = Files.newInputStream(store)) {
            keys.load(input, "changeit".toCharArray());
        }
        PrivateKey key = (PrivateKey) keys.getKey("test", "changeit".toCharArray());
        String pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded()) +
                "\n-----END PRIVATE KEY-----\n";
        Path privateKey = directory.resolve("key.pem");
        Files.writeString(privateKey, pem, java.nio.charset.StandardCharsets.US_ASCII);
        return privateKey;
    }

    private static Path keytool() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "keytool.exe" : "keytool";
        Path tool = Path.of(System.getProperty("java.home"), "bin", executable);
        assumeTrue(Files.isExecutable(tool), "keytool is not available");
        return tool;
    }

    private static void run(Path tool, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(tool.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
