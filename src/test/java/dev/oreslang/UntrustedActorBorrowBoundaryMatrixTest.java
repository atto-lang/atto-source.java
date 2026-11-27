package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Untrusted actors share the same lexical suspension safety rule as shared
 * and private actors. These are static source-admission checks only; untrusted
 * fuel continuity across automatic loop cooperation needs separate execution
 * evidence and must not be assumed from these tests.
 */
final class UntrustedActorBorrowBoundaryMatrixTest {
    private record Boundary(String name, String statement, String diagnostic) {}
    private static final List<Boundary> BOUNDARIES = List.of(
            new Boundary("cooperate", "rt cooperate;", "cannot cooperate"),
            new Boundary("await", "val int result = await (nb readch ch);", "cannot await"),
            new Boundary("blocking readch", "val int result = readch ch;", "cannot readch/writech"),
            new Boundary("blocking writech", "writech ch, 1;", "cannot readch/writech"),
            new Boundary("static select", "select { case readch ch: val n { stdio.println(n); } }",
                    "cannot select"),
            new Boundary("dynamic select",
                    "val Array<SelectCase> cases = [SelectCase.read(ch)]; val result = select from cases;",
                    "cannot select from cases")
    );

    @TestFactory
    Stream<DynamicTest> cannotCarryOrdinaryLoanAcrossUntrustedActorBoundaries() {
        return BOUNDARIES.stream().map(boundary -> DynamicTest.dynamicTest(boundary.name(), () -> {
            String source = """
                    define class Box as
                      pub let int value = 7;
                    end
                    pub untrusted actor fnc probe(): void {
                      val Channel<int> ch = Channel.new<int>(1);
                      let Box target = new Box();
                      val &Box view = rt borrow target;
                    """ + boundary.statement() + """
                    
                      stdio.println(view.value);
                      return;
                    }
                    """;
            var program = assertDoesNotThrow(() -> Parser.parse(source), boundary.name());
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(program), boundary.name());
            assertTrue(failure.getMessage().contains(boundary.diagnostic()),
                    () -> boundary.name() + ": " + failure.getMessage());
        }));
    }
}
