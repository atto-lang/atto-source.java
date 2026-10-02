package dev.oreslang.runtime;

/**
 * Non-guest-catchable execution control signal.
 *
 * Oreslang try/catch handles ordinary RuntimeException failures. Resource
 * deadlines, runtime shutdown and scheduler interruption are host/supervisor
 * control events and must not be suppressible by guest error handling.
 *
 * This is deliberately an Error but not a VirtualMachineError: actor/singleton
 * supervisors treat it as an operation/process failure rather than a corrupt
 * JVM that must poison the entire process singleton cell.
 */
public final class ExecutionTerminated extends Error {
    public ExecutionTerminated(String message) {
        super(message, null, false, false);
    }
}
