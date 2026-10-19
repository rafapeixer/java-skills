package com.example.versions;

/** Version checks copied from Java 8 era code. */
public final class VersionCheck {

    private VersionCheck() {}

    // MISTAKE: string comparison; "9.0.4".compareTo("25") > 0, so Java 9 passes the check
    // and the service starts on a runtime it was never built for.
    public static boolean supported() {
        return System.getProperty("java.version").compareTo("25") >= 0;
    }

    // MISTAKE: the pre-JDK 9 "1.8.0_402" scheme; on "25.0.4.1" this returns 0, and on a GA
    // build such as "27" there is no second element, so it throws ArrayIndexOutOfBoundsException.
    public static int major() {
        String[] parts = System.getProperty("java.version").split("\\.");
        return Integer.parseInt(parts[1]);
    }

    public static void main(String[] args) {
        if (!supported()) {
            throw new IllegalStateException("unsupported Java");
        }
        System.out.println("Java " + major());
    }
}
