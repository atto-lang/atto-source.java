package dev.oreslang.runtime;

/**
 * Escalated Oreslang failure distinguished from ordinary throw and panic.
 *
 * <p>The source-level raise/recover grammar is not yet implemented; this
 * runtime marker reserves the failure classification for future lowering.
 * It must not be caught by ordinary guest try/catch or converted by trap.</p>
 */
public final class OresRaised extends RuntimeException {
    public OresRaised(String message) {
        super(message, null, true, false);
    }
}
