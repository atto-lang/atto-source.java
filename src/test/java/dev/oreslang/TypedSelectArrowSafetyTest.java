package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TypedSelectArrowSafetyTest {
    @Test
    void typedReadBindingsRequireSlimArrowInAllStaticSelectModes() {
        for (String prefix : new String[]{
                "select", "nb select", "do select", "try select"}) {
            String source = """
                    actor fnc handle(Channel<int> input): void {
                      %s {
                        when readch input: int number {
                          stdio.println(number);
                        }
                      }
                      return;
                    }
                    """.formatted(prefix);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source),
                    prefix);
            assertTrue(error.getMessage().contains("requires '->'"),
                    error::getMessage);
        }
        for (String delimiter : new String[]{"over", "among"}) {
            String source = """
                    fnc choose(Channel<int> input): int {
                      const int result = select %s
                        when readch input: int number {
                          return number;
                        }
                        default -> { return 0; }
                      end;
                      return result;
                    }
                    """.formatted(delimiter);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source),
                    delimiter);
            assertTrue(error.getMessage().contains("requires '->'"),
                    error::getMessage);
        }
    }

    @Test
    void typedReadBindingsWithArrowRemainValid() {
        Ast.Program program = assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse("""
                        fnc choose(Channel<int> input): int {
                          const int result = select over
                            when readch input: int number -> {
                              return number;
                            }
                            default -> { return 0; }
                          end;
                          return result;
                        }
                        """)));
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.StaticSelectExpr value = (Ast.StaticSelectExpr)
                ((Ast.BindingStmt) fn.body().getFirst()).initializer();
        assertEquals("int", value.select().arms().getFirst().declaredBindingType().name());
    }

    @Test
    void legacyUntypedReadBindingsRemainCompatible() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc handle(Channel<int> input): void {
                  do select {
                    when readch input: val number {
                      stdio.println(number);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void bindingFirstReadArmsCannotHaveTwoArrows() {
        for (String prefix : new String[]{"select", "do select", "nb select"}) {
            String source = """
                    actor fnc handle(Channel<int> input): void {
                      %s {
                        when val number = readch(input) -> -> {
                          stdio.println(number);
                        }
                      }
                      return;
                    }
                    """.formatted(prefix);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source),
                    prefix);
            assertTrue(error.getMessage().contains("duplicate '->'"),
                    error::getMessage);
        }
    }
}
