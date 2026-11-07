package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.DopInvertedScalarCompilerV1;
import dev.oreslang.compiler.DopInvertedScalarTruffleRootV1;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
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
        if (PRECHECKED_PROGRAM.get() != null || VERIFIED_DOP.get() != null) {
            throw new IllegalStateException(
                    "nested or overlapping Oreslang checked-source leases are not supported");
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

    /**
     * Internal, explicitly host-authorized, exact-source opt-in DOP path.
     * An ordinary Polyglot Context.eval(source) cannot establish this token.
     * The token is also checked by the compiled root on every invocation,
     * including calls to a Truffle source-cache hit after the scope closed.
     */
    private static final ThreadLocal<VerifiedDopLease> VERIFIED_DOP = new ThreadLocal<>();

    private static final class VerifiedDopLease {
        private final Thread owner = Thread.currentThread();
        private final String codeUnitId;
        private final String sourceText;
        private final DopInvertedScalarCompilerV1.Plan plan;
        private boolean consumed;
        private boolean closed;

        private VerifiedDopLease(String codeUnitId, String sourceText,
                                 DopInvertedScalarCompilerV1.Plan plan) {
            this.codeUnitId = codeUnitId;
            this.sourceText = sourceText;
            this.plan = plan;
        }
    }

    public static final class VerifiedDopScope implements AutoCloseable {
        private final VerifiedDopLease lease;
        private VerifiedDopScope(VerifiedDopLease lease) {
            this.lease = lease;
        }

        @Override
        public void close() {
            if (lease.closed) return;
            if (Thread.currentThread() != lease.owner) {
                throw new IllegalStateException(
                        "verified DOP lease must close on its owning thread");
            }
            if (VERIFIED_DOP.get() != lease) {
                throw new IllegalStateException(
                        "verified DOP lease ownership changed before close");
            }
            lease.closed = true;
            VERIFIED_DOP.remove();
        }
    }

    /**
     * Privileged *synchronous* host evaluation scope. Compile the restricted
     * source first; do not put a token in thread-local state on validation
     * failure. No raw AST, Plan, owner, actor or host closure can be supplied
     * by the caller. Not a general guest feature flag.
     */
    public static VerifiedDopScope openVerifiedDopSource(
            String codeUnitId, String sourceText) {
        java.util.Objects.requireNonNull(codeUnitId, "DOP source identity");
        java.util.Objects.requireNonNull(sourceText, "DOP source text");
        if (codeUnitId.isBlank() || VERIFIED_DOP.get() != null
                || PRECHECKED_PROGRAM.get() != null) {
            throw new IllegalStateException(
                    "verified DOP requires nonempty identity and exclusive nonnested scope");
        }
        DopInvertedScalarCompilerV1.Plan checked =
                OresCompiler.compileDopInvertedScalarI64(sourceText);
        VerifiedDopLease lease = new VerifiedDopLease(codeUnitId, sourceText, checked);
        VERIFIED_DOP.set(lease);
        return new VerifiedDopScope(lease);
    }

    static Object consumeVerifiedDopSource(String codeUnitId, String sourceText) {
        VerifiedDopLease lease = VERIFIED_DOP.get();
        if (lease == null) return null;
        if (lease.consumed || lease.closed || Thread.currentThread() != lease.owner) {
            throw new IllegalStateException("verified DOP lease cannot be reused");
        }
        // Mismatches permanently consume this lease. A second parse request
        // cannot use the originally checked bytes after a failed source swap.
        lease.consumed = true;
        if (!lease.codeUnitId.equals(codeUnitId)
                || !lease.sourceText.equals(sourceText)) {
            throw new IllegalStateException(
                    "verified DOP lease does not match exact code unit and source text");
        }
        return lease;
    }

    /**
     * Runtime, not merely parse-time, authority check. A cached call target
     * or a retained Value cannot run after its exact synchronous lease closes.
     * Never invoke a guest-supplied lambda through this admission boundary.
     */
    @TruffleBoundary
    public static void requireLiveVerifiedDopLease(Object authority) {
        VerifiedDopLease current = VERIFIED_DOP.get();
        if (current == null || current != authority || !current.consumed
                || current.closed || Thread.currentThread() != current.owner) {
            throw new SecurityException(
                    "DOP_VERIFIED_SCOPE_REQUIRED: expired, unconsumed or foreign source lease");
        }
    }

    static DopInvertedScalarCompilerV1.Plan checkedDopPlan(Object authority) {
        if (!(authority instanceof VerifiedDopLease lease)) {
            throw new SecurityException("DOP_VERIFIED_SCOPE_REQUIRED: invalid source lease");
        }
        requireLiveVerifiedDopLease(lease);
        return lease.plan;
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

        Object verifiedDop = consumeVerifiedDopSource(codeUnitId, text);
        if (verifiedDop != null) {
            RootCallTarget restricted = DopInvertedScalarTruffleRootV1.lowerForVerifiedSource(
                    this, checkedDopPlan(verifiedDop), verifiedDop);
            return new OresInteropRootNode(this, restricted).getCallTarget();
        }

        Ast.Program program = consumePrecheckedProgram(codeUnitId, text);
        if (program == null) {
            program = OresCompiler.parseAndTypeCheck(text);
        }
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
