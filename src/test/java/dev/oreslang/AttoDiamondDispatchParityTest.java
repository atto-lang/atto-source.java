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
 * Reference-JVM execution and source-level diagnostic controls for the
 * atto-lang.github.io/play trait/multiple-inheritance parity milestone.
 *
 * JVM tests prove source execution, not Graal Native Image guest AOT.
 * Java currently treats an explicit child method as conflict resolution;
 * an 'override' spelling and trait default method bodies remain separate
 * unimplemented source-grammar parity requirements.
 */
final class AttoDiamondDispatchParityTest {
    private static String execute(String program) throws Exception {
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "diamond-parity.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void sharedAncestorIsDeduplicatedAndCompetingOverridesNeedChildResolution() throws Exception {
        String program = """
                define class Root as
                  pub origin(): int { return 1; }
                  pub label(): int { return 0; }
                end
                define class Left extends Root as
                  pub label(): int { return 10; }
                end
                define class Right extends Root as
                  pub label(): int { return 20; }
                end
                define class Diamond extends Left, Right as
                  pub label(): int { return 42; }
                end
                pub routine main(): void {
                  val d = new Diamond();
                  stdio.stdout.write(d.origin());
                  stdio.stdout.write(d.label());
                  return;
                }
                """;
        assertEquals("142", execute(program));
    }

    @Test
    void baseTypedReceiverRetainsDynamicChildOverride() throws Exception {
        String program = """
                define class Base as
                  pub value(): int { return 1; }
                end
                define class Child extends Base as
                  pub value(): int { return 2; }
                end
                fnc invoke_base(Base target): int {
                  return target.value();
                }
                pub routine main(): void {
                  stdio.stdout.write(invoke_base(new Base()));
                  stdio.stdout.write(invoke_base(new Child()));
                  return;
                }
                """;
        assertEquals("12", execute(program));
    }

    @Test
    void staticMethodsAreQualifiedAndNotVirtual() throws Exception {
        String program = """
                define class Base as
                  pub static fnc fixed(): int { return 10; }
                end
                define class Child extends Base as
                  pub static fnc fixed(): int { return 20; }
                end
                pub routine main(): void {
                  stdio.stdout.write(Base.fixed());
                  stdio.stdout.write(Child.fixed());
                  return;
                }
                """;
        assertEquals("1020", execute(program));
    }

    @Test
    void unresolvedTwoOriginDiamondFailsBeforeGuestExecution() {
        String program = """
                define class Left as
                  pub value(): int { return 1; }
                end
                define class Right as
                  pub value(): int { return 2; }
                end
                define class Broken extends Left, Right as end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("ambiguous inherited method"), error.getMessage());
        assertTrue(error.getMessage().contains("value"), error.getMessage());
    }

    @Test
    void nominalMethodSlotUsesNameAndArityNotParameterTypes() {
        String program = """
                define class Invalid as
                  pub value(int n): int { return n; }
                  pub value(bool flag): int { return 42; }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("arity 1"), error.getMessage());
    }

    @Test
    void interfaceSignaturesAcceptFncAndCanonicalFnclessSpelling() {
        // Canonical interface/trait members omit `fnc` (play/traits-interfaces);
        // the historical `fnc name(): T;` spelling stays accepted.
        assertDoesNotThrow(() -> Parser.parse(
                "define interface Named as fnc name(): string; end"));
        assertDoesNotThrow(() -> Parser.parse(
                "define interface Named as name(): string; end"));
    }
}
