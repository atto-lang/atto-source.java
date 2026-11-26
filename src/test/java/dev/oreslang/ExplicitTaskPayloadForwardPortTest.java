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
    private static final String SOURCE = "pub suspends fnc main(): int { val Future<int> pending = task<int> { return 42; }; return await pending; }";
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
        linkedGuest(SOURCE);
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
    @Test void contextualTaskNameRemainsCallable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("fnc task(int n): int { return n; } pub fnc main(): int { return task(42); }"));
    }
}
