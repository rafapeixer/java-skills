package example;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs javac with the pom's Error Prone flags on two snippets: the build must reject the unsafe one. */
class NullAwayTest {

    @TempDir Path out;

    @Test
    void dereferencingANullableReturnFailsTheBuild() {
        String diagnostics = compile("""
                package demo;

                @org.jspecify.annotations.NullMarked
                class Lookup {
                    @org.jspecify.annotations.Nullable String nickname(String id) { return null; }
                    int length(String id) { return nickname(id).length(); }
                }
                """);
        assertTrue(diagnostics.contains("[NullAway] dereferenced expression 'nickname(id)' is @Nullable"), diagnostics);
    }

    @Test
    void returningNullFromANonNullMethodFailsTheBuild() {
        String diagnostics = compile("""
                package demo;

                @org.jspecify.annotations.NullMarked
                class Lookup {
                    String nickname(String id) { return null; }
                }
                """);
        assertTrue(diagnostics.contains("[NullAway] returning @Nullable expression"), diagnostics);
    }

    @Test
    void checkedCodeCompilesCleanly() {
        assertEquals("", compile("""
                package demo;

                @org.jspecify.annotations.NullMarked
                class Lookup {
                    @org.jspecify.annotations.Nullable String nickname(String id) { return null; }
                    int length(String id) {
                        String nick = nickname(id);
                        return nick == null ? 0 : nick.length();
                    }
                }
                """));
    }

    private String compile(String source) {
        String classpath = System.getProperty("java.class.path");
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///demo/Lookup.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var output = new StringWriter();
        List<String> options = List.of("-d", out.toString(), "-classpath", classpath, "-processorpath", classpath,
                "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW",
                "-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true");
        ToolProvider.getSystemJavaCompiler().getTask(output, null, null, options, null, List.of(file)).call();
        return output.toString();
    }
}
