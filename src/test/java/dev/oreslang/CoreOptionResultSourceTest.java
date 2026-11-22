package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end admission gate for source-owned core/option and core/result.
 *
 * These tests are intentionally NOT ignored: if generic sum extraction or
 * linked import semantics are unsupported, this draft PR stays red until
 * the compiler enforces the contracts. No host-side mock fallback is allowed.
 */
final class CoreOptionResultSourceTest {
    private static final Path OPTION = Path.of("core/option.atto");
    private static final Path RESULT = Path.of("core/result.atto");

    @Test
    void libraryModulesMustParseAndTypecheck() throws Exception {
        for (Path file : new Path[]{OPTION, RESULT}) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                    () -> "source module was not admitted: " + file);
        }
    }

    @Test
    void linkedGuestMustExecuteNestedSumRoundtrip() throws Exception {
        Path entry = Path.of("tests/core_option_result/scalar_roundtrip.atto")
                .toAbsolutePath();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                stdout,
                stderr);
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n").strip());
    }

    @Test
    void sourceLinkedCallbacksMustSkipInactiveVariants() throws Exception {
        Path entry = Path.of("tests/core_option_result/lazy_callbacks.atto")
                .toAbsolutePath();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                stdout,
                stderr);
        assertEquals("M42:Etrue:true", stdout.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n").strip());
    }

    @Test
    void resultAndOptionMustRemainNominalDisjointVariants() {
        String wrongOption = """
                fnc invalid(): Option<int> {
                  return Err(7);
                }
                """;
        String wrongResult = """
                fnc invalid(): Result<int, int> {
                  return Some(7);
                }
                """;
        for (String source : new String[]{wrongOption, wrongResult}) {
            assertThrows(RuntimeException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    "nominal sum tags must never coerce to the other carrier");
        }
    }

    @Test
    void nestedOptionMustNotImplicitlyUnwrapTwice() {
        String source = """
                fnc invalid(Option<Option<int>> value): int {
                  match value
                    Some(inner) -> { return inner; }
                    None -> { return 0; }
                  end
                }
                """;
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }
}
