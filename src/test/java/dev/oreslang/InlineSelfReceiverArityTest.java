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

/** Receiver marker normalization and caller-visible arity, isolated from lambda changes. */
final class InlineSelfReceiverArityTest {
    @Test
    void inlineAndImplicitReceiverHaveEqualValueArity() {
        Ast.Program program = Parser.parse("""
                define class Foo as
                  pub one(self, int x): int { return x; }
                  pub implicit(int x): int { return x; }
                  pub zero(self): int { return 0; }
                  pub mutable(mut self, int x): void { return; }
                end
                """);
        Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl one = foo.methods().get(0);
        Ast.MethodDecl implicit = foo.methods().get(1);
        Ast.MethodDecl zero = foo.methods().get(2);
        Ast.MethodDecl mutable = foo.methods().get(3);
        assertEquals(1, one.arity());
        assertEquals(1, implicit.arity());
        assertEquals(0, zero.arity());
        assertNull(one.explicitReceiverType());
        assertNull(zero.explicitReceiverType());
        assertEquals("x", one.parameters().getFirst().name());
        assertEquals(1, mutable.arity());
        assertNotNull(mutable.explicitReceiverType());
        assertTrue(mutable.explicitReceiverType().isBorrow());
        assertTrue(mutable.explicitReceiverType().mutableBorrow());
    }

    @Test
    void receiverMarkerAndImplicitReceiverCollideForClassesAndActorsInEitherOrder() {
        for (String kind : new String[] {"class", "actor"}) {
            for (String methods : new String[] {
                    "foo(int x): void { return; }\nfoo(self, int x): void { return; }",
                    "foo(self, int x): void { return; }\nfoo(int x): void { return; }"
            }) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                        TypeChecker.check(Parser.parse("define " + kind + " Foo as\n"
                                + methods + "\nend")));
                assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
            }
        }
    }

    @Test
    void zeroValueArityCollisionIsRejectedInEitherOrder() {
        for (String methods : new String[] {
                "foo(): void { return; }\nfoo(self): void { return; }",
                "foo(self): void { return; }\nfoo(): void { return; }"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("define class Foo as\n" + methods + "\nend")));
            assertTrue(error.getMessage().contains("already has arity 0"), error::getMessage);
        }
    }

    @Test
    void legacyTypedAndMutableReceiversCannotCreateShadowSlots() {
        for (String methods : new String[] {
                "foo(self Foo)(int x): void { return; }\nfoo(self, int x): void { return; }",
                "foo(mut self)(int x): void { return; }\nfoo(mut self, int x): void { return; }"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("define class Foo as\n" + methods + "\nend")));
            assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
        }
    }

    @Test
    void staticAndMalformedReceiversAreRejected() {
        for (String parameters : new String[] {
                "self", "self, int x", "mut self, int x",
                "int x, self", "self,", "mut self,", "self, self", "int x, mut self"
        }) {
            String kind = parameters.startsWith("int") || parameters.equals("self,")
                    || parameters.equals("mut self,") || parameters.equals("self, self")
                    ? "foo" : "static fnc foo";
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("define class Foo as\npub " + kind + "(" + parameters
                            + "): void { return; }\nend"), parameters);
        }
    }

    @Test
    void passingSelfExplicitlyIsAlwaysAnOrdinaryArgument() throws Exception {
        String output = run("""
                define class Foo as
                  pub foo(int x): int { return x; }
                  pub foo(self other, int x): int { return x + 10; }
                  pub run(): int { return self.foo(5) + self.foo(self, 5); }
                end
                pub routine main(): void {
                  val foo = new Foo();
                  stdio.stdout.write(foo.run());
                }
                """);
        assertEquals("20", output);
    }

    @Test
    void explicitSelfValueIsActuallyUsedInsideSelectedMethod() throws Exception {
        // If the caller argument is accidentally swallowed as a receiver,
        // 'other' will be missing and this guest program must fail.
        String output = run("""
                define class Foo as
                  pub add(int x): int { return x + 10; }
                  pub foo(self other, int x): int { return other.add(x); }
                  pub compute(): int { return self.foo(self, 5); }
                end
                pub routine main(): void {
                  val instance = new Foo();
                  stdio.stdout.write(instance.compute());
                }
                """);
        assertEquals("15", output);
    }

    @Test
    void inlineMutableReceiverMayWriteMutableInstanceFields() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub let int count = 0;
                  pub increment(mut self, int delta): void {
                    self.count = self.count + delta;
                    return;
                  }
                end
                """)));
    }

    @Test
    void inlineMutableReceiverRetainsExclusiveArgumentLoanChecks() {
        // Receiver mutability is NOT included in overload arity, but it
        // must be checked for aliases across all ordinary value arguments.
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int count = 0;
                          pub borrow_and_read(mut self, borrow mut Counter other): int {
                            return other.count;
                          }
                        end
                        fnc unsafe_alias(): int {
                          val Counter counter = new Counter();
                          return counter.borrow_and_read(rt borrow mut counter);
                        }
                        """)));
    }

    @Test
    void explicitSelfValueCannotSatisfyAnImplicitReceiver() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub foo(int x): int { return x; }
                          pub fail(): int { return self.foo(self, 5); }
                        end
                        """)));
        assertTrue(error.getMessage().contains("arity 2"), error::getMessage);
    }

    @Test
    void explicitSelfValueMustTypecheckAgainstSelectedTwoArgumentSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub foo(int x): int { return x; }
                          pub foo(int x, int y): int { return x + y; }
                          pub fail(): int { return self.foo(self, 5); }
                        end
                        """)));
        assertTrue(error.getMessage().contains("argument 1"), error::getMessage);
    }

    @Test
    void explicitReceiverMarkerAndNamedSelfTypedArgumentAreDistinct() {
        Ast.Program program = Parser.parse("""
                define class Foo as
                  pub foo(self, self other, int x): int { return x; }
                end
                """);
        Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl method = foo.methods().getFirst();
        assertEquals(2, method.arity());
        assertEquals("self", method.parameters().getFirst().type().name());
        assertEquals("other", method.parameters().getFirst().name());
        assertEquals("x", method.parameters().get(1).name());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void inlineReceiverUsesExistingOverrideSlot() throws Exception {
        String output = run("""
                define class Parent as
                  pub f(int x): int { return x + 1; }
                end
                define class Child extends Parent as
                  pub f(self, int x): int { return x + 2; }
                end
                pub routine main(): void {
                  val child = new Child();
                  stdio.stdout.write(child.f(4));
                }
                """);
        assertEquals("6", output);
    }

    @Test
    void distinctReceiverFreeAritiesDispatchAcrossInstances() throws Exception {
        String output = run("""
                define class Foo as
                  pub f(self): int { return 7; }
                  pub f(self, int x): int { return x + 1; }
                  pub f(int x, int y): int { return x + y; }
                end
                pub routine main(): void {
                  val a = new Foo();
                  val b = new Foo();
                  stdio.stdout.write(a.f());
                  stdio.stdout.write(a.f(4));
                  stdio.stdout.write(b.f(2, 3));
                }
                """);
        assertEquals("755", output);
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "inline-self-arity.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .out(output).allowAllAccess(false).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
