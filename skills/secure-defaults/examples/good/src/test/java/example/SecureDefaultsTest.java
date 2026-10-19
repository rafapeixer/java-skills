package example;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXParseException;

class SecureDefaultsTest {

    @TempDir Path dir;

    @Test
    void tokensAreLongUrlSafeAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = Tokens.newToken();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertTrue(seen.add(token));
        }
    }

    @Test
    void storedTokenMatchesOnlyTheOriginal() {
        String token = Tokens.newToken();
        String stored = Tokens.storedForm(token);
        assertNotEquals(token, stored);
        assertTrue(Tokens.matches(token, stored));
        assertFalse(Tokens.matches(Tokens.newToken(), stored));
    }

    @Test
    void passwordsAreSaltedBcryptBehindAnAlgorithmId() {
        String first = Passwords.hash("correct horse");
        assertTrue(first.startsWith("{bcrypt}$2a$10$"), first);
        assertNotEquals(first, Passwords.hash("correct horse"));
        assertTrue(Passwords.verify("correct horse", first));
        assertFalse(Passwords.verify("wrong horse", first));
        assertFalse(Passwords.needsRehash(first));
        assertTrue(Passwords.needsRehash("{noop}correct horse"));
    }

    @Test
    void xmlWithADoctypeIsRejectedBeforeAnyEntityIsResolved() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";
        assertThrows(SAXParseException.class, () -> SafeInput.parseXml(utf8(xxe)));
        assertEquals("42", SafeInput.parseXml(utf8("<order><qty>42</qty></order>"))
                .getElementsByTagName("qty").item(0).getTextContent());
    }

    @Test
    void legacySessionsDeserializeAndEveryOtherClassIsRejected() throws Exception {
        var session = new SafeInput.Session("u-1", 1_800_000_000L);
        assertEquals(session, SafeInput.readLegacySession(serialize(session)));
        byte[] gadget = serialize(new HashMap<>(Map.of("k", "v")));
        assertThrows(InvalidClassException.class, () -> SafeInput.readLegacySession(gadget));
    }

    @Test
    void tlsTrustsThePrivateCaAndStillChecksTheHostName() throws Exception {
        KeyStore good = keyStore("localhost");
        try (SSLSocket socket = Tls.connect(Tls.trusting(trustOnly(good)), "localhost", serve(good))) {
            assertEquals("TLSv1.3", socket.getSession().getProtocol());
        }
        KeyStore wrongName = keyStore("billing.internal");
        int port = serve(wrongName);
        SSLContext client = Tls.trusting(trustOnly(wrongName));
        assertThrows(SSLHandshakeException.class, () -> Tls.connect(client, "localhost", port));
    }

    @Test
    void jdkDefaultsOfferTls13AndPostQuantumKeyExchangeOnJdk27() throws Exception {
        SSLParameters defaults = SSLContext.getDefault().getDefaultSSLParameters();
        assertEquals(List.of("TLSv1.3", "TLSv1.2"), List.of(defaults.getProtocols()));
        if (Runtime.version().feature() >= 27) {
            assertEquals("X25519MLKEM768", defaults.getNamedGroups()[0]);
        }
    }

    @Test
    void aMissingSecretStopsStartup() {
        assertEquals("s3cr3t", Secrets.required(Map.of("DB_PASSWORD", "s3cr3t"), "DB_PASSWORD"));
        assertThrows(IllegalStateException.class, () -> Secrets.required(Map.of(), "DB_PASSWORD"));
    }

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] serialize(Object value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /** A self-signed certificate for one DNS name, made with the JDK's keytool. */
    private KeyStore keyStore(String dnsName) throws Exception {
        Path file = dir.resolve(dnsName + ".p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=" + dnsName, "-ext", "SAN=dns:" + dnsName, "-validity", "1",
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", "changeit")
                .inheritIO().start();
        assertEquals(0, keytool.waitFor());
        return KeyStore.getInstance(file.toFile(), "changeit".toCharArray());
    }

    private static KeyStore trustOnly(KeyStore server) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("private-ca", server.getCertificate("server"));
        return trust;
    }

    /** Accepts one connection on a loopback port and completes (or fails) the handshake. */
    private static int serve(KeyStore keys) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "changeit".toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (listener; SSLSocket s = (SSLSocket) listener.accept()) {
                s.startHandshake();
                s.getInputStream().read();
            } catch (IOException expectedWhenTheClientRejectsUs) {
                // The client closes or aborts; nothing to do on the server side.
            }
        });
        return listener.getLocalPort();
    }
}
