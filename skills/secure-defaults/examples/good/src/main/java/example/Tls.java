package example;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** TLS that verifies the peer and keeps the JDK's protocol and key-exchange defaults. */
public final class Tls {

    private Tls() {}

    /** A private CA is trusted by adding it to a trust store, never by turning verification off. */
    public static SSLContext trusting(KeyStore privateCa) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(privateCa);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    /** Raw sockets do not check the host name unless asked; HttpClient and HTTPS URLs always do. */
    public static SSLSocket connect(SSLContext context, String host, int port) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        // No setProtocols, setNamedGroups or setCipherSuites: JDK 27 offers X25519MLKEM768 first.
        socket.setSSLParameters(params);
        socket.startHandshake();
        return socket;
    }
}
