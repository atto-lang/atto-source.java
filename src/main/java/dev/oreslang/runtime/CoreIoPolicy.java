package dev.oreslang.runtime;

/** Mandatory guest core I/O invariant, independent of capability grants. */
public enum CoreIoPolicy {
    NATIVE_ASYNC_ONLY;

    public static CoreIoPolicy fromApplicationArguments(String[] args) {
        boolean specified = false;
        for (String arg : args) {
            if (!arg.startsWith("--ores-core-io=")) continue;
            if (specified) throw new IllegalArgumentException("duplicate --ores-core-io policy");
            specified = true;
            if (!arg.equals("--ores-core-io=native-async-only"))
                throw new IllegalArgumentException("core I/O is mandatory native-async-only; blocking compatibility modes are forbidden");
        }
        return NATIVE_ASYNC_ONLY;
    }

    public UnsupportedOperationException blocking(String operation) {
        return new UnsupportedOperationException(operation
                + " has no native async-only implementation; blocking host I/O and worker fallbacks are disabled");
    }

    public void rejectBlocking(String operation) { throw blocking(operation); }
}
