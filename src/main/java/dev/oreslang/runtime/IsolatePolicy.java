package dev.oreslang.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.io.IOAccess;

import java.time.Duration;
import java.util.Set;

/** Security policy for an Oreslang isolate. */
public record IsolatePolicy(
        Set<Capability> capabilities,
        long maxHeapBytes,
        int maxMailboxMessages,
        Duration maxWallTime) {

    public enum Capability {
        STDIN,
        STDOUT,
        NETWORK,
        FILESYSTEM_READ,
        FILESYSTEM_WRITE,
        ENVIRONMENT
    }

    public IsolatePolicy {
        capabilities = Set.copyOf(capabilities);
        if (maxHeapBytes <= 0) throw new IllegalArgumentException("maxHeapBytes must be positive");
        if (maxMailboxMessages <= 0) throw new IllegalArgumentException("maxMailboxMessages must be positive");
        if (maxWallTime.isNegative() || maxWallTime.isZero()) throw new IllegalArgumentException("maxWallTime must be positive");
    }

    public static IsolatePolicy strictFaas() {
        return new IsolatePolicy(Set.of(Capability.STDOUT), 128L * 1024 * 1024, 1024, Duration.ofSeconds(30));
    }

    /**
     * Baseline Graal context: no host reflection, native access, polyglot calls,
     * environment access, guest-created threads, or host IO. Explicit Oreslang
     * capabilities are injected through OresContext instead of loosening this
     * builder globally.
     */
    public Context.Builder restrictedContextBuilder() {
        return Context.newBuilder("ores")
                .allowHostAccess(HostAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowIO(IOAccess.NONE);
    }

    public boolean allows(Capability capability) {
        return capabilities.contains(capability);
    }
}
