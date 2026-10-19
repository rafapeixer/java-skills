package com.example.versions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class JavaFloorTest {

    @Test
    void classFilesTargetTheFloorEvenWhenBuiltOnANewerJdk() throws Exception {
        try (InputStream in = JavaFloor.class.getResourceAsStream("JavaFloor.class");
                DataInputStream data = new DataInputStream(in)) {
            assertEquals(0xCAFEBABE, data.readInt());
            int minor = data.readUnsignedShort();
            int major = data.readUnsignedShort();
            assertEquals(69, major, "class file 69 is Java 25; --release 25 must hold on JDK 27 too");
            assertEquals(0, minor, "minor 65535 would mean preview features, tied to one JDK release");
        }
    }

    @Test
    void runtimeIsAtOrAboveTheFloor() {
        assertTrue(Runtime.version().feature() >= JavaFloor.FLOOR, "running " + Runtime.version());
        assertDoesNotThrow(() -> JavaFloor.require(Runtime.version()));
    }

    @Test
    void versionsAreComparedAsNumbersNotStrings() {
        assertThrows(IllegalStateException.class, () -> JavaFloor.require(Runtime.Version.parse("21.0.8")));
        assertDoesNotThrow(() -> JavaFloor.require(Runtime.Version.parse("25.0.4.1+1")));
        assertDoesNotThrow(() -> JavaFloor.require(Runtime.Version.parse("27-ea+12")));
        assertTrue("9".compareTo("25") > 0, "as strings, 9 sorts after 25");
    }

    @Test
    void newerJdksRunTheTestsWithTheirStricterOptions() {
        if (Runtime.version().feature() >= 26) {
            assertTrue(ManagementFactory.getRuntimeMXBean().getInputArguments()
                    .contains("--illegal-final-field-mutation=deny"), "the jdk26-plus profile did not apply");
        }
    }

    @Test
    void gatesFollowTheRelease() {
        assertFalse(JavaFloor.compactHeadersByDefault(Runtime.Version.parse("25.0.4.1")));
        assertTrue(JavaFloor.compactHeadersByDefault(Runtime.Version.parse("27")));
    }
}
