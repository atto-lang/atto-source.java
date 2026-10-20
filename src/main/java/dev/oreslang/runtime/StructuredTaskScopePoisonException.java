package dev.oreslang.runtime;

/**
 * Internal control failure raised when structured Task lifetime/authority
 * accounting can no longer prove a safe successful drain.
 *
 * <p>This is deliberately not a guest-recoverable application error. The
 * runtime may surface it only after every affected child is physically
 * quiescent, but trap/catch must still fail closed because the authority proof
 * itself was corrupted.</p>
 */
final class StructuredTaskScopePoisonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StructuredTaskScopePoisonException(String message, Throwable cause) {
        super(message, cause);
    }
}
