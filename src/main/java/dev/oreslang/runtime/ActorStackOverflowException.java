package dev.oreslang.runtime;

/**
 * A guest actor's JVM carrier stack was exhausted during an admitted turn.
 *
 * <p>This is an actor-local terminal failure, not a recoverable guest
 * exception: the affected actor is stopped, its actor-owned frame arena is
 * poisoned and its structured children are cancelled. Independent sibling
 * actors keep their own stack budgets and can use the shared dispatcher
 * after the failed turn exits.
 *
 * <p>Only a `StackOverflowError` which has already unwound to the actor
 * turn boundary is translated. This is a best-effort JVM containment
 * boundary, NOT a guarantee that unbounded native/JNI recursion or arbitrary
 * process-corrupting VM failures can be recovered in-process. Untrusted
 * native execution still requires a separately killable worker/isolate.
 */
public final class ActorStackOverflowException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ActorStackOverflowException() {
        // Keep the diagnostic constant and stackless: allocating a detailed
        // Java trace immediately after native stack exhaustion can recurse.
        super("actor guest carrier stack exhausted; actor generation terminated",
                null, false, false);
    }
}
