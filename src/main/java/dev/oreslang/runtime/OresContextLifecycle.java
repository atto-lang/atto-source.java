package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.util.Objects;

/**
 * Host-owned preflight barrier for disposing a GraalVM Polyglot Context.
 *
 * <p>The ordinary outer Context.close() API cannot promise retryable guest
 * execution after its disposal machinery begins. The embedding host should
 * invoke prepareForDisposal while the Polyglot Context remains valid. A drain
 * timeout throws and deliberately does NOT call outer Context.close(); the
 * host may resolve outstanding producer Futures and retry preflight.</p>
 *
 * <p>This is a host API, not a guest-language operation. It cannot bypass
 * Truffle's own disposal behavior if callers invoke Context.close() directly.</p>
 */
public final class OresContextLifecycle {
    private static final String HOST_PREFLIGHT_SOURCE =
            "pub fnc ores_host_disposal_marker(): void { return; }";

    private OresContextLifecycle() { }

    /**
     * Seal guest runtime admission and require physical quiescence without
     * disposing the outer Polyglot context on failure.
     */
    public static void prepareForDisposal(Context polyglotContext) {
        Objects.requireNonNull(polyglotContext, "polyglotContext");
        Source source = Source.newBuilder(
                OresLanguage.ID, HOST_PREFLIGHT_SOURCE,
                "__ores_host_disposal_preflight__.ores").buildLiteral();
        polyglotContext.parse(source)
                .execute(OresEvalRootNode.PREPARE_CONTEXT_DISPOSAL_COMMAND);
    }

    /** Dispose the outer Context only after its internal owners have drained. */
    public static void closeWhenQuiescent(Context polyglotContext) {
        prepareForDisposal(polyglotContext);
        polyglotContext.close();
    }
}
