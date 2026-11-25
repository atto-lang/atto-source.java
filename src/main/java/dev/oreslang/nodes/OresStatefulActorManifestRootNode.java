package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.NamespaceActorIngressV1;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in, compiler-authenticated Truffle guest entry for one closed-world
 * manifest-linked stateful actor. Ordinary Context.eval still goes through
 * TypeChecker and rejects arbitrary attached state and critical source.
 *
 * This DOES NOT implement general guest `spawn CounterActor()` semantics:
 * Context.eval returns a bounded, one-use guest callable that drives the
 * actor's real channel-backed mailbox, private state and checked Truffle MIR.
 * It cannot expose ActorRef, CodeImage, ActorContext or critical loan handles.
 */
public final class OresStatefulActorManifestRootNode extends RootNode {
    private final Path manifest;
    private final String actorName;
    private final Path actorSourcePath;
    private final String sourceText;

    public OresStatefulActorManifestRootNode(
            OresLanguage language, Path manifest, String actorName,
            String actorSourcePath, String sourceText) {
        super(language);
        this.manifest = manifest;
        this.actorName = actorName;
        this.actorSourcePath = Path.of(actorSourcePath);
        this.sourceText = sourceText;
    }

    @Override public String getName() { return "ores-stateful-manifest-guest-v1"; }
    @Override public boolean isInternal() { return true; }

    @Override public Object execute(VirtualFrame frame) {
        OresLanguage.requireStatefulActorExecution(
                manifest, actorName,
                actorSourcePath.toString().replace('\\', '/'), sourceText);
        if (frame.getArguments().length != 0) {
            throw new IllegalArgumentException(
                    "stateful manifest guest entry cannot accept unverified arguments");
        }
        return openGuest(OresContext.get(this));
    }

    @TruffleBoundary
    private Object openGuest(OresContext context) {
        if (context.isolatePolicy().adversarial()) {
            throw new SecurityException("untrusted isolate cannot load a physical actor manifest");
        }
        if (!actorSourcePath.toAbsolutePath().normalize().equals(
                manifest.getParent().resolve("src/actor.atto").toAbsolutePath().normalize())) {
            throw new SecurityException("guest actor source is not the manifest-owned constituent");
        }
        try {
            if (Files.isSymbolicLink(actorSourcePath)
                    || !Files.readString(actorSourcePath).equals(sourceText)) {
                throw new SecurityException(
                        "physical source changed after trusted actor parse admission");
            }
            var verified = context.compileVerifiedStatefulActorGuest(manifest, actorName);
            // Distinct guest eval sessions reuse immutable linked code and have
            // independent actor state once the one-use callable is invoked.
            return new VerifiedBatch(verified.plan(), context.actors(),
                    context.isolatePolicy());
        } catch (IOException invalidManifest) {
            throw new IllegalArgumentException(
                    "stateful guest manifest snapshot rejected", invalidManifest);
        }
    }

    /**
     * Minimal guest callable for a bounded ingress conformance sequence.
     * No host-selected Plan.spawn, raw Java actor reference, or method handle
     * crosses the Polyglot interop boundary.
     *
     * This is a temporary synchronous EMBEDDING TEST ABI; it must not be used
     * for blocking actor-carrier work or presented as Atto channel syntax.
     */
    @ExportLibrary(InteropLibrary.class)
    public static final class VerifiedBatch implements TruffleObject {
        private final NamespaceActorIngressV1.Plan plan;
        private final ActorRuntime runtime;
        private final IsolatePolicy policy;
        private final AtomicBoolean consumed = new AtomicBoolean();

        VerifiedBatch(NamespaceActorIngressV1.Plan plan,
                      ActorRuntime runtime, IsolatePolicy policy) {
            this.plan = plan;
            this.runtime = runtime;
            this.policy = policy;
        }

        @ExportMessage boolean isExecutable() { return true; }

        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] arguments)
                throws ArityException, UnsupportedTypeException,
                       UnsupportedMessageException {
            if (arguments.length == 0 || arguments.length > 16) {
                throw ArityException.create(1, 16, arguments.length);
            }
            if (!consumed.compareAndSet(false, true)) {
                throw UnsupportedMessageException.create();
            }
            if (ActorRuntime.isActorCarrierThread()) {
                throw new SecurityException(
                        "synchronous embedding test ABI is forbidden from actor carriers");
            }
            // Validate the entire copied scalar input before allocating state
            // or submitting any mailbox message.
            long[] deltas = new long[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                Object value = arguments[i];
                if (value instanceof Long integer) deltas[i] = integer;
                else if (value instanceof Integer integer) deltas[i] = integer.longValue();
                else throw UnsupportedTypeException.create(arguments,
                        "stateful actor guest batch requires owned int scalars");
            }

            var actor = plan.spawn(runtime, policy);
            boolean terminated = false;
            try {
                actor.ready().get(30, TimeUnit.SECONDS);
                long result = 0L;
                for (long delta : deltas) {
                    actor.sendAsync(delta).get(30, TimeUnit.SECONDS);
                    var output = actor.outputs().next().get(30, TimeUnit.SECONDS);
                    if (output.done() || !(output.value().value() instanceof Long scalar)) {
                        throw new IllegalStateException(
                                "compiler-owned actor did not emit its typed critical result");
                    }
                    result = scalar;
                }
                actor.stop();
                terminated = actor.awaitTermination(30, TimeUnit.SECONDS);
                if (!terminated || actor.failure().isPresent()) {
                    throw new IllegalStateException(
                            "stateful actor did not complete safe teardown");
                }
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "guest stateful actor batch was interrupted", interrupted);
            } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IllegalStateException(
                        "guest stateful actor batch execution failed", failure);
            } finally {
                if (!terminated) actor.stop();
            }
        }
    }
}
