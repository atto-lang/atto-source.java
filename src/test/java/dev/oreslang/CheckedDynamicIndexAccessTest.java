package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckedDynamicIndexAccessTest {

    private static String execute(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "checked-index-access.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void dynamicHomogeneousStructAndTupleReadsExecute() throws Exception {
        String source = """
                pub routine main(): void {
                  const point = infer struct{x: 3, y: 4};
                  const string key = "y";
                  stdio.stdout.write(point[key]);
                  stdio.stdout.write("|");
                  const values = (11, 13);
                  const int index = 0;
                  stdio.stdout.write(values[index]);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("4|11", execute(source));
    }

    @Test
    void narrowedRecordViewRejectsHiddenRuntimeKey() {
        String source = """
                fnc read({x: int} point, string key): int {
                  return point[key];
                }
                pub routine main(): void {
                  const point = struct{x: int, secret: string}{
                    x: 7, secret: "hidden"
                  };
                  stdio.stdout.write(read(point, "secret"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertThrows(PolyglotException.class, () -> execute(source));
    }

    @Test
    void narrowedRecordViewAcceptsExposedRuntimeKey() throws Exception {
        String source = """
                fnc read({x: int} point, string key): int {
                  return point[key];
                }
                pub routine main(): void {
                  const point = struct{x: int, secret: string}{
                    x: 7, secret: "hidden"
                  };
                  stdio.stdout.write(read(point, "x"));
                  return;
                }
                """;
        assertEquals("7", execute(source));
    }

    @Test
    void dynamicClassIndexReadsPublicDataField() throws Exception {
        String source = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                fnc get(string name): int {
                  const instance = new Candidate();
                  return instance[name];
                }
                pub routine main(): void {
                  stdio.stdout.write(get("id"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("9", execute(source));
    }

    @Test
    void dynamicClassIndexRejectsPrivateFieldAndMethod() {
        String typeOnly = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                fnc get(string name): int {
                  const instance = new Candidate();
                  return instance[name];
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(typeOnly)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                typeOnly + "fnc bad(): string { return (new Candidate())[\"secret\"]; }")));
        String runtime = typeOnly + """
                pub routine main(): void {
                  stdio.stdout.write(get("secret"));
                  return;
                }
                """;
        assertThrows(PolyglotException.class, () -> execute(runtime));
        String method = typeOnly + """
                pub routine main(): void {
                  stdio.stdout.write(get("score"));
                  return;
                }
                """;
        assertThrows(PolyglotException.class, () -> execute(method));
    }

    @Test
    void tupleIndexedWriteRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  let mut pair = (1, 2);
                  pair[0] = 3;
                  return;
                }
                """)));
    }

    @Test
    void fieldProofRequiresExactAstIdentity() {
        Ast.IndexExpr first = new Ast.IndexExpr(
                new Ast.NameExpr("data"), new Ast.LiteralExpr("x"));
        Ast.IndexExpr second = new Ast.IndexExpr(
                new Ast.NameExpr("data"), new Ast.LiteralExpr("x"));
        Ast.CheckedIndexKeys.record(first, Set.of("x"));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.require(first, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.require(first, "secret"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.require(second, "x"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.record(first, Set.of("secret")));
    }

    @Test
    void visibleProofSurvivesOptimizerCloneWithoutWidening() {
        Ast.IndexExpr site = new Ast.IndexExpr(
                new Ast.NameExpr("point"), new Ast.NameExpr("field"));
        Ast.CheckedIndexKeys.record(site, Set.of("x"));
        Ast.IndexExpr optimized = new Ast.IndexExpr(
                new Ast.NameExpr("point"), new Ast.NameExpr("field"));
        Ast.CheckedIndexKeys.propagate(site, optimized);
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.require(optimized, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.require(optimized, "secret"));

        Ast.IndexExpr tuple = new Ast.IndexExpr(
                new Ast.NameExpr("values"), new Ast.LiteralExpr(0L));
        Ast.IndexExpr tupleRewritten = new Ast.IndexExpr(
                new Ast.NameExpr("values"), new Ast.LiteralExpr(0L));
        Ast.CheckedIndexKeys.propagate(tuple, tupleRewritten);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.require(tupleRewritten, "x"));
    }

    @Test
    void literalClassFieldAccessChecksVisibilityAndMethodNonReflection() {
        String declarations = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(declarations + """
                fnc ok(): int {
                  const instance = new Candidate();
                  return instance["id"];
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(declarations + """
                fnc bad(): string {
                  const instance = new Candidate();
                  return instance["secret"];
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(declarations + """
                fnc bad(): int {
                  const instance = new Candidate();
                  return instance["score"];
                }
                """)));
    }

    @Test
    void heterogeneousDynamicIndexCannotPretendAllSlotsAreInt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(string key): int {
                      const data = struct{x: int, y: string}{x: 3, y: "boo"};
                      return data[key];
                    }
                    """)));
        assertTrue(!failure.getMessage().isBlank());
    }
}
