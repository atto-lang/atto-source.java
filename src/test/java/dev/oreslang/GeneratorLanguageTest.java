package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratorLanguageTest {
    @Test
    void generatorFncAndRoutineArePullDrivenIterables() throws Exception {
        String output = run("""
                generator fnc first(): int {
                  yield 1;
                  yield 2;
                  return;
                }

                generator routine second(): int {
                  yield 3;
                  yield 4;
                  return;
                }

                pub routine main(): void {
                  for value of first() do
                    stdio.stdout.write(value);
                  done
                  for value of second() do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """);

        assertEquals("1234", output);
    }

    @Test
    void asyncGeneratorComposesAwaitAndForAwait() throws Exception {
        String output = run("""
                async fnc later(int value): int {
                  return value;
                }

                async generator fnc values(): int {
                  yield 1;
                  yield await later(2);
                  yield 3;
                  return;
                }

                pub async fnc main(): void {
                  for await value of values() do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """);

        assertEquals("123", output);
    }

    @Test
    void generatorAndAsyncModifiersAreOrderIndependent() {
        AstAssertions.assertAsyncGenerator(Parser.parse("""
                generator async routine values(): int {
                  yield 1;
                  return;
                }
                """));
        AstAssertions.assertAsyncGenerator(Parser.parse("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }
                """));
    }

    @Test
    void classCanExposeAsyncIteratorProtocolWithoutGeneratorMethods() throws Exception {
        String output = run("""
                async generator fnc stream(): int {
                  yield 7;
                  yield 8;
                  return;
                }

                define class Bag as
                  pub [Symbol.asyncIterator](): AsyncGenerator<int> {
                    return stream();
                  }
                end

                pub async routine main(): void {
                  val bag = new Bag();
                  for await item of bag {
                    stdio.stdout.write(item);
                  }
                  return;
                }
                """);

        assertEquals("78", output);
    }

    @Test
    void yieldAndGeneratorReturnsAreCheckedStatically() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc ordinary(): int {
                  yield 1;
                  return 1;
                }
                """));

        IllegalArgumentException returned = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        generator fnc bad(): int {
                          return 1;
                        }
                        """));
        assertTrue(returned.getMessage().contains("generator return"));
    }

    @Test
    void asyncAndSyncIterationCannotBeAccidentallyMixed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }

                pub async fnc main(): void {
                  for value of values() {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }

                pub routine main(): void {
                  for await value of values() {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));
    }

    @Test
    void generatorMethodsAndActorGeneratorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad as
                  pub generator next(): int {
                    yield 1;
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                generator actor fnc bad(): int {
                  yield 1;
                  return;
                }
                """));
    }

    @Test
    void yieldCannotSuspendWithLiveBorrow() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub val int value = 1;
                end

                generator fnc bad(&Box box): int {
                  yield box.value;
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "generators.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class AstAssertions {
        private static void assertAsyncGenerator(dev.oreslang.ast.Ast.Program program) {
            var fn = (dev.oreslang.ast.Ast.FunctionDecl)
                    program.modules().getFirst().declarations().getFirst();
            assertTrue(fn.async());
            assertTrue(fn.generator());
        }
    }
}
