package dev.oreslang;

import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-owned .atto parity candidates. Source hash checks are not compiled
 * guest evidence. Positive dynamic tests actually invoke the Java compiler's
 * linked-source admission, not a Java host arithmetic reference implementation.
 */
final class AttoScalarV1GuestTest {
    private record Fixture(String id, String kind, String blob) {}
    private static final Path ROOT=Path.of("").toAbsolutePath().normalize();
    private static final Fixture[] CASES={
        new Fixture("canonical-add", "positive", "015f8b0f79aafa796a148d2adeff1574e8d6c491"),
        new Fixture("precedence", "positive", "50414e5e95c8007f066696f55aa75309555b83e2"),
        new Fixture("parentheses", "positive", "9f508169c741486d5cec7510ac84fb761f342def"),
        new Fixture("signed-minimum", "positive", "450ebee951733d3f0b567413e70724155d26ce13"),
        new Fixture("signed-max-minus-one", "positive", "c400f440fde2fbc10514ab7cfd2ffe3d19729ee1"),
        new Fixture("type-first-readonly", "positive", "75ff4afc98fc65a774611eb7686e893f3d28a6da"),
        new Fixture("typed-const-readonly", "positive", "0325018c54dd3534ddac76dc29724aa61e979683"),
        new Fixture("inferred-const-readonly", "positive", "4fe471862d90ba57e135b93728bd44690ff2c894"),
        new Fixture("pure-call", "positive", "e5ab32f7d8f684d655a83a3df989f16c52090c05"),
        new Fixture("type-first-parameter", "positive", "3d6c16ed04aa86f954461885ec77c8f10b47fb5c"),
        new Fixture("checked-add-overflow", "negative", "eed3113842125b1d2a295a11e3cc01a5c1e3da00"),
        new Fixture("checked-sub-overflow", "negative", "3f80e2eff35147984b040eb0a0215b439d20d09b"),
        new Fixture("checked-mul-overflow", "negative", "e608942c7e84fddb6ad7ca119a4a11d88aa9434b"),
        new Fixture("type-first-reassignment", "negative", "b118f53e68565dcb9e6acd58deb8ee47dca67606"),
        new Fixture("typed-const-reassignment", "negative", "784ebd47a50211e6f00cb732cc74319ed1e58fac"),
        new Fixture("eager-unused-overflow", "negative", "ff0c520f2bc2efcc3daf032eb61fddc8f0c91d48"),
        new Fixture("removed-modulo", "negative", "45d5794870e93074d9b11b90833a849e96aaa4c1"),
        new Fixture("removed-caret", "negative", "6889c7e83c8d94b906fbfe78723caee9580da6aa"),
        new Fixture("removed-double-caret", "negative", "7ee99948018e927cce74c9ad0edc1975e4251689")
    };

    private static String gitBlob(byte[] bytes) {
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-1");
            sha.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
            sha.update(bytes);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Path reviewed(String rel) throws IOException {
        Path file=ROOT;
        for (String part : rel.split("/")) {
            assertFalse(part.isEmpty() || part.equals(".") || part.equals(".."),
                    "unsafe Atto source path");
            file=file.resolve(part);
            assertFalse(Files.isSymbolicLink(file), "symlinked Atto source: "+rel);
        }
        assertTrue(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),
                "missing reviewed Atto source: "+rel);
        return file;
    }

    private static Path source(Fixture f) throws IOException {
        Path p=reviewed("contracts/atto-scalar-v1/"+f.kind()+"/"+f.id()+".atto");
        byte[] bytes=Files.readAllBytes(p);
        assertTrue(bytes.length>0 && bytes.length<=8192, "source budget: "+f.id());
        assertEquals(f.blob(),gitBlob(bytes), "modified reviewed .atto file: "+f.id());
        return p;
    }

    @Test
    void allNineteenExactSourcesAndManifestArePinnedWithoutGuestClaims() throws Exception {
        Path m=reviewed("contracts/atto-scalar-v1/cases.json");
        assertEquals("43c4d295a237e483f70722bd90ec10d273b73854",
                gitBlob(Files.readAllBytes(m)), "noncanonical common-source inventory");
        assertEquals(19,CASES.length);
        long positives=Stream.of(CASES).filter(c->c.kind().equals("positive")).count();
        assertEquals(10,positives);
        assertEquals(9,CASES.length-positives);
        for (Fixture c:CASES) source(c);
    }

    @TestFactory
    Stream<DynamicTest> positiveCanonicalAttoSourceCompilerAdmission() {
        return Stream.of(CASES).filter(c->c.kind().equals("positive"))
                .map(c->DynamicTest.dynamicTest("java-atto-positive-"+c.id(),()->{
                    Path entry=source(c);
                    var compiled=LinkedProgramRunner.validate(entry,Map.of());
                    assertNotNull(compiled);
                    assertEquals(1,compiled.units().size(),"linked source units: "+c.id());
                }));
    }


    /**
     * Eight results fit the linked runner's 0..255 host exit-status ABI.
     * This is actual .atto -> linked Truffle guest execution, not a host
     * arithmetic reference. Full signed-i64 extremes require a typed guest
     * return/stdio witness and must NOT be reported from process exit codes.
     */
    @TestFactory
    Stream<DynamicTest> executeEightCanonicalAttoGuestsAndObserveReturnStatus() {
        Map<String, Integer> expected = Map.of(
                "canonical-add", 42,
                "precedence", 14,
                "parentheses", 20,
                "type-first-readonly", 42,
                "typed-const-readonly", 42,
                "inferred-const-readonly", 42,
                "pure-call", 42,
                "type-first-parameter", 42);
        assertEquals(8, expected.size(), "bounded Java guest witness scope");
        return Stream.of(CASES).filter(c -> expected.containsKey(c.id()))
                .map(c -> DynamicTest.dynamicTest("java-atto-exec-" + c.id(), () -> {
                    Path entry = source(c); // verified byte-for-byte before execution
                    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    LinkedProgramRunner.RunResult actual = LinkedProgramRunner.runWithExitStatus(
                            entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                            stdout, stderr);
                    assertNotNull(actual.build(), "missing linked guest build: " + c.id());
                    assertEquals(1, actual.build().units().size(), "unexpected source closure");
                    assertEquals(expected.get(c.id()).intValue(), actual.exitStatus(),
                            "actual Truffle guest return value: " + c.id());
                    assertEquals("", stdout.toString(StandardCharsets.UTF_8),
                            "unexpected guest stdout: " + c.id());
                }));
    }

    @TestFactory
    Stream<DynamicTest> removedOperatorsAndReadonlyRebindingFailBeforeGuestExecution() {
        return Stream.of(CASES).filter(c->c.kind().equals("negative") &&
                (c.id().startsWith("removed-")||c.id().endsWith("-reassignment")))
                .map(c->DynamicTest.dynamicTest("java-atto-deny-"+c.id(),()->{
                    Path entry=source(c);
                    RuntimeException e=assertThrows(RuntimeException.class,
                            ()->LinkedProgramRunner.validate(entry,Map.of()));
                    assertNotNull(e.getMessage(),"missing compiler diagnostic: "+c.id());
                    assertFalse(e.getMessage().isBlank(),"blank compiler diagnostic: "+c.id());
                }));
    }
}
