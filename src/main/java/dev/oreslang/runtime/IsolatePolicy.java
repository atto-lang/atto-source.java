package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Deny-by-default security policy for an Oreslang isolate/context. */
public record IsolatePolicy(
        Set<Capability> capabilities,
        long maxHeapBytes,
        int maxMailboxMessages,
        Duration maxWallTime,
        boolean adversarial) {

    public enum Capability {
        STDIN,
        STDOUT,
        PROCESS_INFO,
        PROCESS_EXIT,
        GC_CONTROL,
        ACTOR_SHARE_READONLY,
        ACTOR_SPAWN,
        SHARED_MEMORY,
        NETWORK,
        IPC,
        GPU,
        FILESYSTEM_READ,
        FILESYSTEM_WRITE,
        ENVIRONMENT,
        HOT_CODE_LOAD,
        JAVA_INTEROP,
        JAVA_SOURCE_INTEROP,
        FFI,
        NATIVE,
        REFLECTION,
        CHILD_PROCESS,
        THREAD_CREATE,
        POLYGLOT
    }

    public IsolatePolicy(Set<Capability> capabilities, long maxHeapBytes, int maxMailboxMessages, Duration maxWallTime) {
        this(capabilities, maxHeapBytes, maxMailboxMessages, maxWallTime, false);
    }

    public IsolatePolicy {
        capabilities = Set.copyOf(capabilities);
        if (maxHeapBytes < 16L * 1024 * 1024) throw new IllegalArgumentException("maxHeapBytes must be at least 16 MiB");
        if (maxMailboxMessages <= 0) throw new IllegalArgumentException("maxMailboxMessages must be positive");
        if (maxWallTime.isNegative() || maxWallTime.isZero()) throw new IllegalArgumentException("maxWallTime must be positive");
        if (capabilities.contains(Capability.JAVA_SOURCE_INTEROP)
                && !capabilities.contains(Capability.JAVA_INTEROP)) {
            throw new IllegalArgumentException("JAVA_SOURCE_INTEROP requires JAVA_INTEROP");
        }
        if (adversarial && capabilities.contains(Capability.THREAD_CREATE)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant THREAD_CREATE");
        }
        if (adversarial && capabilities.contains(Capability.JAVA_INTEROP)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant JAVA_INTEROP");
        }
        if (adversarial && capabilities.contains(Capability.JAVA_SOURCE_INTEROP)) {
            throw new IllegalArgumentException("adversarial isolates cannot grant JAVA_SOURCE_INTEROP");
        }
    }

    /**
     * Resource baseline for untrusted function code: stdout only and one
     * guest thread. Actual execution must use the application's single pooled
     * UNTRUSTED Engine; this factory does not allocate its own isolate.
     */
    public static IsolatePolicy strictFaas() {
        return new IsolatePolicy(Set.of(Capability.STDOUT), 128L * 1024 * 1024, 1024, Duration.ofSeconds(30), true);
    }

    /**
     * Fixed baseline for hostile hot-loaded actor code. The supervisor owns
     * loading and routing; an untrusted actor starts with no ambient capabilities.
     */
    public static IsolatePolicy untrustedActor() {
        return new IsolatePolicy(
                Set.of(),
                32L * 1024 * 1024,
                128,
                Duration.ofMillis(250),
                true);
    }

    /**
     * Group CONTEXT baseline for many untrusted actors sharing one Graal
     * isolate. The context's sandbox.MaxCPUTime is a CUMULATIVE lifetime
     * budget, not the per-actor turn budget. Individual spawned UNTRUSTED
     * actors still receive the untrustedActor() 250 ms baseline.
     *
     * The supervisor must rotate long-lived group contexts before exhausting
     * this budget, safely fencing and draining pending tasks and I/O first.
     */
    public static IsolatePolicy untrustedActorGroup() {
        return new IsolatePolicy(
                Set.of(),
                128L * 1024 * 1024,
                4096,
                Duration.ofHours(12),
                true);
    }

    /** Restricted local/test baseline. Java interop/FFI/native/reflection/process spawning remain denied. */
    public static IsolatePolicy developer() {
        return new IsolatePolicy(
                Set.of(Capability.STDIN, Capability.STDOUT, Capability.PROCESS_INFO, Capability.PROCESS_EXIT, Capability.GC_CONTROL,
                        Capability.ACTOR_SHARE_READONLY, Capability.ACTOR_SPAWN,
                        Capability.SHARED_MEMORY, Capability.HOT_CODE_LOAD),
                512L * 1024 * 1024, 8192, Duration.ofMinutes(10), false);
    }

    public IsolatePolicy withCapabilities(Capability... added) {
        EnumSet<Capability> next = capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        next.addAll(Arrays.asList(added));
        return new IsolatePolicy(next, maxHeapBytes, maxMailboxMessages, maxWallTime, adversarial);
    }

    public IsolatePolicy withoutCapabilities(Capability... removed) {
        EnumSet<Capability> next = capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        next.removeAll(Arrays.asList(removed));
        return new IsolatePolicy(next, maxHeapBytes, maxMailboxMessages, maxWallTime, adversarial);
    }

    public IsolatePolicy asAdversarial() {
        return adversarial ? this : new IsolatePolicy(capabilities, maxHeapBytes, maxMailboxMessages, maxWallTime, true);
    }

    /**
     * Graal baseline: no host reflection, native access, polyglot calls,
     * environment access, guest-created threads, or host filesystem/network IO.
     *
     * Adversarial callers must supply the application's pool-owned Engine
     * through sharedUntrustedIsolateContextBuilder(). Ad-hoc per-Context
     * isolates and JVM fallback are rejected before guest execution.
     */
    public Context.Builder restrictedContextBuilder() {
        return restrictedContextBuilder(ExecutionProfile.parse(NativeBuildMode.defaultExecutionMode(), "server"), Set.of());
    }

    public Context.Builder restrictedContextBuilder(ExecutionProfile profile) {
        return restrictedContextBuilder(profile, Set.of());
    }

    /**
     * Builds a deny-by-default Graal context. Java host classes require two
     * independent grants: JAVA_INTEROP and this exact fully-qualified allowlist.
     */
    public Context.Builder restrictedContextBuilder(
            ExecutionProfile profile,
            Set<String> allowedHostClasses) {
        return restrictedContextBuilder(
                profile,
                allowedHostClasses,
                RuntimePermissions.fromCapabilities(capabilities),
                PermissionCheckMode.COMPILE);
    }

    public Context.Builder restrictedContextBuilder(
            ExecutionProfile profile,
            Set<String> allowedHostClasses,
            RuntimePermissions permissions,
            PermissionCheckMode permissionCheckMode) {
        return restrictedContextBuilderWithEngine(
                profile, allowedHostClasses, permissions, permissionCheckMode, null);
    }

    /**
     * Group-level untrusted context running within ONE supervisor-owned engine
     * and polyglot isolate, instead of creating a Graal isolate per actor.
     * Package-private so callers cannot pass an arbitrary, non-isolated Engine.
     *
     * Guest JIT capability belongs to the isolated engine. In particular an
     * AOT-only supervisor executable may own a distinct JIT-capable isolate.
     */
    Context.Builder sharedUntrustedIsolateContextBuilder(
            ExecutionProfile guestProfile, org.graalvm.polyglot.Engine sharedEngine) {
        if (!adversarial) throw new SecurityException("shared untrusted isolate requires adversarial policy");
        if (!guestProfile.guestJitAllowed()) {
            throw new IllegalArgumentException("untrusted isolate guest must permit JIT compilation");
        }
        return restrictedContextBuilderWithEngine(
                guestProfile, Set.of(), RuntimePermissions.fromCapabilities(capabilities),
                PermissionCheckMode.COMPILE, Objects.requireNonNull(sharedEngine, "sharedEngine"));
    }

    private Context.Builder restrictedContextBuilderWithEngine(
            ExecutionProfile profile,
            Set<String> allowedHostClasses,
            RuntimePermissions permissions,
            PermissionCheckMode permissionCheckMode,
            org.graalvm.polyglot.Engine sharedEngine) {
        // Never silently create one new polyglot Engine/isolate per adversarial
        // Context. The application owns ONE pooled untrusted Engine; admission
        // without it is a security/topology error, not a reason to fall back to
        // an isolated ad-hoc Engine or the JVM interpreter.
        if (adversarial && sharedEngine == null) {
            throw new SecurityException(
                    "untrusted execution requires SharedUntrustedIsolatePool; "
                    + "standalone per-context Graal isolates are disabled");
        }
        // The normal context must match the native HOST image mode. A separate
        // guest engine can include its own JIT inside its own isolate, even if
        // the host was compiled AOT-only.
        if (sharedEngine == null) NativeBuildMode.requireCompatible(profile);
        Set<String> hostClasses = Set.copyOf(allowedHostClasses);
        if (!hostClasses.isEmpty()) {
            require(Capability.JAVA_INTEROP, "Java host imports");
            if (adversarial) {
                throw new SecurityException("Java host imports are disabled for adversarial isolates");
            }
            for (String className : hostClasses) validateHostClassAuthority(className);
        }

        HostAccess hostAccess;
        if (!hostClasses.isEmpty()) {
            hostAccess = explicitHostAccess(hostClasses);
        } else {
            hostAccess = adversarial
                    ? HostAccess.UNTRUSTED
                    : HostAccess.NONE;
        }

        Context.Builder builder = Context.newBuilder(OresLanguage.ID)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(hostClasses.isEmpty() ? ignored -> false : hostClasses::contains)
                .allowHostClassLoading(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowIO(IOAccess.NONE)
                .in(new ByteArrayInputStream(new byte[0]))
                .out(new ByteArrayOutputStream())
                .err(new ByteArrayOutputStream())
                .arguments(OresLanguage.ID, applicationArguments(profile, permissions, permissionCheckMode));

        /*
         * Graal's engine.IsolateLibrary option is experimental in 25.x. Opt in
         * only when the embedding process explicitly supplies a polyglot
         * isolate library; ordinary strict/adversarial contexts remain on the
         * non-experimental builder path.
         */
        if (System.getProperty("polyglot.engine.IsolateLibrary") != null) {
            builder.allowExperimentalOptions(true);
        }

        if (!profile.guestJitAllowed() && NativeBuildMode.kind() != NativeBuildMode.Kind.AOT) {
            try (org.graalvm.polyglot.Engine probe = org.graalvm.polyglot.Engine.create()) {
                if (probe.getOptions().get("engine.Compilation") != null) {
                    builder.allowExperimentalOptions(true).option("engine.Compilation", "false");
                }
            }
        }

        if (adversarial) {
            long guestHeap = Math.max(8L * 1024 * 1024, maxHeapBytes * 3 / 4);
            long maxOutput = allows(Capability.STDOUT) ? 1024L * 1024 : 0L;
            builder.sandbox(SandboxPolicy.UNTRUSTED)
                    .option("sandbox.MaxHeapMemory", bytes(guestHeap))
                    .option("sandbox.MaxCPUTime", duration(maxWallTime))
                    .option("sandbox.MaxASTDepth", "256")
                    .option("sandbox.MaxThreads", "1")
                    .option("sandbox.MaxOutputStreamSize", bytes(maxOutput))
                    .option("sandbox.MaxErrorStreamSize", "64KB");
            // A standalone adversarial Engine would break the exactly-one
            // untrusted-isolate topology. Admission above requires a pool-
            // managed Engine and the matching UNTRUSTED sandbox policy.
            builder.engine(sharedEngine);
        }

        return builder;
    }

    private void validateHostClassAuthority(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("allowlisted Java host class name cannot be blank");
        }

        if (className.equals("java.lang.Class")
                || className.equals("java.lang.ClassLoader")
                || className.equals("java.lang.Module")
                || className.equals("java.lang.Runtime")
                || className.equals("java.lang.System")
                || className.equals("java.lang.Process")
                || className.equals("java.lang.ProcessBuilder")
                || className.equals("java.lang.ProcessHandle")
                || className.equals("java.lang.Thread")
                || className.equals("java.lang.ThreadGroup")
                || className.equals("java.lang.SecurityManager")
                || className.equals("java.util.ServiceLoader")
                || className.startsWith("java.lang.reflect.")
                || className.startsWith("java.lang.invoke.")
                || className.startsWith("java.beans.")
                || className.startsWith("javax.script.")
                || className.startsWith("javax.tools.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")) {
            throw new SecurityException("Java host class is blocked from class-level interop: " + className);
        }

        /*
         * Scoped Ores permissions are enforced only at Ores-owned native I/O
         * boundaries. Handing guest code a raw JDK filesystem/socket/channel
         * class would hand it an authority that cannot be narrowed to a path,
         * host, port, or explicit deny rule. Keep those packages behind the
         * Ores fs/network/http APIs instead.
         *
         * Trusted mixed Java source remains a deliberately stronger,
         * separately gated escape hatch under JAVA_SOURCE_INTEROP.
         */
        if (className.startsWith("java.io.")
                || className.startsWith("java.nio.file.")
                || className.startsWith("java.net.")
                || className.startsWith("java.nio.channels.")) {
            throw new SecurityException(
                    "direct Java host I/O is blocked from class-level interop; "
                            + "use Oreslang fs/network/http APIs: " + className);
        }
        if (className.startsWith("java.util.concurrent.")) {
            require(Capability.THREAD_CREATE, "Java host class " + className);
        }
        if (className.startsWith("java.lang.foreign.")) {
            require(Capability.NATIVE, "Java host class " + className);
        }
        if (className.startsWith("java.lang.management.")) {
            require(Capability.PROCESS_INFO, "Java host class " + className);
        }
    }

    private static HostAccess explicitHostAccess(Set<String> hostClasses) {
        HostAccess.Builder access = HostAccess.newBuilder(HostAccess.NONE)
                .allowAccessInheritance(false);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (String className : hostClasses) {
            final Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException failure) {
                throw new IllegalArgumentException("allowlisted Java host class is unavailable: " + className, failure);
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                throw new IllegalArgumentException("allowlisted Java host class must be public: " + className);
            }

            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) access.allowAccess(constructor);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) access.allowAccess(method);
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) access.allowAccess(field);
            }
        }
        return access.build();
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
        return applicationArguments(
                profile,
                RuntimePermissions.fromCapabilities(capabilities),
                PermissionCheckMode.COMPILE);
    }

    public String[] applicationArguments(
            ExecutionProfile profile,
            RuntimePermissions permissions,
            PermissionCheckMode permissionCheckMode) {
        String caps = capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--ores-capabilities=" + caps,
                "--ores-max-heap-bytes=" + maxHeapBytes,
                "--ores-max-mailbox-messages=" + maxMailboxMessages,
                "--ores-max-wall-ms=" + maxWallTime.toMillis(),
                "--ores-adversarial=" + adversarial,
                "--ores-execution-mode=" + profile.mode().name(),
                "--ores-platform=" + profile.platform().name(),
                "--ores-permission-check=" + permissionCheckMode.name().toLowerCase(Locale.ROOT)));
        args.addAll(java.util.List.of(permissions.applicationArguments()));
        return args.toArray(String[]::new);
    }

    public static IsolatePolicy fromApplicationArguments(String[] args) {
        String raw = null;
        long maxHeap = 128L * 1024 * 1024;
        int maxMailbox = 1024;
        long maxWallMs = 30_000L;
        boolean adversarial = false;
        for (String arg : args) {
            if (arg.startsWith("--ores-capabilities=")) raw = arg.substring("--ores-capabilities=".length());
            else if (arg.startsWith("--ores-max-heap-bytes=")) maxHeap = Long.parseLong(arg.substring("--ores-max-heap-bytes=".length()));
            else if (arg.startsWith("--ores-max-mailbox-messages=")) maxMailbox = Integer.parseInt(arg.substring("--ores-max-mailbox-messages=".length()));
            else if (arg.startsWith("--ores-max-wall-ms=")) maxWallMs = Long.parseLong(arg.substring("--ores-max-wall-ms=".length()));
            else if (arg.startsWith("--ores-adversarial=")) adversarial = Boolean.parseBoolean(arg.substring("--ores-adversarial=".length()));
        }
        if (raw == null) return developer();
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (!raw.isBlank()) {
            for (String value : raw.split(",")) caps.add(Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
        return new IsolatePolicy(caps, maxHeap, maxMailbox, Duration.ofMillis(maxWallMs), adversarial);
    }

    public static ExecutionProfile executionProfileFromApplicationArguments(String[] args) {
        String mode = NativeBuildMode.defaultExecutionMode();
        String platform = "SERVER";
        for (String arg : args) {
            if (arg.startsWith("--ores-execution-mode=")) mode = arg.substring("--ores-execution-mode=".length());
            else if (arg.startsWith("--ores-platform=")) platform = arg.substring("--ores-platform=".length());
        }
        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        NativeBuildMode.requireCompatible(profile);
        return profile;
    }

    private static String bytes(long value) {
        return value + "B";
    }

    private static String duration(Duration value) {
        return value.toMillis() + "ms";
    }
}
