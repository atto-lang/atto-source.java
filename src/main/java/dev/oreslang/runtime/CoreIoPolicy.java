package dev.oreslang.runtime;

/** Guest core I/O policy; independent of permission grants and API naming. */
public enum CoreIoPolicy {
    LEGACY_BLOCKING,
    NATIVE_ASYNC_ONLY;

    public static CoreIoPolicy fromApplicationArguments(String[] args) {
        CoreIoPolicy policy = LEGACY_BLOCKING;
        boolean specified = false;
        for (String arg : args) {
            if (!arg.startsWith("--ores-core-io=")) continue;
            if (specified) throw new IllegalArgumentException("duplicate --ores-core-io policy");
            specified = true;
            policy = switch (arg.substring("--ores-core-io=".length())) {
                case "legacy-blocking" -> LEGACY_BLOCKING;
                case "native-async-only" -> NATIVE_ASYNC_ONLY;
                default -> throw new IllegalArgumentException("expected --ores-core-io=native-async-only or legacy-blocking");
            };
        }
        return policy;
    }

    public void requireLegacy(String operation) {
        if (this == NATIVE_ASYNC_ONLY) {
            throw new UnsupportedOperationException(operation
                    + " has no native async-only implementation; blocking host I/O and worker fallbacks are disabled");
        }
    }
}
