package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ConstructorDelegation;
import dev.oreslang.types.ConstructorValInitialization;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ConstructorDelegationTest {

    @Test
    void delegatesAcrossThreeAritiesOnOneReceiverAndExecutesTailsInReverseOrder() throws Exception {
        assertEquals("42|11|42|10|3|0", run("""
                define class Counter as
                  val int value;
                  let int steps: 0;
                  constructor(self) {
                    constructor(40);
                    self.steps = self.steps + 1;
                  }
                  constructor(self, int value) {
                    constructor(value, 2);
                    self.steps = self.steps + 10;
                  }
                  constructor(self, int value, int bonus) {
                    self.value = value + bonus;
                  }
                  pub get(): int { return self.value; }
                  pub getSteps(): int { return self.steps; }
                end
                pub routine main(): void {
                  val a = new Counter();
                  val b = new Counter(40);
                  val c = new Counter(1, 2);
                  stdio.stdout.write(a.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(a.getSteps());
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.getSteps());
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.getSteps());
                  return;
                }
                """));
    }

    @Test
    void fieldInitializersRunOnceWithTerminalConstructorArguments() throws Exception {
        assertEquals("42|42", run("""
                define class Counter as
                  val int initial: base;
                  val int value;
                  constructor(self) {
                    constructor(42);
                  }
                  constructor(self, int base) {
                    self.value = base;
                  }
                  pub get(): int { return self.initial; }
                  pub value(): int { return self.value; }
                end
                pub routine main(): void {
                  val a = new Counter();
                  stdio.stdout.write(a.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(a.value());
                  return;
                }
                """));
    }

    @Test
    void blankValCannotBeInitializedAfterDelegation() {
        expectReject("""
                define class Broken as
                  val int value;
                  constructor(self) {
                    constructor(10);
                    self.value = 11;
                  }
                  constructor(self, int n) {
                    self.value = n;
                  }
                end
                """, "immutable");
    }

    @Test
    void delegatesCannotCycleEvenWhenArityChanges() {
        expectReject("""
                define class Broken as
                  constructor() { constructor(1); }
                  constructor(int n) { constructor(); }
                end
                """, "delegation cycle");
        expectReject("""
                define class Broken as
                  constructor() { constructor(); }
                end
                """, "delegation cycle");
    }

    @Test
    void targetIsResolvedOnlyByArity() {
        expectReject("""
                define class Broken as
                  constructor() { constructor(1, 2); }
                  constructor(int n) { return; }
                end
                """, "no target constructor");
        expectReject("""
                define class Broken as
                  constructor() { constructor("wrong"); }
                  constructor(int n) { return; }
                end
                """, "constructor delegation argument");
    }

    @Test
    void rejectsLateAndNestedDelegation() {
        expectReject("""
                define class Broken as
                  constructor() {
                    val int n = 1;
                    constructor(n);
                  }
                  constructor(int n) { return; }
                end
                """, "first top-level");
        expectReject("""
                define class Broken as
                  constructor(bool flag) {
                    if flag then
                      constructor(1, 2);
                    fi
                  }
                  constructor(int a, int b) { return; }
                end
                """, "first top-level");
    }

    @Test
    void delegatesCannotCallOrCaptureSelfInArgumentEvaluation() {
        expectReject("""
                define class Broken as
                  constructor(self) { constructor(self); }
                  constructor(int n) { return; }
                end
                """, "pure scalar");
        expectReject("""
                fnc get_number(): int { return 42; }
                define class Broken as
                  constructor() { constructor(get_number()); }
                  constructor(int n) { return; }
                end
                """, "pure scalar");
    }

    @Test
    void delegationIsNotAnOrdinaryStatementOrCallable() {
        expectReject("""
                define class Sample as
                  constructor() { return; }
                end
                pub routine main(): void {
                  constructor();
                  return;
                }
                """, "first top-level");
    }

    @Test
    void unchangedConstructorArityAndActorRulesStillApply() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Broken as
                  constructor(int n) { return; }
                  constructor(self, string n) { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor Worker {
                  constructor() { return; }
                }
                """));
    }

    @Test
    void terminalConstructorMustStillInitializeEveryBlankVal() {
        expectReject("""
                define class Broken as
                  val int value;
                  constructor() { constructor(1); }
                  constructor(int n) { return; }
                end
                """, "did not initialize field");
    }

    @Test
    void repeatedDelegationChainCannotHideMissingInitialization() {
        expectReject("""
                define class Broken as
                  val int value;
                  constructor() { constructor(1); }
                  constructor(int n) { constructor(n, 2); }
                  constructor(int n, int x) {
                    if n > 10 then
                      self.value = x;
                    fi
                  }
                end
                """, "did not initialize field");
    }

    @Test
    void rejectsDelegationInsideLambdaAndDeferredCleanup() {
        expectReject("""
                define class Broken as
                  constructor() {
                    val f = || -> { constructor(1); };
                  }
                  constructor(int n) { return; }
                end
                """, "first top-level");
        expectReject("""
                define class Broken as
                  constructor() {
                    defer || -> { constructor(1); };
                  }
                  constructor(int n) { return; }
                end
                """, "first top-level");
    }

    @Test
    void rejectsNonScalarDelegationParametersAndAllocations() {
        expectReject("""
                define class Broken as
                  constructor(self, (int) => int fn) {
                    constructor(fn(4));
                  }
                  constructor(int n) { return; }
                end
                """, "pure scalar");
        expectReject("""
                define class Item as
                  constructor() { return; }
                end
                define class Broken as
                  constructor() {
                    constructor(new Item());
                  }
                  constructor(Item n) { return; }
                end
                """, "pure scalar");
    }

    @Test
    void recursiveChainWithThreeDifferentAritiesIsRejected() {
        expectReject("""
                define class Broken as
                  constructor() { constructor(1); }
                  constructor(int n) { constructor(n, 2); }
                  constructor(int n, int extra) { constructor(); }
                end
                """, "delegation cycle");
    }

    @Test
    void constructorDelegationArgumentsCanUsePureArithmetic() throws Exception {
        assertEquals("42", run("""
                define class Number as
                  val int value;
                  constructor(self, int x) {
                    constructor(x * 2, 2);
                  }
                  constructor(self, int x, int y) {
                    self.value = x + y;
                  }
                  pub get(): int { return self.value; }
                end
                pub routine main(): void {
                  val n = new Number(20);
                  stdio.stdout.write(n.get());
                  return;
                }
                """));
    }

    private static void expectReject(String source, String fragment) {
        Ast.Program program = Parser.parse(source);
        RuntimeException failure = assertThrows(
                RuntimeException.class, () -> TypeChecker.checkTypes(program));
        assertTrue(failure.toString().contains(fragment), failure::toString);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "constructor-delegation.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
