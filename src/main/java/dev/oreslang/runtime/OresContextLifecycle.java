package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.util.Objects;

/**
 * Host-owned preflight barrier for disposing a GraalVM Polyglot Context.
 *
 * <p>The outer Context.close() API is not retryable once disposal begins.
 * Embedders that may own suspended Oreslang actors should therefore run this
 * preflight while the Polyglot Context is still live. If physical quiescence
 * cannot be proven, the exception is propagated and outer disposal is not
 * started.</p>
 */
public final class OresContextLifecycle {
    private static final String HOST_PREFLIGHT_SOURCE =
            "pub fnc ores_host_disposal_marker(): void { return; }";

    private OresContextLifecycle() { }

    /**
     * Seal new runtime admission and require all internal owners to cross
     * their physical-exit barriers without disposing the outer context.
     */
    public static void prepareForDisposal(Context polyglotContext) {
        Objects.requireNonNull(polyglotContext, "polyglotContext");
        Source source = Source.newBuilder(
                OresLanguage.ID,
                HOST_PREFLIGHT_SOURCE,
                "__ores_host_disposal_preflight__.ores").buildLiteral();
        polyglotContext.parse(source)
                .execute(OresEvalRootNode.PREPARE_CONTEXT_DISPOSAL_COMMAND);
    }

    /** Dispose the outer Context only after successful internal preflight. */
    public static void closeWhenQuiescent(Context polyglotContext) {
        prepareForDisposal(polyglotContext);
        polyglotContext.close();
    }
}
