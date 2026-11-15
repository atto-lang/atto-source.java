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

import static org.junit.jupiter.api.Assertions.*;

/** Named <(Args...) => Result> is a callable TYPE, not a runtime conversion. */
final class NamedCallableBindingSignatureTest {
    private static void rejectsType(String guest) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void namedSignatureLowersToAnOrdinaryFunctionType() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                    const add2<(int, int) => int> = fnc(a: int, b: int) -> int {
                        return a + b;
                    };
                    return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) fn.body().getFirst();
        assertEquals("add2", binding.name());
        assertEquals(Ast.BindingKind.CONST, binding.kind());
        assertNotNull(binding.declaredType());
        assertEquals("Fnc", binding.declaredType().name());
        assertEquals(3, binding.declaredType().arguments().size());
        for (Ast.TypeRef part : binding.declaredType().arguments()) {
            assertEquals("int", part.name());
        }
        assertNotNull(assertInstanceOf(Ast.LambdaExpr.class,
                binding.initializer()).declaredReturnType());
    }

    @Test
    void namedAndInferredBindingsWithAnonymousFncExecute() throws Exception {
        String guest = """
                fnc calculate(): int {
                    const add = |a: int, b: int| -> int {
                        return a + b;
                    };
                    const add2<(int, int) => int> = fnc(a: int, b: int) -> int {
                        return add(a, b);
                    };
                    return add2(19, 23);
                }
                pub fnc main(): void {
                    stdio.println(calculate());
                    return;
                }
                """;
        Ast.Program program = Parser.parse(guest);
        var typed = TypeChecker.check(program);
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest,
                "named-callable-signature.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void annotatedVoidCallbackMustReturnVoid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc main(): void {
                    const log_sum<(int, int) => void> = fnc(a: int, b: int) -> void {
                        stdio.println(a + b);
                    };
                    return;
                }
                """)));
    }

    @Test
    void mismatchedNamedReturnAnnotationFailsClosed() {
        rejectsType("""
                fnc main(): void {
                    const bad<(int, int) => void> = fnc(a: int, b: int) -> int {
                        return a + b;
                    };
                    return;
                }
                """);
        rejectsType("""
                fnc main(): void {
                    const bad<(int, int) => int> = fnc(a: int, b: int) -> void {
                        stdio.println(a + b);
                    };
                    return;
                }
                """);
    }

    @Test
    void wrongArityAndParameterTypeAreRejected() {
        rejectsType("""
                fnc main(): void {
                    const bad<(int, int) => int> = fnc(a: int) -> int {
                        return a;
                    };
                    return;
                }
                """);
        rejectsType("""
                fnc main(): void {
                    const bad<(bool, int) => int> = fnc(a: int, b: int) -> int {
                        return a + b;
                    };
                    return;
                }
                """);
    }

    @Test
    void namedSignatureCanBeUsedOnModuleConst() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                const add2<(int, int) => int> = fnc(a: int, b: int) -> int {
                    return a + b;
                };
                pub fnc main(): void {
                    stdio.println(add2(19, 23));
                    return;
                }
                """)));
    }

    @Test
    void genericTypeFirstBindingsDoNotBecomeCallableAscriptions() {
        Ast.Program parsed = Parser.parse("""
                fnc main(): void {
                    const Option<int> local = Some(42);
                    return;
                }
                const Option<int> global = Some(7);
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) parsed.modules().getFirst()
                .declarations().getFirst();
        Ast.BindingStmt local = (Ast.BindingStmt) fn.body().getFirst();
        assertEquals("local", local.name());
        assertEquals("Option", local.declaredType().name());
        assertEquals("int", local.declaredType().arguments().getFirst().name());
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }

    @Test
    void namedSignatureRejectsMalformedAndNonCallableHeaders() {
        for (String signature : new String[] {
                "int",
                "(int, int)",
                "(int, int) -> int",
                "(int, int) =>"
        }) {
            String guest = "fnc main(): void { const bad<" + signature
                    + "> = fnc(a: int, b: int) -> int { return a + b; }; return; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(guest),
                    signature);
        }
    }

    @Test
    void inferredVoidDeferIsContextuallyChecked() throws Exception {
        String guest = """
                fnc calculate(): int {
                    defer || -> {
                        stdio.println("defer done");
                    };
                    return 42;
                }
                pub fnc main(): void {
                    stdio.println(calculate());
                    return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest,
                "inferred-void-defer.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("defer done\n42",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void inferredDeferMustNotReturnAValue() {
        rejectsType("""
                fnc main(): void {
                    defer || -> {
                        return 42;
                    };
                    return;
                }
                """);
    }
}
