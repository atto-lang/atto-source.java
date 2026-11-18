package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scalar typing parity contract shared with the native LLVM tests.
 *
 * A declaration annotation (const int x = 7) checks the binding, whereas
 * (const x = 7 as int) explicitly casts the value before inferring x.
 * Neither construct may silently convert bool/string to int.
 */
final class ScalarCastInferenceParityTest {
    private static void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "pub fnc main(): int { " + body + " return 42; }")), body);
    }

    private static void rejects(String body) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main(): int { " + body + " return 42; }")), body);
    }

    @Test void declarationAndExpressionTypeAscriptionsAgreeForScalars() {
        for (String body : new String[] {
                "const int x = 7; const y = 7 as int; const int z = y;",
                "const x: int = 7; const y = 7 as int; const z: int = y;",
                "const x = 7; const int y = x; const z = x as int;",
                "const bool x = true; const y = true as bool; const bool z = y;",
                "const string x = \"a\"; const y = \"a\" as string; const string z = y;",
                "const int x = (2 + 5) as int; const y = (2 + 5) as int;",
                "const bool x = (2 eq 2) as bool; const y = (2 eq 2) as bool;"
        }) accepts(body);
    }

    @Test void inferenceFlowsThroughFunctionsConditionalsAndScopes() {
        for (String body : new String[] {
                "const inferred = (true ? 7 : 8); const int checked = inferred;",
                "const inferred = (true ? 7 : 8) as int; const int checked = inferred;",
                "const x = 7 as int; if true; do const nested = x as int; const int y = nested; fi",
                "let number = 7 as int; number = 9; val int result = number;",
                "const x = 7 as? int; const Option<int> checked = x;"
        }) accepts(body);
    }

    @Test void runtimeConstAndTypeFirstShareFixedBindingSemantics() {
        String accepted = """
                fnc next(int p): int {
                  val int typed = p + 1;
                  const inferred = p + 1;
                  val int annotated = p + 1;
                  return typed + inferred + annotated;
                }
                pub fnc main(): int { return next(13); }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(accepted)));
        for (String source : new String[] {
                "fnc f(int p):int{const x=p+1;x=9;return x;}",
                "fnc f(int p):int{int x=p+1;x=9;return x;}"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
        }
    }

    @Test void typeFirstLocalsAreImmutableByDefault() {
        for (String body : new String[] {
                "int x = 7; const y = x; const int z = y as int;",
                "bool flag = true; const bool same = flag as bool;",
                "string text = \"ok\"; const string same = text as string;",
                "int x = (2 + 5) as int; const int y = x;",
                "int x = 7; const y = x as int; const int z = y;"
        }) accepts(body);

        for (String body : new String[] {
                "int x = 7; x = 8;",
                "bool flag = true; flag = false;",
                "string text = \"a\"; text = \"b\";",
                "int x = 7; rt mut borrow x;",
                "bool flag = true; rt mut borrow flag;",
                "int x = true;",
                "bool flag = 7;",
                "string text = 7;"
        }) rejects(body);
    }

    @Test void invalidCastsAndIncompatibleInferredBindingsAreRejected() {
        for (String body : new String[] {
                "const x = 7 as bool;",
                "const x = true as int;",
                "const x = \"7\" as int;",
                "const x = 7 as string;",
                "const x = 7 as? bool;",
                "const int x = true;",
                "const bool x = 7;",
                "const int x = 7 as bool;",
                "const x = false; const int y = x;",
                "const x = 7 as MissingNominalType;"
        }) rejects(body);
    }

    @Test void inferredConstDoesNotGainMutationCapability() {
        rejects("const x = 7 as int; x = 8;");
        rejects("const int x = 7; x = 8;");
    }

    @Test void checkedCastsDoNotMaskTypeInferenceInComposedExpressions() {
        for (String body : new String[] {
                "const x = 7; const y = x as int; const int z = y + 35;",
                "const x = (2 * 3 + 1) as int; const int y = x;",
                "const x = 7 as int as int; const int y = x;",
                "const yes = true; const bool same = yes as bool;",
                "const no = false as bool; const bool same = no;",
                "const answer = (7 + 35) as int; const int same = answer;",
                "const x = true ? 7 : 8; const y = x as int; const int z = y;",
                "const x = true ? true : false; const bool y = x as bool;",
                "const x = tuple (7, true); val int y = x[0] as int;",
                "const x = tuple (7, true); val bool y = x[1] as bool;",
                "const x = infer struct { number: 7 }; val int y = x.number as int;",
                "const x = infer struct { flag: true }; val bool y = x.flag as bool;",
                "val x = 7; val int y = x as int;",
                "let x = 7; x = 8; val int y = x as int;",
                "const x = 7 as? int; const Option<int> value = x;"
        }) accepts(body);
    }

    @Test void unrelatedAndUnsupportedConversionsCannotBeSilentlyInferred() {
        for (String body : new String[] {
                "const x = 7 as bool as int;",
                "const x = false as int as bool;",
                "const x = true as string;",
                "const x = \"false\" as bool;",
                "const int x = false as bool;",
                "const bool x = 7 as int;",
                "const x = true; const int y = x;",
                "const x = 7; const bool y = x;",
                "const x = 7; const string y = x;",
                "const x = 7 as int; const bool y = x;",
                "const x = 7 as? int; const int y = x;",
                "const x = 7 as? int; const bool y = x;",
                "const x = true as? int;",
                "const x = \"7\" as? int;",
                "let x = true; x = 7;"
        }) rejects(body);
    }

    @Test void aTypeAnnotationAndCastMustAgreeAtFunctionBoundaries() {
        String accepted = """
                fnc increment(int value): int { return value + 1; }
                pub fnc main(): int {
                  val int first = increment(6);
                  const second = increment(6) as int;
                  const inferred = increment(6);
                  val int result = first + second + inferred;
                  return result;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(accepted)));
        String rejected = """
                fnc increment(int value): int { return value + 1; }
                pub fnc main(): int {
                  const inferred = increment(6);
                  const bool wrong = inferred as bool;
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(rejected)));
    }

    @Test void fiveBackendCommonCheckedIntCastExpressionIsNotABitcast() {
        // Shared five-backend source grammar. Typechecking here is not a
        // claim that LLVM, Wasm, Cranelift or .NET guests executed.
        for (String source : new String[] {
                "pub fnc main():int{return (7 as int)+35;}",
                "pub fnc main():int{return (7 as int as int)+35;}"
        }) {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), source);
        }
        for (String source : new String[] {
                "pub fnc main():int{return 7 as bool;}",
                "pub fnc main():int{return true as int;}",
                "pub fnc main():int{return 9223372036854775808 as int;}"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
        }
    }

    @Test void fiveBackendNestedCastAndInferenceAdversarialMatrix() {
        // Sources match the LLVM/Wasm native guest matrix. This check establishes
        // Java typechecker admission, not an executed five-backend result.
        for (String body : new String[] {
                "const x = (7 as int) + (35 as int);",
                "const x = ((2 + 5) as int) * 6;",
                "const x = (7 as int as int) * 6;",
                "const x = 7 as int; const y = x as int; const z = y * 6;"
        }) accepts(body);
        for (String body : new String[] {
                "const x = (7 as int) as bool;",
                "const x = (false as bool) as int;",
                "const x = (7 as int) as? int; const int wrong = x;",
                "const x = (7 as int) + true;"
        }) rejects(body);
    }

    @Test void pinnedFiveBackendCastFixturesAreByteIdenticalAndTypechecked() throws Exception {
        // Source bytes, not a backend-specific reformatting, are shared with
        // LLVM/Wasm/.NET/Cranelift candidates 45–49. This checks Java's
        // compiler frontend only; it is NOT five-backend guest execution.
        String[][] cases = {
                {"45-cast-int-literal.ores", "69cecd231d32bf70ea36424e59c932861d01fe44"},
                {"46-chained-cast-int.ores", "b088dd3f5e3114be4affbb4935582b58f010d75f"},
                {"47-grouped-cast-multiply.ores", "9ebc3f7bc230557fe2f2639172883bb2f4589464"},
                {"48-readonly-local-cast.ores", "87d0773504e0d22fc3cf1ee76755b36a2fc415c6"},
                {"49-const-typed-identity.ores", "bff7c6070008c5c6a8b3316e8a289f93d3f8c5b3"}
        };
        for (String[] testCase : cases) {
            String filename = testCase[0];
            byte[] bytes;
            try (InputStream resource =
                         ScalarCastInferenceParityTest.class.getResourceAsStream("/parity/" + filename)) {
                assertNotNull(resource, "missing native Oreslang guest: " + filename);
                bytes = resource.readAllBytes();
            }
            assertTrue(bytes.length > 0 && bytes.length < 8192, filename);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            sha1.update(bytes);
            assertEquals(testCase[1], HexFormat.of().formatHex(sha1.digest()),
                    "Git blob drift for shared native guest " + filename);
            String oreslang = new String(bytes, StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(oreslang)), filename);
        }
    }

    @Test void immutableSnapshotsSurviveExplicitLetRebinding() {
        // These six programs match the native LLVM, wasm32, .NET and
        // independent Cranelift scalar SSA conformance candidates.
        for (String body : new String[] {
                "let x=7; x=x+1; x=x+34; val int result=x;",
                "let x=7; const snap=x; x=42; val int result=snap+35;",
                "let x=7; val snap=x; x=42; val int result=snap+35;",
                "let x=7; const snap=x as int; x=42; val int result=snap+35;",
                "let x=7 as int; x=(x+35) as int; val int result=x;",
                "const base=7; let x=base; x=42; val int result=x;"
        }) accepts(body);
        rejects("let x=7; const snap=x; x=42; snap=8;");
        rejects("let x=7; val snap=x; x=42; snap=8;");
        rejects("let x=7 as int; x=true;");
        rejects("const x=7; x=42 as int;");
    }

    @Test void graalGuestPreservesImmutableSnapshotAcrossLetWrite() throws Exception {
        String source = """
                pub fnc main(): void {
                  let x = 7;
                  const fixed = x as int;
                  x = 42;
                  stdio.println(fixed + 35);
                  stdio.println(x);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "ssa-snapshot.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(program));
        }
        String printed = output.toString(StandardCharsets.UTF_8);
        assertEquals("42\n42", printed.replace("\r\n", "\n").trim(),
                "snapshot read must be independent of later let rebinding");
    }

    @Test void exactFiveBackendCheckedCastSourcesMatchGitBlobs() throws Exception {
        // Compile the identical UTF-8 Oreslang source used by LLVM, Wasm,
        // Cranelift, and .NET. Java typecheck alone is not guest evidence.
        String[][] cases = {
                {"portable-checked-cast-positive-v2.ores",
                        "1a80e07da1289c87d04454866883f4e06cfc8d77", "accept"},
                {"portable-checked-cast-negative-v2.ores",
                        "ecc4ccea80f016243e38f351df60cfad9eb6e136", "reject"}
        };
        for (String[] item : cases) {
            Path sourcePath = Path.of("contracts", "v1", item[0]);
            assertTrue(Files.isRegularFile(sourcePath), "missing native guest " + item[0]);
            byte[] bytes = Files.readAllBytes(sourcePath);
            assertTrue(bytes.length > 0 && bytes.length < 1024, item[0]);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            sha1.update(bytes);
            assertEquals(item[1], HexFormat.of().formatHex(sha1.digest()),
                    "source bytes diverged from other backends: " + item[0]);
            String nativeOreslang = new String(bytes, StandardCharsets.UTF_8);
            if ("accept".equals(item[2])) {
                assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(nativeOreslang)));
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(nativeOreslang)));
            }
        }
    }

    @Test void byteIdenticalFiveBackendFixtureExecutesAsFortyTwo() throws Exception {
        String source = Files.readString(
                Path.of("tests", "scalar-cast-inference-five-backend.ores"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        Source guest = Source.newBuilder(
                        OresLanguage.ID, source, "scalar-cast-inference-five-backend.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            var result = context.eval(guest);
            assertTrue(result.fitsInLong(), "five-backend fixture must return an i64-compatible value");
            assertEquals(42L, result.asLong(), "five-backend fixture guest result");
        }
    }

    @Test void bothFormsProduceTheSameGuestValue() throws Exception {
        String source = """
                pub fnc main(): void {
                  const int annotated = 7;
                  const inferred = 7 as int;
                  const bool flag = true as bool;
                  const text = "ok" as string;
                  stdio.println(annotated + inferred);
                  stdio.println(flag);
                  stdio.println(text);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "scalar-cast-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(program));
        }
        String printed = output.toString(StandardCharsets.UTF_8);
        assertEquals("14\ntrue\nok", printed.replace("\r\n", "\n").trim(),
                "checked casts must produce exact guest values in lexical order");
    }
}
