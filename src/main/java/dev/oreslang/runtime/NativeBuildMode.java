package dev.oreslang.runtime;

import org.graalvm.nativeimage.ImageInfo;

/** Native capability captured during image generation, never from launch-time flags. */
public final class NativeBuildMode {
    public enum Kind { JVM, AOT, HYBRID }
    public enum ManagedReferenceMode { JVM_MANAGED, COMPRESSED32, NATIVE64 }

    private static final Kind KIND = imageBuildKind();
    private static final ManagedReferenceMode MANAGED_REFERENCE_MODE =
            imageManagedReferenceMode();

    private NativeBuildMode() { }

    private static Kind imageBuildKind() {
        if (!ImageInfo.inImageBuildtimeCode()) return Kind.JVM;
        String selected = System.getProperty("ores.native.build-mode");
        if ("aot".equals(selected)) return Kind.AOT;
        if ("hybrid".equals(selected)) return Kind.HYBRID;
        throw new IllegalStateException("Native builds require -Dores.native.build-mode=aot|hybrid");
    }

    private static ManagedReferenceMode imageManagedReferenceMode() {
        return parseManagedReferenceMode(
                ImageInfo.inImageBuildtimeCode(),
                System.getProperty("ores.native.ref-mode"));
    }

    static ManagedReferenceMode parseManagedReferenceMode(boolean nativeImageBuild, String selected) {
        if (!nativeImageBuild) return ManagedReferenceMode.JVM_MANAGED;
        if ("compressed32".equals(selected)) return ManagedReferenceMode.COMPRESSED32;
        if ("native64".equals(selected)) return ManagedReferenceMode.NATIVE64;
        throw new IllegalStateException(
                "Native builds require -Dores.native.ref-mode=compressed32|native64");
    }

    public static Kind kind() { return KIND; }
    public static ManagedReferenceMode managedReferenceMode() {
        return MANAGED_REFERENCE_MODE;
    }
    /**
     * These are *certification* boundaries, not mode switches. Building the
     * Java/Truffle host with Native Image still interprets guest .atto source
     * in the current AOT profile. A compressed Java reference is not an
     * actor-owned offset and Native Image's managed heap can still collect.
     *
     * Do not promote these claims with a runtime system property. A future
     * offline guest compiler must embed verifiable build-time code artifacts,
     * ownership/drop evidence and measured process/guest memory receipts.
     */
    public static boolean fullGuestAotProven() { return false; }
    public static boolean zeroGuestTracingGcProven() { return false; }
    public static boolean zeroProcessGcProven() { return false; }

    public static String defaultExecutionMode() {
        return switch (KIND) {
            case JVM -> "jit";
            case AOT -> "aot";
            case HYBRID -> "hybrid";
        };
    }

    public static void requireCompatible(ExecutionProfile profile) {
        requireCompatible(KIND, profile.mode());
    }

    static void requireCompatible(Kind built, ExecutionProfile.Mode requested) {
        if (built == Kind.AOT && requested != ExecutionProfile.Mode.AOT) {
            throw new IllegalArgumentException("This executable was built AOT-only; --mode="
                    + requested.name().toLowerCase(java.util.Locale.ROOT)
                    + " cannot enable runtime compilation. Rebuild with native-hybrid.");
        }
        if (built == Kind.HYBRID && requested == ExecutionProfile.Mode.JIT) {
            throw new IllegalArgumentException("Native hybrid executables accept --mode=hybrid or --mode=aot, not JVM --mode=jit");
        }
    }
}
