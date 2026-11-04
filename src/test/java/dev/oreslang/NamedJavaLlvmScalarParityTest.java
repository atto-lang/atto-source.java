package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java guest counterparts of LLVM's native_scalar_generic_source_execution.py.
 *
 * The same library-level AttoLang call expressions must produce 42 on both
 * compilers; entrypoint adapters differ (Java writes stdout, LLVM exits 42).
 * These are real Java guest tests, not a reference-model substitute for LLVM.
 */
final class NamedJavaLlvmScalarParityTest {
    private static String execute(String code) throws Exception {
        Ast.Program parsed = Parser.parse(code);
        OwnershipChecker.check(TypeChecker.check(parsed));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, "named-scalar-parity.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void reorderedGenericAndValueSlots() throws Exception {
        assertEquals("42", execute("""
                fnc combine<A=int, B=A>(A first, B second): int {
                    return first * 10 + second;
                }
                pub routine main(): void {
                    stdio.stdout.write(combine<B=int, A=int>(second: 2, first: 4));
                    return;
                }
                """));
    }

    @Test
    void positionalGenericPrefixAndNamedValueTail() throws Exception {
        assertEquals("42", execute("""
                fnc add<T=int>(T first, T second): int { return first + second; }
                pub routine main(): void {
                    stdio.stdout.write(add<int>(19, second: 23));
                    return;
                }
                """));
    }

    @Test
    void inferredGenericFromReorderedValueArguments() throws Exception {
        assertEquals("42", execute("""
                fnc add<T>(T first, T second): int { return first + second; }
                pub routine main(): void {
                    stdio.stdout.write(add(second: 23, first: 19));
                    return;
                }
                """));
    }

    @Test
    void routineNamedValueOrder() throws Exception {
        assertEquals("42", execute("""
                routine encode(int first, int second): int {
                    return first * 10 + second;
                }
                pub routine main(): void {
                    stdio.stdout.write(encode(second: 2, first: 4));
                    return;
                }
                """));
    }

    @Test
    void lexicalEffectsExecuteOnceBeforeFormalPermutation() throws Exception {
        assertEquals("BA42", execute("""
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                fnc second(): int {
                    stdio.stdout.write("B");
                    return 2;
                }
                fnc combine<A=int, B=A>(A first, B second): int {
                    return first * 10 + second;
                }
                pub routine main(): void {
                    stdio.stdout.write(combine<B=int, A=int>(
                            second: second(), first: first()));
                    return;
                }
                """));
    }

    @Test
    void unknownLateNamedValueFailsWithoutGuestExecution() {
        String code = """
                fnc add<T>(T first, T second): int { return first + second; }
                pub routine main(): void {
                    stdio.stdout.write(add<T=int>(
                            first: doesNotExist(), unknown: 23));
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        // A missing nested callee is an independent failure. A passing negative
        // must prove the late label was rejected by the actual checked binder.
        assertTrue(failure.getMessage().contains("unknown named argument 'unknown'"),
                failure.getMessage());
    }

    @Test
    void genericSlotCollisionIsNotAcceptedAsTwoSpecializations() {
        String code = """
                fnc choose<T, U>(T first, U second): T { return first; }
                pub routine main(): void {
                    stdio.stdout.write(choose<int, T=int>(1, 2));
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        assertTrue(failure.getMessage().contains("duplicate generic argument 'T'"),
                failure.getMessage());
    }

    @Test
    void genericLabelsAreCaseSensitiveBeforeValueInference() {
        String code = """
                fnc choose<T, U>(T first, U second): T { return first; }
                pub routine main(): void {
                    stdio.stdout.write(choose<t=int, U=int>(1, 2));
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        assertTrue(failure.getMessage().contains("no generic parameter named 't'"),
                failure.getMessage());
    }

    @Test
    void valueLabelsAreCaseSensitiveBeforeEmittingTheGuest() {
        String code = """
                fnc combine<T=int>(T first, T second): int {
                    return first * 10 + second;
                }
                pub routine main(): void {
                    stdio.stdout.write(combine<T=int>(First: 4, second: 2));
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        assertTrue(failure.getMessage().contains("unknown named argument 'First'"),
                failure.getMessage());
    }
}
