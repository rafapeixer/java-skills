package com.example.versions;

/** The supported Java range, checked once at startup and used for version-gated behavior. */
public final class JavaFloor {

    /** The newest LTS, the same number as maven.compiler.release. */
    public static final int FLOOR = 25;

    private JavaFloor() {}

    /** Fails fast on a runtime below the floor. Compares numbers from Runtime.Version, never strings. */
    public static void require(Runtime.Version running) {
        if (running.feature() < FLOOR) {
            throw new IllegalStateException("needs Java " + FLOOR + "+, running " + running);
        }
    }

    /** A gate for a JDK 27+ default: compact object headers are on by default from JDK 27 (JEP 534). */
    public static boolean compactHeadersByDefault(Runtime.Version running) {
        return running.feature() >= 27;
    }

    public static void main(String[] args) {
        require(Runtime.version());
        System.out.println("running " + Runtime.version() + ", floor " + FLOOR);
    }
}
