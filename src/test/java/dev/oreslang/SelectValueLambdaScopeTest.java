package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectValueLambdaScopeTest {
    @Test
    void lambdaReturnInBlockingValueArmBelongsToLambda() throws Exception {
        String source = """
                pub fnc main(): void {
                  const int answer = select over
                    default -> {
                      const Fnc<int,int> twice = |x| -> {
                        return x * 2;
                      };
                      return twice(5);
                    }
                  end;
                  stdio.stdout.write(answer);
                  return;
                }
                """;
        assertEquals("10", run(source));
    }

    @Test
    void deferredValueArmMayConstructCallableWithItsOwnReturnTarget() {
        String source = """
                actor fnc handle(): void {
                  const Future<int> answer = nb select over
                    default -> {
                      const Fnc<int,int> plusOne = |x| -> {
                        return x + 1;
                      };
                      return plusOne(6);
                    }
                  end;
                  return;
                }
                """;
        assertTrue(OresCompiler.parseAndTypeCheckWithDiagnostics(source)
                .warnings().isEmpty());
    }

    @Test
    void callableNestedInsideDeferredArmMayContainIndependentValueSelect() {
        String source = """
                actor fnc handle(): void {
                  const Future<int> answer = nb select over
                    default -> {
                      const Fnc<int,int> inner = |x| -> {
                        const int value = select over
                          default -> { return x + 3; }
                        end;
                        return value * 2;
                      };
                      return inner(4);
                    }
                  end;
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void callableReturnDoesNotCountAsMissingOuterValueArmReturn() {
        String source = """
                actor fnc handle(): void {
                  const Future<int> answer = nb select over
                    default -> {
                      const Fnc<int,int> inner = |x| -> {
                        return x * 2;
                      };
                    }
                  end;
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void actualOuterCallableReturnInsideDeferredArmRemainsForbidden() {
        String source = """
                actor fnc handle(): void {
                  const Future<int> answer = nb select over
                    default -> {
                      do select over
                        default -> { return 123; }
                      end
                      return 4;
                    }
                  end;
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "select-lambda-scope.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
