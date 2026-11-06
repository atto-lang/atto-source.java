package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end edge cases for named generic arguments ({@code f<foo=String, bar=int>()})
 * and named value arguments ({@code f(bar: 5, a: true)}), executed through the
 * full parse / type / ownership / runtime pipeline.
 */
final class NamedArgumentsEdgeCaseHardeningTest {
    private record Run(long value, String out) { }

    private static Run run(String code) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
            Value value = context.eval(Source.newBuilder("ores", code, "named-edge.atto").buildLiteral());
            return new Run(value.asLong(), out.toString(StandardCharsets.UTF_8));
        }
    }

    private static void rejects(String code, String fragment) {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(code));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    // ---- named generic (type) arguments -------------------------------------------

    @Test
    void lowercaseNamedGenericsBindInAnyOrder() {
        String decl = "fnc f<foo, bar>(foo a, bar b): bar { return b; }\n";
        assertEquals(42, run(decl + "pub fnc main(): int { return f<foo=String, bar=int>(\"s\", 42); }").value());
        assertEquals(42, run(decl + "pub fnc main(): int { return f<bar=int, foo=String>(\"s\", 42); }").value());
    }

    @Test
    void namedGenericsAreCaseSensitiveAndRejectDuplicatesCollisionsAndUnknowns() {
        String decl = "fnc f<A, B>(A a, B b): A { return a; }\n";
        rejects(decl + "pub fnc main(): int { return f<a=int>(5, 6); }", "no generic parameter named");
        rejects(decl + "pub fnc main(): int { return f<A=int, A=int>(5, 6); }", "duplicate");
        rejects(decl + "pub fnc main(): int { return f<int, A=int>(5, 6); }", "duplicate");
        rejects(decl + "pub fnc main(): int { return f<A=int, int>(5, 6); }", "precede named");
        rejects(decl + "pub fnc main(): int { return f<int, String, C=int>(5, \"x\"); }", "at most 2");
    }

    @Test
    void inferenceHolesNeverSelectDeclarationDefaults() {
        String decl = "fnc f<A = int>(): int { return 1; }\n";
        rejects(decl + "pub fnc main(): int { return f<_>(); }", "cannot infer");
        rejects(decl + "pub fnc main(): int { return f<A=_>(); }", "cannot infer");
        assertEquals(1, run(decl + "pub fnc main(): int { return f(); }").value());
    }

    @Test
    void namedGenericsWorkOnMethodsStaticsAndRoutines() {
        assertEquals(11, run("""
                define class K as
                  pub id<T>(T v): T { return v; }
                  pub static fnc sid<T>(T v): T { return v; }
                end
                routine r<T>(T v): T { return v; }
                pub fnc main(): int {
                  val k = new K();
                  return k.id<T=int>(5) + K.sid<T=int>(4) + r<T=int>(2);
                }
                """).value());
    }

    @Test
    void namedTypePositionArgumentsAreDeclarationOrderedIdentities() {
        assertEquals(5, run("""
                define class Pair<A, B> as
                  pub val A a;
                  pub val B b;
                  constructor(A a, B b) { self.a = a; self.b = b; }
                end
                fnc take(Pair<int, String> p): int { return p.a; }
                pub fnc main(): int {
                  val Pair<B=String, A=int> p = new Pair<int, String>(5, "x");
                  return take(p);
                }
                """).value());
        rejects("""
                define class Pair<A, B> as
                end
                fnc take(Pair<a=int, b=String> p): int { return 1; }
                pub fnc main(): int { return 1; }
                """, "no generic parameter named");
    }

    // ---- named value arguments ----------------------------------------------------

    @Test
    void contractExampleBindsByNameAndEvaluatesInSourceOrder() {
        Run result = run("""
                fnc e1(): bool { stdio.stdout.write("1"); return true; }
                fnc e2(): int { stdio.stdout.write("2"); return 19; }
                fnc foo(int bar, bool a): int { return a ? bar : 0; }
                pub fnc main(): int {
                  val int x = foo(5, true);
                  val int y = foo(bar: 5, a: true);
                  val int z = foo(a: true, bar: 6);
                  val int w = foo(7, a: true);
                  return x * 1000 + y * 100 + z * 10 + w + foo(a: e1(), bar: e2()) * 0;
                }
                """);
        assertEquals(5567, result.value());
        assertEquals("12", result.out());
    }

    @Test
    void equalsInsideCallIsAssignmentNeverALabel() {
        assertEquals(55, run("""
                fnc foo(int bar, bool a): int { return a ? bar : 0; }
                pub fnc main(): int {
                  let mut int bar = 0;
                  val int r = foo(bar = 5, true);
                  return r * 10 + bar;
                }
                """).value());
    }

    @Test
    void receiverEvaluatesBeforeNamedArgumentsWhichEvaluateLexically() {
        Run result = run("""
                define class K as
                  pub enc(int a, int b): int { return a * 10 + b; }
                end
                fnc mk(): K { stdio.stdout.write("3"); return new K(); }
                fnc x(): int { stdio.stdout.write("1"); return 1; }
                fnc y(): int { stdio.stdout.write("2"); return 2; }
                pub fnc main(): int { return mk().enc(b: x(), a: y()); }
                """);
        assertEquals(21, result.value());
        assertEquals("312", result.out());
    }

    @Test
    void namedTailCallsStayProperTailCalls() {
        assertEquals(200000, run("""
                fnc loop(int n, int acc): int {
                  if (n eq 0) { return acc; } fi
                  return loop(acc: acc + 1, n: n - 1);
                }
                pub fnc main(): int { return loop(200000, 0); }
                """).value());
    }

    @Test
    void overrideDispatchThroughBaseTypeUsesInheritedLabels() {
        assertEquals(2319, run("""
                define class Base as
                  pub enc(int a, int b): int { return a * 100 + b; }
                end
                define class Child extends Base as
                  pub enc(int a, int b): int { return b * 100 + a; }
                end
                pub fnc main(): int {
                  val Base c = new Child();
                  return c.enc(b: 23, a: 19);
                }
                """).value());
    }

    @Test
    void overridesAndImplementationsMustKeepParameterLabels() {
        rejects("""
                define class Base as
                  pub enc(int a, int b): int { return a * 100 + b; }
                end
                define class Child extends Base as
                  pub enc(int b, int a): int { return b * 100 + a; }
                end
                pub fnc main(): int { return 1; }
                """, "keep parameter labels");
        rejects("""
                define class Base as
                  pub static fnc enc(int a, int b): int { return a; }
                end
                define class Child extends Base as
                  pub static fnc enc(int x, int y): int { return x; }
                end
                pub fnc main(): int { return 1; }
                """, "keep parameter labels");
        rejects("""
                define class A as
                  pub enc(int a, int b): int { return 1; }
                end
                define class B as
                  pub enc(int x, int y): int { return 2; }
                end
                define class C extends A, B as
                  pub enc(int a, int b): int { return 3; }
                end
                pub fnc main(): int { return 1; }
                """, "keep parameter labels");
    }

    @Test
    void malformedNamedCallsAreRejected() {
        String decl = "fnc add(int a, int b): int { return a * 10 + b; }\n";
        rejects(decl + "pub fnc main(): int { return add(a: 1, a: 2); }", "duplicate");
        rejects(decl + "pub fnc main(): int { return add(1, a: 2); }", "");
        rejects(decl + "pub fnc main(): int { return add(a: 1); }", "");
        rejects(decl + "pub fnc main(): int { return add(a: 1, nope: 2); }", "");
        rejects(decl + "pub fnc main(): int { return add(a: 1, 2); }", "");
        rejects(decl + "pub fnc main(): int { return add(A: 1, B: 2); }", "");
        rejects(decl + "pub fnc main(): int { return add(b: \"s\", a: 2); }", "");
    }

    @Test
    void namedArgumentsOnUnlabeledCallableValuesAreRejected() {
        rejects("""
                fnc add(int a, int b): int { return a * 10 + b; }
                pub fnc main(): int {
                  val f: Fnc<int, int, int> = add;
                  return f(b: 1, a: 2);
                }
                """, "");
        rejects("""
                pub fnc main(): int {
                  val f = (int a, int b) -> a * 10 + b;
                  return f(b: 1, a: 2);
                }
                """, "");
    }

    @Test
    void namedArgumentsCannotHideABorrowOrderingViolation() {
        rejects("""
                define class Counter as
                  pub let int value = 1;
                end
                fnc f(int a, borrow mut Counter b): int { return a; }
                pub fnc main(): int {
                  let mut Counter c = new Counter();
                  return f(b: rt mut borrow c, a: c.value);
                }
                """, "");
    }
}
