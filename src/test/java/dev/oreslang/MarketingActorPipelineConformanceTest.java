package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class MarketingActorPipelineConformanceTest {
    private static final Path FIXTURE =
            Path.of("examples/marketing-actor-pipeline-three-stage.ores");
    // Exact git blob recorded in contracts/conformance/language-motion-v1.json.
    private static final String FIXTURE_GIT_BLOB_SHA = "87136de0c131a20ca3891d88f6b69f0d13177b3a";

    private static String program() throws Exception {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    private static String execute(String source) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "marketing-actor-pipeline-three-stage.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void sourceFixtureMatchesPinnedBlobAndSiteTopology() throws Exception {
        byte[] source = Files.readAllBytes(FIXTURE);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] prefix = ("blob " + source.length + "\0").getBytes(StandardCharsets.UTF_8);
        sha1.update(prefix);
        sha1.update(source);
        assertEquals(FIXTURE_GIT_BLOB_SHA, HexFormat.of().formatHex(sha1.digest()));

        String code = program();
        assertEquals(3, code.split("define actor ", -1).length - 1);
        assertTrue(code.contains("pub fnc connect(): ActorStream {"));
        assertTrue(code.contains(
                "return spawn Source() pipe spawn Transform() pipe spawn Sink();"));
        assertTrue(code.contains("receive(ActorMail<PipeMessages> mail): void"));
        assertTrue(code.contains("self.backward(Signal.Result(n + 1));"));
        assertTrue(code.contains("await route.get_done();"));
    }

    @Test
    void evidenceRegistryPinsThisFixtureWithoutClaimingBackendVerification() throws Exception {
        String receipt = Files.readString(
                Path.of("contracts/conformance/language-motion-v1.json"),
                StandardCharsets.UTF_8);
        assertTrue(receipt.contains("\\"source_blob_sha1\\": \\"" + FIXTURE_GIT_BLOB_SHA + "\\""));
        assertTrue(receipt.contains("\\"java_status\\": \\"pending_ci\\""));
        assertTrue(receipt.contains("\\"not_in_main\\": true"));
        assertFalse(receipt.matches("(?s).*\\\"java_status\\\"\\s*:\\s*\\\"verified\\\".*"));
        assertFalse(receipt.matches("(?s).*\\\"llvm_status\\\"\\s*:\\s*\\\"verified\\\".*"));
    }

    @Test
    void infixThreeActorPipelineDeliversForwardAndBackwardAndSettlesDone() throws Exception {
        assertEquals("PIPE_3_RESULT_8", execute(program()));
    }

    @Test
    void staticConstructionHasSameObservableResultAsInfix() throws Exception {
        String code = program().replace(
                "spawn Source() pipe spawn Transform() pipe spawn Sink()",
                "ActorUtils::pipe(spawn Source(), spawn Transform(), spawn Sink())");
        assertNotEquals(program(), code);
        assertEquals("PIPE_3_RESULT_8", execute(code));
    }

    @Test
    void nonActorStageFailsStaticAdmission() throws Exception {
        String malformed = program().replace(
                "spawn Source() pipe spawn Transform() pipe spawn Sink()",
                "spawn Source() pipe 42 pipe spawn Sink()");
        assertNotEquals(program(), malformed);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(malformed)));
    }

    @Test
    void prematureMiddleStageTerminationCannotFalselyCompleteRoute() throws Exception {
        String malformed = program().replace(
                "self.forward(Signal.Data(n + 1));", "self.end();");
        assertNotEquals(program(), malformed);
        assertThrows(PolyglotException.class, () -> execute(malformed),
                "whole-route completion cannot succeed on an incomplete forward/backward exchange");
    }
}
