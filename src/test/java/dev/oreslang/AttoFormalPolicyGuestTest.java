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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real compiler-backed Java/Truffle guest check for public AttoLang policy
 * sources. The bounded abstract models and production implementation adapters
 * remain independently owned by ores-otel and opto-sync.
 */
final class AttoFormalPolicyGuestTest {
    @TempDir Path temp;

    // Original immutable public PR-source blobs:
    // ores-otel/ores.otel.log#124 @ 27d10b6e4b80e91fb785aa008b56404b1d04c044
    private static final String OTEL_BLOB = "0388755a1660ed3d9543e1a5db982a9c328b91bc";
    // opto-sync/opto-sync-clients#164 @ 284b2b94c3c62b6581e8e2ddf148b0e874a5e5fb
    private static final String OPTO_BLOB = "50f691b51c782835b26cb7d9cea3db44d7678d41";

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path fixture(String name, String reviewedBlob) throws Exception {
        try (InputStream input = AttoFormalPolicyGuestTest.class.getResourceAsStream(
                "/atto/formal-policies/" + name)) {
            assertNotNull(input, "missing reviewed AttoLang source: " + name);
            byte[] bytes = input.readAllBytes();
            assertTrue(bytes.length > 0 && bytes.length <= 8192, "bounded source: " + name);
            assertEquals(reviewedBlob, gitBlob(bytes), "source identity must match public PR blob");
            Path source = temp.resolve(name);
            Files.write(source, bytes);
            return source;
        }
    }

    private void assertGuestOutput(Path source, String expected) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(source,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), stdout, stderr);
        assertEquals(1, build.units().size(), "exactly one linked AttoLang unit");
        assertEquals(expected, stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).isEmpty(), "guest stderr must be empty");
    }

    @Test
    void otelConservationRunsRealGuestAndRejectsMissingWork() throws Exception {
        Path source = fixture("otel-delivery.atto", OTEL_BLOB);
        assertGuestOutput(source, String.join("\n",
                "delivery|conserved|1",
                "delivery|missing|0",
                "delivery|open|1",
                "delivery|closed|0",
                "delivery|full|0",
                ""));
    }

    @Test
    void optoAckCorrelationRunsRealGuestAndRejectsStaleResponses() throws Exception {
        Path source = fixture("opto-ack.atto", OPTO_BLOB);
        assertGuestOutput(source, String.join("\n",
                "ack|exact|1",
                "ack|stale|0",
                "ack|future|0",
                "ack|uncommitted|0",
                "ack|none|0",
                ""));
    }

    @Test
    void unresolvedGuestPolicySymbolFailsClosedAtCompilerBoundary() throws Exception {
        Path source = fixture("otel-delivery.atto", OTEL_BLOB);
        String original = Files.readString(source, StandardCharsets.UTF_8);
        String mutated = original.replace("conserved(3, 1, 1, 0, 1)",
                "unresolved_formal_policy_symbol(3)");
        assertNotEquals(original, mutated, "negative mutation must affect source");
        Files.writeString(source, mutated, StandardCharsets.UTF_8);
        RuntimeException denied = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.validate(source, Map.of()));
        assertNotNull(denied.getMessage(), "missing compiler diagnostic");
        assertFalse(denied.getMessage().isBlank(), "blank compiler diagnostic");
    }
}
package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual compiler/linked-guest refinement of two independently authored,
 * bounded policy vectors. This is not production adapter or backend parity.
 * The authored source files match the immutable reviewed Git blobs below.
 */
final class AttoFormalPolicyGuestTest {
    @TempDir Path scratch;

    private record Case(String id, String sourceBlob, String stdoutBlob, String unsafeOutput) {}
    private static final Case[] CASES = {
        new Case("otel-delivery", "0388755a1660ed3d9543e1a5db982a9c328b91bc", "c4e6e607cedc1dd890981ff5f76e101f41d016f6", "delivery|missing|1"),
        new Case("opto-ack", "50f691b51c782835b26cb7d9cea3db44d7678d41", "8a9e229a36d840df32d35fd54dc021ac7c9503af", "ack|future|1")
    };

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] reviewedBytes(String name, String expectedBlob) throws Exception {
        String resource = "/atto/formal-policy-v1/" + name;
        byte[] bytes;
        try (InputStream stream = AttoFormalPolicyGuestTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing canonical fixture " + resource);
            bytes = stream.readAllBytes();
        }
        assertTrue(bytes.length > 0 && bytes.length < 8192, "fixture size bound");
        assertEquals(expectedBlob, gitBlob(bytes), "source/contract drift " + resource);
        return bytes;
    }

    private Path stage(String name, byte[] source) throws IOException {
        Path path = scratch.resolve(name);
        Files.write(path, source);
        return path;
    }

    @TestFactory
    Stream<DynamicTest> compileAndRunOriginalPolicyGuestOutput() {
        return Stream.of(CASES).map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            byte[] source = reviewedBytes(c.id() + ".atto", c.sourceBlob());
            byte[] expected = reviewedBytes(c.id() + ".stdout", c.stdoutBlob());
            Path entry = stage(c.id() + ".atto", source);
            assertEquals(1, LinkedProgramRunner.validate(entry, Map.of()).units().size());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    Set.of(), Map.of(), output, errors);
            assertEquals(1, build.units().size(), "linked guest units");
            String actual = output.toString(StandardCharsets.UTF_8);
            assertEquals(new String(expected, StandardCharsets.UTF_8), actual,
                    "real guest stdout must equal independent owner corpus");
            assertFalse(actual.contains(c.unsafeOutput()), "unsafe policy branch observed");
        }));
    }

    @TestFactory
    Stream<DynamicTest> parserRejectsCorruptedPolicySourceBeforeExecution() {
        return Stream.of(CASES).map(c -> DynamicTest.dynamicTest(c.id() + "-negative", () -> {
            String original = new String(
                    reviewedBytes(c.id() + ".atto", c.sourceBlob()), StandardCharsets.UTF_8);
            assertTrue(original.contains("pub routine main(): void {"));
            String corrupted = original.replace("pub routine main(): void {", "pub routine main( {");
            assertNotEquals(original, corrupted);
            Path bad = stage(c.id() + "-corrupt.atto", corrupted.getBytes(StandardCharsets.UTF_8));
            assertThrows(RuntimeException.class, () -> LinkedProgramRunner.validate(bad, Map.of()),
                    "invalid fixture must be rejected by the compiler, not treated as a guest");
        }));
    }
}
