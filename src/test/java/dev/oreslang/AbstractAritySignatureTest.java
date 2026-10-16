package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AbstractAritySignatureTest {

    @Test
    void signatureOnlyDeclarationsInAbstractClassUseNameAndAritySlots() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define abstract class Foo as
                  pub abc(): void;
                  pub abc(int a): void;
                  pub abc(int a, int b): void;
                  pub abc(bool a, int b, bool c): void;
                end
                """)));
    }

    @Test
    void sameAritySignatureIsDuplicateEvenWhenTypesAndReturnTypesDiffer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Foo as
                          pub abc(): void;
                          pub abc(int a): void;
                          pub abc(int a, int b): void;
                          pub abc(bool a, int b, bool c): void;
                          pub abc(int a, bool b): int;
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 2"), error.getMessage());
        assertTrue(error.getMessage().contains("overload identity is name + arity only"), error.getMessage());
    }

    @Test
    void concreteSubclassCanImplementAllImplicitAbstractAritySlots() throws Exception {
        String source = """
                define abstract class Foo as
                  pub abc(): int;
                  pub abc(int a): int;
                  pub abc(int a, int b): int;
                  pub abc(bool a, int b, bool c): int;
                end

                define class Bar extends Foo as
                  pub abc(): int { return 0; }
                  pub abc(int a): int { return a; }
                  pub abc(int a, int b): int { return a + b; }
                  pub abc(bool a, int b, bool c): int { return b; }
                end

                pub routine main(): void {
                  val bar = new Bar();
                  stdio.stdout.write(bar.abc());
                  stdio.stdout.write(bar.abc(1));
                  stdio.stdout.write(bar.abc(2, 3));
                  stdio.stdout.write(bar.abc(true, 6, false));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "abstract-arity-slots.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("0156", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void concreteClassCannotUseSignatureOnlyDeclarations() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Concrete as
                          abc(int a): void;
                        end
                        """));
        assertTrue(error.getMessage().contains("signature-only method 'abc' requires an abstract class"),
                error.getMessage());
    }

    @Test
    void explicitPrivateAbstractSignatureIsAnOverrideOnlySlot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define abstract class Foo as
                  private abc(int a): void;
                end
                define class Bar extends Foo as
                  private abc(int a): void { return; }
                end
                """)));
    }

    @Test
    void concreteClassMustImplementEveryImplicitAbstractArity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Foo as
                          pub abc(): int;
                          pub abc(int a): int;
                        end
                        define class Incomplete extends Foo as
                          pub abc(): int { return 0; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("unresolved abstract method 'abc' with arity 1"),
                error.getMessage());
    }

    @Test
    void wrongArgumentTypeDoesNotSearchAnotherOverload() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Foo as
                          pub abc(int value): int;
                          pub abc(int value, int delta): int;
                        end
                        define class Impl extends Foo as
                          pub abc(int value): int { return value; }
                          pub abc(int value, int delta): int { return value + delta; }
                        end
                        pub fnc bad(): int {
                          val value = new Impl();
                          return value.abc(true);
                        }
                        """)));
        assertFalse(error.getMessage().contains("ambiguous overload"), error.getMessage());
    }

    @Test
    void staticFunctionCannotBecomeImplicitAbstract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Foo as
                          pub static fnc abc(int value): int;
                        end
                        """)));
        assertTrue(error.getMessage().contains("static class functions cannot be abstract"),
                error.getMessage());
    }

    @Test
    void explicitSelfReceiverDoesNotAddAnOverloadArgument() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define abstract class Foo as
                          pub abc(): void;
                          pub abc(self Foo)(): void;
                        end
                        """)));
        assertTrue(error.getMessage().contains("already has arity 0"), error.getMessage());
    }
}
