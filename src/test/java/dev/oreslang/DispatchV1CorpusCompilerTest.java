package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared five-backend canonical .atto fixtures, not a native guest AOT claim.
 * The virtual override stdout witness is AotDispatchGuestV1Test.
 * Direct, instance and qualified static cases execute here with both
 * guest-JIT-enabled and guest-JIT-disabled profiles. Neither is native AOT.
 */
final class DispatchV1CorpusCompilerTest {
    private static String fixture(String name) throws Exception {
        Path source = Path.of("contracts", "dispatch-v1", name + ".atto");
        assertTrue(Files.isRegularFile(source), "canonical fixture missing: " + source);
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    @Test
    void directFunctionChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("direct_call_42")));
    }

    @Test
    void instanceMethodChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("instance_call_42")));
    }

    @Test
    void qualifiedStaticMethodChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("static_qualified_42")));
    }

    private static void assertNativeAttoGuestReturn42(String name, ExecutionProfile profile)
            throws Exception {
        Path source = Path.of("contracts", "dispatch-v1", name + ".atto")
                .toAbsolutePath();
        assertTrue(Files.isRegularFile(source),
                "canonical guest fixture missing: " + source);
        // This runs the real linked Java/GraalVM AttoLang guest, rather than
        // merely parsing source or simulating the expected result on the host.
        // JIT-disabled means guest policy AOT, NOT native guest machine code.
        var result = LinkedProgramRunner.runWithExitStatus(
                source, IsolatePolicy.developer(), profile,
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus(),
                "guest dispatch must return 42: " + name
                        + " profile=" + profile.mode());
    }

    @Test
    void canonicalDirectInstanceStaticGuestsExecuteWithJitEnabled() throws Exception {
        ExecutionProfile profile = ExecutionProfile.serverJit();
        assertNativeAttoGuestReturn42("direct_call_42", profile);
        assertNativeAttoGuestReturn42("instance_call_42", profile);
        assertNativeAttoGuestReturn42("static_qualified_42", profile);
    }

    @Test
    void canonicalDirectInstanceStaticGuestsExecuteWithGuestJitDisabled() throws Exception {
        ExecutionProfile profile = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        assertFalse(profile.guestJitAllowed(),
                "JIT-disabled guest profile must not silently permit guest JIT");
        assertNativeAttoGuestReturn42("direct_call_42", profile);
        assertNativeAttoGuestReturn42("instance_call_42", profile);
        assertNativeAttoGuestReturn42("static_qualified_42", profile);
    }

    @Test
    void wrongArityIsRejectedByRealCheckedCompiler() throws Exception {
        String source = fixture("wrong_arity_reject");
        assertDoesNotThrow(() -> Parser.parse(source),
                "arity negative must reach semantic checking, not just a syntax rejection");
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "wrong source call arity must not produce a checked program");
        assertThrows(RuntimeException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "direct type-checker gate must also reject wrong arity");
    }
}
