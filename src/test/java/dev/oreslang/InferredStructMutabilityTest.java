package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InferredStructMutabilityTest {
    @Test void runtimeStructFieldsAreMutableOnlyThroughOwnedMutAccess() throws Exception {
        String program = """
                pub routine main(): void {
                  val point = struct infer{label: "before", count: 1};
                  point.label = "after";
                  point.count = 2;
                  stdio.stdout.write(point.label);
                  stdio.stdout.write(point.count);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "struct-infer.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("after2", output.toString(StandardCharsets.UTF_8));
    }

    @Test void inferredStructCanMutateAfterAsyncSuspension() throws Exception {
        String program = """
                async fnc answer(): int { return 7; }

                pub async fnc main(): void {
                  val point = struct infer{label: "before", count: 1};
                  val value = await answer();
                  point.label = "after";
                  point.count = value;
                  stdio.stdout.write(point.label);
                  stdio.stdout.write(point.count);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "struct-async-mutation.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("after7", output.toString(StandardCharsets.UTF_8));
    }

    @Test void readOnlyStructFieldMutationIsRejectedTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const nested = struct infer{inside: struct infer{foo: "bar"}};
                  nested.inside.foo = "changed";
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  let value = struct infer{foo: "bar"};
                  value.foo = "changed";
                  return;
                }
                """)));
    }

    @Test void explicitConstMutAllowsFieldMutationButNotRebinding() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                  const mut value = struct infer{foo: "bar"};
                  value.foo = "changed";
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut value = struct infer{foo: "bar"};
                  value = struct infer{foo: "replaced"};
                  return;
                }
                """)));
    }

    @Test void inferenceStaysContextualAndRejectsDynamicStructKeys() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc infer(int value): int { return value; }
                fnc ok(): int {
                  const value = struct infer{foo: "bar"};
                  return infer(3);
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  const value = struct infer{`dynamic`: 42};
                  return;
                }
                """));
    }
}
