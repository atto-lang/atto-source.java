package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Real ActorLinearArena-backed ownership admission; host-only, not Atto guest lowering. */
@Timeout(30)
class TrustedAllocationScopeV1Test {
    @Test
    void opaqueOwnerAndReverseDropUnderNativeArena() throws Exception {
        assertDoesNotThrow(() -> TrustedAllocationScopeV1HostProbe.main(new String[0]));
    }

    @Test
    void guestCannotMintRawOwnerOrLoan() {
        for (var type : new Class<?>[] {
                TrustedAllocationScopeV1.class,
                TrustedAllocationScopeV1.Owner.class,
                TrustedAllocationScopeV1.Loan.class
        }) {
            assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()),
                    "trusted native owner type must not be public: " + type);
            for (var constructor : type.getDeclaredConstructors()) {
                assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                        "no owner/loan may be minted from a public constructor: " + type);
            }
        }
    }
}
