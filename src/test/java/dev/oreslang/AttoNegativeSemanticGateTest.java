package dev.oreslang;

import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed, compiler-backed negative admission. Each .atto fixture matches
 * the native LLVM source blob, but this Java test is not native/LLVM parity.
 * A generic parser error or an unresolved symbol MUST NOT be credited as the
 * intended effect, binding, or operator diagnostic.
 */
final class AttoNegativeSemanticGateTest {
    private record Case(String id, String path, String blob) {}

    private static final Case[] CASES = {
        new Case("removed-modulo", "contracts/atto-scalar-v1/negative/removed-modulo.atto",
                "45d5794870e93074d9b11b90833a849e96aaa4c1"),
        new Case("removed-caret", "contracts/atto-scalar-v1/negative/removed-caret.atto",
                "6889c7e83c8d94b906fbfe78723caee9580da6aa"),
        new Case("removed-double-caret", "contracts/atto-scalar-v1/negative/removed-double-caret.atto",
                "7ee99948018e927cce74c9ad0edc1975e4251689"),
        new Case("type-first-reassignment", "contracts/atto-scalar-v1/negative/type-first-reassignment.atto",
                "b118f53e68565dcb9e6acd58deb8ee47dca67606"),
        new Case("typed-const-reassignment", "contracts/atto-scalar-v1/negative/typed-const-reassignment.atto",
                "784ebd47a50211e6f00cb732cc74319ed1e58fac"),
        new Case("legacy-async-modifier", "tests/formal-policy-atto/negative/legacy-async-modifier.atto",
                "26d392c8f609a730e81b503d9495de7e29b46755"),
        new Case("critical-suspends-modifier", "tests/formal-policy-atto/negative/critical-suspends-modifier.atto",
                "6be781ff89f9ebc48ee5870d3401943d50b0f9db"),
        new Case("suspends-critical-modifier", "tests/formal-policy-atto/negative/suspends-critical-modifier.atto",
                "c8bb7c921749f06e9d187a6dfbd71685952e3d88")
    };

    // Same reviewed source blobs as LLVM. These are linked-source positive
    // controls, not JIT/native execution or proof of no process GC.
    private static final Case[] POSITIVES = {
        new Case("canonical-add", "contracts/atto-scalar-v1/positive/canonical-add.atto",
                "015f8b0f79aafa796a148d2adeff1574e8d6c491"),
        new Case("pure-call", "contracts/atto-scalar-v1/positive/pure-call.atto",
                "e5ab32f7d8f684d655a83a3df989f16c52090c05"),
        new Case("typed-const-readonly", "contracts/atto-scalar-v1/positive/typed-const-readonly.atto",
                "0325018c54dd3534ddac76dc29724aa61e979683"),
        new Case("inferred-const-readonly", "contracts/atto-scalar-v1/positive/inferred-const-readonly.atto",
                "4fe471862d90ba57e135b93728bd44690ff2c894"),
        new Case("type-first-parameter", "contracts/atto-scalar-v1/positive/type-first-parameter.atto",
                "3d6c16ed04aa86f954461885ec77c8f10b47fb5c"),
        new Case("type-first-readonly", "contracts/atto-scalar-v1/positive/type-first-readonly.atto",
                "75ff4afc98fc65a774611eb7686e893f3d28a6da")
    };

    @TestFactory
    Stream<DynamicTest> canonicalPositiveControlsRemainAdmitted() {
        return Stream.of(POSITIVES).map(c -> DynamicTest.dynamicTest("java-atto-positive-" + c.id(), () -> {
            Path source = fixture(c);
            var compiled = assertDoesNotThrow(() -> LinkedProgramRunner.validate(source, Map.of()),
                    "valid canonical source was rejected: " + c.id());
            assertNotNull(compiled);
            assertEquals(1, compiled.units().size(), "exact linked source: " + c.id());
        }));
    }

    @Test
    void positiveControlsAreUniqueAndIndependentlyBlobPinned() throws Exception {
        assertEquals(6, POSITIVES.length);
        Set<String> ids = new HashSet<>();
        for (Case c : CASES) assertTrue(ids.add(c.id()), "duplicate negative: " + c.id());
        for (Case c : POSITIVES) {
            assertTrue(ids.add(c.id()), "positive/negative name collision: " + c.id());
            assertTrue(c.path().contains("/positive/"), "positive in negative directory: " + c.id());
            fixture(c);
        }
    }

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(bytes);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static Path fixture(Case c) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path file = root;
        for (String segment : c.path().split("/")) {
            assertFalse(segment.isBlank() || segment.equals(".") || segment.equals(".."),
                    "unsafe fixture segment: " + c.id());
            file = file.resolve(segment);
            assertFalse(Files.isSymbolicLink(file), "symlinked fixture: " + c.id());
        }
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "fixture missing: " + c.id());
        byte[] contents = Files.readAllBytes(file);
        assertTrue(contents.length > 0 && contents.length <= 8192, "fixture byte budget: " + c.id());
        assertEquals(c.blob(), gitBlob(contents), "cross-backend source drift: " + c.id());
        return file;
    }

    @Test
    void allEightCasesAreUniqueAndBlobPinned() throws Exception {
        assertEquals(8, CASES.length);
        Set<String> ids = new HashSet<>();
        for (Case c : CASES) {
            assertTrue(ids.add(c.id()), "duplicate semantic case: " + c.id());
            assertTrue(Files.readString(fixture(c), StandardCharsets.UTF_8).contains("fnc"),
                    "invalid negative fixture: " + c.id());
        }
    }

    private static boolean any(String actual, String... terms) {
        for (String term : terms) if (actual.contains(term)) return true;
        return false;
    }

    private static boolean wholeWord(String value, String word) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9_])"
                + java.util.regex.Pattern.quote(word) + "(?![a-z0-9_])")
                .matcher(value).find();
    }

    private static boolean intendedDiagnostic(Case c, String message) {
        if (message == null || message.isBlank() || message.length() > 65536) return false;
        String d = message.toLowerCase(Locale.ROOT);
        // No generic lexing/parser failures, missing-symbol diagnostics, or crashes.
        if (any(d, "syntax error", "parse error", "unexpected token",
                "unexpected character", "unsupported character",
                "unexpected const", "unrecognized token", "unresolved identifier",
                "unknown identifier", "internal error", "stack overflow")) return false;
        for (String diagnosticLine : d.split("\\R", -1)) {
            String line = diagnosticLine;
            boolean accepted = switch (c.id()) {
            case "removed-modulo" ->
                    (line.contains("%") || line.contains("modulo"))
                    && line.contains("operator")
                    && any(line, "removed", "unsupported", "forbidden", "not allowed", "invalid");
            case "removed-caret" ->
                    (line.contains("^") || line.contains("caret"))
                    && !line.contains("^^") && !any(line, "double caret", "double-caret")
                    && line.contains("operator")
                    && any(line, "removed", "unsupported", "forbidden", "not allowed", "invalid");
            case "removed-double-caret" ->
                    (line.contains("^^") || any(line, "double caret", "double-caret"))
                    && line.contains("operator")
                    && any(line, "removed", "unsupported", "forbidden", "not allowed", "invalid");
            case "type-first-reassignment", "typed-const-reassignment" ->
                    wholeWord(line, "x")
                    && any(line, "immutable", "readonly", "read-only", "constant binding",
                            "const binding", "const variable")
                    && any(line, "assign", "rebind", "mutation");
            case "legacy-async-modifier" ->
                    wholeWord(line, "async")
                    && any(line, "unsupported", "forbidden", "disallowed",
                            "not allowed", "deprecated", "use suspends", "invalid modifier");
            case "critical-suspends-modifier", "suspends-critical-modifier" ->
                    wholeWord(line, "critical") && wholeWord(line, "suspends")
                    && any(line, "invalid", "forbidden", "disallowed", "cannot",
                            "not allowed", "incompatible", "mutually exclusive");
                default -> false;
            };
            if (accepted) return true;
        }
        return false;
    }

    @Test
    void spoofedOrWrongStageDiagnosticsMustFail() {
        String[] generic = {
                "error: unexpected token critical suspends",
                "error: unsupported character ^^",
                "error: unknown identifier x",
                "error: immutable const unexpected const x",
                "error: unresolved identifier async",
                "error: internal error: invalid critical suspends",
                "error: stack overflow: removed modulo operator",
                "error: critical function is invalid\\nsource: suspends",
                "error: immutable binding\\nsource: x cannot assign"
        };
        for (Case c : CASES) {
            for (String message : generic) {
                assertFalse(intendedDiagnostic(c, message), c.id() + ": " + message);
            }
        }
    }

    @Test
    void eachCaseRequiresItsOwnSpecificSemanticDiagnostic() {
        String[] valid = {
                "error: removed modulo operator %",
                "error: caret ^ operator is forbidden",
                "error: double caret ^^ operator is not allowed",
                "error: cannot assign to immutable binding x",
                "error: cannot reassign const binding x",
                "error: async modifier is forbidden; use suspends",
                "error: invalid combination critical suspends fnc",
                "error: suspends critical fnc is incompatible"
        };
        for (int i = 0; i < CASES.length; i++) {
            assertTrue(intendedDiagnostic(CASES[i], valid[i]), CASES[i].id());
            for (int j = 0; j < CASES.length; j++) {
                if (i != j) {
                    // The two reordered effect declarations intentionally share
                    // a semantic rejection family; all other cases must differ.
                    if ((i >= 6 && j >= 6) || (i >= 3 && i <= 4 && j >= 3 && j <= 4)) continue;
                    assertFalse(intendedDiagnostic(CASES[i], valid[j]),
                            CASES[i].id() + " accepted " + CASES[j].id());
                }
            }
        }
    }

    @Test
    void operatorAndModifierNearMissesDoNotEarnCredit() {
        assertFalse(intendedDiagnostic(CASES[2], "error: unsupported caret ^ operator"));
        assertFalse(intendedDiagnostic(CASES[6], "error: critical function is invalid"));
        assertFalse(intendedDiagnostic(CASES[7], "error: suspends modifier is invalid"));
        assertFalse(intendedDiagnostic(CASES[5], "error: async method symbol unresolved"));
        assertFalse(intendedDiagnostic(CASES[3], "error: unknown x after const"));
        assertFalse(intendedDiagnostic(CASES[3], "error: immutable x in unrelated write scope"));
    }

    @TestFactory
    Stream<DynamicTest> compilerRejectsWithTheIntendedDiagnosticNotGenericSyntaxFailure() {
        return Stream.of(CASES).map(c -> DynamicTest.dynamicTest("java-atto-deny-" + c.id(), () -> {
            Path source = fixture(c);
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.validate(source, Map.of()),
                    "accepted forbidden source: " + c.id());
            String message = error.getMessage();
            assertNotNull(message, "diagnostic missing: " + c.id());
            String lower = message.toLowerCase(Locale.ROOT);
            assertFalse(lower.isBlank(), "blank diagnostic: " + c.id());
            assertTrue(intendedDiagnostic(c, lower),
                    "wrong semantic diagnostic for " + c.id() + ": " + message);
            assertFalse(lower.contains("internal error") || lower.contains("stack overflow"),
                    "compiler crashed on " + c.id() + ": " + message);
            // LinkedProgramRunner.validate only admits/types source. It cannot
            // prove an executable artifact was not published by another route.
        }));
    }
}
