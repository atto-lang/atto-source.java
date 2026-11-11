package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real guest-source semantic regression, NOT evidence of native guest AOT.
 * Native AOT and every other backend require independent execution receipts.
 */
final class AotDispatchGuestV1Test {
    @Test
    void baseTypedReceiverRetainsVirtualOverrideAndStaticCallsStayQualified()
            throws Exception {
        checkFixture(ExecutionProfile.serverJit());
    }

    @Test
    void sameFixtureRunsWithGuestJitDisabledByProfile() throws Exception {
        ExecutionProfile noGuestJit = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        org.junit.jupiter.api.Assertions.assertFalse(noGuestJit.guestJitAllowed());
        // This tests a JIT-disabled guest execution profile on a JVM; it does
        // NOT claim compiled AttoLang native guest code or Native Image.
        checkFixture(noGuestJit);
    }

    private static void checkFixture(ExecutionProfile profile) throws Exception {
        String source = Files.readString(
                Path.of("contracts/dispatch-v1/virtual_override_121020.atto"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(profile).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "known_and_virtual_v1.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("121020", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scalarGenericIntReuseRunsWithAndWithoutGuestJit() throws Exception {
        String source = Files.readString(
                Path.of("tests/fixtures/dispatch/generic_i64_reuse_v1.atto"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        for (ExecutionProfile profile : new ExecutionProfile[] {
                ExecutionProfile.serverJit(),
                new ExecutionProfile(ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = IsolatePolicy.developer()
                    .restrictedContextBuilder(profile).out(out).build()) {
                context.eval(Source.newBuilder(
                                OresLanguage.ID, source, "generic_i64_reuse_v1.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
            assertEquals("2022", out.toString(StandardCharsets.UTF_8),
                    "generic interpreter semantics must not depend on guest JIT");
        }
    }
}
