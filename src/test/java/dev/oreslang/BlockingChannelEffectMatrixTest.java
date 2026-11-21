package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.DeclaredEffectVerifier;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Distinguishes suspend (cooperative/continuation) from block (may wait).
 * A _blocking suffix is ordinary source naming, not a trusted effect summary.
 * Every case must parse before effect verification to avoid false confidence.
 */
final class BlockingChannelEffectMatrixTest {
    private record Case(String name, String code, boolean accepted, String error) {}
    private static final List<Case> CASES = List.of(
        new Case("readch forbidden by no_block",
            "fnc bad(Channel<int> ch): void requires no_block { val int n = readch ch; return; }",
            false, "no_block"),
        new Case("writech forbidden by no_block",
            "fnc bad(Channel<int> ch): void requires no_block { writech ch, 1; return; }",
            false, "no_block"),
        new Case("static select without default forbidden by no_block",
            "fnc bad(Channel<int> ch): void requires no_block { select { case readch ch: val n { val int observed = n; } } return; }",
            false, "no_block"),
        new Case("dynamic select forbidden by no_block",
            "fnc bad(Array<SelectCase> cases): void requires no_block { val item = select from cases; return; }",
            false, "no_block"),
        new Case("nonblocking readch permitted by no_block",
            "fnc good(Channel<int> ch): void requires no_block { val pending = nb readch ch; return; }",
            true, null),
        new Case("nonblocking writech permitted by no_block",
            "fnc good(Channel<int> ch): void requires no_block { nb writech ch, 1; return; }",
            true, null),
        new Case("nonblocking select permitted by no_block",
            "fnc good(Channel<int> ch): void requires no_block { nb select { case readch ch: val n { val int observed = n; } } return; }",
            true, null),
        new Case("select default eliminates waiting",
            "fnc good(Channel<int> ch): void requires no_block { select { case readch ch: val n { val int observed = n; } default: { val int observed = 0; } } return; }",
            true, null),
        new Case("with suspend alone rejects a blocking read",
            "fnc bad(Channel<int> ch): void with suspend { val int n = readch ch; return; }",
            false, "effect block"),
        new Case("with suspend and block admits readch",
            "fnc good_blocking(Channel<int> ch): void with suspend, block { val int n = readch ch; return; }",
            true, null),
        new Case("no_block rejects _blocking named function when it waits",
            "fnc bad_blocking(Channel<int> ch): void requires no_block { val int n = readch ch; return; }",
            false, "no_block"),
        new Case("cooperation suspends but does not block",
            "fnc good(): void requires no_block { rt cooperate; return; }",
            true, null)
    );
    @TestFactory
    Stream<DynamicTest> channelEffectCases() {
        return CASES.stream().map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            var program = assertDoesNotThrow(() -> Parser.parse(test.code()), test.name());
            if (test.accepted()) {
                assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(program), test.name());
            } else {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> DeclaredEffectVerifier.verify(program), test.name());
                assertTrue(failure.getMessage().contains(test.error()),
                        () -> test.name() + ": " + failure.getMessage());
            }
        }));
    }
}
