package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.io.IOAccess;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Deny-by-default security policy for an Oreslang isolate/context. */
public record IsolatePolicy(
        Set<Capability> capabilities,
        long maxHeapBytes,
        int maxMailboxMessages,
        Duration maxWallTime) {

    public enum Capability {
        STDIN,
        STDOUT,
        PROCESS_INFO,
        ACTOR_SHARE_READONLY,
        NETWORK,
        FILESYSTEM_READ,
        FILESYSTEM_WRITE,
        ENVIRONMENT,
        HOT_CODE_LOAD,
        FFI,
        NATIVE,
        REFLECTION,
        CHILD_PROCESS,
        THREAD_CREATE,
        POLYGLOT
    }

    public IsolatePolicy {
        capabilities = Set.copyOf(capabilities);
        if (maxHeapBytes <= 0) throw new IllegalArgumentException("maxHeapBytes must be positive");
        if (maxMailboxMessages <= 0) throw new IllegalArgumentException("maxMailboxMessages must be positive");
        if (maxWallTime.isNegative() || maxWallTime.isZero()) throw new IllegalArgumentException("maxWallTime must be positive");
    }

    /** Production FaaS baseline: stdout only; everything else is denied. */
    public static IsolatePolicy strictFaas() {
        return new IsolatePolicy(Set.of(Capability.STDOUT), 128L * 1024 * 1024, 1024, Duration.ofSeconds(30));
    }

    /** Local/test baseline. This still denies FFI/native/reflection/process spawning. */
    public static IsolatePolicy developer() {
        return new IsolatePolicy(
                Set.of(Capability.STDIN, Capability.STDOUT, Capability.PROCESS_INFO,
                        Capability.ACTOR_SHARE_READONLY, Capability.HOT_CODE_LOAD),
                512L * 1024 * 1024, 8192, Duration.ofMinutes(10));
    }

    public IsolatePolicy withCapabilities(Capability... added) {
        EnumSet<Capability> next = capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        next.addAll(Arrays.asList(added));
        return new IsolatePolicy(next, maxHeapBytes, maxMailboxMessages, maxWallTime);
    }

    /**
     * Graal baseline: no host reflection, native access, polyglot calls,
     * environment access, guest-created threads, or host filesystem/network IO.
     * Oreslang APIs are admitted separately by CapabilityChecker and runtime
     * facades, so enabling one Oreslang capability never globally enables host
     * access.
     */
    public Context.Builder restrictedContextBuilder() {
        return restrictedContextBuilder(ExecutionProfile.serverJit());
    }

    public Context.Builder restrictedContextBuilder(ExecutionProfile profile) {
        return Context.newBuilder(OresLanguage.ID)
                .allowHostAccess(HostAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowIO(IOAccess.NONE)
                .arguments(OresLanguage.ID, applicationArguments(profile));
    }

    public boolean allows(Capability capability) {
        return capabilities.contains(capability);
    }

    public void require(Capability capability, String api) {
        if (!allows(capability)) {
            throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
        }
    }

    public String[] applicationArguments(ExecutionProfile profile) {
        String caps = capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return new String[] {
                "--ores-capabilities=" + caps,
                "--ores-execution-mode=" + profile.mode().name(),
                "--ores-platform=" + profile.platform().name()
        };
    }

    public static IsolatePolicy fromApplicationArguments(String[] args) {
        String raw = null;
        for (String arg : args) if (arg.startsWith("--ores-capabilities=")) raw = arg.substring("--ores-capabilities=".length());
        if (raw == null) return developer();
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (!raw.isBlank()) {
            for (String value : raw.split(",")) caps.add(Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
        return new IsolatePolicy(caps, 128L * 1024 * 1024, 1024, Duration.ofSeconds(30));
    }

    public static ExecutionProfile executionProfileFromApplicationArguments(String[] args) {
        String mode = "JIT";
        String platform = "SERVER";
        for (String arg : args) {
            if (arg.startsWith("--ores-execution-mode=")) mode = arg.substring("--ores-execution-mode=".length());
            else if (arg.startsWith("--ores-platform=")) platform = arg.substring("--ores-platform=".length());
        }
        return ExecutionProfile.parse(mode, platform);
    }
}
