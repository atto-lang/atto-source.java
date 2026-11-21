package dev.oreslang.runtime;

/**
 * Explicit migration boundary for legacy host transports.
 *
 * <p>Strict mode rejects synchronous or virtual-thread-offloaded host I/O
 * rather than presenting a parked host worker as a genuinely nonblocking
 * syscall. Supported NIO operations must use the VM-owned readiness domain.
 */
final class NioOnlyPolicy {
    static final String PROPERTY = "ores.runtime.io.mode";

    enum Mode { NIO_ONLY, LEGACY_COMPAT }

    private NioOnlyPolicy() { }

    static Mode parse(String value) {
        if (value == null || value.isBlank()
                || value.equalsIgnoreCase("legacy-compat")) {
            return Mode.LEGACY_COMPAT;
        }
        if (value.equalsIgnoreCase("nio-only")) return Mode.NIO_ONLY;
        throw new IllegalArgumentException(
                PROPERTY + " must be nio-only or legacy-compat");
    }

    static boolean enabled() {
        return parse(System.getProperty(PROPERTY)) == Mode.NIO_ONLY;
    }

    static UnsupportedOperationException denyLegacy(String operation) {
        return new UnsupportedOperationException(
                operation
                        + " uses a legacy blocking host I/O adapter and is unavailable in "
                        + PROPERTY
                        + "=nio-only; no blocking-worker fallback is permitted");
    }

    static void requireLegacyAllowed(String operation) {
        if (enabled()) throw denyLegacy(operation);
    }
}
