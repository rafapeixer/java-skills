package com.example.guarded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The guardrails are configuration; this test fails the build when someone loosens one. */
class GuardrailsTest {

    @Test
    void codeStillWorks() {
        assertEquals(3_000, Prices.total(List.of(1_000L, 2_000L)));
    }

    @Test
    void everyPluginIsPinnedAndWarningsFailTheBuild() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        NodeList plugins = pom.getElementsByTagName("plugin");
        assertTrue(plugins.getLength() >= 10, "expected the lifecycle plugins to be declared");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element p = (Element) plugins.item(i);
            String id = text(p, "artifactId");
            assertTrue(text(p, "version").matches("\\d+(\\.\\d+)+"), id + " needs an exact release version");
        }
        assertEquals("true", text(pom.getDocumentElement(), "failOnWarning"));
        assertEquals("-Xlint:all", text(pom.getDocumentElement(), "arg"));
        int cvss = Integer.parseInt(text(pom.getDocumentElement(), "failBuildOnCVSS"));
        assertTrue(cvss >= 0 && cvss <= 10, "failBuildOnCVSS " + cvss + " can never fail the build");
    }

    @Test
    void wrapperPinsMavenWithAChecksum() throws Exception {
        Properties wrapper = new Properties();
        try (var in = new FileInputStream(".mvn/wrapper/maven-wrapper.properties")) {
            wrapper.load(in);
        }
        assertTrue(wrapper.getProperty("distributionUrl").contains("/apache-maven/3.10."));
        assertTrue(wrapper.getProperty("distributionSha256Sum", "").matches("[0-9a-f]{64}"));
    }

    @Test
    void workflowCannotGoGreenByAccident() throws Exception {
        String ci = Files.readString(Path.of("ci.yml"));
        for (String forbidden : List.of("continue-on-error: true", "|| true", "skipTests", "testFailureIgnore",
                "maven.test.skip", "-Dmaven.test.failure.ignore", "mvn ")) {
            assertFalse(ci.contains(forbidden), "ci.yml contains " + forbidden);
        }
        assertTrue(ci.contains("./mvnw -B"), "CI must run the committed wrapper in batch mode");
        assertTrue(ci.contains("permissions:\n  contents: read"), "GITHUB_TOKEN must default to read-only");
        Matcher uses = Pattern.compile("uses: (\\S+)").matcher(ci);
        while (uses.find()) {
            assertTrue(uses.group(1).matches("[\\w.-]+/[\\w.-]+@[0-9a-f]{40}"), uses.group(1) + " is not a commit SHA");
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
