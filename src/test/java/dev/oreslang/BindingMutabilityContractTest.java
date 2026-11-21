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

    @Test void mutableReceiverMethodRequiresMutableReferentCapability() {
        String box = """
                define class Counter as
                  pub let int count = 0;
                  pub increment(mut self)(): void {
                    self.count = self.count + 1;
                    return;
                  }
                  pub read(): int {
                    return self.count;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(box + """
                fnc bad(): int {
                  const Counter counter = new Counter();
                  counter.increment();
                  return counter.read();
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(box + """
                fnc bad(): int {
                  let Counter counter = new Counter();
                  counter.increment();
                  return counter.read();
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(box + """
                fnc ok(): int {
                  val Counter counter = new Counter();
                  counter.increment();
                  return counter.read();
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(box + """
                fnc ok(): int {
                  let mut Counter counter = new Counter();
                  counter.increment();
                  counter = new Counter();
                  return counter.read();
                }
                """)));
    }

    @Test void fieldMutatingMethodInfersExclusiveSelfAndNeedsWritableReceiver() {
        // A method that writes self.field holds an inferred exclusive self
        // loan; the mutation is never secret because the call site must own a
        // writable receiver.
        String counter = """
                define class Counter as
                  pub let int count = 0;
                  pub bump(): void {
                    self.count = self.count + 1;
                    return;
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(counter + """
                pub fnc main(): int { val Counter c = new Counter(); c.bump(); return 0; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(counter + """
                pub fnc main(): int { let Counter c = new Counter(); c.bump(); return 0; }
                """)));
    }


    @Test void valDoesNotOverrideLiveImmutableBorrow() {
        rejects("""
                  val Box x = new Box();
                  val view = rt borrow x;
                  x.foo = "blocked";
                  stdio.println(view.foo);
                """);
    }

    @Test void immutableBorrowEndingAtInnerScopeRestoresValMutationPermission() {
        accepts("""
                  val Box x = new Box();
                  if true; do
                    val view = rt borrow x;
                    stdio.println(view.foo);
                  fi
                  x.foo = "allowed";
                """);
    }


    @Test void duplicateValMutQualifierIsRedundantWithWarning() {
        // Owner ruling 2026-11-20: `val` means `const mut`, so `val mut`
        // compiles as a redundant spelling and reports a warning.
        var parsed = Parser.parseWithWarnings("""
                fnc bad(): void {
                  val mut Box x = new Box();
                  return;
                }
                """);
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.message().contains("redundant 'mut' after 'val'")));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc bad(): void {
                  val Channel<Box> inbox = Channel.new<Box>(1);
                  select {
                    case val mut job = readch(inbox) -> { }
                  }
                  return;
                }
                """));
    }

}
