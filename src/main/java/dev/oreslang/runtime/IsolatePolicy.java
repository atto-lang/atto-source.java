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
     * Production FaaS baseline: malicious-source threat model, stdout only,
     * one guest thread, hard VM isolate and guest resource limits.
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
     * For adversarial policies, Graal's UNTRUSTED sandbox is selected. It
     * spawns a VM-level polyglot isolate when the language-isolate artifact is
     * available and enforces guest/isolate resource limits.
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
        NativeBuildMode.requireCompatible(profile);
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
                    ? HostAccess.newBuilder(HostAccess.NONE).allowMutableTargetMappings().methodScoping(true).build()
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
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", bytes(maxHeapBytes))
                    .option("sandbox.MaxHeapMemory", bytes(guestHeap))
                    .option("sandbox.MaxCPUTime", duration(maxWallTime))
                    .option("sandbox.MaxASTDepth", "256")
                    .option("sandbox.MaxThreads", "1")
                    .option("sandbox.MaxOutputStreamSize", bytes(maxOutput))
                    .option("sandbox.MaxErrorStreamSize", "64KB");
        }

        return builder;
    }

    private void validateHostClassAuthority(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("allowlisted Java host class name cannot be blank");
        }

        // Public nested classes use binary names (Outer$Nested). A blocked
        // outer class must not be reachable through one of its nested APIs.
        int nestedSeparator = className.indexOf('$');
        String authorizationRoot = nestedSeparator < 0
                ? className : className.substring(0, nestedSeparator);

        // java.lang.Object exposes public wait()/notify()/notifyAll().
        // Allowing its declared host methods permits unscheduled thread
        // parking and monitor signaling outside the actor scheduler.
        if (authorizationRoot.equals("java.lang.Object")
                || authorizationRoot.equals("java.lang.Class")
                || authorizationRoot.equals("java.lang.ClassLoader")
                || authorizationRoot.equals("java.lang.Module")
                || authorizationRoot.equals("java.lang.Runtime")
                || authorizationRoot.equals("java.lang.System")
                || authorizationRoot.equals("java.lang.Process")
                || authorizationRoot.equals("java.lang.ProcessBuilder")
                || authorizationRoot.equals("java.lang.ProcessHandle")
                || authorizationRoot.equals("java.lang.Thread")
                // ThreadLocal and ClassValue bypass actor-private state by
                // storing data in host-thread or JVM Class-keyed caches.
                || authorizationRoot.equals("java.lang.ThreadLocal")
                || authorizationRoot.equals("java.lang.InheritableThreadLocal")
                || authorizationRoot.equals("java.lang.ClassValue")
                || authorizationRoot.equals("java.lang.ThreadGroup")
                || authorizationRoot.equals("java.lang.SecurityManager")
                || authorizationRoot.equals("java.util.ServiceLoader")
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
                || className.startsWith("java.nio.channels.")
                // Public constructors/static helpers outside the usual I/O
                // packages can still open arbitrary files by name.
                || className.equals("java.util.Scanner")
                || className.equals("java.util.Formatter")
                || className.equals("java.util.zip.ZipFile")
                || className.equals("java.util.jar.JarFile")
                || className.equals("javax.imageio.ImageIO")
                // Exact class names do not constrain public method authority.
                // These other JDK APIs can open files, sockets, preferences,
                // resources, database connections, or remote XML entities.
                // Keep such operations behind scoped Ores-owned APIs.
                || className.startsWith("java.util.logging.")
                || className.startsWith("java.util.prefs.")
                || className.startsWith("java.util.zip.")
                || className.startsWith("java.util.jar.")
                || className.startsWith("javax.imageio.")
                // A keystore can directly open host files, while Security
                // mutates JVM-wide provider/properties configuration. JAAS
                // login configuration can also read host resources or change
                // process-wide authentication state. None is scoped by the
                // Ores READ/WRITE/NET/SYS permission rules.
                || authorizationRoot.equals("java.security.KeyStore")
                || authorizationRoot.equals("java.security.Security")
                || className.startsWith("javax.security.auth.login.")
                || className.startsWith("javax.naming.")
                || className.startsWith("javax.net.")
                || className.startsWith("java.sql.")
                || className.startsWith("javax.sql.")
                || className.startsWith("java.rmi.")
                || className.startsWith("javax.xml.")
                || className.startsWith("org.xml.sax.")
                // AWT/Swing and Java desktop service classes expose raw
                // screen capture, input injection, clipboard, print, audio
                // device, and file-picker authority outside Ores permission
                // scopes. Deny whole packages rather than individual types.
                || className.startsWith("java.awt.")
                || className.startsWith("javax.swing.")
                || className.startsWith("javax.print.")
                || className.startsWith("javax.sound.")
                // Timer constructors create unmanaged host threads without
                // going through the actor scheduler or THREAD_CREATE checks.
                || authorizationRoot.equals("java.util.Timer")
                || authorizationRoot.equals("java.util.TimerTask")
                // Cleaner.create() starts its own daemon thread outside actor
                // admission and THREAD_CREATE checks. Likewise, Arrays
                // parallelSort and java.util.stream parallel pipelines can
                // execute guest-directed callbacks on unmanaged pool workers.
                // Reject them even when THREAD_CREATE is granted: actor code
                // must use the Oreslang scheduler, never a raw host worker.
                || authorizationRoot.equals("java.lang.ref.Cleaner")
                || authorizationRoot.equals("java.util.Arrays")
                || className.startsWith("java.util.stream.")
                // Public CompletableFuture, Executors and ForkJoinPool APIs
                // may run guest callbacks outside the single-flight lane.
                // THREAD_CREATE is not permission to bypass actor scheduling.
                || className.startsWith("java.util.concurrent.")
                || authorizationRoot.equals("java.util.ResourceBundle")) {
            throw new SecurityException(
                    "direct Java host I/O is blocked from class-level interop; "
                            + "use Oreslang fs/network/http APIs: " + className);
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
                if (Modifier.isPublic(method.getModifiers())
                        && !isUnscopedGlobalMutator(type, method)
                        && !isUnscopedNativeAllocation(type, method)) {
                    access.allowAccess(method);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) access.allowAccess(field);
            }
        }
        return access.build();
    }

    /**
     * A Java host-class allowlist does not imply authorization to mutate
     * process-wide defaults shared by every isolate and actor. Keep useful
     * read-only Locale/TimeZone functionality, but never export their global
     * setters through Graal HostAccess.
     */
    static boolean isUnscopedGlobalMutator(Class<?> type, Method method) {
        if (!Modifier.isStatic(method.getModifiers())
                || !method.getName().equals("setDefault")) return false;
        return type == java.util.Locale.class
                || java.util.TimeZone.class.isAssignableFrom(type);
    }

    /**
     * DirectByteBuffer storage consumes process-native memory outside an
     * actor's bounded heap/arena accounting. An explicit Java host-class
     * allowlist must not expose this unscoped allocation path. Ordinary
     * heap-backed ByteBuffer utilities remain available.
     */
    static boolean isUnscopedNativeAllocation(Class<?> type, Method method) {
        return type == java.nio.ByteBuffer.class
                && Modifier.isStatic(method.getModifiers())
                && method.getName().equals("allocateDirect");
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
        boolean sawHostPolicyArgument = false;
        Set<String> seenPolicyFields = new java.util.HashSet<>();
        for (String arg : args) {
            if (arg == null)
                throw new IllegalArgumentException("null Oreslang application argument");
            // Empty guest argument arrays still support legacy embedding.
            // A partially serialized host policy must not silently expand
            // into the developer capability baseline.
            if (arg.startsWith("--ores-")) sawHostPolicyArgument = true;
            String field = null;
            if (arg.startsWith("--ores-capabilities=")) {
                field = "capabilities";
                raw = arg.substring("--ores-capabilities=".length());
            } else if (arg.startsWith("--ores-max-heap-bytes=")) {
                field = "max-heap-bytes";
                maxHeap = Long.parseLong(arg.substring("--ores-max-heap-bytes=".length()));
            } else if (arg.startsWith("--ores-max-mailbox-messages=")) {
                field = "max-mailbox-messages";
                maxMailbox = Integer.parseInt(arg.substring("--ores-max-mailbox-messages=".length()));
            } else if (arg.startsWith("--ores-max-wall-ms=")) {
                field = "max-wall-ms";
                maxWallMs = Long.parseLong(arg.substring("--ores-max-wall-ms=".length()));
            } else if (arg.startsWith("--ores-adversarial=")) {
                field = "adversarial";
                String value = arg.substring("--ores-adversarial=".length());
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException("invalid Oreslang adversarial marker");
                adversarial = Boolean.parseBoolean(value);
            }
            if (field != null && !seenPolicyFields.add(field))
                throw new IllegalArgumentException("duplicate Oreslang host policy field: " + field);
        }
        if (raw == null) {
            if (sawHostPolicyArgument)
                throw new IllegalArgumentException("missing Oreslang capabilities in serialized host policy");
            return developer();
        }
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (!raw.isEmpty()) {
            // Preserve explicit empty capability sets but reject ambiguous
            // empty segments, duplicates and malformed serialized lists.
            for (String value : raw.split(",", -1)) {
                if (value.isBlank())
                    throw new IllegalArgumentException("empty serialized Oreslang capability");
                Capability capability = Capability.valueOf(
                        value.trim().toUpperCase(Locale.ROOT));
                if (!caps.add(capability))
                    throw new IllegalArgumentException("duplicate serialized Oreslang capability: " + capability);
            }
        }
        return new IsolatePolicy(caps, maxHeap, maxMailbox, Duration.ofMillis(maxWallMs), adversarial);
    }

    public static ExecutionProfile executionProfileFromApplicationArguments(String[] args) {
        String mode = NativeBuildMode.defaultExecutionMode();
        String platform = "SERVER";
        boolean sawMode = false;
        boolean sawPlatform = false;
        for (String arg : args) {
            if (arg == null)
                throw new IllegalArgumentException("null Oreslang application argument");
            if (arg.startsWith("--ores-execution-mode=")) {
                if (sawMode)
                    throw new IllegalArgumentException("duplicate Oreslang execution mode");
                sawMode = true;
                mode = arg.substring("--ores-execution-mode=".length());
            } else if (arg.startsWith("--ores-platform=")) {
                if (sawPlatform)
                    throw new IllegalArgumentException("duplicate Oreslang platform");
                sawPlatform = true;
                platform = arg.substring("--ores-platform=".length());
            }
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
