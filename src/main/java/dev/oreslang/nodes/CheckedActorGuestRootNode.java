package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.CheckedNamespaceActorGuestProof;
import dev.oreslang.runtime.OresContext;

import java.util.Objects;

/**
 * Experimental checked-IR guest root installed only with a one-shot,
 * exact-byte trusted-host lease. Never created by untrusted .atto source.
 *
 * <p>Runs the compiler-verified actor IR under a real Graal Context.eval
 * and the context-owned ActorRuntime. This is not general source-level
 * spawn/receive code generation, a guest-visible actor reference or
 * a replacement for OresEvalRootNode's ordinary admission checks.
 */
public final class CheckedActorGuestRootNode extends RootNode {
    private static final Object ROOT_PERMIT = new Object();
    private final CheckedNamespaceActorGuestProof checked;

    public CheckedActorGuestRootNode(
            OresLanguage language, CheckedNamespaceActorGuestProof checked) {
        super(language);
        this.checked = Objects.requireNonNull(checked, "checked namespace actor program");
    }

    /** Host-only internal dispatch token. Never exposed to guest values. */
    public static Object permit() { return ROOT_PERMIT; }

    @Override public String getName() { return "ores-checked-actor-guest-probe"; }
    @Override public boolean isInternal() { return true; }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(OresContext.get(this), frame.getArguments());
    }

    @TruffleBoundary
    private long executeBoundary(OresContext context, Object[] args) {
        if (args.length != 0) {
            throw new SecurityException("checked actor guest root takes no arguments");
        }
        if (context.isolatePolicy().adversarial()) {
            throw new SecurityException(
                    "experimental checked actor admission unavailable in adversarial contexts");
        }
        return checked.executeGuestProof(context.actors(), ROOT_PERMIT);
    }
}
