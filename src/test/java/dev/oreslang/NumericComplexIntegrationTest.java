package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current-main guest complex arithmetic smoke; numerical reference fixtures
 * are source-free and do not substitute for actually executing this class.
 * Merge of the older Matrix/Vector/EE feature branch is deliberately excluded.
 */
final class NumericComplexIntegrationTest {
    private static boolean evaluatesTrue(String body) {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                    """ + body + """
                    }
                    """).asBoolean();
        }
    }

    @Test
    void complexAdditionSubtractionAndCrossTerms() {
        assertTrue(evaluatesTrue("""
                return ((1.0 + 2.0i) + (3.0 - 4.0i)) eq (4.0 - 2.0i)
                    && ((1.0 + 2.0i) - (3.0 - 4.0i)) eq (-2.0 + 6.0i)
                    && ((3.0 + 4.0i) * (5.0 - 2.0i)) eq (23.0 + 14.0i);
                """));
    }

    @Test
    void complexDivisionBasicAndEqualScaleInputs() {
        assertTrue(evaluatesTrue("""
                return ((3.0 + 4.0i) / (1.0 - 2.0i)) eq (-1.0 + 2.0i)
                    && ((1e308 + 1e308i) / (1e308 + 1e308i)) eq 1
                    && ((1e-300 + 1e-300i) / (1e-300 + 1e-300i)) eq 1
                    && (5e-324i / 5e-324i) eq 1;
                """));
    }

    @Test
    void complexDivisionRetainsRepresentableSubnormalScaleCrossComponent() {
        // Corresponds to the fixed regression in numeric complex issue #1042.
        // This is a targeted oracle case, NOT proof of correctly rounded
        // complex division for every binary64 input.
        assertTrue(evaluatesTrue("""
                val complex actual =
                    (-3.8267306126191597e229 - 2.653330234432702e-108i)
                    / (4.996866008987324e199 + 5.230549533052834e-126i);
                return actual eq (-7.658261409724479e29 + 8.016407798119527e-296i);
                """));
    }

    @Test
    void complexMultiplyPreservesMinSubnormalImaginaryAndFiniteCancellation() {
        assertTrue(evaluatesTrue("""
                return ((1.3e-162 + 1.3e-162i)
                        * (1.3e-162 + 1.3e-162i)) eq 5e-324i
                    && ((1e308 + 1e308i) * (1e308 + 1e308i))
                        eq ((1e308 * 1e308i) + (1e308 * 1e308i));
                """));
    }

    @Test
    void complexNaNIsNotEqualToItselfEvenAtSameReferenceIdentity() {
        assertTrue(evaluatesTrue("""
                val complex invalid = 0.0i / 0.0i;
                return !(invalid eq invalid);
                """));
    }

    @Test
    void complexDivisionDoesNotOverflowWhenOnlyNumeratorIsLarge() {
        assertTrue(evaluatesTrue("""
                return ((1e308i / 1e308i) eq 1)
                    && ((1e-300i / 1e-300i) eq 1);
                """));
    }
}
