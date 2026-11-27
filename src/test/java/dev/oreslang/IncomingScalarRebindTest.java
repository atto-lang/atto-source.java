package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Copied scalar formals only. No new loan or mutable referent authority. */
final class IncomingScalarRebindTest {
    @TempDir Path temp;

    @Test void exactSharedScalarParameterFixture() throws Exception {
        Path entry = Path.of("contracts/incoming-scalar-bindings-v1/scalar-name-first.atto");
        byte[] bytes = Files.readAllBytes(entry);
        var hash = java.security.MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        hash.update((byte) 0);
        assertEquals("acd7353fb5b0d17e48dffad206210e770304a2f8", java.util.HexFormat.of().formatHex(hash.digest(bytes)));
        var result = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(23, result.exitStatus());
    }

    @Test void exactSharedMatchAliasFixtures() throws Exception {
        String[][] cases = {
            {"incoming-alias-typed", "a468f12a990b9e228b3dda649ba839526d3a32f9", null},
            {"incoming-alias-rebind", "389e63ac3aea25b4f20d2baac14908d98809b57a", null},
            {"incoming-alias-fixed-reject", "3f8fbcd373405d950a16bcc2779d78d46027f493", "E_IMMUTABLE"},
            {"incoming-alias-type-reject", "f92cd4e128cafdface1a1e0eb8abd3cb0d14698f", "E_TYPE"},
            {"incoming-alias-duplicate-reject", "c35ceb04f96e8aab70efded4d6ba09854167a390", "E_SYNTAX"},
        };
        for (String[] row : cases) {
            Path entry = Path.of("contracts/incoming-scalar-bindings-v1", row[0] + ".atto");
            byte[] bytes = Files.readAllBytes(entry);
            var hash = java.security.MessageDigest.getInstance("SHA-1");
            hash.update(("blob " + bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            hash.update((byte) 0);
            assertEquals(row[1], java.util.HexFormat.of().formatHex(hash.digest(bytes)), row[0]);
            if (row[2] == null) {
                var result = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
                assertEquals(42, result.exitStatus(), row[0]);
            } else {
                var ex = assertThrows(RuntimeException.class,
                        () -> LinkedProgramRunner.validate(entry), row[0]);
                assertTrue(ex.getMessage().contains(row[2]), ex.getMessage());
            }
        }
    }

    @Test void realGuestPreservesCallerSlot() throws Exception {
        for (String formal : new String[] {"rebind int n", "rebind n: int"}) {
            Path entry = temp.resolve("main.atto");
            Files.writeString(entry, "fnc bump(" + formal + "): int { n = n + 2; return n; } "
                    + "pub fnc main(): int { val int n = 20; return bump(n) + n; }");
            var result = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(), formal);
        }
    }

    private static void check(String source) {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
    }

    @Test void typedAndNameFirstScalarFormals() {
        for (String formal : new String[] {"rebind int n", "rebind n: int"}) {
            assertDoesNotThrow(() -> check("fnc bump(" + formal + "): int { n = n + 2; return n; } "
                    + "pub fnc main(): int { const int n = 20; return bump(n) + n; }"));
        }
        assertDoesNotThrow(() -> check("fnc flip(rebind bool b): bool { b = !b; return b; }"));
    }

    @Test void fixedAndUnsupportedFormalsFailClosed() {
        var fixed = assertThrows(IllegalArgumentException.class,
                () -> check("fnc bump(int n): int { n = n + 2; return n; }"));
        assertTrue(fixed.getMessage().contains("E_IMMUTABLE_REBIND"), fixed.getMessage());
        for (String formal : new String[] {"rebind mut int n", "rebind borrow int n", "rebind Option<int> n",
                "rebind rebind int n", "rebind int mut n"}) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(" + formal + "): void { return; }"));
            assertTrue(ex.getMessage().contains("E_REBIND_"), ex.getMessage());
        }
    }

    @Test void contextualIdentifierRemainsLegal() {
        assertDoesNotThrow(() -> check("fnc f(int rebind): int { return rebind; }"));
        assertDoesNotThrow(() -> check("fnc f(rebind: int): int { return rebind; }"));
    }
}
