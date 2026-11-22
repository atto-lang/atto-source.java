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
 * Real Atto guest execution, separate from pure Java host witness tests.
 * This must execute the identical class-layout oracle on the JVM, including
 * the real type checker and Truffle evaluator. JIT-disabled JVM execution
 * does NOT constitute a Graal Native Image executable.
 */
final class ReadonlyShapeGuest84Test {
    private static final String COMMON = """
            define class A as
                pub val int x = 42;
            end

            define class B as
                pub val int padding = 10;
                pub val int x = 42;
            end

            pub fnc main(): void {
                stdio.println(read_x(new A()) + read_x(new B()));
                return;
            }
            """;

    private static void compileAndRun(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                "real Atto type checker must accept structural call");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, source, "readonly-shape-guest-84.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("84\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n","\n"));
    }

    @Test
    void structuralInterfaceToDifferentlyLaidOutClassesMustExecute() {
        compileAndRun("""
                define interface HasX
                    x: int;
                end
                fnc read_x(structural HasX p): int {
                    return p.x;
                }
                """ + COMMON);
    }

    @Test
    void bareAnonymousShapeToDifferentlyLaidOutClassesMustExecute() {
        compileAndRun("""
                fnc read_x(p: {x: int}): int {
                    return p.x;
                }
                """ + COMMON);
    }
}
