package dev.oreslang.types;

/**
 * Stable codes for every {@link OwnershipChecker} diagnostic.
 *
 * <p>Diagnostics render as {@code Atto ownership error: <CODE>: <message>}.
 * Codes marked {@code registryFamily} are source-level ownership families
 * from the canonical code registry in atto-lang/atto-borrow-checker.rs
 * ({@code conformance_v1::REGISTRY}). The master checker's L2/L3
 * conformance tooling scores those codes against canonical verdicts. The
 * remaining codes are Java-local: implementation limits, type/arity errors
 * and MutexGuard linearity rules that the canonical model does not express.
 * Conformance tooling treats them as non-ownership rejects.
 *
 * <p>Adding a code: prefer an existing registry family when the canonical
 * model has the concept. A new registry family must first be added to the
 * master registry so that the two stay in sync.
 */
public enum OwnershipDiagnosticCode {
    // ---- canonical registry families -------------------------------------
    /** Use, borrow or move of a moved/dropped owner. */
    E_MOVED(true),
    /** Conflicting loans, mutation/move/consume while borrowed, borrow-mode mismatch. */
    E_BORROW(true),
    /** A loan or lexical capability would outlive its scope or be stored longer-lived. */
    E_LIFETIME(true),
    /** Mutation through a read-only binding, field, record or borrow. */
    E_MUTABILITY(true),
    /** A loan or MutexGuard is live across a suspension point (yield, select, await). */
    E_LOAN_SUSPEND(true),
    /** A borrow, guard or actor-owned state escapes the actor's mailbox turn. */
    E_ACTOR_LOAN_ESCAPE(true),
    /** A Task/continuation capture lacks Copy/Send/lifetime proof. */
    E_TASK_CAPTURE(true),
    /** Copy of a move-only value without a checked copy contract. */
    E_COPY_SHALLOW_ALIAS(true),
    /** Sharing a move-only value without the owning shared convergence. */
    E_SHARE_ALIAS(true),

    // ---- Java-local (not canonical ownership families) --------------------
    /** MutexGuard linearity: must be bound once, released, never discarded or formatted. */
    E_GUARD(false),
    /** Conservative implementation limit; fails closed until the proof exists. */
    E_UNSUPPORTED(false),
    E_ARITY(false),
    E_TYPE(false),
    E_UNKNOWN(false),
    E_DUPLICATE(false),
    E_SYNTAX(false),
    /** Constructor self is not definitely initialized. */
    E_INIT(false),
    E_COPY_FORBIDDEN(false),
    E_COPY_UNPROVEN(false),
    E_COPY_ANNOTATION(false),
    E_MOVE_UNPROVEN(false);

    /** Leading text of every ownership diagnostic message. */
    public static final String PREFIX = "Atto ownership error: ";

    private final boolean registryFamily;

    OwnershipDiagnosticCode(boolean registryFamily) {
        this.registryFamily = registryFamily;
    }

    /** True when the master borrow checker's registry scores this code. */
    public boolean registryFamily() {
        return registryFamily;
    }

    /**
     * The code of an ownership diagnostic message, or {@code null} if the
     * message is not one. Accepts the exception message as thrown.
     */
    public static OwnershipDiagnosticCode of(String message) {
        String prefix = PREFIX;
        if (message == null || !message.startsWith(prefix)) {
            return null;
        }
        int end = message.indexOf(':', prefix.length());
        if (end < 0) {
            return null;
        }
        try {
            return valueOf(message.substring(prefix.length(), end));
        } catch (IllegalArgumentException notACode) {
            return null;
        }
    }
}
