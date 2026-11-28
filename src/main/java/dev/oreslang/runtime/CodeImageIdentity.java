package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque runtime-issued identity for one linked code image generation.
 *
 * <p>Equality is intentionally by object identity. Diagnostic fields such as
 * code-unit id and generation are not authentication material: two independent
 * managers may legitimately allocate the same numeric generation.</p>
 */
final class CodeImageIdentity {
    private final String codeUnitId;
    private final long generation;
    private final Object issuerSeal;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private static final Object RUNTIME_ISSUER = new Object();

    private CodeImageIdentity(
            String codeUnitId,
            long generation,
            Object issuerSeal) {
        if (Objects.requireNonNull(codeUnitId, "codeUnitId").isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("code image generation must be positive");
        }
        this.codeUnitId = codeUnitId;
        this.generation = generation;
        this.issuerSeal = Objects.requireNonNull(issuerSeal, "issuerSeal");
    }

    /** Trusted runtime/compiler issuance only; never expose as a guest factory. */
    static CodeImageIdentity issue(String codeUnitId, long generation) {
        return new CodeImageIdentity(codeUnitId, generation, RUNTIME_ISSUER);
    }

    String codeUnitId() {
        return codeUnitId;
    }

    long generation() {
        return generation;
    }

    boolean runtimeAuthenticated() {
        return issuerSeal == RUNTIME_ISSUER;
    }

    boolean isActive() {
        return runtimeAuthenticated() && active.get();
    }

    /**
     * Revokes future use. An already-entered synchronous recovery dispatch may
     * finish its current callback; generation retirement must separately wait
     * for physical/logical execution quiescence before destroying executable
     * code or actor state.
     */
    void revoke() {
        active.set(false);
    }

    boolean sameActiveImage(CodeImageIdentity other) {
        return this == other && isActive();
    }

    @Override
    public String toString() {
        return "CodeImageIdentity[" + codeUnitId + "#" + generation
                + ", active=" + active.get() + "]";
    }
}
