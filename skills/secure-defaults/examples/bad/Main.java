import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Random;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** The overrides older answers and code generators still produce. Compiles; never run it. */
public class Main {

    // MISTAKE: a hard-coded secret ships in the jar and in git history, and every environment shares it.
    static final String SIGNING_KEY = "dev-signing-key-change-me";

    // MISTAKE: java.util.Random is a 48-bit LCG; a few outputs reveal the seed and every later token.
    private static final Random RANDOM = new Random();

    static String newResetToken() {
        return Long.toHexString(RANDOM.nextLong());
    }

    static String newApiKey() throws Exception {
        // MISTAKE: getInstanceStrong() is NativePRNGBlocking on Linux and can stall requests on /dev/random.
        byte[] bytes = new byte[16];
        SecureRandom.getInstanceStrong().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static boolean tokenMatches(String presented, String stored) {
        // MISTAKE: equals() returns at the first differing byte, so timing leaks the stored token.
        return presented.equals(stored);
    }

    static String hashPassword(String password) throws Exception {
        // MISTAKE: one fast unsalted SHA-256: a GPU tries billions of guesses a second, and equal passwords look equal.
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    static Object readSession(byte[] bytes) throws Exception {
        // MISTAKE: no ObjectInputFilter: any gadget class on the classpath runs code during readObject().
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    static Document parseXml(InputStream in) throws Exception {
        // MISTAKE: defaults accept a DOCTYPE, so <!ENTITY x SYSTEM "file:///etc/passwd"> is read and echoed back.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
    }

    static void trustEverything() throws Exception {
        // MISTAKE: a trust-all manager accepts any certificate, so anyone on the path can impersonate the server.
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        // MISTAKE: accepting every host name lets a valid certificate for one site stand in for any other.
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
    }

    static SSLSocket connect(SSLContext context, String host) throws Exception {
        Socket raw = new Socket(host, 443);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, host, 443, true);
        SSLParameters params = socket.getSSLParameters();
        // MISTAKE: a pinned group list drops X25519MLKEM768, the JDK 27 post-quantum default, with no warning.
        params.setNamedGroups(new String[] {"x25519", "secp256r1"});
        // MISTAKE: pinning TLSv1.2 removes TLS 1.3, and the list never picks up later JDK changes.
        params.setProtocols(new String[] {"TLSv1.2"});
        socket.setSSLParameters(params);
        return socket;
    }

    public static void main(String[] args) {
        throw new UnsupportedOperationException("bad example: compile only");
    }
}
