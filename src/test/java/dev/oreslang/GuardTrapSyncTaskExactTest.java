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

    // Exact new corpus from conformance #703; historical fixture pins remain unchanged.
    @Test
    void canonicalStdoutDirectStdio() throws Exception {
        runCanonical("stdio-direct.atto", "fcb8c1b56322b1b068007513cd60791504de5918", "AB");
    }
    @Test
    void canonicalStdoutDirectProcess() throws Exception {
        runCanonical("process-direct.atto", "0d4a07d1c1ccf6c9f1083816a3740091a8467641", "AB");
    }
    @Test
    void canonicalStdoutMixedDefer() throws Exception {
        runCanonical("mixed-defer.atto", "7d1868164d3263bb8158c590a7ad0c36e069bb56", "CBA");
    }
    @Test
    void canonicalStdoutTrappedTask() throws Exception {
        runCanonical("mixed-trapped-task.atto", "5b122c0396240cee7d0be125f952655560b1e78d", "ED");
    }
    private static void runCanonical(String file, String blob, String expected) throws Exception {
        String name = "canonical-stdout-v1-" + file;
        String code = fixture(name, blob);
        OresCompiler.parseAndTypeCheckStrictAtto(code);
        execute(code, name, expected);
    }

    private static void execute(String code, String name) throws Exception {
        execute(code, name, "D");
    }

    private static void execute(String code, String name, String expected) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, code, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(stdout).build()) {
            var result = context.eval(source);
            assertTrue(result.fitsInLong());
            assertEquals(42L, result.asLong());
            assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), stdout.toByteArray());
        }
    }
}
