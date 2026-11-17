package dev.oreslang.launcher;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.CompilerEffectAnalysisV0;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SourceUnitDiagnosticException;
import dev.oreslang.compiler.StaticFactsV0Exporter;
import dev.oreslang.compiler.ReachabilityReport;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.ast.Ast;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.NativeBuildMode;
import dev.oreslang.runtime.CorePerf;
import dev.oreslang.runtime.CoreDebug;
import dev.oreslang.runtime.CoreLog;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OresMain {
    private static final Pattern POSITIONED_DIAGNOSTIC = Pattern.compile(
            "^Oreslang\\s+(?:lexer|parse)\\s+error\\s+at\\s+(\\d+):(\\d+):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private OresMain() { }

    /**
     * Only the standalone command-line host is permitted to terminate its process.
     * Embedders use LinkedProgramRunner.runWithExitStatus() instead.
     */
    public static void main(String[] args) {
        int status = exitStatusForArgs(args);
        if (status != 0) System.exit(status);
    }

    /** Testable CLI boundary: syntax/configuration failures never masquerade as success. */
    static int exitStatusForArgs(String[] args) {
        try {
            int status = runForExitStatus(args);
            // PrintStream records failed writes instead of throwing IOException.
            // Flush and inspect both CLI streams before publishing success.
            if (status == 0 && (System.out.checkError() || System.err.checkError())) {
                System.err.println("oreslang: failed to publish CLI output");
                return 1;
            }
            return status;
        } catch (UsageException failure) {
            System.err.println("oreslang: " + safeDiagnosticText(failure.getMessage()));
            return 2;
        } catch (Exception failure) {
            String message = failure.getMessage();
            System.err.println("oreslang: " + safeDiagnosticText(
                    message == null || message.isBlank()
                            ? failure.getClass().getSimpleName() : message));
            return 1;
        }
    }

    static String buildInfoJson() {
        return "{\"build_mode\":\"" + NativeBuildMode.kind().name().toLowerCase(Locale.ROOT)
                + "\",\"default_execution_mode\":\"" + NativeBuildMode.defaultExecutionMode()
                + "\",\"managed_reference_mode\":\""
                + NativeBuildMode.managedReferenceMode().name().toLowerCase(Locale.ROOT)
                + "\",\"guest_execution\":\"" + (NativeBuildMode.kind() == NativeBuildMode.Kind.AOT
                    ? "interpreter-no-jit" : "runtime-selected")
                + "\",\"full_guest_aot_proven\":" + NativeBuildMode.fullGuestAotProven()
                + ",\"zero_guest_tracing_gc_proven\":" + NativeBuildMode.zeroGuestTracingGcProven()
                + ",\"zero_process_gc_proven\":" + NativeBuildMode.zeroProcessGcProven()
                + "}";
    }

    private static int runForExitStatus(String[] args) throws Exception {
        // Explicit on-device LLVM AOT build path. Never interpret "build" as a source file.
        // This is intentionally limited to LLVM's currently supported host subset.
        if (args.length > 0 && args[0].equals("build")) {
            return DeviceBuildCommand.run(args);
        }
        if (args.length == 1 && args[0].equals("--build-info")) {
            System.out.println(buildInfoJson());
            return 0;
        }
        String perfEnv = System.getenv("ORES_CORE_PERF");
        boolean corePerf = "true".equalsIgnoreCase(perfEnv) || "1".equals(perfEnv);
        String coreLogLevel = System.getenv().getOrDefault("ORES_CORE_LOG_LEVEL", "off");
        String debugEnv = System.getenv("ORES_CORE_DEBUG");
        boolean coreDebug = "true".equalsIgnoreCase(debugEnv) || "1".equals(debugEnv);
        boolean strict = false;
        boolean checkOnly = false;
        boolean analyzeOnly = false;
        boolean buildAnalysis = false;
        boolean staticFacts = false;
        boolean explicitExecutionProfile = false;
        boolean requireFullGuestAot = false;
        boolean requireZeroGuestTracingGc = false;
        String reachabilityReportPath = null;
        List<String> buildDefines = new ArrayList<>();
        Set<String> buildEntryPoints = new LinkedHashSet<>();
        String mode = NativeBuildMode.defaultExecutionMode();
        String platform = "server";
        PermissionCheckMode permissionCheckMode = PermissionCheckMode.RUNTIME;
        RuntimePermissions.Builder permissionBuilder = new RuntimePermissions.Builder();
        EnumSet<RuntimePermissions.Permission> touchedPermissions =
                EnumSet.noneOf(RuntimePermissions.Permission.class);
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        Set<String> allowedHostClasses = new LinkedHashSet<>();
        String filename = null;

        for (String arg : args) {
            try {
                if (arg.equals("--strict-isolate")) strict = true;
                else if (arg.equals("--check")) checkOnly = true;
                else if (arg.equals("--analyze")) analyzeOnly = true;
                else if (arg.equals("--core-perf")) corePerf = true;
                else if (arg.equals("--no-core-perf")) corePerf = false;
                else if (arg.startsWith("--core-log-level="))
                    coreLogLevel = arg.substring("--core-log-level=".length());
                else if (arg.equals("--no-core-log")) coreLogLevel = "off";
                else if (arg.equals("--core-debug")) coreDebug = true;
                else if (arg.equals("--no-core-debug")) coreDebug = false;
                else if (arg.equals("--build-analysis")) buildAnalysis = true;
                else if (arg.equals("--static-facts")) staticFacts = true;
                else if (arg.equals("--require-full-guest-aot")) requireFullGuestAot = true;
                else if (arg.equals("--require-zero-guest-tracing-gc")) requireZeroGuestTracingGc = true;
                else if (arg.startsWith("--reachability-report=")) {
                    if (reachabilityReportPath != null) {
                        throw new UsageException("--reachability-report may be supplied only once");
                    }
                    reachabilityReportPath = arg.substring("--reachability-report=".length()).trim();
                    if (reachabilityReportPath.isEmpty()) {
                        throw new UsageException("--reachability-report requires a file path");
                    }
                }
                else if (arg.equals("--no-prompt")) {
                    // Oreslang permissions never prompt. This Deno-compatible
                    // spelling is accepted so automation can state that intent.
                } else if (arg.startsWith("--permission-check=")) {
                    permissionCheckMode = PermissionCheckMode.parse(
                            arg.substring("--permission-check=".length()));
                } else if (arg.equals("--allow-all")) {
                    for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                        permissionBuilder.allowAll(permission);
                        touchedPermissions.add(permission);
                    }
                } else if (parsePermissionFlag(arg, permissionBuilder, touchedPermissions)) {
                    // handled by the scoped Deno-style permission parser
                } else if (arg.startsWith("--define=")) {
                    String raw = arg.substring("--define=".length()).trim();
                    if (raw.isEmpty()) throw new IllegalArgumentException("--define requires name=value");
                    buildDefines.add(raw);
                } else if (arg.startsWith("--entry=")) {
                    String raw = arg.substring("--entry=".length()).trim();
                    if (raw.isEmpty()) throw new IllegalArgumentException("--entry requires a symbol name");
                    buildEntryPoints.add(raw);
                } else if (arg.startsWith("--mode=")) {
                    mode = arg.substring("--mode=".length());
                    explicitExecutionProfile = true;
                }
                else if (arg.startsWith("--platform=")) {
                    platform = arg.substring("--platform=".length());
                    explicitExecutionProfile = true;
                }
                else if (arg.startsWith("--allow=")) {
                    String raw = arg.substring("--allow=".length());
                    if (raw.isBlank()) {
                        throw new UsageException("--allow requires a comma-separated capability list");
                    }
                    for (String value : raw.split(",", -1)) {
                        if (value.isBlank()) {
                            throw new UsageException("--allow cannot contain empty capabilities");
                        }
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(
                                value.trim().toUpperCase(Locale.ROOT)));
                    }
                } else if (arg.startsWith("--allow-host-class=")) {
                    String raw = arg.substring("--allow-host-class=".length()).trim();
                    if (raw.isEmpty()) {
                        throw new IllegalArgumentException("--allow-host-class requires a fully qualified Java class name");
                    }
                    if (raw.indexOf('#') >= 0)
                        throw new UsageException("method selectors require --allow-host-method, not --allow-host-class");
                    allowedHostClasses.add(raw);
                } else if (arg.startsWith("--allow-host-method=")) {
                    String raw = arg.substring("--allow-host-method=".length()).trim();
                    // Exact manifest validation happens in IsolatePolicy
                    // before building a guest Graal Context.
                    dev.oreslang.runtime.SafeHostMemberManifest.className(raw);
                    allowedHostClasses.add(raw);
                } else if (arg.startsWith("--")) {
                    throw new UsageException("unknown option: " + arg);
                } else if (filename == null) filename = arg;
                else throw new UsageException("only one .atto, .ores, or .java source file may be supplied");
            } catch (IllegalArgumentException invalidOption) {
                throw new UsageException(invalidOption.getMessage());
            }
        }

        // Report mutually exclusive CLI flags before opening/discovering a file.
        // An invalid invocation is status 2 even when the source path is absent.
        if ((checkOnly ? 1 : 0) + (analyzeOnly ? 1 : 0) + (buildAnalysis ? 1 : 0) + (staticFacts ? 1 : 0) > 1) {
            throw new UsageException("--analyze, --check, --build-analysis and --static-facts are mutually exclusive");
        }
        if (reachabilityReportPath != null && !buildAnalysis) {
            throw new UsageException("--reachability-report requires --build-analysis");
        }

        final CoreLog.Level admittedLogLevel;
        try {
            admittedLogLevel = CoreLog.Level.parse(coreLogLevel);
        } catch (IllegalArgumentException badLogLevel) {
            throw new UsageException(badLogLevel.getMessage());
        }
        CoreLog.configure(admittedLogLevel);
        CorePerf.configure(corePerf);
        CoreDebug.configure(coreDebug);
        try {
            NativeBuildMode.requireCompatible(ExecutionProfile.parse(mode, platform));
        } catch (IllegalArgumentException invalidOption) {
            throw new UsageException(invalidOption.getMessage());
        }
        // Admission happens before source discovery or guest execution. A
        // Native Image of the Java host and a disabled collector do not
        // constitute an authenticated guest AOT / ownership-drop proof.
        if (requireFullGuestAot && !NativeBuildMode.fullGuestAotProven()) {
            throw new UsageException("--require-full-guest-aot denied: this build "
                    + "does not contain certified compiled guest machine code");
        }
        if (requireZeroGuestTracingGc && !NativeBuildMode.zeroGuestTracingGcProven()) {
            throw new UsageException("--require-zero-guest-tracing-gc denied: "
                    + "guest ownership/drop lowering is not certified");
        }
        Path path;
        if (filename == null) {
            OresProjectConfig project = OresProjectConfig.discover(
                    Path.of("").toAbsolutePath().normalize(),
                    System.getenv());
            path = project.mainEntrypoint().orElse(null);
            if (path == null) {
                printUsage();
                System.err.println("or define [entrypoints].main in " + OresProjectConfig.ATTO_MANIFEST_NAME
                        + " (legacy: " + OresProjectConfig.MANIFEST_NAME + ")");
                return 2;
            }
            filename = path.toString();
        } else {
            try {
                path = Path.of(filename);
            } catch (java.nio.file.InvalidPathException invalidPath) {
                throw new UsageException("invalid source path: " + invalidPath.getMessage());
            }
        }
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        String directOresSource = isGuestSourceFile(filename) ? readBoundedOresSource(path) : null;
        if (staticFacts) {
            if (directOresSource == null) {
                throw new UsageException("--static-facts requires an AttoLang source file (.atto or .ores)");
            }
            if (!buildDefines.isEmpty() || !buildEntryPoints.isEmpty() || reachabilityReportPath != null) {
                throw new UsageException("--static-facts does not accept build defines, entries or reports");
            }
            if (strict || explicitExecutionProfile || permissionCheckMode != PermissionCheckMode.RUNTIME
                    || !touchedPermissions.isEmpty() || !additionalCapabilities.isEmpty()
                    || !allowedHostClasses.isEmpty()) {
                throw new UsageException("--static-facts is advisory only and cannot claim isolate/capability-policy admission");
            }
            System.out.println(StaticFactsV0Exporter.fromSource(directOresSource));
            return 0;
        }
        if (directOresSource != null) {
            formatSyntaxWarnings(path, directOresSource).forEach(System.err::println);
            formatSelectReturnWarnings(path, directOresSource).forEach(System.err::println);
        }

        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        final RuntimePermissions permissions;
        if (touchedPermissions.isEmpty()) {
            permissions = RuntimePermissions.fromCapabilities(policy.capabilities());
        } else {
            permissions = permissionBuilder.build();

            // A Deno-style permission flag is the authority for its category.
            // Remove any inherited/generic coarse grant, then restore it only
            // when the scoped policy grants at least one resource.
            for (RuntimePermissions.Permission permission : touchedPermissions) {
                policy = policy.withoutCapabilities(permission.capability());
            }
            Set<IsolatePolicy.Capability> scopedCapabilities = permissions.grantedCapabilities();
            if (!scopedCapabilities.isEmpty()) {
                policy = policy.withCapabilities(
                        scopedCapabilities.toArray(IsolatePolicy.Capability[]::new));
            }
        }

        if (buildAnalysis) {
            if (!isGuestSourceFile(filename)) {
                throw new UsageException("--build-analysis requires a .atto or .ores source file");
            }
            String source = directOresSource;
            if (permissionCheckMode == PermissionCheckMode.COMPILE) {
                OresCompiler.validateForIsolate(source, policy);
            }
            Map<String, String> defines = BuildOptions.mergeDefines(System.getenv(), buildDefines);
            Set<String> entries = buildEntryPoints.isEmpty() ? Set.of("main") : Set.copyOf(buildEntryPoints);
            BuildOptions buildOptions = new BuildOptions(defines, entries, false);
            final TreeShaker.Result result;
            try {
                result = OresCompiler.compileForBuild(directOresSource, buildOptions);
            } catch (IllegalArgumentException error) {
                System.err.println(formatCheckDiagnostic(path, error));
                return 1;
            }

            if (reachabilityReportPath != null) {
                final Path reportPath;
                try {
                    reportPath = Path.of(reachabilityReportPath);
                } catch (java.nio.file.InvalidPathException invalidPath) {
                    throw new UsageException(
                            "invalid reachability report path: " + invalidPath.getMessage());
                }
                String report = ReachabilityReport.render(
                        result,
                        buildOptions,
                        "graal",
                        "analysis",
                        "graal-" + platform,
                        "executable");
                Files.writeString(reportPath, report + System.lineSeparator());
            }

            System.out.println("tree-shake retained:");
            result.retainedSymbols().stream().sorted().forEach(symbol -> System.out.println("  + " + symbol));
            System.out.println("tree-shake removed:");
            result.removedSymbols().stream().sorted().forEach(symbol -> System.out.println("  - " + symbol));
            return 0;
        }

        if (!buildDefines.isEmpty() || !buildEntryPoints.isEmpty() || reachabilityReportPath != null) {
            throw new UsageException("--define/--entry/--reachability-report require --build-analysis until the artifact build command is wired");
        }

        if (analyzeOnly) {
            if (!isGuestSourceFile(filename)) {
                throw new UsageException("--analyze requires a .atto or .ores guest source");
            }
            // Use the real linked-admission frontend: no guest execution,
            // LLVM IR or emitted build artifacts. No fake success from a
            // formatter stub or syntactic token-only scanner.
            try {
                LinkedProgramRunner.validate(
                        path, System.getenv(), policy, permissionCheckMode);
                Ast.Program checked = OresCompiler.parseAndTypeCheck(directOresSource);
                CompilerEffectAnalysisV0.Report report = CompilerEffectAnalysisV0.analyze(checked);
                for (String record : report.records()) System.out.println(record);
                return report.exitStatus();
            } catch (Exception error) {
                System.err.println(formatCheckDiagnostic(path, error));
                return 1;
            }
        }

        if (checkOnly) {
            try {
                LinkedProgramRunner.validate(
                        path,
                        System.getenv(),
                        policy,
                        permissionCheckMode);
            } catch (Exception error) {
                System.err.println(formatCheckDiagnostic(path, error));
                return 1;
            }
            return 0;
        }

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        int exitStatus = (policy.capabilities().contains(IsolatePolicy.Capability.STDIN)
                ? LinkedProgramRunner.runWithInput(
                        path, policy, permissions, permissionCheckMode, profile,
                        allowedHostClasses, System.getenv(), System.in, System.out, System.err)
                : LinkedProgramRunner.runWithExitStatus(
                path,
                policy,
                permissions,
                permissionCheckMode,
                profile,
                allowedHostClasses,
                System.getenv(),
                System.out,
                System.err)).exitStatus();
        return exitStatus;
    }

    // Preserve legacy .ores while admitting new AttoLang .atto with the same
    // bounded UTF-8 reader, warnings, compiler gates, and permission checks.
    private static boolean isGuestSourceFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ores") || lower.endsWith(".atto");
    }

    // Share admission policy with the linked-unit loader, including imports.
    private static String readBoundedOresSource(Path path) throws IOException {
        return dev.oreslang.runtime.BoundedSourceReader.readUtf8(path);
    }

    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) { super(message); }
    }

    private static boolean parsePermissionFlag(
            String arg,
            RuntimePermissions.Builder builder,
            EnumSet<RuntimePermissions.Permission> touched) {
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            String allow = "--allow-" + permission.cliName();
            String deny = "--deny-" + permission.cliName();

            if (arg.equals(allow)) {
                builder.allowAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(allow + "=")) {
                String scopes = arg.substring((allow + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.allowCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
            if (arg.equals(deny)) {
                builder.denyAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(deny + "=")) {
                String scopes = arg.substring((deny + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.denyCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
        }
        return false;
    }

    private static void requirePermissionScopes(String option, String scopes) {
        if (scopes.isBlank()) {
            throw new IllegalArgumentException(option.substring(0, option.indexOf('='))
                    + " requires a non-empty comma-separated scope or the bare flag for all");
        }
    }

    private static void printUsage() {
        System.err.println(
                "usage: oreslang-compiler [--check|--analyze|--build-analysis|--static-facts] "
                        + "[--permission-check=runtime|compile] [--allow-all] "
                        + "[--allow-read[=PATH,...]] [--deny-read[=PATH,...]] "
                        + "[--allow-write[=PATH,...]] [--deny-write[=PATH,...]] "
                        + "[--allow-net[=HOST[:PORT],...]] [--deny-net[=HOST[:PORT],...]] "
                        + "[--allow-env[=NAME,...]] [--deny-env[=NAME,...]] "
                        + "[--allow-run[=CMD,...]] [--deny-run[=CMD,...]] "
                        + "[--allow-sys[=API,...]] [--deny-sys[=API,...]] "
                        + "[--allow-ffi[=PATH,...]] [--deny-ffi[=PATH,...]] "
                        + "[--allow-import[=PATH,...]] [--deny-import[=PATH,...]] "
                        + "[--no-prompt] [--define=name=value ...] [--entry=symbol ...] [--reachability-report=FILE] "
                        + "[--strict-isolate] [--core-perf|--no-core-perf] [--core-debug|--no-core-debug] "
                        + "[--core-log-level=off|error|warn|info|debug|trace] [--no-core-log] [--mode=aot|jit|hybrid] "
                        + "[--platform=server|windows|macos|linux|android|ios] "
                        + "[--allow=CAP,...] [--allow-host-class=java.util.ArrayList ...] [--allow-host-method=java.lang.Long#sum(long,long)] "
                        + "[file.atto|file.ores|file.java]");
    }

    static List<String> formatSyntaxWarnings(Path path, String source) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return Parser.parseWithWarnings(source).warnings().stream()
                    .map(warning -> normalized
                            + ":" + warning.line()
                            + ":" + warning.column()
                            + ": warning: " + warning.message())
                    .toList();
        } catch (IllegalArgumentException ignored) {
            // The normal compile/check path will report the authoritative parse
            // error. Do not replace it with a secondary warning-scan failure.
            return List.of();
        }
    }

    /**
     * Report no-result select arm returns on the normal CLI path, not only
     * through the programmatic diagnostics API. The linked program compiler
     * remains the authoritative checker; a failed preliminary analysis must
     * not mask imports, source errors, or the normal diagnostic location.
     */
    static List<String> formatSelectReturnWarnings(Path path, String source) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().stream()
                    .map(warning -> normalized + ": warning: " + warning)
                    .toList();
        } catch (IllegalArgumentException ignored) {
            // Linked modules may need the import graph before full typechecking;
            // always let the authoritative compiler report the actual error.
            return List.of();
        }
    }

    /** Keep CLI diagnostics bounded and safe to print on an ANSI terminal. */
    private static String safeDiagnosticText(String value) {
        if (value == null || value.isBlank()) return "<unknown error>";
        StringBuilder safe = new StringBuilder(Math.min(2048, value.length()));
        for (int i = 0; i < value.length() && safe.length() < 2048; i++) {
            char ch = value.charAt(i);
            // Reject ASCII/C1 controls and Unicode format controls (including
            // bidi overrides and zero-width reordering) in untrusted text.
            safe.append(Character.isISOControl(ch)
                    || Character.getType(ch) == Character.FORMAT ? '?' : ch);
        }
        if (value.length() > 2048) safe.append("...");
        return safe.toString();
    }

    private static String diagnosticUnit(Path entry, String sourceId) {
        Path fallback = entry.toAbsolutePath().normalize();
        try {
            Path unit = Path.of(sourceId).toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            if (unit.startsWith(cwd)) return safeDiagnosticText(cwd.relativize(unit).toString());
            Path parent = fallback.getParent();
            if (parent != null && unit.startsWith(parent))
                return safeDiagnosticText(parent.relativize(unit).toString());
            // Avoid printing out-of-workspace absolute paths in CI logs.
            return safeDiagnosticText(unit.getFileName() == null ? "<source>" : unit.getFileName().toString());
        } catch (java.nio.file.InvalidPathException invalid) {
            return "<source>";
        }
    }

    static String formatCheckDiagnostic(Path path, Exception error) {
        String unit = diagnosticUnit(path, path.toString());
        String message = error.getMessage();
        String context = "";
        if (error instanceof SourceUnitDiagnosticException linked) {
            unit = diagnosticUnit(path, linked.unitId());
            message = linked.detail();
            if (!linked.moduleName().isBlank())
                context += "[module: " + safeDiagnosticText(linked.moduleName()) + "] ";
            if (!linked.namespaceName().isBlank())
                context += "[namespace: " + safeDiagnosticText(linked.namespaceName()) + "] ";
        }
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        Matcher matcher = POSITIONED_DIAGNOSTIC.matcher(message);
        if (matcher.matches()) {
            return unit + ":" + matcher.group(1)
                    + ":" + matcher.group(2)
                    + ": error: " + context + safeDiagnosticText(matcher.group(3));
        }
        return unit + ":1:1: error: " + context + safeDiagnosticText(message);
    }
}
