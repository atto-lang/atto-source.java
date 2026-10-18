package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GUEST execution gate for the actual Oreslang ML scalar actor-state fixture.
 *
 * The .ores fixture was copied byte-for-byte from oreslang-ml's stacked
 * actor-state PR #7 (Git blob 5a6c8240ea257b0cee14f0d086ab2a14f0b369d3).
 * This JUnit class requires an actual GraalVM Oreslang context evaluation;
 * pure host-side arithmetic is explicitly NOT accepted as execution evidence.
 *
 * Stacked onto the canonical Java with/using reconciliation branch. Do not
 * infer that LLVM, GPU tensor VJP, or multi-stage actor routing are tested.
 */
final class MlActorStateScalarGuestTest {
    private static final Path FIXTURE =
            Path.of("tests", "fixtures", "ml-actor-state-scalar.ores");

    private static String source() throws Exception {
        return Files.readString(FIXTURE);
    }

    private static void verify(String text) {
        Ast.Program program = Parser.parse(text);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void compiledActorStageHasOneStateSchemaAndDeclaredEffects() throws Exception {
        String text = source();
        assertEquals("5a6c8240ea257b0cee14f0d086ab2a14f0b369d3",
                gitBlobSha(text), "ML fixture drifted from pinned source Git blob");
        assertDoesNotThrow(() -> verify(text));
        assertEquals(1, count(text, "    state {"));
        assertFalse(text.contains("self.state."));
        assertTrue(text.contains("await worker.get_ready();"));
        assertTrue(text.contains("await worker.get_done();"));
        assertFalse(text.contains("await worker.ready;"));
        assertFalse(text.contains("await worker.done;"));
        assertTrue(text.contains("with state.read"));
        assertTrue(text.contains("with state.read, state.write"));
        assertTrue(text.contains("using state.read("));
        assertTrue(text.contains("private eval_affine("));
        assertTrue(text.contains("issued_first: bool"));
        assertTrue(text.contains("issued_second: bool"));
        assertTrue(text.contains("input_first: int"));
        assertTrue(text.contains("input_second: int"));
        assertTrue(text.contains("if upstream < -10000 || upstream > 10000"));
        assertTrue(text.contains("if candidate_weight < -10000 || candidate_weight > 10000"));
        assertTrue(text.contains("if s.step >= 1000000 || s.version >= 1000000"));
        assertTrue(text.contains("s.input_first * upstream"));
        assertTrue(text.contains("s.input_second * upstream"));
        assertFalse(text.contains("private forward("));
        assertTrue(text.contains("using state.write(critical "));
        assertTrue(text.contains("self.handle_state(critical read "));
        assertTrue(text.contains("self.handle_state(critical write "));
    }

    @Test void actualGuestForwardBackwardDuplicateAndCommitMatchPinnedOutput() throws Exception {
        String text = source();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            Source executable = Source.newBuilder(OresLanguage.ID, text,
                    "ml-actor-state-scalar.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            // The real guest actor mailbox, state subsystem and scheduler
            // must execute here. A missing feature fails the test.
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("-6\n-8\n20\n-7\n30\n-8\n2\n-4\n-2\n5\n5\n2\n-1\n50000\n50000\n100000000\n200000000\n-8\n2\n10\n",
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test void readEffectsDoNotGrantStateWriteAndLoanCannotEscape() {
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read {
                        return using state.read(|s| -> {
                            s.weight = 13;
                            return s.weight;
                        });
                    }
                end
                """);
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read {
                        return using state.read(|s| -> {
                            return s;
                        });
                    }
                end
                """);
    }

    @Test void undeclaredWriteEffectCannotBeSmuggledThroughCritical() {
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read {
                        return self.handle_state(critical write |s| -> {
                            s.weight = s.weight + 1;
                            return s.weight;
                        });
                    }
                end
                """);
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read, state.write {
                        return using state.write(|s| -> {
                            s.weight = 42;
                            return s.weight;
                        });
                    }
                end
                """);
    }

    @Test void oneStateOnlyAndDirectStateFieldAccessAreSealed() {
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    state { version: int = 1; }
                end
                """);
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read {
                        return self.state.weight;
                    }
                end
                """);
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    val int extra = 3;
                end
                """);
    }

    @Test void liveLoanCannotCooperateOrAwait() {
        assertRejected("""
                define actor Stage as
                    state { weight: int = 10; }
                    private invalid(): int with state.read {
                        return using state.read(|s| -> {
                            rt cooperate;
                            return s.weight;
                        });
                    }
                end
                """);
    }

    @Test void actualGuestRejectsSealedStateViolationsBeforeRunningMain() throws Exception {
        // Host-only static assertRejected() is not enough: context.eval must
        // itself enforce the same seal and effect checks before guest code
        // executes, or an embedding application could bypass the helper.
        assertGuestRejectedBeforeMain("""
                define actor Stage as
                  state { weight: int = 10; }
                  invalid(): int with state.read {
                    return self.state.weight;
                  }
                end
                pub routine main(): void {
                  stdio.println("UNSAFE_MAIN_EXECUTED");
                  return;
                }
                """);
        assertGuestRejectedBeforeMain("""
                define actor Stage as
                  state { weight: int = 10; }
                  invalid(): int with state.read {
                    return using state.read(|s| -> {
                      s.weight = 13;
                      return s.weight;
                    });
                  }
                end
                pub routine main(): void {
                  stdio.println("UNSAFE_MAIN_EXECUTED");
                  return;
                }
                """);
        assertGuestRejectedBeforeMain("""
                define actor Stage as
                  state { weight: int = 10; }
                  invalid(): int with state.read {
                    return using state.read(|s| -> {
                      return s;
                    });
                  }
                end
                pub routine main(): void {
                  stdio.println("UNSAFE_MAIN_EXECUTED");
                  return;
                }
                """);
    }

    private static void assertGuestRejectedBeforeMain(String invalidSource) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source executable = Source.newBuilder(OresLanguage.ID, invalidSource,
                    "ml-invalid-actor-state.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertThrows(PolyglotException.class, () -> context.eval(executable),
                    "context.eval must enforce actor ownership/effect checks");
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "illegal actor-state program must not execute its main routine");
    }

    private static String gitBlobSha(String text) throws Exception {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("blob " + content.length + "\0")
                .getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(prefix);
        sha.update(content);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void assertRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> verify(text));
    }

    private static int count(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
