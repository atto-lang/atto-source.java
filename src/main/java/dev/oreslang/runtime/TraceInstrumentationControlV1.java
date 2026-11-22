package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Supervisor-owned runtime switch for compiler-emitted trace sites.
 * The build mode is immutable: runtime configuration can never create a probe
 * missing from a sealed AOT binary. No guest-facing entrypoint is provided.
 *
 * Note: this gate is a policy substrate, not a compiler pass or an exporter.
 */
final class TraceInstrumentationControlV1 {
    static final int BOUNDARY = 1;
    static final int CALL = 2;
    static final int DIAGNOSTIC = 4;
    static final int ALL = BOUNDARY | CALL | DIAGNOSTIC;

    enum BuildMode {
        NONE(0), BOUNDARIES(BOUNDARY), CALLS(BOUNDARY | CALL),
        FULL(ALL), ADAPTIVE(ALL);

        private final int permitted;
        BuildMode(int permitted) { this.permitted = permitted; }
    }

    private record Policy(int enabledMask, long version) { }

    private final BuildMode buildMode;
    private volatile Policy policy = new Policy(0, 0);

    TraceInstrumentationControlV1(BuildMode buildMode) {
        this.buildMode = Objects.requireNonNull(buildMode, "build mode");
    }

    BuildMode buildMode() { return buildMode; }

    /** Policy-only mutation; actual probe coverage requires emitted code. */
    synchronized long configure(int enabledMask) {
        if ((enabledMask & ~buildMode.permitted) != 0 || enabledMask < 0) {
            throw new IllegalArgumentException("instrumentation exceeds build coverage");
        }
        if (policy.version == Long.MAX_VALUE) {
            throw new IllegalStateException("telemetry policy version exhausted");
        }
        policy = new Policy(enabledMask, policy.version + 1);
        return policy.version;
    }

    /** Hot-path check: zero allocations, zero clocks, zero formatting. */
    boolean enabled(int probeKind) {
        return (probeKind == BOUNDARY || probeKind == CALL || probeKind == DIAGNOSTIC)
                && (policy.enabledMask & probeKind) != 0;
    }

    int activeMask() { return policy.enabledMask; }
    long version() { return policy.version; }

    /** One immutable volatile policy read, avoiding mixed-version mask checks. */
    boolean permitsAtVersion(int probeMask, long expectedVersion) {
        Policy current = policy;
        return probeMask != 0 && (probeMask & ~ALL) == 0
                && current.version == expectedVersion
                && (probeMask & ~current.enabledMask) == 0;
    }

    /** The AOT binary has static sites; adaptive code has to be compiled. */
    boolean hasStaticSite(int probeKind) {
        return buildMode != BuildMode.ADAPTIVE &&
                (probeKind == BOUNDARY || probeKind == CALL || probeKind == DIAGNOSTIC) &&
                (buildMode.permitted & probeKind) != 0;
    }

    boolean canRecompile() { return buildMode == BuildMode.ADAPTIVE; }
}
