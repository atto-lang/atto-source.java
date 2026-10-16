package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InlineSelfReceiverTest {
    @Test
    void inlineSelfNormalizesToTheSameArityAndReceiverAsAnImplicitMethod() {
        Ast.Program program = Parser.parse("""
                define class Foo as
                  pub first(self, int value): int { return value; }
                  pub second(int value): int { return value; }
                  pub zero(self): void { return; }
                end
                """);
        Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl first = foo.methods().get(0);
        Ast.MethodDecl second = foo.methods().get(1);
        Ast.MethodDecl zero = foo.methods().get(2);
        assertNull(first.explicitReceiverType());
        assertNull(second.explicitReceiverType());
        assertEquals(first.arity(), second.arity());
        assertEquals(1, first.arity());
        assertEquals(1, first.parameters().size());
        assertEquals("value", first.parameters().getFirst().name());
        assertEquals(0, zero.arity());
        assertTrue(zero.parameters().isEmpty());
        assertNull(zero.explicitReceiverType());
    }

    @Test
    void inlineSelfAndImplicitActorReceiverAreDuplicateMethodSlots() {
        for (String methods : new String[] {
                """
                receive(self, int value): void { return; }
                receive(int value): void { return; }
                """,
                """
                receive(int value): void { return; }
                receive(self, int value): void { return; }
                """
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("define actor Foo as\n" + methods + "\nend")));
            assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
        }
    }

    @Test
    void inlineSelfAndImplicitClassReceiverAreDuplicateMethodSlots() {
        // Declaration order must not change receiver identity or arity.
        for (String methods : new String[] {
                """
                pub read(): int { return 1; }
                pub read(self): int { return 2; }
                """,
                """
                pub read(self): int { return 2; }
                pub read(): int { return 1; }
                """
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("define class Foo as\n" + methods + "\nend")));
            assertTrue(error.getMessage().contains("already has arity 0"), error::getMessage);
        }
    }

    @Test
    void inlineSelfAndLegacyTypedReceiverAreDuplicateMethodSlots() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub read(self Foo)(int value): int { return value; }
                          pub read(self, int value): int { return value + 1; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void differentValueAritiesRemainLegalAndDispatchNormally() throws Exception {
        String output = run("""
                define class Foo as
                  pub value(self): int { return 7; }
                  pub value(self, int input): int { return input + 1; }
                  pub value(int left, int right): int { return left + right; }
                end
                pub routine main(): void {
                  val foo = new Foo();
                  stdio.stdout.write(foo.value());
                  stdio.stdout.write(foo.value(4));
                  stdio.stdout.write(foo.value(2, 3));
                }
                """);
        assertEquals("755", output);
    }

    @Test
    void inlineMutableReceiverUsesExistingBorrowedMutabilitySemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub let int value = 0;
                  pub bump(mut self, int delta): void {
                    self.value = self.value + delta;
                    return;
                  }
                end
                """)));
        Ast.Program program = Parser.parse("""
                define class Counter as
                  pub bump(mut self, int delta): void { return; }
                end
                """);
        Ast.ClassDecl counter = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl bump = counter.methods().getFirst();
        assertEquals(1, bump.arity());
        assertNotNull(bump.explicitReceiverType());
        assertTrue(bump.explicitReceiverType().isBorrow());
        assertTrue(bump.explicitReceiverType().mutableBorrow());
    }

    @Test
    void inlineMutableReceiverAndLegacyMutableReceiverShareSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub change(mut self)(int x): void { return; }
                          pub change(mut self, int x): void { return; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void inlineReceiverIsRejectedOnStaticFunctions() {
        for (String parameters : new String[] {"self", "self, int x", "mut self, int x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define class Foo as pub static fnc bad(" + parameters
                            + "): void { return; } end"));
        }
    }

    @Test
    void receiverMustBeFirstAndTrailingCommaMustHaveAParameter() {
        for (String parameters : new String[] {
                "int x, self", "self,", "mut self,", "self, self", "int x, mut self"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define class Foo as pub invalid(" + parameters
                            + "): void { return; } end"), parameters);
        }
    }

    @Test
    void nlexLambdaInsideAnInlineReceiverMethodCannotAccessSelf() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub number(self): int { return 42; }
                          pub callback(self): Fnc<int> {
                            return nlex || -> self.number();
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("self")
                || error.getMessage().contains("unknown name"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "inline-self-receiver.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .out(output)
                .allowAllAccess(true)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
