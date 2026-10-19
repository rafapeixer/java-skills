package example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Parsers for bytes that came from outside the process. */
public final class SafeInput {

    private SafeInput() {}

    /** XML from a request or a partner: no DOCTYPE, so no external entities and no entity bombs. */
    public static Document parseXml(InputStream in) throws IOException, SAXException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK parser lacks a standard feature", e);
        }
    }

    public record Session(String userId, long expiresAtEpochSecond) implements java.io.Serializable {}

    /** Legacy Java serialization, kept only for old cached sessions: exactly one class, small graph. */
    public static Session readLegacySession(byte[] bytes) throws IOException, ClassNotFoundException {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxdepth=2;maxrefs=10;maxbytes=4096;example.SafeInput$Session;!*"));
            return (Session) in.readObject();
        }
    }
}
