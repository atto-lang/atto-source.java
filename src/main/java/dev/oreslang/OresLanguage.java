package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.NamespaceActorUnleasedSpawnV0;
import dev.oreslang.compiler.CheckedNamespaceActorGuestProof;
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

    private static final ThreadLocal<CheckedActorGuestLease> CHECKED_ACTOR_GUEST =
            new ThreadLocal<>();

    private static final class CheckedActorGuestLease {
        final CheckedNamespaceActorGuestProof proof;
        final Thread owner = Thread.currentThread();
        boolean consumed;
        CheckedActorGuestLease(CheckedNamespaceActorGuestProof proof) {
            this.proof = java.util.Objects.requireNonNull(proof);
        }
    }

    /**
     * Trusted-host, single-parse capability for a compiler-verified actor IR.
     * This is NOT available to guest code and does not relax normal
     * TypeChecker admission for unleased namespace actor source.
     */
    static CheckedActorGuestScope openCheckedActorGuest(
            CheckedNamespaceActorGuestProof proof) {
        if (CHECKED_ACTOR_GUEST.get() != null || PRECHECKED_PROGRAM.get() != null) {
            throw new IllegalStateException("nested checked-source lease denied");
        }
        CheckedActorGuestLease lease = new CheckedActorGuestLease(proof);
        CHECKED_ACTOR_GUEST.set(lease);
        return new CheckedActorGuestScope(lease);
    }

    static final class CheckedActorGuestScope implements AutoCloseable {
        private final CheckedActorGuestLease lease;
        private boolean closed;
        private CheckedActorGuestScope(CheckedActorGuestLease lease) {
            this.lease = lease;
        }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != lease.owner
                    || CHECKED_ACTOR_GUEST.get() != lease) {
                throw new IllegalStateException(
                        "checked actor lease must close on its owning thread");
            }
            CHECKED_ACTOR_GUEST.remove();
            closed = true;
        }
    }

    private static CheckedNamespaceActorGuestProof consumeCheckedActorGuest(
            String codeUnitId, String sourceText) {
        CheckedActorGuestLease lease = CHECKED_ACTOR_GUEST.get();
        if (lease == null) return null;
        if (lease.consumed) throw new IllegalStateException(
                "checked actor guest lease is single-use");
        // Wrong source or file identity consumes the lease permanently.
        lease.consumed = true;
        if (!lease.proof.codeUnitId().equals(codeUnitId)
                || !lease.proof.sourceText().equals(sourceText)) {
            throw new SecurityException(
                    "checked actor guest lease source identity/content mismatch");
        }
        return lease.proof;
    }


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
        if (PRECHECKED_PROGRAM.get() != null
                || CHECKED_ACTOR_GUEST.get() != null) {
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

        CheckedNamespaceActorGuestProof actorProof =
                consumeCheckedActorGuest(codeUnitId, text);
        if (actorProof != null) {
            return actorProof.guestRootTarget(this);
        }
        Ast.Program program = consumePrecheckedProgram(codeUnitId, text);
        if (program == null) {
            // Only a fully parsed and closed-world verified namespace actor
            // plus source-level spawn/send main can enter the unleased v0
            // root. All other actor source still fails the normal TypeChecker.
            RootCallTarget actorMain = NamespaceActorUnleasedSpawnV0.tryCreate(
                    this, codeUnitId, text);
            if (actorMain != null) return actorMain;
            program = OresCompiler.parseAndTypeCheck(text);
        }
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
