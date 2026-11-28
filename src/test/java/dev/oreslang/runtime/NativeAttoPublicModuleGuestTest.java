package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Positive guest proof is required in addition to fail-closed negatives. */
final class NativeAttoPublicModuleGuestTest {
    @Test
    void wildcardNamespaceExportsPublicModuleButNotPrivateModule() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/http-public-module-namespace.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertEquals("true", out.toString(StandardCharsets.UTF_8).trim(),
                () -> "public module guest must actually execute: "
                        + err.toString(StandardCharsets.UTF_8));
    }
}
