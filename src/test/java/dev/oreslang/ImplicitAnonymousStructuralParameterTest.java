package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression matrix: anonymous parameter shapes are implicitly structural. */
final class ImplicitAnonymousStructuralParameterTest {
    @Test
    void omittedAndRedundantKeywordsAdmitTheSameReadonlyWidthProjections() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Candidate as
                  pub val int id = 13;
                  pub val string extra = "visible";
                end

                fnc bare_type_first(struct{id: int} row): int { return row.id; }
                fnc bare_name_first(row: struct{id: int}): int { return row.id; }
                fnc explicit_type_first(structural struct{id: int} row): int { return row.id; }
                fnc explicit_name_first(row: structural struct{id: int}): int { return row.id; }
                fnc old_name_first(row structural struct{id: int}): int { return row.id; }
                fnc record_bare(row: record{id: int}): int { return row.id; }
                fnc record_explicit(row: structural record{id: int}): int { return row.id; }

                fnc exercise(): int {
                  const sample = infer struct{id: 17, other: true};
                  const row = record{id: 19, extra: "column"};
                  return bare_type_first(sample)
                       + bare_name_first(row)
                       + explicit_type_first(sample)
                       + explicit_name_first(row)
                       + old_name_first(new Candidate())
                       + record_bare(row)
                       + record_explicit(sample);
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void nestedAnonymousParametersAreReadonlyWithOrWithoutKeyword() {
        for (String param : new String[] {
                "struct{inner: struct{id: int}} row",
                "structural struct{inner: struct{id: int}} row",
                "row: struct{inner: struct{id: int}}",
                "row: structural struct{inner: struct{id: int}}"
        }) {
            String source = "fnc invalid(" + param + "): void { row.inner.id = 1; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), param);
        }
    }

    @Test
    void implicitStructuralParametersRejectMutationAndSuspendingLifetime() {
        for (String source : new String[] {
                "fnc bad(struct{id: int} mut row): void {}",
                "fnc bad(row: struct{id: int} mut): void {}",
                "fnc bad(structural struct{id: int} mut row): void {}",
                "fnc bad(row: structural struct{id: int} mut): void {}"
        }) {
            assertThrows(RuntimeException.class, () -> Parser.parse(source), source);
        }
        for (String source : new String[] {
                "async fnc bad(struct{id: int} row): int { return row.id; }",
                "async fnc bad(structural struct{id: int} row): int { return row.id; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
        }
    }

    @Test
    void namedContractsStillRequireStructuralOptIn() {
        String definitions = """
                define interface HasId
                  id: int;
                end
                define class Candidate as
                  pub val int id = 7;
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(definitions + """
                fnc project(structural HasId row): int { return row.id; }
                fnc project_name_first(row: structural HasId): int { return row.id; }
                fnc exercise(): int {
                  return project(new Candidate()) + project_name_first(new Candidate());
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(definitions + """
                fnc nominal(HasId row): int { return row.id; }
                fnc exercise(): int { return nominal(new Candidate()); }
                """)));
    }

    @Test
    void implicitAndExplicitShapesRejectMissingAndWrongFieldTypes() {
        for (String parameter : new String[] {
                "struct{id: int} row", "row: struct{id: int}",
                "structural struct{id: int} row", "row: structural struct{id: int}"
        }) {
            for (String argument : new String[] {
                    "record{other: 1}", "record{id: \"not an int\"}"
            }) {
                String source = "fnc project(" + parameter + "): int { return row.id; }"
                        + " fnc run(): int { return project(" + argument + "); }";
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)), source);
            }
        }
    }

    @Test
    void malformedStructuralQualifiersFailClosed() {
        for (String source : new String[] {
                "fnc bad(row: structural): void {}",
                "fnc bad(structural struct{id: int}): void {}",
                "fnc bad(row: structural struct{id:}): void {}",
                "fnc bad(row: structural struct{id: int, id: int}): void {}",
                "fnc bad(row: structural struct{id: int} another): void {}"
        }) {
            assertThrows(RuntimeException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void typedPipeAndParenthesizedLambdasUseTheSameAnonymousRule() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc exercise(): void {
                    val a = |struct{id: int} row| -> row.id;
                    val b = |row: struct{id: int}| -> row.id;
                    val c = |structural struct{id: int} row| -> row.id;
                    val d = |row: structural struct{id: int}| -> row.id;
                    val e = (struct{id: int} row) -> row.id;
                    val f = (row: structural struct{id: int}) -> row.id;
                }
                """));
        assertThrows(RuntimeException.class, () -> Parser.parse("""
                fnc exercise(): void {
                    val invalid = |struct{id: int} mut row| -> row.id;
                }
                """));
    }


    @Test
    void bareBraceAnonymousRecordFormsAreAlsoImplicitlyStructural() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc implicit({id: int} row): int { return row.id; }
                fnc redundant(row: structural {id: int}): int { return row.id; }
                fnc run(): int {
                    return implicit(record{id: 1, extra: true})
                         + redundant(infer struct{id: 2, extra: true});
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }


    @Test
    void implicitShapesNeverWidenRawFieldStorageOrReadPrivateClassMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc read(struct{value: float} row): float { return row.value; }
                fnc bad(): float { return read(record{value: 1}); }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Secret as
                  val int id = 1;
                end
                fnc read(struct{id: int} row): int { return row.id; }
                fnc bad(): int { return read(new Secret()); }
                """)));
    }

}
