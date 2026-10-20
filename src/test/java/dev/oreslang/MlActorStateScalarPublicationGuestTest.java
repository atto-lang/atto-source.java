package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MlActorStateScalarPublicationGuestTest {
    private static final Path FIXTURE =
            Path.of("tests", "fixtures", "ml-actor-state-scalar.ores");
    private static final Path GOLDEN =
            Path.of("tests", "golden", "ml-actor-state-scalar.stdout");

    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void typedTrainingActorUsesDetachedAtomicPublicationContract() throws Exception {
        String source = Files.readString(FIXTURE);
        assertEquals("6e0e44243ea6967eb22e62986112de577280d5b4",
                gitBlobSha(source));
        assertDoesNotThrow(() -> verify(source));

        assertEquals(1, count(source, "    state {"));
        assertTrue(source.contains("define enum TrainingError as"));
        assertTrue(source.contains("Result<int, TrainingError>"));
        assertTrue(source.contains("match problem over"));
        assertTrue(source.contains("await worker.get_ready();"));
        assertTrue(source.contains("await worker.get_done();"));

        assertTrue(source.contains("state_snapshot("));
        assertTrue(source.contains("snapshot_version(snapshot)"));
        assertTrue(source.contains("snapshot_int(snapshot, \"weight\")"));
        assertTrue(source.contains("prepare_scalar_publication("));
        assertTrue(source.contains("publish_scalar_publication(s, publication)"));
        assertTrue(source.contains(
                "private commit(int expected_step, int expected_version, int expected_run, int expected_incarnation, int expected_graph): Result<int, TrainingError> with state.read, state.write, alloc"));
        assertFalse(source.contains("s.weight = candidate_weight;"));
        assertFalse(source.contains("s.version = s.version + 1;"));
    }

    @Test void realGuestPreservesPinnedTrainingBehavior() throws Exception {
        String source = Files.readString(FIXTURE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Source executable = Source.newBuilder(
                    OresLanguage.ID,
                    source,
                    "ml-actor-state-scalar.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build();
            assertDoesNotThrow(() -> context.eval(executable));
        }

        String expected = Files.readString(GOLDEN);
        assertEquals("b602f9821817696f16499af1a74cb22d49a0ecab",
                gitBlobSha(expected));
        assertEquals(expected, output.toString(StandardCharsets.UTF_8));
    }

    @Test void commitSnapshotCapturesEveryFieldItUsesForAdmissionOrPublication()
            throws Exception {
        String source = Files.readString(FIXTURE);
        for (String field : new String[] {
                "run_id", "graph_id", "incarnation", "weight",
                "accumulated_gradient", "pending_gradients", "step",
                "issued_first", "issued_second", "input_first", "input_second",
                "seen_first", "seen_second"
        }) {
            assertTrue(source.contains("\"" + field + "\""),
                    "commit snapshot must include " + field);
        }
        assertTrue(source.contains("\"version\", version + 1"));
        assertTrue(source.contains("\"step\", step + 1"));
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

    private static int count(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
