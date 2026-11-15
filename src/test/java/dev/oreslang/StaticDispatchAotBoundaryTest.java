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

/**
 * Language-level static-dispatch admission controls.
 *
 * These tests use the currently supported JVM execution path to guard method
 * selection semantics. They do NOT prove Graal Native Image direct-call
 * devirtualization, a JIT-disabled guest, or a performance improvement.
 */
final class StaticDispatchAotBoundaryTest {
    @Test
    void selectedOverloadAndOverrideRemainSemanticallyDistinct() throws Exception {
        String program = """
                define class Base as
                  pub pick(): int { return 1; }
                  pub pick(int n): int { return n + 1; }
                end
                define class Child extends Base as
                  pub pick(int n): int { return n + 40; }
                end
                pub routine main(): void {
                  val base = new Base();
                  val child = new Child();
                  stdio.stdout.write(base.pick(1));
                  stdio.stdout.write(":");
                  stdio.stdout.write(child.pick(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(child.pick());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "static-dispatch-aot.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("2:42:1", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void parameterTypesCannotCreateAnAmbiguousSameArityMethodSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Unsafe as
                          pub pick(int n): int { return n; }
                          pub pick(String s): int { return 42; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error.getMessage());
        assertTrue(error.getMessage().contains("name + arity only"), error.getMessage());
    }

    @Test
    void unrelatedInheritedImplementationsNeedExplicitResolution() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Left as
                          pub ping(): int { return 1; }
                        end
                        define class Right as
                          pub ping(): int { return 2; }
                        end
                        define class Ambiguous extends Left, Right as
                        end
                        """)));
        assertTrue(error.getMessage().contains("ambiguous inherited method 'Ambiguous.ping' with arity 0"),
                error.getMessage());
        assertTrue(error.getMessage().contains("Left"), error.getMessage());
        assertTrue(error.getMessage().contains("Right"), error.getMessage());
    }

    @Test
    void declaredBaseTypeDoesNotProveUniqueVirtualTarget() {
        // The language contract must not allow devirtualization merely from
        // a declared base receiver. Base and Child are both reachable.
        // This checks the type hierarchy admission, not emitted machine code.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Base as
                  pub ping(): int { return 1; }
                end
                define class Child extends Base as
                  pub ping(): int { return 42; }
                end
                """)));
    }
}
