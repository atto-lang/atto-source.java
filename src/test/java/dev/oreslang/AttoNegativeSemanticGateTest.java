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
    private record Case(String id, String path, String blob,
                        String[] requiredTerms, String[] policyTerms) {}

    private static final Case[] CASES = {
        new Case("removed-modulo", "contracts/atto-scalar-v1/negative/removed-modulo.atto",
                "45d5794870e93074d9b11b90833a849e96aaa4c1",
                new String[]{"%", "modulo"}, new String[]{"operator", "removed", "forbidden", "unsupported operator"}),
        new Case("removed-caret", "contracts/atto-scalar-v1/negative/removed-caret.atto",
                "6889c7e83c8d94b906fbfe78723caee9580da6aa",
                new String[]{"^", "caret"}, new String[]{"operator", "removed", "forbidden", "unsupported operator"}),
        new Case("removed-double-caret", "contracts/atto-scalar-v1/negative/removed-double-caret.atto",
                "7ee99948018e927cce74c9ad0edc1975e4251689",
                new String[]{"^^", "caret"}, new String[]{"operator", "removed", "forbidden", "unsupported operator"}),
        new Case("type-first-reassignment",
                "contracts/atto-scalar-v1/negative/type-first-reassignment.atto",
                "b118f53e68565dcb9e6acd58deb8ee47dca67606",
                new String[]{"x", "binding", "value"},
                new String[]{"immutable", "readonly", "constant binding", "reassign", "cannot assign"}),
        new Case("typed-const-reassignment",
                "contracts/atto-scalar-v1/negative/typed-const-reassignment.atto",
                "784ebd47a50211e6f00cb732cc74319ed1e58fac",
                new String[]{"x", "binding", "const"},
                new String[]{"immutable", "readonly", "constant binding", "reassign", "cannot assign"}),
        new Case("legacy-async-modifier",
                "tests/formal-policy-atto/negative/legacy-async-modifier.atto",
                "26d392c8f609a730e81b503d9495de7e29b46755",
                new String[]{"async"}, new String[]{"unsupported", "forbidden", "disallowed", "modifier", "use suspends"}),
        new Case("critical-suspends-modifier",
                "tests/formal-policy-atto/negative/critical-suspends-modifier.atto",
                "6be781ff89f9ebc48ee5870d3401943d50b0f9db",
                new String[]{"critical", "suspends"}, new String[]{"forbidden", "disallowed", "cannot", "modifier", "invalid"}),
        new Case("suspends-critical-modifier",
                "tests/formal-policy-atto/negative/suspends-critical-modifier.atto",
                "c8bb7c921749f06e9d187a6dfbd71685952e3d88",
                new String[]{"suspends", "critical"}, new String[]{"forbidden", "disallowed", "cannot", "modifier", "invalid"})
    };

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

    private static boolean containsAny(String actual, String[] expected) {
        for (String term : expected) {
            if (term.equals("x") ? actual.matches("(?s).*\\bx\\b.*") : actual.contains(term)) return true;
        }
        return false;
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
            assertTrue(containsAny(lower, c.requiredTerms()),
                    "wrong subject for " + c.id() + ": " + message);
            assertTrue(containsAny(lower, c.policyTerms()),
                    "generic parse failure is not a semantic rejection: " + c.id() + ": " + message);
            assertFalse(lower.contains("internal error") || lower.contains("stack overflow"),
                    "compiler crashed on " + c.id() + ": " + message);
            // LinkedProgramRunner.validate only admits/types source. It cannot
            // prove an executable artifact was not published by another route.
        }));
    }
}
