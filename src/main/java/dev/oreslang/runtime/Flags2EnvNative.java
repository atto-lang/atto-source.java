package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;

/** Native C parser bridge for canonical flags-2-env. No shell or token reparser. */
public final class Flags2EnvNative {
    private Flags2EnvNative() { }

    private static final class Holder {
        static {
            // Fixed library name: guest source never selects arbitrary native code.
            System.loadLibrary("flags2env_oreslang_jni");
        }
        static final boolean LOADED = true;
    }

    private static native byte[] parseFileBytes(byte[] config, byte[] argv);

    /** The source VM must authorize READ for config and FFI for flags2env. */
    public static String parseFile(String configPath, String argvJson) {
        if (configPath == null || configPath.isBlank() || argvJson == null) {
            throw new IllegalArgumentException("configPath and argvJson required");
        }
        if (configPath.indexOf('\0') >= 0 || argvJson.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("embedded NUL denied");
        }
        if (!Holder.LOADED) throw new IllegalStateException("native library unavailable");
        byte[] output = parseFileBytes(
                configPath.getBytes(StandardCharsets.UTF_8),
                argvJson.getBytes(StandardCharsets.UTF_8));
        if (output == null) throw new IllegalArgumentException("native flags2env rejected input");
        return new String(output, StandardCharsets.UTF_8);
    }
}
