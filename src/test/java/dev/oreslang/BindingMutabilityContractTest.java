package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression contract: rebinding and mutable referent capabilities are orthogonal. */
final class BindingMutabilityContractTest {
    private static final String BOX = """
            define class Box as
              pub let string foo = "start";
            end
            """;

    private void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc subject(): void {
                """ + body + """
                  return;
                }
                """)));
    }

    private void rejects(String body) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc subject(): void {
                """ + body + """
                  return;
                }
                """)));
    }

    @Test void valIsFixedMutableReference() {
        accepts("val Box x = new Box(); x.foo = \"updated\";");
        rejects("val Box x = new Box(); x = new Box();");
    }

    @Test void constIsFixedReadonlyReferenceWithRuntimeInitializer() {
        accepts("const Box x = new Box();");
        rejects("const Box x = new Box(); x.foo = \"updated\";");
        rejects("const Box x = new Box(); x = new Box();");
    }

    @Test void constMutIsSpelledOutVal() {
        accepts("const mut Box x = new Box(); x.foo = \"updated\";");
        rejects("const mut Box x = new Box(); x = new Box();");
    }

    @Test void letIsRebindableButReadonly() {
        accepts("let Box x = new Box(); x = new Box();");
        rejects("let Box x = new Box(); x.foo = \"updated\";");
    }

    @Test void letMutPermitsBothWithOwnership() {
        accepts("let mut Box x = new Box(); x.foo = \"updated\"; x = new Box();");
    }

    @Test void selectDefaultsToReadonlyButAllowsExplicitMutableCase() {
        rejects("""
                  val Channel<Box> inbox = Channel.new<Box>(1);
                  select {
                    case job = readch(inbox) -> { job.foo = "changed"; }
                  }
                """);
        accepts("""
                  val Channel<Box> inbox = Channel.new<Box>(1);
                  select {
                    case val job = readch(inbox) -> { job.foo = "changed"; }
                  }
                """);
        accepts("""
                  val Channel<Box> inbox = Channel.new<Box>(1);
                  select {
                    case mut job = readch(inbox) -> { job.foo = "changed"; job = new Box(); }
                  }
                """);
        rejects("""
                  val Channel<Box> inbox = Channel.new<Box>(1);
                  select {
                    case const mut job = readch(inbox) -> { job = new Box(); }
                  }
                """);
    }
}
