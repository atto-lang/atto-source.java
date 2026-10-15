package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admission/compatibility tests ONLY: postfix ? cannot run until the type,
 * ownership, unwind, and effect contracts have executable source lowering.
 */
final class PostfixQuestionAdmissionTest {
    @Test
    void ternaryRemainsTernaryRatherThanEarlyReturn() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc choose(bool flag): int {
                  return flag ? 1 : 2;
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc choose_compact(bool flag): int {
                  return flag?1:2;
                }
                """));
    }

    @Test
    void asQuestionCastRemainsAnOptionalCast() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc cast_value(Any value): Option<int> {
                  return value as? int;
                }
                """));
    }

    @Test
    void postfixQuestionFailsClosedUntilTypedEffectLoweringExists() {
        for (String source : new String[] {
                "fnc sum_result(): Result<int, string> { val Result<int,string> v = Ok(3); return v?; }",
                "fnc maybe_value(): Option<int> { val Option<int> v = Some(3); return v?; }",
                "fnc sum_chained(): Result<int, string> { return parse_value()?.validate()?; }",
                "fnc future_result(): int { return (await fetch_result())?; }"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("postfix '?'"),
                    "unexpected error for " + source + ": " + failure.getMessage());
        }
    }

    @Test
    void postfixDoesNotBecomeImplicitOptionalMemberLookup() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): int { return find_user()?.age; }"));
        assertTrue(failure.getMessage().contains("postfix '?'"));
    }
}
