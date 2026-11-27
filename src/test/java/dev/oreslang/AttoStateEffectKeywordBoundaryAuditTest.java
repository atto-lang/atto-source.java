package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gates for namespace imports, declared effects and lexical loans.
 * The out-of-line critical subset is intentionally not general effect lowering.
 */
final class AttoStateEffectKeywordBoundaryAuditTest {
    private static final String HEADER = """
            use namespace keyword_boundary;
            declare actor Counter;
            define state on actor Counter as {
                let int count : 0;
            }
            external mtd (Counter c) init(): void { return; }
            external mtd (Counter c) receive(ActorMail<int> mail): void { return; }
            """;

    @Test
    void importingNamespaceDoesNotMintWriteLoanOrActorStateAuthority() {
        var noLoan = """
                critical mtd (Counter c) inc(): int {
                    state.count = 1;
                    return 1;
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(HEADER + noLoan));
        assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_LOAN_STATIC_REJECTED"),
                failure.getMessage());
    }

    @Test
    void declaringWriteEffectDoesNotReplaceScopedUsingAcquisition() {
        var effectWithoutLoan = """
                critical mtd (Counter c) inc(): int with state.write {
                    return 1;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(HEADER + effectWithoutLoan),
                "declared permission alone may never mint a state capability");
    }

    @Test
    void readLoanCannotBePromotedIntoExclusiveWriteByKeywordSpelling() {
        var forgedRead = """
                critical mtd (Counter c) inc(): int {
                    let int count = 0;
                    using state.read(critical |s| -> {
                        s.count = 1;
                        count = s.count;
                    });
                    return count;
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(HEADER + forgedRead));
        assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_LOAN_STATIC_REJECTED"),
                failure.getMessage());
    }

    @Test
    void nestedUsingOrEscapingStateAliasFailsAdmission() {
        var nested = """
                critical mtd (Counter c) inc(): int {
                    let int answer = 0;
                    using state.write(critical |s| -> {
                        using state.write(critical |t| -> { answer = t.count; });
                    });
                    return answer;
                }
                """;
        var escaped = """
                critical mtd (Counter c) inc(): int {
                    let int answer = 0;
                    using state.write(critical |s| -> {
                        answer = s;
                    });
                    return answer;
                }
                """;
        for (var source : new String[] { nested, escaped }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(HEADER + source));
        }
    }
}
