package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An explicit adversarial flag is a security boundary even when an embedder
 * does not also provide --ores-capabilities.
 */
final class IsolatePolicyAdversarialTraceTest {
    @Test
    void adversarialWithoutCapabilitiesMustUseStrictBaseline() {
        IsolatePolicy policy = IsolatePolicy.fromApplicationArguments(
                new String[]{"--ores-adversarial=true"});
        assertTrue(policy.adversarial());
        assertEquals(IsolatePolicy.strictFaas().capabilities(), policy.capabilities());
        assertFalse(policy.allows(IsolatePolicy.Capability.HOT_CODE_LOAD));
        assertFalse(policy.allows(IsolatePolicy.Capability.SHARED_MEMORY));
    }

    @Test
    void missingFlagPreservesDeveloperBaseline() {
        IsolatePolicy policy = IsolatePolicy.fromApplicationArguments(new String[0]);
        assertFalse(policy.adversarial());
        assertEquals(IsolatePolicy.developer().capabilities(), policy.capabilities());
    }

    @Test
    void malformedThreatModelFlagMustFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.fromApplicationArguments(
                        new String[]{"--ores-adversarial=TRUE"}));
        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.fromApplicationArguments(
                        new String[]{"--ores-adversarial=maybe"}));
    }
}
