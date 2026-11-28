package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiler-backed Java/Truffle acceptance of source-controlled AttoLang files.
 * Host fixture loading is not itself guest proof: run() must execute the
 * linked .atto program and return its actual stdout.
 */
final class AttoCanonicalFixtureGuestTest {
    @TempDir Path temp;

    private Path stageFixture(String name) throws IOException {
        try (InputStream source = AttoCanonicalFixtureGuestTest.class.getResourceAsStream(
                "/atto/canonical-v1/" + name)) {
            assertNotNull(source, "missing checked-in AttoLang fixture: " + name);
            Path target = temp.resolve(name);
            Files.copy(source, target);
            return target;
        }
    }

    @Test
    void canonicalAttoFixtureParsesTypechecksAndResolvesExtensionlessImport() throws Exception {
        stageFixture("helper.atto");
        Path entry = stageFixture("hello.atto");
        assertEquals(2, LinkedProgramRunner.validate(entry, Map.of()).units().size());
    }

    @Test
    void canonicalAttoFixtureLinksAndExecutesGuestWithExpectedOutput() throws Exception {
        stageFixture("helper.atto");
        Path entry = stageFixture("hello.atto");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, errors);
        assertEquals(2, build.units().size());
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void absentCanonicalAttoHelperFailsClosedBeforeGuestExecution() throws Exception {
        Path entry = stageFixture("hello.atto");
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("does not resolve to a file"));
    }
}
