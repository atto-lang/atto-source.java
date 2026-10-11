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

    // ContextPolicy.EXCLUSIVE creates a separate language instance per context.
    // The Polyglot embedder's original owner thread must remain admissible to
    // inspect a returned Future/Channel host handle while a CONTROL task is
    // simultaneously entered. Never grant this to arbitrary outside threads.
    private volatile Thread trustedEmbedderThread;

    @Override
    protected OresContext createContext(Env env) {
        OresContext context = new OresContext(this, env);
        // Adversarial/untrusted contexts keep their isolated single-thread
        // admission and must not acquire concurrent host re-entry privileges.
        trustedEmbedderThread = context.isolatePolicy().adversarial()
                ? null : Thread.currentThread();
        System.err.println("ORES_CREATE_CONTEXT lang=" + System.identityHashCode(this)
                + " owner=" + trustedEmbedderThread
                + " caller=" + Thread.currentThread()
                + " adversarial=" + context.isolatePolicy().adversarial());
        return context;
    }

    /**
     * Host-owned actor dispatcher and async workers may enter the context.
     * Guest source still has no raw thread-creation authority; that remains controlled by
     * IsolatePolicy and the Polyglot Context builder.
     *
     * Strict/adversarial contexts serialize actor guest turns in OresContext.
     * In trusted contexts, one original embedder thread is also authorized for
     * concurrent Future/Channel host-handle access, in addition to owned VM
     * carrier threads. A random host thread is never made a trusted carrier.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        if (singleThreaded) return true;
        // The thread argument may differ from Thread.currentThread() during
        // Truffle access checks. A carrier-local flag cannot authorize some
        // *other* thread. Explicitly restrict those privileges to the caller.
        boolean currentThread = thread == Thread.currentThread();
        boolean allowed = thread == trustedEmbedderThread
                || (currentThread && (ActorRuntime.isActorCarrierThread()
                    || AsyncRuntime.isAsyncCarrierThread()
                    || OresScheduler.isSchedulerCarrierThread()));
        if (!allowed && "main".equals(thread.getName())) {
            System.err.println("ORES_TRUFFLE_DENIED lang=" + System.identityHashCode(this)
                    + " requested=" + thread
                    + " trustedOwner=" + trustedEmbedderThread
                    + " current=" + Thread.currentThread()
                    + " sameCurrent=" + currentThread);
        }
        return allowed;
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
