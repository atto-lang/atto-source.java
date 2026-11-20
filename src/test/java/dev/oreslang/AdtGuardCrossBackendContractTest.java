package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure ADT semantics shared with Atto Rust/Wasm PR #219.
 * The Rust-only E_UNSUPPORTED_TYPE negatives are intentionally NOT ported:
 * generic Option<bool> and Result<int,int> are valid Java types.
 *
 * These are real Graal guest tests, not host-side pattern emulations.
 */
final class AdtGuardCrossBackendContractTest {
    private static void checked(String source) {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse(source));
            OwnershipChecker.check(typed);
        });
    }

    private static void semanticReject(String source, String diagnostic) {
        assertDoesNotThrow(() -> Parser.parse(source),
                "ADT semantic negative must not count unsupported syntax");
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().toLowerCase().contains(diagnostic),
                "wrong semantic diagnostic: " + error.getMessage());
    }

    private static String guest(String code) throws Exception {
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code,
                    "adt-guard-cross-backend.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void taggedSomeGuardReadsBoundPayloadBeforeGuardAndFallsThrough() throws Exception {
        String code = """
                fnc classify(Option<int> x): int {
                  match x over
                    on Some(n) when n > 10 -> { return 99; }
                    on Some(n) when n > 0 -> { return n + 1; }
                    on Some(n) -> { return -7; }
                    on None -> { return 0; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(classify(Some(4)));
                  stdio.println(classify(Some(-2)));
                  stdio.println(classify(None));
                  return;
                }
                """;
        checked(code);
        assertEquals("5\n-7\n0\n", guest(code));
    }

    @Test
    void resultGuardsReadOnlyMatchingTagAndRetainErrPayload() throws Exception {
        String code = """
                fnc classify(Result<int,string> x): int {
                  match x over
                    on Ok(n) when n > 10 -> { return 99; }
                    on Ok(n) -> { return n + 1; }
                    on Err(e) when e == "retry" -> { return -2; }
                    on Err(e) -> { return -3; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(classify(Ok(4)));
                  stdio.println(classify(Err("retry")));
                  stdio.println(classify(Err("fatal")));
                  return;
                }
                """;
        checked(code);
        assertEquals("5\n-2\n-3\n", guest(code));
    }

    @Test
    void nestedSumGuardDoesNotImplicitlyFlattenAndPreservesOuterNone() throws Exception {
        String code = """
                fnc classify(Option<Result<int,string>> x): int {
                  match x over
                    on Some(Ok(n)) when n > 0 -> { return n + 1; }
                    on Some(Ok(n)) -> { return 0; }
                    on Some(Err(e)) -> { return -1; }
                    on None -> { return -2; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(classify(Some(Ok(7))));
                  stdio.println(classify(Some(Err("failure"))));
                  stdio.println(classify(None));
                  return;
                }
                """;
        checked(code);
        assertEquals("8\n-1\n-2\n", guest(code));
    }

    @Test
    void guardedOnlySomeCannotProveExhaustiveness() {
        semanticReject("""
                fnc invalid(Option<int> x): int {
                  match x over
                    on Some(n) when n > 0 -> { return 1; }
                    on None -> { return 0; }
                  end
                }
                """, "non-exhaustive");
    }

    @Test
    void wrongVariantFamilyNeverCountsAsSemanticCoverage() {
        semanticReject("""
                fnc invalid(Option<int> x): int {
                  match x over
                    on Err(e) -> { return 0; }
                    on _ -> { return 1; }
                  end
                }
                """, "constructor");
    }

    @Test
    void guardMustBeBooleanRatherThanPayloadOrI64() {
        semanticReject("""
                fnc invalid(Option<int> x): int {
                  match x over
                    on Some(n) when n + 1 -> { return n; }
                    on Some(n) -> { return n; }
                    on None -> { return 0; }
                  end
                }
                """, "match guard");
    }

    @Test
    void javaGenericAdtSupersetCannotBeForcedToRustOnlyTypeRejection() {
        checked("""
                fnc classify(Option<bool> x): int {
                  match x over
                    on Some(true) -> { return 1; }
                    on Some(false) -> { return 0; }
                    on None -> { return -1; }
                  end
                }
                fnc classify_result(Result<int,int> x): int {
                  match x over
                    on Ok(n) -> { return n; }
                    on Err(e) -> { return e; }
                  end
                }
                """);
    }
}
