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
 * The .ores fixture started from oreslang-ml's stacked actor-state PR #7 and
 * is now reconciled with #1547's detached scalar snapshot/publication contract
 * (Git blob a492c9d5a195b2ff244d2441042e1e0e4e297bfc).
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
        assertEquals("a492c9d5a195b2ff244d2441042e1e0e4e297bfc",
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
        assertTrue(text.contains("private print_outcome(Result<int, TrainingError> outcome): void"));
        assertTrue(text.contains("return Err(TrainingError.INVALID_INPUT())"));
        assertTrue(text.contains("return Err(TrainingError.INVALID_GRADIENT())"));
        assertTrue(text.contains("return Err(TrainingError.UNSAFE_CANDIDATE())"));
        assertTrue(text.contains("self.eval_affine(3, 3, 2, -2, 101, 1, 202)"));
        assertTrue(text.contains("issued_first: bool"));
        assertTrue(text.contains("issued_second: bool"));
        assertTrue(text.contains("input_first: int"));
        assertTrue(text.contains("input_second: int"));
        assertTrue(text.contains("if upstream < -10000 || upstream > 10000"));
        assertTrue(text.contains("if candidate_weight < -10000 || candidate_weight > 10000"));
        assertTrue(text.contains("if step >= 1000000 || version >= 1000000"));
        assertTrue(text.contains("state_snapshot("));
        assertTrue(text.contains("snapshot_version(snapshot)"));
        assertTrue(text.contains("prepare_scalar_publication("));
        assertTrue(text.contains("publish_scalar_publication(s, publication)"));
        assertFalse(text.contains("s.weight = candidate_weight"));
        assertFalse(text.contains("s.version = s.version + 1"));
        assertTrue(text.contains("private cancel_rejected("));
        assertTrue(text.contains("define enum TrainingError as"));
        assertTrue(text.contains("match outcome over"));
        assertTrue(text.contains("match problem over"));
        assertTrue(text.contains("on TrainingError.STALE_GRAPH ->"));
        assertTrue(text.contains("on TrainingError.VALID_CANDIDATE_MUST_COMMIT ->"));
        assertTrue(text.contains("run_id: int = 101"));
        assertTrue(text.contains("incarnation: int = 1"));
        assertTrue(text.contains("graph_id: int = 202"));
        assertTrue(text.contains("return Err(TrainingError.STALE_GRAPH())"));
        assertTrue(text.contains("worker.send(\"wrong_graph\")"));
        assertTrue(text.contains("return Err(TrainingError.STALE_RUN())"));
        assertTrue(text.contains("return Err(TrainingError.STALE_INCAR())"));
        assertFalse(text.contains("mail.value == \"cancel_failed\""));
        assertTrue(text.contains("self.cancel_rejected(2, 2, 101, 1, 202)"));
        assertFalse(text.contains("mail.value == \"high_commit\""));
        assertFalse(text.contains("worker.send(\"high_commit\")"));
        assertTrue(text.contains("if !final_gradient.is_err(); do"));
        assertFalse(text.contains("mail.value == \"after\""));
        assertFalse(text.contains("worker.send(\"after\")"));
        assertTrue(text.contains("private demo_finish_ready(): bool with state.read"));
        assertTrue(text.contains("s.issued_first && s.issued_second"));
        assertTrue(text.contains("!final_result.is_err() && self.demo_finish_ready()"));
        assertTrue(text.contains("worker.send(\"wrong_incarnation\")"));
        assertTrue(text.contains("Result<int, TrainingError>"));
        assertTrue(text.contains("return Err(TrainingError.STALE_STEP())"));
        assertTrue(text.contains("return Ok(version + 1)"));
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
        String expected = Files.readString(Path.of("tests", "golden",
                "ml-actor-state-scalar.stdout"));
        assertEquals("b602f9821817696f16499af1a74cb22d49a0ecab",
                gitBlobSha(expected), "scalar training output golden drifted");
        assertEquals(expected, stdout.toString(StandardCharsets.UTF_8));
    }

    @Test void actualGuestTypedResultSeparatesNegativeSuccessAndError() throws Exception {
        // This is deliberately a small independent compiler guest fixture,
        // not a Java host evaluation of the Result numerical semantics.
        String text = """
                pub routine main(): void {
                    val Result<int, String> negative = Ok(-8);
                    val Result<int, String> rejected = Err("INVALID_INPUT");
                    if negative.is_err() || negative.unwrap() != -8; do
                        stdio.println("TYPED_RESULT_FAIL");
                        return;
                    fi
                    if !rejected.is_err(); do
                        stdio.println("TYPED_RESULT_FAIL");
                        return;
                    fi
                    stdio.println("ORES_TYPED_RESULT_PASS");
                    return;
                }
                """;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            Source executable = Source.newBuilder(OresLanguage.ID, text,
                    "ml-typed-result.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("ORES_TYPED_RESULT_PASS\n",
                stdout.toString(StandardCharsets.UTF_8));
    }


    @Test void actualGuestNominalErrorRoundTripAndExhaustiveResultMatch() throws Exception {
        // A positive gate prevents parser errors or blanket enum refusal from
        // being mistaken for successful negative semantic tests below.
        String program = """
                define enum LocalTrainingError as
                    INVALID_INPUT = 1
                    STALE_GRAPH = 2
                end
                pub fnc display(Result<int, LocalTrainingError> result): int {
                    match result over
                        on Ok(value) -> { return value; }
                        on Err(problem) -> {
                            match problem over
                                on LocalTrainingError.INVALID_INPUT -> { return -7; }
                                on LocalTrainingError.STALE_GRAPH -> { return -8; }
                            end
                        }
                    end
                }
                pub routine main(): void {
                    val Result<int, LocalTrainingError> rejected =
                        Err(LocalTrainingError.STALE_GRAPH());
                    val Result<int, LocalTrainingError> negative = Ok(-10);
                    stdio.println(display(rejected));
                    stdio.println(display(negative));
                    return;
                }
                """;
        assertDoesNotThrow(() -> verify(program),
                "closed nominal enum must pass parser/type/effect/ownership checks");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source executable = Source.newBuilder(OresLanguage.ID, program,
                    "ml-nominal-result-positive.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("-8\n-10\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test void nominalTrainingErrorsRequireExactPayloadTypesAndExhaustiveCases() {
        String wrongErrorType = """
                define enum TrainingError as
                    INVALID_INPUT
                end
                pub fnc bad(): Result<int, TrainingError> {
                    return Err("INVALID_INPUT");
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongErrorType),
                "nominal wrong-type negative must parse");
        assertRejected(wrongErrorType);
        String omittedCase = """
                define enum TrainingError as
                    INVALID_INPUT
                    STALE_GRAPH
                end
                pub fnc bad(TrainingError reason): int {
                    match reason over
                        on TrainingError.INVALID_INPUT -> { return 1; }
                    end
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(omittedCase),
                "non-exhaustive negative must parse");
        assertRejected(omittedCase);

        // Identical case names and numeric wire tags do NOT confer type
        // identity. This negative must pass the parser and fail semantically.
        String wrongNominalIdentity = """
                define enum TrainingError as
                    INVALID_INPUT = 1
                end
                define enum ForeignTrainingError as
                    INVALID_INPUT = 1
                end
                pub fnc bad(): Result<int, TrainingError> {
                    return Err(ForeignTrainingError.INVALID_INPUT());
                }
                pub routine main(): void {
                    stdio.println("UNSAFE_MAIN_EXECUTED");
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(wrongNominalIdentity),
                "nominal foreign-type negative must be syntactically valid");
        assertRejected(wrongNominalIdentity);
        assertGuestRejectedBeforeMain(wrongNominalIdentity);
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
                  private invalid(): int with state.read {
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
                  private invalid(): int with state.read {
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
                  private invalid(): int with state.read {
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
        assertGuestRejectedBeforeMain("""
                define actor Stage as
                  state { weight: int = 10; }
                  private invalid(): int with state.read {
                    return self.handle_state(critical write |s| -> {
                      s.weight = s.weight + 1;
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
                  private invalid(): int with state.read {
                    return using state.read(|s| -> {
                      rt cooperate;
                      return s.weight;
                    });
                  }
                end
                pub routine main(): void {
                  stdio.println("UNSAFE_MAIN_EXECUTED");
                  return;
                }
                """);
    }

    @Test void validSameShapeActorCanReachMain() throws Exception {
        // A positive control ensures negative tests do not merely exploit an
        // invalid actor-method grammar or a broken polyglot embedding.
        String program = """
                define actor Stage as
                  state { weight: int = 10; }
                  private value(): int with state.read {
                    return using state.read(|s| -> { return s.weight; });
                  }
                end
                pub routine main(): void {
                  stdio.println("SAFE_MAIN_EXECUTED");
                  return;
                }
                """;
        assertDoesNotThrow(() -> verify(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source executable = Source.newBuilder(OresLanguage.ID, program,
                    "ml-valid-control.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("SAFE_MAIN_EXECUTED\n", output.toString(StandardCharsets.UTF_8));
    }

    private static void assertGuestRejectedBeforeMain(String invalidSource) throws Exception {
        // Reject by the state/effect/ownership admission pipeline, not merely
        // by a parser error; malformed grammar is NOT security test evidence.
        assertDoesNotThrow(() -> Parser.parse(invalidSource),
                "negative fixture must be syntactically valid");
        assertThrows(IllegalArgumentException.class, () -> verify(invalidSource),
                "state/effect/ownership verifier must reject this valid program");
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
