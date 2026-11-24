package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unqualified instance calls are sugar for self.method(...), never a
 * receiver-capturing method value or a new overload/ownership namespace.
 */
final class ImplicitSelfMethodCallTest {

    @Test
    void unqualifiedCallUsesTheSamePerCallReceiverAsExplicitCall() throws Exception {
        assertEquals("14", run("""
                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): int {
                    return roodles() + self.roodles();
                  }
                end
                pub routine main(): void {
                  val foo = new Foo();
                  stdio.stdout.write(foo.park_it());
                }
                """));
    }

    @Test
    void inheritedImplicitCallUsesVirtualDispatch() throws Exception {
        assertEquals("4", run("""
                define class Base as
                  pub roodles(): int { return 1; }
                  pub park_it(): int { return roodles(); }
                end
                define class Child extends Base as
                  pub roodles(): int { return 4; }
                end
                pub routine main(): void {
                  val child = new Child();
                  stdio.stdout.write(child.park_it());
                }
                """));
    }

    @Test
    void lexicalFunctionValueShadowsTheInstanceMethod() throws Exception {
        assertEquals("9", run("""
                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): int {
                    val (() => int) roodles = || -> 9;
                    return roodles();
                  }
                end
                pub routine main(): void {
                  val foo = new Foo();
                  stdio.stdout.write(foo.park_it());
                }
                """));
    }

    @Test
    void instanceMethodFamilyWinsOverSameNamedFileFunction() throws Exception {
        assertEquals("7", run("""
                fnc roodles(): int { return 99; }
                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): int { return roodles(); }
                end
                pub routine main(): void {
                  val foo = new Foo();
                  stdio.stdout.write(foo.park_it());
                }
                """));
    }

    @Test
    void wrongArityNeverFallsBackToFileFunction() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc roodles(int value): int { return value; }
                        define class Foo as
                          pub roodles(): int { return 7; }
                          pub park_it(): int { return roodles(99); }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("method")
                || failure.getMessage().contains("arity"), failure.getMessage());
    }

    @Test
    void aClassMethodCannotBeExtractedAsAnImplicitBoundClosure() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub roodles(): int { return 7; }
                          pub park_it(): int {
                            val r = self.roodles;
                            return r();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("direct-call-only"), failure.getMessage());
    }

    @Test
    void bareMethodNameDoesNotMaterializeAReceiver() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub roodles(): int { return 7; }
                          pub park_it(): void {
                            val r = roodles;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("unknown name"), failure.getMessage());
    }

    @Test
    void implicitCallCannotUpgradeAnImmutableSelfLoan() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int count = 0;
                          pub bump(mut self Counter)(): void {
                            self.count = self.count + 1;
                          }
                          pub read_only(): void {
                            bump();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("mutable")
                || failure.getMessage().contains("read-only"), failure.getMessage());
    }

    @Test
    void unqualifiedMethodInConstructorIsStillForbidden() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Foo as
                          constructor() { roodles(); }
                          pub roodles(): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("constructor")
                || failure.getMessage().contains("partially initialized"), failure.getMessage());
    }


    @Test
    void pkgQualifierSelectsPrivateFileRootOverInstanceAndLexicalNames() throws Exception {
        assertEquals("7|99|99", run("""
                fnc roodles(): int { return 99; }
                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): void {
                    val pkg = 123;
                    val (() => int) roodles = || -> 11;
                    stdio.stdout.write(self.roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(pkg::roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(roodles() + pkg::roodles() - 11);
                  }
                end
                pub routine main(): void { val foo = new Foo(); foo.park_it(); }
                """));
    }

    @Test
    void pkgQualifierIsDistinctFromAUserDefinedModuleNamedPkg() throws Exception {
        assertEquals("7|12|99", run("""
                fnc roodles(): int { return 99; }
                define module pkg as
                  pub fnc roodles(): int { return 12; }
                end
                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): void {
                    stdio.stdout.write(roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(pkg.roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(pkg::roodles());
                  }
                end
                pub routine main(): void { val foo = new Foo(); foo.park_it(); }
                """));
    }

    @Test
    void pkgQualifierValueExtractionIsRejectedUntilCheckedCallableABISupport() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc roodles(int x): int { return x + 1; }
                        define class Foo as
                          pub park_it(): void {
                            val r = pkg::roodles;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("direct-call-only"), failure::getMessage);
    }

    @Test
    void pkgQualifierNeverFallsBackOnArityMismatch() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc roodles(int value): int { return value; }
                        define class Foo as
                          pub roodles(): int { return 7; }
                          pub park_it(): int { return pkg::roodles(); }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("pkg::roodles"), failure.getMessage());
    }

    @Test
    void pkgQualifierRejectsModuleOnlyFunctionAndUnrelatedRootClass() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module helpers as
                          pub fnc roodles(): int { return 1; }
                        end
                        define class Foo as
                          pub park_it(): int { return pkg::roodles(); }
                        end
                        """)));
        assertTrue(missing.getMessage().contains("pkg"), missing.getMessage());

        IllegalArgumentException nonCallable = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Widget as
                        end
                        fnc test(): void {
                          val x = pkg::Widget;
                        }
                        """)));
        assertTrue(nonCallable.getMessage().contains("pkg::Widget"), nonCallable.getMessage());
    }

    @Test
    void pkgQualifierDoesNotTurnRoutinesIntoValues() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        routine roodles(): void { return; }
                        fnc test(): void { val r = pkg::roodles; }
                        """)));
        assertTrue(failure.getMessage().contains("direct-call-only"), failure.getMessage());
    }

    @Test
    void actorCannotUsePkgQualifierToBypassExplicitDependencyRule() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc roodles(): void { return; }
                        define actor Worker as
                          pub work(): void { pkg::roodles(); }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("actor")
                && failure.getMessage().contains("module"), failure.getMessage());
    }

    @Test
    void fileRootRoutineRemainsDirectCallableThroughPkgQualifier() throws Exception {
        assertEquals("12", run("""
                routine roodles(int value): int { return value + 2; }
                define class Foo as
                  pub roodles(int value): int { return value + 100; }
                  pub park_it(): int { return pkg::roodles(10); }
                end
                pub routine main(): void { val foo = new Foo(); stdio.stdout.write(foo.park_it()); }
                """));
    }

    @Test
    void pkgQualifierCannotReenterTheFileInitializationHook() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc init(): void { return; }
                        fnc test(): void { pkg::init(); }
                        """)));
        assertTrue(failure.getMessage().contains("pkg::init"), failure.getMessage());

        IllegalArgumentException extracted = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc init(): void { return; }
                        fnc test(): void { val r = pkg::init; }
                        """)));
        assertTrue(extracted.getMessage().contains("pkg::init"), extracted.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "implicit-self-method-call.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
