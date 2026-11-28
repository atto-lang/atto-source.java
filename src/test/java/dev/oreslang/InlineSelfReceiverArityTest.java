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
    void exactX1X2EmptyVoidMethodsAreBothZeroArity() {
        // Exact standalone syntax: declaring self explicitly never adds an
        // argument to the name+arity overload selector.
        String source = """
                define class X1 as

                   foo():void {}

                end

                define class X2 as

                   foo(self):void {}

                end
                """;
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(2, program.modules().getFirst().declarations().size());
        Ast.ClassDecl x1 = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().get(0));
        Ast.ClassDecl x2 = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().get(1));

        Ast.MethodDecl implicit = x1.methods().getFirst();
        Ast.MethodDecl explicit = x2.methods().getFirst();
        assertTrue(implicit.parameters().isEmpty());
        assertTrue(explicit.parameters().isEmpty());
        assertEquals(0, implicit.arity());
        assertEquals(0, explicit.arity());
        assertEquals(new Ast.MethodArity("foo", 0), implicit.overloadIdentity());
        assertEquals(implicit.overloadIdentity(), explicit.overloadIdentity());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void emptyVoidMethodCallsNeverRequireAnExplicitSelfArgument() throws Exception {
        String output = run("""
                define class X1 as
                  pub foo():void {}
                end
                define class X2 as
                  pub foo(self):void {}
                end
                pub routine main():void {
                  val x1 = new X1();
                  val x2 = new X2();
                  x1.foo();
                  x2.foo();
                  stdio.stdout.write("ok");
                }
                """);
        assertEquals("ok", output);
    }

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
    void everyEquivalentOneArgumentReceiverSpellingCollidesInEitherOrder() {
        String[] declarations = {
                "pub foo(int x): int { return x; }",
                "pub foo(self, int x): int { return x; }",
                "pub foo(mut self, int x): int { return x; }",
                "pub foo(self Foo)(int x): int { return x; }",
                "pub foo(mut self)(int x): int { return x; }"
        };
        // Ten distinct pairs, in both declaration orders. Receiver borrowing
        // and spelling never create a new (owner, method, arity) slot.
        for (int i = 0; i < declarations.length; i++) {
            for (int j = i + 1; j < declarations.length; j++) {
                for (String[] order : new String[][] {
                        {declarations[i], declarations[j]},
                        {declarations[j], declarations[i]}
                }) {
                    String program = "define class Foo as\n"
                            + order[0] + "\n" + order[1] + "\nend";
                    IllegalArgumentException error = assertThrows(
                            IllegalArgumentException.class,
                            () -> TypeChecker.check(Parser.parse(program)),
                            "spelling pair " + i + ":" + j);
                    assertTrue(error.getMessage().contains("already has arity 1"),
                            error::getMessage);
                }
            }
        }
    }

    @Test
    void namedSelfTypedFirstParameterIsNotAReceiverMarker() {
        // In foo(self other), 'other' is a real value parameter whose type
        // is receiver-polymorphic self; foo(self, self other) normalizes to
        // exactly the same foo/1 slot.
        for (String[] order : new String[][] {
                {"pub foo(self other): int { return 1; }",
                 "pub foo(self, self other): int { return 2; }"},
                {"pub foo(self, self other): int { return 2; }",
                 "pub foo(self other): int { return 1; }"}
        }) {
            Ast.Program first = Parser.parse("define class Foo as\n" + order[0] + "\nend");
            Ast.ClassDecl owner = (Ast.ClassDecl) first.modules().getFirst().declarations().getFirst();
            Ast.MethodDecl decl = owner.methods().getFirst();
            assertEquals(1, decl.arity());
            assertEquals("other", decl.parameters().getFirst().name());
            assertEquals("self", decl.parameters().getFirst().type().name());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("define class Foo as\n"
                            + order[0] + "\n" + order[1] + "\nend")));
            assertTrue(duplicate.getMessage().contains("already has arity 1"),
                    duplicate::getMessage);
        }
    }

    @Test
    void zeroArgumentReceiverFormsAlsoCollideInActors() {
        for (String[] order : new String[][] {
                {"tick(): void { return; }", "tick(self): void { return; }"},
                {"tick(mut self): void { return; }", "tick(): void { return; }"},
                {"tick(self): void { return; }", "tick(mut self): void { return; }"}
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("define actor A as\n"
                            + order[0] + "\n" + order[1] + "\nend")));
            assertTrue(error.getMessage().contains("already has arity 0"),
                    error::getMessage);
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
    void inlineReadOnlyReceiverCannotMutateInstanceFields() {
        // This is the control case for the explicitly mutable receiver:
        // a syntactic 'self' marker is read-only, not an authorization
        // to mutate state just because the field is declared 'let'.
        Ast.Program parsed = Parser.parse("""
                define class Counter as
                  pub let int count = 0;
                  pub increment(self, int delta): void {
                    self.count = self.count + delta;
                    return;
                  }
                end
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertFalse(error.getMessage().isBlank(), "must reject read-only self mutation");
    }

    @Test
    void inlineInstanceMethodAndStaticMethodHaveDistinctSlots() {
        // Only instance declarations normalize the leading receiver marker;
        // the independently named static f/1 must not collide with instance f/1.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  pub f(self, int x): int { return x + 1; }
                  pub static fnc f(int x): int { return x + 2; }
                end
                """)));
    }

    @Test
    void inlineMutableReceiverRetainsExclusiveArgumentLoanChecks() {
        // First prove that the inline syntax parses; a parser failure must
        // NOT accidentally count as evidence that ownership rejected a loan.
        Ast.Program parsed = Parser.parse("""
                define class Counter as
                  pub let int count = 0;
                  pub borrow_and_read(mut self, borrow mut Counter other): int {
                    return other.count;
                  }
                end
                fnc unsafe_alias(): int {
                  val Counter counter = new Counter();
                  return counter.borrow_and_read(rt mut borrow counter);
                }
                """);
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertFalse(error.getMessage().isBlank(), "ownership rejection needs a diagnostic");
    }

    @Test
    void inlineMutableReceiverAcceptsBorrowFromDifferentOwner() {
        // Matched positive control: the receiver's exclusive loan may coexist
        // with an independent mutable argument. This guards against making
        // every inline-mut-self call fail and calling that 'safety'.
        Ast.Program parsed = Parser.parse("""
                define class Counter as
                  pub let int count = 0;
                  pub borrow_and_read(mut self, borrow mut Counter other): int {
                    return other.count;
                  }
                end
                fnc valid(): int {
                  val Counter counter = new Counter();
                  val Counter other = new Counter();
                  return counter.borrow_and_read(rt mut borrow other);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
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
