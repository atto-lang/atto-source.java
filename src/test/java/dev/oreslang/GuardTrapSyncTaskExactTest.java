package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Exact user program. Failure is a parity gap, not an acceptable fallback. */
final class GuardTrapSyncTaskExactTest {
    private static final String NAME = "trap-sync-task-defer.atto";
    private static final String PROCESS_NAME = "trap-sync-task-defer-process.atto";
    private static String fixture(String name, String expectedBlob) throws Exception {
        try (var in = GuardTrapSyncTaskExactTest.class.getResourceAsStream("/guard-condition-list/" + name)) {
            assertNotNull(in);
            byte[] bytes = in.readAllBytes();
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedBlob, HexFormat.of().formatHex(digest.digest(bytes)));
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @Test
    void compatibilityGuestMustPreserveMutationAndRunCleanupOnce() throws Exception {
        String code = fixture(NAME, "bcd5e2692aaaaf4c4c070a5746cd6c3ab9e5082a");
        OresCompiler.parseAndTypeCheck(code);
        execute(code, "guard-trap-sync-task.ores");
    }

    @Test
    void strictAttoMustAdmitAndExecuteExactUserProgram() throws Exception {
        String code = fixture(NAME, "bcd5e2692aaaaf4c4c070a5746cd6c3ab9e5082a");
        OresCompiler.parseAndTypeCheckStrictAtto(code);
        execute(code, NAME);
    }

    @Test
    void compatibilityProcessAliasMustPreserveMutationAndCleanup() throws Exception {
        String code = fixture(PROCESS_NAME, "c511c17818f725c9fc5d52a129ca257790276477");
        OresCompiler.parseAndTypeCheck(code);
        execute(code, "guard-trap-sync-task-process.ores");
    }

    @Test
    void strictAttoProcessAliasMustPreserveMutationAndCleanup() throws Exception {
        String code = fixture(PROCESS_NAME, "c511c17818f725c9fc5d52a129ca257790276477");
        OresCompiler.parseAndTypeCheckStrictAtto(code);
        execute(code, PROCESS_NAME);
    }

    private static void execute(String code, String name) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, code, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(stdout).build()) {
            var result = context.eval(source);
            assertTrue(result.fitsInLong());
            assertEquals(42L, result.asLong());
            assertEquals("D", stdout.toString(StandardCharsets.UTF_8));
        }
    }
}
