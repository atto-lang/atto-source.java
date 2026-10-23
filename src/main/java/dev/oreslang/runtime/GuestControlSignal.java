package dev.oreslang.runtime;

import dev.oreslang.nodes.OresEvalRootNode;
import java.lang.StackWalker;

/**
 * Guest-language exceptional control channels.
 *
 * <p>Throw is an ordinary guest error and may be handled by try/catch or
 * converted to TrapResult at a trap boundary. Raise may be handled only
 * by an eligible recovery boundary. Panic is never locally consumable and
 * must escape to the applicable supervisor/termination boundary.
 * Cancellation and forced termination use separate runtime signals.
 *
 * <p>The constructor stamps the actual issuing Java class. Only source
 * execution code nested inside the exact VM evaluator class is marked
 * compiler-issued. Foreign/FFI code can construct diagnostic host signals,
 * but cannot make them eligible for guest trap/recover admission merely by
 * choosing one of these public Java types. This is a temporary synchronous
 * provenance fence, not yet task-/actor-incarnation-bound authorization.
 * Reflective access to VM internals must remain denied to untrusted callers.
 */
public abstract sealed class GuestControlSignal extends RuntimeException
        permits GuestControlSignal.Throw, GuestControlSignal.Raise, GuestControlSignal.Panic {
    private static final StackWalker ISSUER_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final Object payload;
    private final String kind;
    private final boolean compilerIssued;

    private GuestControlSignal(String kind, Object payload) {
        super("Oreslang " + kind + " signal", null, true, true);
        this.kind = kind;
        this.payload = payload;
        // Unlike class/package-name comparisons, this uses JVM Class identity
        // of the actual issuing frame. Skip only constructors of this sealed
        // carrier and its final variants; do not trust caller-provided hints,
        // mutable causes, StackTraceElement arrays or Java package names.
        this.compilerIssued = ISSUER_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != GuestControlSignal.class
                        && type != Throw.class
                        && type != Raise.class
                        && type != Panic.class)
                .findFirst()
                .map(type -> type.getNestHost() == OresEvalRootNode.class)
                .orElse(false));
    }

    /** Whether this signal came directly from a trusted interpreter frame. */
    public final boolean compilerIssued() {
        return compilerIssued;
    }

    public final Object payload() {
        return payload;
    }

    public final String kind() {
        return kind;
    }

    public static final class Throw extends GuestControlSignal {
        public Throw(Object payload) {
            super("throw", payload);
        }
    }

    public static final class Raise extends GuestControlSignal {
        public Raise(Object payload) {
            super("raise", payload);
        }
    }

    public static final class Panic extends GuestControlSignal {
        public Panic(Object payload) {
            super("panic", payload);
        }
    }
}
