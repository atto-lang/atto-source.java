package dev.oreslang.runtime;

/**
 * Deterministic guest stack exhaustion of one logical stack (receive
 * invocation or Task). Raised by {@link LogicalStackQuota} before the host
 * thread stack can overflow.
 *
 * <p>It is deliberately a {@link RuntimeException}, not a
 * {@link VirtualMachineError}: it fails the owning actor incarnation or Task
 * under its supervision policy without killing the carrier thread. Guest
 * {@code trap} and {@code catch} cannot consume it (see
 * {@link TrapFailurePolicy}); recovery belongs to the supervisor.</p>
 */
public final class LogicalStackOverflowException extends RuntimeException {
    private final String owner;
    private final int depth;
    private final int quota;
    private final boolean carrierCeiling;

    LogicalStackOverflowException(String owner, int depth, int quota, boolean carrierCeiling) {
        // Stackless: a full Java stack trace of an exhausted stack is itself
        // a deep allocation, and the guest-visible trace is recorded by
        // SourceBoundaryTrace frames as the failure unwinds.
        super("StackOverflow: logical stack '" + owner + "' exceeded "
                + (carrierCeiling ? "the carrier frame ceiling" : "its quota of " + quota + " guest frames")
                + " at depth " + depth, null, true, false);
        this.owner = owner;
        this.depth = depth;
        this.quota = quota;
        this.carrierCeiling = carrierCeiling;
    }

    public String owner() { return owner; }
    public int depth() { return depth; }
    public int quota() { return quota; }
    /** True when the per-carrier ceiling, not the segment quota, tripped first. */
    public boolean carrierCeiling() { return carrierCeiling; }
}
