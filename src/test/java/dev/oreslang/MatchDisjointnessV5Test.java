package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** V5 proof-mode match conformance: no accidental priority on ordinary match. */
final class MatchDisjointnessV5Test {
    private static final String UNPROVED = "overlapping/unproved explicit patterns";

    private static void accept(String program) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
    }

    private static void rejectOverlap(String program) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains(UNPROVED), error.getMessage());
    }

    private static void guest(String source, String expected) {
        accept(source);
        Source compiled = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "match-proof-v5.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(compiled);
            }
        });
        assertEquals(expected, out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void distinctScalarLiteralArmsExecuteAndPreserveDefaultFallback() {
        guest("""
                pub fnc main(): void {
                    const n = 1;
                    const code = match<int> n over
                        on 0 -> 10;
                        on 1 -> 42;
                        default -> -1;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
    }

    @Test
    void ordinaryValueMatchRejectsOverlappingGuardedAndUnguardedTypePatterns() {
        rejectOverlap("""
                fnc classify(int n): int {
                    return match<int> n over
                        on int x when x > 0 -> 1;
                        on int x -> 2;
                        default -> 0;
                    end;
                }
                """);
    }

    @Test
    void ordinaryStatementMatchRejectsUnprovedOverlapsEvenWithFallback() {
        rejectOverlap("""
                fnc classify(int n): int {
                    match n over
                        on int x when x > 0 -> { return 1; }
                        on int x -> { return 2; }
                        default -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void matchFirstRemainsExplicitPriorityWithGuard() {
        guest("""
                pub fnc main(): void {
                    const n = 5;
                    const code = match<int> first n over
                        on int x when x >= 0 -> 42;
                        on int x when x <= 10 -> 0;
                        default -> -1;
                    end;
                    stdio.println(code);
                    return;
                }
                """, "42\n");
    }

    @Test
    void disjointBoundedGuardsOfSameSubjectAliasAreAdmitted() {
        accept("""
                fnc classify(int n): int {
                    return match<int> n over
                        on int x when x < 0 -> -1;
                        on int x when x >= 0 -> 1;
                        default -> 0;
                    end;
                }
                """);
    }

    @Test
    void hugeI64GuardBoundsNeverCreateRoundedDisjointnessProof() {
        rejectOverlap("""
                fnc classify(int n): int {
                    return match<int> n over
                        on int x when x >= 9007199254740992 -> 1;
                        on int x when x < 9007199254740993 -> 2;
                        default -> 0;
                    end;
                }
                """);
    }

    @Test
    void tuplePatternsDisjointInOnePositionAreAccepted() {
        accept("""
                fnc classify(int n): int {
                    return match<int> (n, true) over
                        on (0, false) -> -1;
                        on (0, true) -> 42;
                        default -> 0;
                    end;
                }
                """);
    }

    @Test
    void tuplePatternsWithOverlappingWildcardsAreRejected() {
        rejectOverlap("""
                fnc classify(int n): int {
                    return match<int> (n, true) over
                        on (0, _) -> 42;
                        on (0, true) -> 0;
                        default -> -1;
                    end;
                }
                """);
    }

    @Test
    void optionConstructorPayloadsCanBeProvablyDisjoint() {
        accept("""
                fnc classify(Option<int> n): int {
                    return match<int> n over
                        on Some(0) -> 1;
                        on Some(1) -> 2;
                        on None -> 3;
                        default -> 4;
                    end;
                }
                """);
    }

    @Test
    void sameConstructorWithOverlappingPayloadIsRejected() {
        rejectOverlap("""
                fnc classify(Option<int> n): int {
                    return match<int> n over
                        on Some(0) -> 1;
                        on Some(0) -> 2;
                        default -> 3;
                    end;
                }
                """);
    }

    @Test
    void mixedRecordFieldAndStructuralTypePatternsFailClosed() {
        rejectOverlap("""
                fnc classify(): int {
                    const row = ~record(status: 200, ready: true);
                    return match<int> row over
                        on ~(status: 200) -> 42;
                        on structural { status: int } shaped -> 0;
                        default -> -1;
                    end;
                }
                """);
    }

    @Test
    void booleanFiniteProofDoesNotNeedWildcard() {
        accept("""
                fnc classify(bool b): int {
                    return match<int> b over
                        on true -> 1;
                        on false -> 0;
                    end;
                }
                """);
    }

    @Test
    void nonDisjointNumberAndEquivalentNumericLiteralAreRejected() {
        rejectOverlap("""
                fnc classify(float n): int {
                    return match<int> n over
                        on 1 -> 1;
                        on 1.0 -> 2;
                        default -> 0;
                    end;
                }
                """);
    }

    @Test
    void forgedAstCannotSilentlyTurnProofMatchIntoFirst() {
        String source = """
                fnc f(int n): int {
                    return match<int> n over
                        on 1 -> 10;
                        on 1 -> 11;
                        default -> 0;
                    end;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        rejectOverlap(source);
    }
}
