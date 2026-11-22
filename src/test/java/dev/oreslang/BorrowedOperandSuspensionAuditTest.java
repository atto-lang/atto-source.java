package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** A freshly evaluated operand cannot create an untracked cross-turn loan. */
final class BorrowedOperandSuspensionAuditTest {
    private static final String CHANNEL = "val Channel<int> ch = Channel.new<int>(1); ";
    private record Case(String name, String program, boolean admit, String diagnostic) { }
    private static final List<Case> CASES = List.of(
        new Case("await temporary borrowed Future", "fnc bad(): void { "
            + CHANNEL + "val Future<int> f = nb readch ch; val result = await (rt borrow f); return; }",
            false, "cannot await a borrowed operand"),
        new Case("await Future with borrowed result", "fnc bad(Future<borrow int> f): void { "
            + "val result = await f; return; }",
            false, "Future containing borrowed data"),
        new Case("blocking readch temporary borrowed channel", "fnc bad(): void { "
            + CHANNEL + "val result = readch (rt borrow ch); return; }",
            false, "borrowed channel operand"),
        new Case("nonblocking readch temporary borrowed channel", "fnc bad(): void { "
            + CHANNEL + "val result = nb readch (rt borrow ch); return; }",
            false, "borrowed channel operand"),
        new Case("blocking writech temporary borrowed channel", "fnc bad(): void { "
            + CHANNEL + "writech(rt borrow ch, 1); return; }",
            false, "borrowed channel operand"),
        new Case("nonblocking writech temporary borrowed channel", "fnc bad(): void { "
            + CHANNEL + "nb writech(rt borrow ch, 1); return; }",
            false, "borrowed channel operand"),
        new Case("dynamic blocking select borrowed plan", "fnc bad(): void { "
            + CHANNEL + "val Array<SelectCase> cases = [SelectCase.read(ch)]; "
            + "val result = select from (rt borrow cases); return; }",
            false, "borrowed case-set operand"),
        new Case("dynamic nonblocking select borrowed plan", "fnc bad(): void { "
            + CHANNEL + "val Array<SelectCase> cases = [SelectCase.read(ch)]; "
            + "val result = nb select from (rt borrow cases); return; }",
            false, "borrowed case-set operand"),
        new Case("static blocking select borrowed channel", "fnc bad(): void { "
            + CHANNEL + "select { case readch (rt borrow ch): val item { stdio.println(item); } } return; }",
            false, "borrowed channel operand"),
        new Case("static nonblocking select borrowed channel", "actor fnc bad(): void { "
            + CHANNEL + "nb select { case readch (rt borrow ch): val item { stdio.println(item); } } return; }",
            false, "borrowed channel operand"),
        new Case("owned await after temporary loan ends", "fnc good(): void { "
            + CHANNEL + "val pending = nb readch ch; val result = await pending; return; }",
            true, null),
        new Case("owned immediate select remains available", "fnc good(): void { "
            + CHANNEL + "select { case readch ch: val item { stdio.println(item); } default: { stdio.println(0); } } return; }",
            true, null),
        new Case("owned nonblocking readch remains available", "fnc good(): void { "
            + CHANNEL + "val pending = nb readch ch; return; }",
            true, null)
    );
    @TestFactory
    Stream<DynamicTest> freshOperandLoansMustNotCrossSuspension() {
        return CASES.stream().map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            var parsed = assertDoesNotThrow(() -> Parser.parse(test.program()), test.name());
            if (test.admit()) {
                assertDoesNotThrow(() -> OwnershipChecker.check(parsed), test.name());
            } else {
                var error = assertThrows(IllegalArgumentException.class,
                        () -> OwnershipChecker.check(parsed), test.name());
                assertTrue(error.getMessage().contains(test.diagnostic()),
                        () -> test.name() + ": " + error.getMessage());
            }
        }));
    }
}
