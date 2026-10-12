package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
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

    /**
     * ContextPolicy.EXCLUSIVE gives each language instance one context.
     * Remember the host entry thread which created that context: when a guest
     * async scheduler starts during a public host invocation, Truffle checks
     * access on BOTH threads with singleThreaded=false. Denying the original
     * host thread sporadically fails safe async invocation at that boundary.
     *
     * Do not grant blanket multithreading: a different/foreign host thread
     * still needs exclusive single-threaded entry, and guest-created carrier
     * threads do not gain runtime scheduling authority by this check.
     */
    private volatile Thread initialContextHostThread;

    // Explicit, context-owned scheduler admission for a root CONTROL carrier.
    // Truffle can query one thread's access while running on another thread;
    // a carrier-local ThreadLocal alone is not sufficient in that situation.
    private final java.util.concurrent.ConcurrentHashMap<Thread, Integer>
            admittedRootCarriers = new java.util.concurrent.ConcurrentHashMap<>();

    private void admitRootCarrier(Thread carrier) {
        admittedRootCarriers.merge(
                java.util.Objects.requireNonNull(carrier, "carrier"), 1, Integer::sum);
    }

    private void releaseRootCarrier(Thread carrier) {
        admittedRootCarriers.compute(carrier, (thread, count) -> {
            if (count == null || count < 1) {
                throw new IllegalStateException("unbalanced root CONTROL admission");
            }
            return count == 1 ? null : count - 1;
        });
    }

    @Override
    protected OresContext createContext(Env env) {
        OresContext context = new OresContext(
                this, env, this::admitRootCarrier, this::releaseRootCarrier);
        // Publish the embedding-thread privilege only after we know this
        // context's isolation policy. An adversarial guest must never retain
        // this trusted-host concurrent admission exception.
        initialContextHostThread = context.isolatePolicy().adversarial()
                ? null : Thread.currentThread();
        return context;
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
        return threadAccessDecision(
                thread,
                Thread.currentThread(),
                initialContextHostThread,
                singleThreaded,
                ActorRuntime.isActorCarrierThread(),
                AsyncRuntime.isAsyncCarrierThread(),
                OresScheduler.isSchedulerCarrierThread(),
                admittedRootCarriers.containsKey(thread));
    }

    /**
     * Pure admission predicate, independently exhaustively checked in tests.
     * A carrier-local flag must never authenticate a different requested
     * Thread; only the explicitly published trusted host identity can do so.
     */
    static boolean threadAccessDecision(
            Thread requested,
            Thread caller,
            Thread trustedHost,
            boolean singleThreaded,
            boolean actorCarrier,
            boolean asyncCarrier,
            boolean schedulerCarrier) {
        return threadAccessDecision(requested, caller, trustedHost,
                singleThreaded, actorCarrier, asyncCarrier, schedulerCarrier, false);
    }

    /**
     * Physical root CONTROL admission is an explicit lease, not a blanket
     * language-wide multithreading grant. Revoked/stale carrier identities
     * must not pass admission merely because they once ran scheduler work.
     */
    static boolean threadAccessDecision(
            Thread requested, Thread caller, Thread trustedHost,
            boolean singleThreaded, boolean actorCarrier,
            boolean asyncCarrier, boolean schedulerCarrier,
            boolean registeredRootCarrier) {
        return requested != null
                && (singleThreaded
                    || requested == trustedHost
                    || registeredRootCarrier
                    || (requested == caller
                        && (actorCarrier || asyncCarrier || schedulerCarrier)));
    }

    @Override
    protected void initializeMultiThreading(OresContext context) {
        // All mutable language state used by actor turns is context-owned,
        // actor-owned, immutable, or explicitly synchronized.
    }

    @Override
    protected void disposeContext(OresContext context) {
        // Never retain the embedding-thread multithreading exception after a
        // successful physical retirement. A failed close must remain retryable
        // because pending finally/defer cleanup can still own live carriers.
        context.close();
        initialContextHostThread = null;
        admittedRootCarriers.clear();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        String text = source.getCharacters().toString();
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
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
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
