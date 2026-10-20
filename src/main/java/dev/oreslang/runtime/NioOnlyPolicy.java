package dev.oreslang.runtime;

/**
 * Explicit migration boundary for the legacy host transports.
 *
 * <p>Strict mode rejects synchronous/virtual-thread-offloaded I/O rather
 * than presenting a blocked host worker as a genuinely asynchronous syscall.
 * The current default remains legacy-compat until std/net, HTTP, FS and stdio
 * have proven nonblocking backend parity. The eventual release gate switches
 * that default; never silently substitute legacy work in strict mode.
 */
final class NioOnlyPolicy {
    static final String PROPERTY = "ores.runtime.io.mode";
    enum Mode { NIO_ONLY, LEGACY_COMPAT }

    private NioOnlyPolicy() { }

    static Mode parse(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("legacy-compat"))
            return Mode.LEGACY_COMPAT;
        if (value.equalsIgnoreCase("nio-only")) return Mode.NIO_ONLY;
        throw new IllegalArgumentException(
                PROPERTY + " must be nio-only or legacy-compat");
    }

    static boolean enabled() {
        return parse(System.getProperty(PROPERTY)) == Mode.NIO_ONLY;
    }

    static UnsupportedOperationException denyLegacy(String operation) {
        return new UnsupportedOperationException(
                operation + " uses a legacy blocking host I/O adapter and is unavailable in "
                + PROPERTY + "=nio-only; no blocking-worker fallback is permitted");
    }

    static void requireLegacyAllowed(String operation) {
        if (enabled()) throw denyLegacy(operation);
    }
}
