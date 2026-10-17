package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void complexIntegralPromotionRequiresExactBinary64Range() {
        assertTrue(evaluatesTrue("""
                return (9007199254740992 + 1i)
                        eq (9007199254740992.0 + 1i)
                    && (-9007199254740992 + 1i)
                        eq (-9007199254740992.0 + 1i);
                """));

        for (String literal : new String[] {
                "9007199254740993",
                "-9007199254740993"
        }) {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                PolyglotException failure = assertThrows(
                        PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, """
                                pub routine main(): complex {
                                  return %s + 1i;
                                }
                                """.formatted(literal)));
                assertTrue(failure.getMessage().contains("exact binary64 range"),
                        failure::toString);
            }
        }
    }

    @Test
    void complexIntegralVariablePromotionAlsoRequiresExactBinary64RangeAtRuntime() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, """
                            pub routine main(): complex {
                              val int value = 9007199254740993;
                              return value + 1i;
                            }
                            """));
            assertTrue(failure.getMessage().contains("exact binary64 range"),
                    failure::toString);
        }
    }

    @Test
    void complexOrderingAndRemainderAreRejectedStatically() {
        for (String body : new String[] {
                "return (1.0 + 2.0i) < (3.0 + 4.0i);",
                "return (1.0 + 2.0i) % (3.0 + 4.0i);"
        }) {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                PolyglotException failure = assertThrows(
                        PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, """
                                pub routine main(): bool {
                                  %s
                                }
                                """.formatted(body)));
                assertTrue(failure.getMessage().contains("complex ordering")
                                || failure.getMessage().contains("complex remainder"),
                        failure::toString);
            }
        }
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
    void complexMultiplyRoundsOnceAcrossSeparatedCrossTerms() {
        // Independent exact-rational audit found the former two-product plus
        // rounded-sum path one ULP high in the real component for this tuple.
        assertTrue(evaluatesTrue("""
                val complex actual =
                    (-2.876527695159415e173 + 1.1062282252099771e52i)
                    * (37053981341.46522 + 3.503535910293817e134i);
                return actual
                    eq (-3.8863689923582155e186 - 1.0078018076945717e308i);
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

    @Test
    void complexDivisionCompensatesBalancedOneUlpFiniteCase() {
        assertTrue(evaluatesTrue("""
                val complex actual =
                    (-1.9297622926115045e138 + 1.5512392579051227e150i)
                    / (-1.4976784258940757e292 - 1.8366476025309775e288i);
                return actual
                    eq (-1.270186418667072e-146 - 1.0357625560708896e-142i);
                """));
    }

    @Test
    void complexDivisionExactFallbackRoundsSubnormalOutputs() {
        assertTrue(evaluatesTrue("""
                val complex actual =
                    (-4.273780448709542e-254 - 2.0788903192019146e-253i)
                    / (-1.1323026279913343e49 - 1.2990151610525855e55i);
                return actual
                    eq (1.6003590674378104e-308 - 3.29000188445742e-309i);
                """));
    }

}
