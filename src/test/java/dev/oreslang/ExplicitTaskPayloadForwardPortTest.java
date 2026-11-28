package dev.oreslang;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExplicitTaskPayloadForwardPortTest {
    // Exact shared canonical Future<int> fixture; do not normalize whitespace.
    private static final String SOURCE = "pub suspends fnc main(): int {\n  val Future<int> pending = task<int> { return 42; };\n  return await pending;\n}\n";
    @Test void checkedExplicitPayloadAndContext() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(SOURCE));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("task<int>", "task<bool>")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("Future<int>", "Future<bool>")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("return 42;", "return true;")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("task<int>", "task<Future<int>>")));
    }
    private static void linkedGuest(String source) throws Exception {
        Path file = Files.createTempFile("typed-task-", ".atto");
        try {
            Files.writeString(file, source);
            LinkedProgramRunner.validate(file, Map.of());
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            assertEquals(42, LinkedProgramRunner.runWithExitStatus(file, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err).exitStatus(), err.toString());
        } finally { Files.deleteIfExists(file); }
    }
    @Test void actualIntegerGuest() throws Exception {
        assertEquals("502cee379862a887ebb95938ca21fa1e378a30db6b54db9b63e769e3380a4fd2",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        linkedGuest(SOURCE);
    }
    @Test void directTypedTaskAwaitGuest() throws Exception {
        linkedGuest("pub suspends fnc main(): int { return await task<int> { return 42; }; }");
    }
    @Test void actualStringGuest() throws Exception {
        linkedGuest("""
            pub suspends fnc main(): int {
              val Future<String> pending = task<String> { return "ready"; };
              val String result = await pending;
              if result eq "ready" then return 42; fi
              return 1;
            }
            """);
    }
    @Test void actualBooleanPayloadGuest() throws Exception {
        linkedGuest("""
            pub suspends fnc main(): int {
              val Future<bool> pending = task<bool> { return true; };
              if await pending then return 42; fi
              return 1;
            }
            """);
    }
    @Test void actualVoidPayloadGuest() throws Exception {
        linkedGuest("""
            pub suspends fnc main(): int {
              await task<void> { return; };
              return 42;
            }
            """);
    }
    @Test void explicitPayloadRejectsMissingOrWrongReturnPaths() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub suspends fnc main(): int { return await task<int> { }; }"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub suspends fnc main(): int { return await task<int> { return; }; }"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub suspends fnc main(): int { await task<void> { return 42; }; return 42; }"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub suspends fnc main(): int { return await task<int> { if true then return true; fi return 42; }; }"));
    }
    @Test void contextualTaskNameRemainsCallable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("fnc task(int n): int { return n; } pub fnc main(): int { return task(42); }"));
    }
}
