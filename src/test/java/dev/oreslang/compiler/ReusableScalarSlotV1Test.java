package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReusableScalarSlotV1Test {
    private static final String PREFIX = "pub fnc main(): int { let a: int = 42; let mut RawPointer<int> p = RawPointer.empty<int>(); ";
    @Test void verifiedPromotionUsesNormalCompiler() {
        String rebind = "pub fnc main(): int { let a: int = 40; let b: int = 2; let mut RawPointer<int> p = RawPointer.empty<int>(); ref! p to a; val first = deref! p; unref! p; ref! p to b; val second = deref! p; unref! p; return first + second; }";
        for (String source : new String[] { rebind, PREFIX + "return 42; }",
                PREFIX + "ref! p to a; unref! p; return a; }" }) {
            assertEquals("pub fnc main(): int { return 42; }", ReusableScalarSlotV1.lower(source));
            assertNotNull(OresCompiler.compileReusableScalarSlotV1(source));
        }
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(rebind));
    }
    @Test void invalidSlotsAreRejectedBeforePromotion() {
        for (String body : new String[] {"val x = deref! p; return x;", "p = null; return 42;",
            "ref! p to a; ref! p to a; unref! p; return 42;",
            "ref! p to a; unref! p; unref! p; return 42;", "ref! p to a; return 42;",
            "return p;", "ref ! p to a; unref! p; return 42;",
            "return 9223372036854775807 + 1;"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileReusableScalarSlotV1(PREFIX + body + " }"), body);
        }
    }
}
