package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental, trusted-host-only bridge from one exact source identity to
 * an executable Truffle guest root for a closed namespace actor IR.
 *
 * <p>This does not grant general guest source admission. A separate one-shot
 * lease must be installed by trusted Java code before Context.eval parses the
 * exact source bytes; an unleased actor source still fails in TypeChecker.
 * The compiler verifies each actor field/statement/ingress before this object
 * can be issued, and source code cannot name, capture or call this object.
 */
public final class CheckedNamespaceActorGuestProof implements AutoCloseable {
    private final String codeUnitId;
    private final String sourceText;
    private final SharedCodeImageStore images = new SharedCodeImageStore();
    private final ActorRuntime.ActorCodeExecutor executable;
    private final AtomicBoolean executed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private CheckedNamespaceActorGuestProof(String codeUnitId, String sourceText) {
        this.codeUnitId = Objects.requireNonNull(codeUnitId, "code unit id");
        this.sourceText = Objects.requireNonNull(sourceText, "source text");
        if (codeUnitId.isBlank() || sourceText.isBlank()) {
            throw new IllegalArgumentException("source identity and bytes are required");
        }
        // No prechecked AST is accepted from callers. The real parser and
        // static actor/owner/critical IR admission run on the actual bytes.
        this.executable = NamespaceActorCriticalIrCompiler.compile(
                images.publish(codeUnitId, Parser.parse(sourceText)));
    }

    public static CheckedNamespaceActorGuestProof compile(
            String codeUnitId, String sourceText) {
        return new CheckedNamespaceActorGuestProof(codeUnitId, sourceText);
    }

    public String codeUnitId() { return codeUnitId; }
    public String sourceText() { return sourceText; }

    /**
     * Executed only from the dedicated Graal RootNode after the exact-source
     * lease is consumed. Host tests cannot call this before a live guest turn:
     * the RootNode passes a private dispatch token that only it can obtain.
     *
     * <p>Demonstrates source-bound actor creation and 1,000 actual mailbox
     * turns without exposing any ActorRef or mutable state to guest code.
     * Returns the number of admitted, drained messages, not state contents.
     */
    public long executeGuestProof(ActorRuntime runtime, Object guestRootPermit) {
        Objects.requireNonNull(runtime, "context-owned actor runtime");
        if (guestRootPermit != dev.oreslang.nodes.CheckedActorGuestRootNode.permit()) {
            throw new SecurityException("only a checked Graal guest root may run actor proof");
        }
        if (closed.get() || !executed.compareAndSet(false, true)) {
            throw new IllegalStateException("checked actor guest proof is closed or already executed");
        }
        ActorRuntime.ActorRef<Object> first = null;
        ActorRuntime.ActorRef<Object> sibling = null;
        try {
            first = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            sibling = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            first.ready().get(5, TimeUnit.SECONDS);
            sibling.ready().get(5, TimeUnit.SECONDS);
            if (first.id().equals(sibling.id())) {
                throw new SecurityException("two actor incarnations share runtime identity");
            }
            for (int i = 0; i < 1_000; i++) first.send(1L);
            first.drain().get(10, TimeUnit.SECONDS);
            sibling.send(7L);
            sibling.drain().get(5, TimeUnit.SECONDS);
            if (first.failure().isPresent() || sibling.failure().isPresent()) {
                throw new IllegalStateException("checked actor mailbox execution failed");
            }
            return 1_000L;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("checked guest actor proof interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("checked guest actor proof failed", failure);
        } finally {
            // No guest-visible actor handle escapes this narrow bridge.
            if (first != null) first.stop();
            if (sibling != null) sibling.stop();
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) images.close();
    }
}
