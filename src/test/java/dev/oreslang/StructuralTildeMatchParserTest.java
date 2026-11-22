package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralTildeMatchParserTest {
    private static Ast.StructuralPattern parseStructuralArm(String pattern) {
        Ast.Program program = Parser.parse("""
                fnc identify(): string {
                  match account over
                    on %s -> { return "matched"; }
                    on _ -> { return "unknown"; }
                  end
                }
                """.formatted(pattern));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.MatchStmt match = assertInstanceOf(Ast.MatchStmt.class, fn.body().getFirst());
        return assertInstanceOf(Ast.StructuralPattern.class, match.arms().getFirst().pattern());
    }

    @Test
    void tildeShapeDesugarsToTheSameStructuralPatternAsLongForm() {
        Ast.StructuralPattern shortForm =
                parseStructuralArm("~ {id: string, active: bool} as a");
        Ast.StructuralPattern longForm =
                parseStructuralArm("structural {id: string, active: bool} as a");

        assertEquals("a", shortForm.binding());
        assertEquals(longForm.target(), shortForm.target());
        assertEquals(longForm.binding(), shortForm.binding());
    }

    @Test
    void longFormAlsoSupportsExplicitAsForNamedShapes() {
        Ast.StructuralPattern pattern = parseStructuralArm("structural HasName as named");
        assertEquals("named", pattern.binding());
        assertEquals("HasName", pattern.target().name());
    }

    @Test
    void shapeBindingMustUseAsInNewTildeForm() {
        assertThrows(IllegalArgumentException.class,
                () -> parseStructuralArm("~ {id: string} a"));
        assertThrows(IllegalArgumentException.class,
                () -> parseStructuralArm("~ {id: string} as"));
    }

    @Test
    void compatibleRecordStructAndClassShapesTypeCheckWithExtraFields() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Account as
                  pub id: string = "acct_42";
                  pub active: bool = true;
                  pub extra: int = 9;
                end

                fnc check_record(): void {
                  const account = record {id: "acct_42", active: true, extra: 9};
                  match account over
                    on ~ {id: string, active: bool} as a -> {
                      const id = a.id;
                    }
                    on _ -> {}
                  end
                  return;
                }

                fnc check_struct(): void {
                  const account = infer struct {id: "acct_42", active: true, extra: 9};
                  match account over
                    on ~ {id: string, active: bool} as a -> {
                      const id = a.id;
                    }
                    on _ -> {}
                  end
                  return;
                }

                fnc check_class(Account account): void {
                  match account over
                    on ~ {id: string, active: bool} as a -> {
                      const id = a.id;
                    }
                    on _ -> {}
                  end
                  return;
                }
                """)));
    }

    @Test
    void missingOrIncompatibleRecordFieldsCannotSatisfyStructuralPattern() {
        for (String initializer : new String[]{
                "record {id: \"acct_42\"}",
                "record {id: \"acct_42\", active: \"yes\"}"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            fnc check(): void {
                              const account = %s;
                              match account over
                                on ~ {id: string, active: bool} as a -> { return; }
                                on _ -> { return; }
                              end
                            }
                            """.formatted(initializer))));
        }
    }

    @Test
    void existingTildeRecordPatternIsNotReinterpretedAsStructural() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                fnc test(): void {
                  match account over
                    on ~(id: item) -> { return; }
                    on _ -> { return; }
                  end
                }
                """));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.MatchStmt match = assertInstanceOf(Ast.MatchStmt.class, fn.body().getFirst());
        assertInstanceOf(Ast.RecordPattern.class, match.arms().getFirst().pattern());
    }
}
