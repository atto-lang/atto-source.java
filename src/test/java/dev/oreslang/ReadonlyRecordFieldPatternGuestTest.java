package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** v4: only statically named readonly record fields with literal/wildcard tests. */
final class ReadonlyRecordFieldPatternGuestTest {
    private static void checkGuest(String program, String expected) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        Source guest = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, program, "readonly-field-match.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                ctx.eval(guest);
            }
        });
        assertEquals(expected, out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void staticReadonlyRecordFieldTestsExecuteInGuest() {
        checkGuest("""
                pub fnc main(): void {
                    const row = ~record(status: 200, ready: true);
                    const code = match<int> row over
                        on ~(status: 404) -> 0;
                        on ~(status: 200, ready: true) -> 42;
                        default -> -1;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
    }

    @Test
    void matchFirstPermitsIntersectingFieldTestsWithSourcePriority() {
        checkGuest("""
                pub fnc main(): void {
                    const row = ~record(status: 200, ready: true);
                    const code = match<int> first row over
                        on ~(ready: true) -> 42;
                        on ~(status: 200) -> 0;
                        default -> -1;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
    }

    @Test
    void explicitStructuralStillCannotGrantFieldsOrBorrowBindings() {
        checkGuest("""
                pub fnc main(): void {
                    const row = ~record(status: 200, ready: true);
                    const code = match<int> row over
                        on structural ~(status: 200, ready: _) -> 42;
                        default -> 0;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
        for (String arm : new String[]{
                "on ~(status: captured) -> 42;",
                "on ~(status: 200, status: 404) -> 42;",
                "on ~(...row) -> 42;",
                "on ~(\"ready\" => true) -> 42;",
                "on ~() -> 42;"}) {
            String source = "fnc f(): int { const row = ~record(status: 200, ready: true); "
                    + "return match<int> row over " + arm + " default -> 0; end; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), arm);
        }
    }

    @Test
    void ordinaryMatchRejectsOverlappingFieldTestsEvenWithDefault() {
        String source = """
                fnc f(): int {
                    const row = ~record(status: 200, ready: true);
                    return match<int> row over
                        on ~(status: 200) -> 42;
                        on ~(ready: true) -> 0;
                        default -> -1;
                    end;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("overlapping/unproved explicit patterns"),
                failure.getMessage());
    }

    @Test
    void unprovedFieldOrDynamicMapDoesNotImpersonateReadonlyRecord() {
        for (String source : new String[]{
                """
                fnc f(): int {
                    const row = ~record(status: 200);
                    return match<int> row over
                        on ~(private_field: 42) -> 1;
                        default -> 0;
                    end;
                }
                """,
                """
                fnc f(): int {
                    const row = ~map<string,int>("status" => 200);
                    return match<int> row over
                        on structural ~(status: 200) -> 1;
                        default -> 0;
                    end;
                }
                """}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
        }
    }

    @Test
    void symbolRecordIdentityIsNotCoercedIntoString() {
        checkGuest("""
                pub fnc main(): void {
                    const row = ~record(state: #ready);
                    const code = match<int> row over
                        on ~(state: #done) -> 0;
                        on ~(state: #ready) -> 42;
                        default -> -1;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
    }

    @Test
    void nominalPublicFieldRequiresExplicitStructuralAndRejectsPrivate() {
        checkGuest("""
                define class Payload as
                    pub val int status = 200;
                    private val int secret = 42;
                end
                pub fnc main(): void {
                    const payload = new Payload();
                    const code = match<int> payload over
                        on structural ~(status: 200) -> 42;
                        default -> 0;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");

        for (String arm : new String[]{
                "on ~(status: 200) -> 42;",
                "on structural ~(secret: 42) -> 42;"}) {
            String source = """
                    define class Payload as
                        pub val int status = 200;
                        private val int secret = 42;
                    end
                    fnc inspect(Payload payload): int {
                        return match<int> payload over
                    """ + arm + " default -> 0; end; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), arm);
        }
    }

    @Test
    void patternAstPreservesOrdinaryAndFirstMode() {
        String base = """
                fnc f(): int {
                    const row = ~record(status: 200);
                    return match<int> %s row over
                        on ~(status: 200) -> 42;
                        default -> 0;
                    end;
                }
                """;
        for (boolean priority : new boolean[]{false, true}) {
            Ast.Program parsed = Parser.parse(base.formatted(priority ? "first" : ""));
            Ast.FunctionDecl f = (Ast.FunctionDecl) parsed.modules().stream()
                    .flatMap(m -> m.declarations().stream())
                    .filter(Ast.FunctionDecl.class::isInstance)
                    .findFirst().orElseThrow();
            Ast.ReturnStmt ret = (Ast.ReturnStmt) f.body().get(1);
            Ast.MatchExpr expr = assertInstanceOf(Ast.MatchExpr.class, ret.value());
            assertEquals(priority, expr.ordered());
            Ast.RecordFieldPattern field = assertInstanceOf(
                    Ast.RecordFieldPattern.class, expr.arms().getFirst().pattern());
            assertEquals("status", field.fields().getFirst().name());
        }
    }
}
