package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.nodes.OresStatefulActorManifestRootNode;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.SandboxPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    /*
     * LinkedProgramRunner has already run the cross-unit TypeChecker and
     * OwnershipChecker with imported class signatures. Re-parsing that source
     * through the standalone front end would lose AST-identity proof metadata
     * such as CheckedClassView/CheckedStructuralFieldDomain and cannot recover
     * imported class layouts. This one-shot, thread-confined lease lets the
     * trusted host hand the exact checked AST to Truffle's parse boundary.
     */
    private static final ThreadLocal<PrecheckedProgramLease> PRECHECKED_PROGRAM =
            new ThreadLocal<>();

    private static final class PrecheckedProgramLease {
        private final String codeUnitId;
        private final String sourceText;
        private final Ast.Program program;
        private boolean consumed;

        private PrecheckedProgramLease(
                String codeUnitId,
                String sourceText,
                Ast.Program program) {
            this.codeUnitId = codeUnitId;
            this.sourceText = sourceText;
            this.program = program;
        }
    }

    public static final class PrecheckedProgramScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final PrecheckedProgramLease lease;
        private boolean closed;

        private PrecheckedProgramScope(PrecheckedProgramLease lease) {
            this.lease = lease;
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "prechecked Oreslang program lease must close on its owning thread");
            }
            if (PRECHECKED_PROGRAM.get() != lease) {
                throw new IllegalStateException(
                        "prechecked Oreslang program lease ownership changed before scope close");
            }
            PRECHECKED_PROGRAM.remove();
            closed = true;
        }
    }

    /**
     * Trusted-host bridge for a single synchronous Polyglot Context.parse-style
     * operation. The language parse boundary consumes the lease exactly once
     * and requires exact code-unit identity and exact source text.
     */
    public static PrecheckedProgramScope openPrecheckedProgram(
            String codeUnitId,
            String sourceText,
            Ast.Program program) {
        java.util.Objects.requireNonNull(codeUnitId, "code unit id");
        java.util.Objects.requireNonNull(sourceText, "source text");
        java.util.Objects.requireNonNull(program, "prechecked program");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("prechecked code unit id cannot be blank");
        }
        if (PRECHECKED_PROGRAM.get() != null || STATEFUL_ACTOR_LEASE.get() != null) {
            throw new IllegalStateException(
                    "nested or mixed prechecked Oreslang program leases are not supported");
        }
        PrecheckedProgramLease lease = new PrecheckedProgramLease(
                codeUnitId, sourceText, program);
        PRECHECKED_PROGRAM.set(lease);
        return new PrecheckedProgramScope(lease);
    }

    static Ast.Program consumePrecheckedProgram(
            String codeUnitId,
            String sourceText) {
        PrecheckedProgramLease lease = PRECHECKED_PROGRAM.get();
        if (lease == null) return null;
        if (lease.consumed) {
            throw new IllegalStateException(
                    "prechecked Oreslang program lease was already consumed");
        }

        // A mismatched parse request is terminal for this one-shot lease.
        // Mark it consumed before checking identity/content so trusted host
        // code cannot retry the same checked AST against a different source.
        lease.consumed = true;
        if (!lease.codeUnitId.equals(codeUnitId)
                || !lease.sourceText.equals(sourceText)) {
            throw new IllegalStateException(
                    "prechecked Oreslang program lease does not match parsed source identity/content");
        }
        return lease.program;
    }

    /*
     * Host-verified stateful namespace actor parse boundary. A guest cannot
     * opt in using source text, Source MIME, names, imports or manifest paths.
     * The trusted host explicitly leases one exact physical actor constituent
     * source + canonical manifest for ONE synchronous Context.eval parse.
     *
     * Unlike a general checked Ast.Program lease, this is not TypeChecker
     * approval of arbitrary stateful declarations. The root re-runs the
     * closed-world manifest admission and checked critical MIR before execution.
     */
    private static final ThreadLocal<StatefulActorLease> STATEFUL_ACTOR_LEASE =
            new ThreadLocal<>();

    private static final class StatefulActorLease {
        private final String codeUnitId;
        private final String text;
        private final Path manifest;
        private final String actorName;
        private boolean consumed;
        private boolean executed;

        private StatefulActorLease(String codeUnitId, String text,
                                   Path manifest, String actorName) {
            this.codeUnitId = codeUnitId;
            this.text = text;
            this.manifest = manifest;
            this.actorName = actorName;
        }
    }

    public static final class StatefulActorScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final StatefulActorLease lease;
        private boolean closed;

        private StatefulActorScope(StatefulActorLease lease) {
            this.lease = lease;
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "stateful actor parse lease must close on its owning thread");
            }
            if (STATEFUL_ACTOR_LEASE.get() != lease) {
                throw new IllegalStateException(
                        "stateful actor parse lease ownership changed before scope close");
            }
            STATEFUL_ACTOR_LEASE.remove();
            closed = true;
        }
    }

    /**
     * Trusted embedding-only single-use admission. The exact source file must
     * be the canonical actor constituent under the manifest's own src root.
     * The guest cannot select another root, receiver or source at parse time.
     */
    public static StatefulActorScope openVerifiedStatefulActor(
            Path manifest, Path actorUnit, String sourceText, String actorName) {
        java.util.Objects.requireNonNull(manifest, "canonical manifest");
        java.util.Objects.requireNonNull(actorUnit, "physical actor unit");
        java.util.Objects.requireNonNull(sourceText, "exact actor source");
        java.util.Objects.requireNonNull(actorName, "nominal declared actor");
        Path normalizedManifest = manifest.toAbsolutePath().normalize();
        Path normalizedActor = actorUnit.toAbsolutePath().normalize();
        if (!normalizedManifest.getFileName().toString().equals(".atto.pkg.toml")
                || !normalizedActor.equals(
                        normalizedManifest.getParent().resolve("src/actor.atto"))
                || actorName.isBlank()) {
            throw new IllegalArgumentException(
                    "stateful guest requires one canonical manifest and actor constituent");
        }
        if (PRECHECKED_PROGRAM.get() != null || STATEFUL_ACTOR_LEASE.get() != null) {
            throw new IllegalStateException("nested or mixed compiler parse leases are forbidden");
        }
        StatefulActorLease lease = new StatefulActorLease(
                normalizedActor.toString().replace('\\', '/'),
                sourceText, normalizedManifest, actorName);
        STATEFUL_ACTOR_LEASE.set(lease);
        return new StatefulActorScope(lease);
    }

    private static StatefulActorLease consumeStatefulActorLease(
            String codeUnitId, String sourceText) {
        StatefulActorLease lease = STATEFUL_ACTOR_LEASE.get();
        if (lease == null) return null;
        if (lease.consumed) {
            throw new IllegalStateException(
                    "stateful actor parse lease was already consumed");
        }
        lease.consumed = true; // consume even on identity mismatch/replay
        if (!lease.codeUnitId.equals(codeUnitId) || !lease.text.equals(sourceText)) {
            throw new IllegalStateException(
                    "stateful actor parse lease does not match exact physical source");
        }
        return lease;
    }

    /**
     * Required at EXECUTION too: Truffle may cache a parsed CallTarget and
     * reuse it without a fresh parse. The root must never run outside its
     * live one-shot host lease, even on a cached Source.
     */
    public static void requireStatefulActorExecution(
            Path manifest, String actorName, String codeUnitId, String sourceText) {
        StatefulActorLease lease = STATEFUL_ACTOR_LEASE.get();
        if (lease == null || !lease.consumed || lease.executed
                || !lease.manifest.equals(manifest)
                || !lease.actorName.equals(actorName)
                || !lease.codeUnitId.equals(codeUnitId)
                || !lease.text.equals(sourceText)) {
            throw new SecurityException(
                    "stateful guest requires a fresh, unconsumed exact source execution lease");
        }
        lease.executed = true;
    }

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    /**
     * Host-owned actor dispatcher and async workers may enter the context.
     * Guest source still has no raw thread-creation authority; that remains controlled by
     * IsolatePolicy and the Polyglot Context builder.
     *
     * Strict/adversarial contexts serialize actor guest turns in OresContext.
     * Non-adversarial contexts may execute independent actor turns concurrently.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded
                || ActorRuntime.isActorCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()
                || OresScheduler.isSchedulerCarrierThread();
    }

    @Override
    protected void initializeMultiThreading(OresContext context) {
        // All mutable language state used by actor turns is context-owned,
        // actor-owned, immutable, or explicitly synchronized.
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        String text = source.getCharacters().toString();
        String codeUnitId = source.getPath();
        if (codeUnitId == null || codeUnitId.isBlank()) {
            codeUnitId = source.getName();
        } else {
            try {
                codeUnitId = Path.of(codeUnitId).toAbsolutePath().normalize().toString().replace('\\', '/');
            } catch (InvalidPathException invalidPath) {
                throw new IllegalArgumentException("invalid Oreslang source path identity", invalidPath);
            }
        }
        if (codeUnitId == null || codeUnitId.isBlank()) codeUnitId = "<anonymous>";

        StatefulActorLease stateful = consumeStatefulActorLease(codeUnitId, text);
        if (stateful != null) {
            return new OresStatefulActorManifestRootNode(
                    this, stateful.manifest, stateful.actorName,
                    stateful.codeUnitId, stateful.text).getCallTarget();
        }
        Ast.Program program = consumePrecheckedProgram(codeUnitId, text);
        if (program == null) {
            program = OresCompiler.parseAndTypeCheck(text);
        }
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
