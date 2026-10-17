package dev.oreslang;

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

final class MultiIndexProtocolTest {
    @Test
    void commaIndexIsOneArityTwoOperationNotNestedUnaryIndexing() throws Exception {
        String output = run("""
                define class Row as
                  pub [Symbol.index](int col): int {
                    return 900 + col;
                  }
                end

                define class Matrix as
                  pub [Symbol.index](int row): Row {
                    return new Row();
                  }

                  pub [Symbol.index](int row, int col): int {
                    return row * 10 + col;
                  }
                end

                pub routine main(): void {
                  val matrix = new Matrix();
                  stdio.stdout.write(matrix[2, 3]);
                  stdio.stdout.write("|");
                  stdio.stdout.write(matrix[2][3]);
                  return;
                }
                """);

        assertEquals("23|903", output);
    }

    @Test
    void indexedSetterCountsIndicesSeparatelyFromAssignedValue() throws Exception {
        String output = run("""
                define class Matrix as
                  let int stored = 0;

                  pub [Symbol.indexSet](int row, int col, int value): void {
                    self.stored = row * 100 + col * 10 + value;
                    return;
                  }

                  pub read(): int {
                    return self.stored;
                  }
                end

                pub routine main(): void {
                  let mut matrix = new Matrix();
                  matrix[1, 2] = 7;
                  stdio.stdout.write(matrix.read());
                  return;
                }
                """);

        assertEquals("127", output);
    }

    @Test
    void everyCommaIndexOperandEvaluatesLeftToRightExactlyOnce() throws Exception {
        String output = run("""
                fnc first(): int {
                  stdio.stdout.write("A");
                  return 1;
                }

                fnc second(): int {
                  stdio.stdout.write("B");
                  return 2;
                }

                define class Matrix as
                  pub [Symbol.index](int row, int col): int {
                    return row + col;
                  }
                end

                pub routine main(): void {
                  val matrix = new Matrix();
                  stdio.stdout.write(matrix[first(), second()]);
                  return;
                }
                """);

        assertEquals("AB3", output);
    }

    @Test
    void indexedSetterEvaluatesIndicesBeforeAssignedValue() throws Exception {
        String output = run("""
                fnc first(): int {
                  stdio.stdout.write("A");
                  return 1;
                }

                fnc second(): int {
                  stdio.stdout.write("B");
                  return 2;
                }

                fnc assigned(): int {
                  stdio.stdout.write("V");
                  return 7;
                }

                define class Matrix as
                  pub [Symbol.indexSet](int row, int col, int value): void {
                    stdio.stdout.write("S");
                    return;
                  }
                end

                pub routine main(): void {
                  let mut matrix = new Matrix();
                  matrix[first(), second()] = assigned();
                  return;
                }
                """);

        assertEquals("ABVS", output);
    }

    @Test
    void builtInCollectionsRemainUnary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Array<int> values): int {
                          return values[0, 1];
                        }
                        """)));
        assertTrue(failure.getMessage().contains("exactly one index"), failure::getMessage);
    }

    @Test
    void indexProtocolStillChecksArgumentAndAssignedValueTypes() {
        IllegalArgumentException getter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Matrix as
                          pub [Symbol.index](int row, int col): int { return row + col; }
                        end

                        fnc bad(Matrix matrix): int {
                          return matrix[1, "wrong"];
                        }
                        """)));
        assertTrue(getter.getMessage().contains("argument 2"), getter::getMessage);

        IllegalArgumentException setter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Matrix as
                          pub [Symbol.indexSet](int row, int col, int value): void { return; }
                        end

                        fnc bad(): void {
                          let mut matrix = new Matrix();
                          matrix[1, 2] = "wrong";
                          return;
                        }
                        """)));
        assertTrue(setter.getMessage().contains("argument 3"), setter::getMessage);
    }

    @Test
    void customIndexedWriteRequiresMutableReceiverAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Matrix as
                          pub [Symbol.indexSet](int row, int col, int value): void { return; }
                        end

                        fnc bad(): void {
                          val matrix = new Matrix();
                          matrix[1, 2] = 7;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("read-only binding"), failure::getMessage);
    }

    @Test
    void mutableIndexGetterRequiresMutableReceiverAuthority() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                define class Counter as
                  let int reads = 0;

                  pub [Symbol.index](mut self)(int key): int {
                    self.reads = self.reads + 1;
                    return self.reads + key;
                  }
                end

                pub routine main(): void {
                  val counter = new Counter();
                  stdio.stdout.write(counter[0]);
                  return;
                }
                """));
        assertTrue(failure.getMessage().contains("read-only binding")
                || failure.getCause() != null
                    && String.valueOf(failure.getCause().getMessage()).contains("read-only binding"),
                failure::getMessage);
    }

    @Test
    void genericIndexMethodSpecializesAfterAritySelection() throws Exception {
        String output = run("""
                define class IdentityIndex as
                  pub [Symbol.index]<T>(T value): T {
                    return value;
                  }
                end

                pub routine main(): void {
                  val index = new IdentityIndex();
                  stdio.stdout.write(index[42]);
                  stdio.stdout.write("|");
                  stdio.stdout.write(index["ok"]);
                  return;
                }
                """);

        assertEquals("42|ok", output);
    }

    @Test
    void malformedIndexProtocolDeclarationsFailClosed() {
        IllegalArgumentException zeroArity = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub [Symbol.index](): int { return 1; }
                        end
                        """)));
        assertTrue(zeroArity.getMessage().contains("at least one index"), zeroArity::getMessage);

        IllegalArgumentException setterResult = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad as
                          pub [Symbol.indexSet](int row, int value): int { return value; }
                        end
                        """)));
        assertTrue(setterResult.getMessage().contains("must return void"), setterResult::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "multi-index.ores")
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
}
