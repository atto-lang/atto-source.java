package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.CallableSelector;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Receiver syntax must never change caller-visible overload arity.
 * A self expression inside a call's argument list is a real argument.
 */
final class InlineSelfReceiverArityTest {

    @Test
    void inlineReceiverIsNotInUserParameterListForClassesAndActors() {
        for (String kind : new String[] {"class", "actor"}) {
            Ast.ClassDecl owner = parseOwner("""
                    define %s Counter as
                      pub zero(self): int { return 0; }
                      pub one(self, int x): int { return x; }
                      pub mutable(mut self, int x): int { return x; }
                      pub typed(self Counter, int x): int { return x; }
                      pub typed_mut(mut self Counter, int x): int { return x; }
                    end
                    """.formatted(kind));
            assertEquals(5, owner.methods().size());
            assertEquals(0, owner.methods().get(0).arity());
            assertEquals(1, owner.methods().get(1).arity());
            assertEquals(1, owner.methods().get(2).arity());
            assertEquals("self", owner.methods().get(0).explicitReceiverType().name());
            assertEquals("self", owner.methods().get(1).explicitReceiverType().name());
            assertTrue(owner.methods().get(2).explicitReceiverType().isBorrow());
            assertEquals("Counter", owner.methods().get(3).explicitReceiverType().name());
            assertEquals(1, owner.methods().get(3).arity());
            assertTrue(owner.methods().get(4).explicitReceiverType().isBorrow());
            assertEquals("Counter", owner.methods().get(4).explicitReceiverType().borrowedTarget().name());
            assertEquals(1, owner.methods().get(4).arity());
            assertEquals(CallableSelector.instance("zero", 0),
                    CallableSelector.of(owner.methods().get(0)));
            assertEquals(CallableSelector.instance("one", 1),
                    CallableSelector.of(owner.methods().get(1)));
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                    define %s Counter as
                      pub zero(self): int { return 0; }
                      pub one(self, int x): int { return x; }
                      pub mutable(mut self, int x): int { return x; }
                      pub typed(self Counter, int x): int { return x; }
                      pub typed_mut(mut self Counter, int x): int { return x; }
                    end
                    """.formatted(kind))));
        }
    }

    @Test
    void inlineAndImplicitReceiverAreOneOverloadSlot() {
        for (String kind : new String[] {"class", "actor"}) {
            for (String pair : new String[] {
                    "foo(int x): int { return x; } foo(self, int x): int { return x; }",
                    "foo(self, int x): int { return x; } foo(int x): int { return x; }",
                    "foo(): int { return 0; } foo(self): int { return 0; }",
                    "foo(self): int { return 0; } foo(): int { return 0; }",
                    "foo(int x): int { return x; } foo(mut self, int x): int { return x; }",
                    "foo(self Counter)(int x): int { return x; } foo(self, int x): int { return x; }",
                    "foo(int x): int { return x; } foo(self Counter, int x): int { return x; }",
                    "foo(int x): int { return x; } foo(mut self Counter, int x): int { return x; }",
                    "foo(): int { return 0; } foo(self Counter): int { return 0; }",
                    "foo(self Counter)(int x): int { return x; } foo(self Counter, int x): int { return x; }"
            }) {
                String source = "define " + kind + " Counter as " + pair + " end";
                IllegalArgumentException error = assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)),
                        source);
                assertTrue(error.getMessage().contains("already has arity"),
                        () -> source + ": " + error.getMessage());
            }
        }
    }

    @Test
    void inlineSelfAllowsIndependentCallerVisibleArities() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub foo(self): int { return 0; }
                  pub foo(self, int x): int { return x; }
                  pub foo(self, int x, int y): int { return x + y; }
                end
                """)));
    }

    @Test
    void selfAtCallSiteIsAnOrdinaryArgumentNotASecondReceiver() {
        Ast.ClassDecl owner = parseOwner("""
                define class Counter as
                  pub foo(Counter other, int x): int { return x; }
                  pub run(self): int { return self.foo(self, 5); }
                end
                """);
        Ast.MethodDecl method = owner.methods().get(1);
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, method.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());
        assertEquals(2, call.arguments().size(), "self inside call parentheses counts toward arity");
        assertEquals(new Ast.NameExpr("self"), call.arguments().getFirst());
        Ast.MemberExpr member = assertInstanceOf(Ast.MemberExpr.class, call.callee());
        assertEquals(new Ast.NameExpr("self"), member.receiver());
        assertEquals("foo", member.member());
    }

    @Test
    void supplyingSelfToOneUserParameterMethodDoesNotDisappear() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub foo(self, int x): int { return x; }
                          pub run(self): int { return self.foo(self, 5); }
                        end
                        """)));
        assertTrue(error.getMessage().contains("arity") || error.getMessage().contains("argument"),
                error::getMessage);
    }

    @Test
    void borrowedSelfCannotBecomeAnOwnedNonCopyArgument() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub accept(self, Counter other, int x): int { return x; }
                          pub run(self): int { return self.accept(self, 5); }
                        end
                        """)));
        assertTrue(error.getMessage().contains("borrowed value")
                || error.getMessage().contains("argument"),
                error::getMessage);
    }

    @Test
    void explicitlyBorrowedSelfIsARealUserArgument() {
        String program = """
                define class Counter as
                  pub use_other(self, borrow self other, int value): int {
                    return value;
                  }
                  pub forward(self): int {
                    return self.use_other(rt borrow self, 5);
                  }
                end
                """;
        Ast.ClassDecl owner = parseOwner(program);
        assertEquals(2, owner.methods().get(0).arity());
        Ast.MethodDecl forward = owner.methods().get(1);
        Ast.ReturnStmt returned =
                assertInstanceOf(Ast.ReturnStmt.class, forward.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());
        assertEquals(2, call.arguments().size());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void actorSelfCannotEscapeAsASecondExplicitCallArgument() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub forward(self, Worker other, int value): int { return value; }
                          pub run(self): int { return self.forward(self, 5); }
                        end
                        """)));
        assertNotNull(error.getMessage());
    }

    @Test
    void inlineReceiverCallsExecuteWithOnlyUserArguments() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                define module m
                  pub define class Counter as
                    pub zero(self): int { return 7; }
                    pub add_one(self, int x): int { return x + 1; }
                  end
                end
                pub routine main(): void {
                  val c = new m.Counter();
                  stdio.stdout.write(c.add_one(4));
                  stdio.stdout.write(c.zero());
                }
                """, "inline-self-arity.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("57", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void staticAndMalformedInlineReceiversFailClosed() {
        for (String member : new String[] {
                "static fnc foo(self, int x): int { return x; }",
                "static fnc foo(mut self, int x): int { return x; }",
                "foo(self,): int { return 0; }",
                "foo(mut self,): int { return 0; }",
                "foo(int x, self): int { return x; }",
                "foo(self, self, int x): int { return x; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define class Counter as " + member + " end"),
                    member);
        }
    }

    @Test
    void actorLifecycleAndMailboxMethodsCountOnlyUserParameters() {
        String program = """
                define actor Worker as
                  on_start(self): void { return; }
                  receive(self, ActorMail<int> mail): void { return; }
                end
                """;
        Ast.ClassDecl worker = parseOwner(program);
        assertEquals(0, worker.methods().get(0).arity());
        assertEquals(1, worker.methods().get(1).arity());
        assertEquals(CallableSelector.instance("on_start", 0),
                CallableSelector.of(worker.methods().get(0)));
        assertEquals(CallableSelector.instance("receive", 1),
                CallableSelector.of(worker.methods().get(1)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void splitReceiverSpellingRemainsSupported() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub foo(self Counter)(int x): int { return x; }
                  pub bar(mut self)(int x): int { return x; }
                end
                """)));
    }

    private static Ast.ClassDecl parseOwner(String source) {
        Ast.Program program = Parser.parse(source);
        return assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }
}
