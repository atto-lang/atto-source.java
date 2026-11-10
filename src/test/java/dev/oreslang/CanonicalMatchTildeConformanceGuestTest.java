package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact-fixture, NON-PROMOTING Java/GraalVM conformance audit.
 *
 * Expected positives compile, typecheck, and execute real Truffle guest main()
 * with integer result 42. Expected negatives fail at parser/type/ownership admission
 * before Context.eval is entered. This does not prove no native IR artifacts,
 * GraalVM Native Image, other backends, or compiler/formatter parity.
 *
 * Source contract: ores-truffle-oreslang/oreslang-platform-conformance @ 2a831f56df6dc92993d2344cbedfe0127b2eeb58
 */
final class CanonicalMatchTildeConformanceGuestTest {
    private record Case(String id, boolean expectedAccept, String sourceGitBlobSha1) {}

    private static final Case[] CASES = new Case[] {
        new Case("typed-scalar-match", true, "a88abd698245c26e9349eac8eb308702cb22ef0c"),
        new Case("first-ordered-overlap", true, "433708965b7b55361bc70af8cd348234ce6153f9"),
        new Case("exhaustive-bool-no-default", true, "5bb74f85865c3a344c96ccf71f0f49581c960740"),
        new Case("short-record", true, "a8546cec560184e2bbd6725dbf43bcfc5faaef6a"),
        new Case("short-contextual-map", true, "470817819d42d3a74ef313f4bfa34611ab328f15"),
        new Case("evaluated-map-key-vs-static-field", true, "942209dcb6764ca6d85e9940713d1c653f9b7b5d"),
        new Case("symbol-map-key", true, "cbce5609e6152c8888d4970050e8d89c7baeb09e"),
        new Case("explicit-empty-kinds", true, "a33b13341dc4a76b24d84b1bc8632583cc739d28"),
        new Case("concrete-struct-preserved", true, "95978b7d71a5c446d4ad653f8b4874e9b3f2832a"),
        new Case("positional-tuple-preserved", true, "f05a235d55773db7b6783cd30d151b1ba5bed50c"),
        new Case("explicit-structural-view", true, "c1e27f3365e3e32e8a3835a1234663c356b6de02"),
        new Case("legacy-record-preserved", true, "37f55f636fba1bc8ade6b96f57c8a86dd1aef048"),
        new Case("legacy-on-default", true, "ae648e3ff414e8f5609f3c03ceb68f4f8b3b602b"),
        new Case("missing-over", false, "bfe0326a18db70e4fb64ca4a1495f09344b8c081"),
        new Case("missing-on", false, "61b717b2df5b76a066272b97aee9f72fee8bd5d6"),
        new Case("duplicate-default", false, "e7aeca9f92afb7ccf844bf8db26bc80eb8d23b22"),
        new Case("default-before-on", false, "80bc36c32dc6b4b2cf7d03bead398952928d6c52"),
        new Case("guarded-default", false, "0554ae58791ec06b89bde9fe2f39362a6bc1e246"),
        new Case("overlap-without-first", false, "e50126cd3c3b8fab0ca9c5439510e2221cc0430c"),
        new Case("nonexhaustive-without-default", false, "2b3693354a497ce4a3fcbd6d2b047f77f28a6824"),
        new Case("mixed-map-and-record", false, "58a755a7e26ceee52a891b7611299cb39ce2d606"),
        new Case("ambiguous-empty-tilde", false, "e70f69ae41f0d583fdec8ac3d552258e80bf325e"),
        new Case("duplicate-record-field", false, "d2d432a47ef8c39f4b077612134f3990bec22d55"),
        new Case("implicit-structural-nominal-view", false, "f3c96094b0a1213d148408d2b80cea2bf1b78f9f"),
        new Case("guarded-legacy-default", false, "1f1f9e6b708c869651905a2bb2d76af1060067b5"),
        new Case("duplicate-literal-pattern", false, "29d725ef0463dc16254394681835da6c561c67e7")
    };

    private static byte[] fixtureBytes(Case c) throws Exception {
        String path = "/atto/canonical-match-tilde/v1/" + c.id() + ".atto";
        try (InputStream input = CanonicalMatchTildeConformanceGuestTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "Missing pinned source fixture " + path);
            byte[] bytes = input.readAllBytes();
            assertTrue(bytes.length > 0 && bytes.length <= 65_536, "Unbounded fixture " + path);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(bytes);
            assertEquals(c.sourceGitBlobSha1(),
                    HexFormat.of().formatHex(digest.digest()), "Source Git blob drift " + path);
            return bytes;
        }
    }

    @TestFactory
    Stream<DynamicTest> executeCanonicalContractCases() {
        assertEquals(26, CASES.length, "The canonical contract inventory must remain complete");
        assertEquals(13, Arrays.stream(CASES).filter(Case::expectedAccept).count());
        return Arrays.stream(CASES).map(testCase -> DynamicTest.dynamicTest(
                testCase.id() + (testCase.expectedAccept() ? " ACCEPT/42" : " REJECT"),
                () -> executeOne(testCase)));
    }

    private static void executeOne(Case testCase) throws Exception {
        byte[] bytes = fixtureBytes(testCase);
        String program = new String(bytes, StandardCharsets.UTF_8);
        if (!testCase.expectedAccept()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program),
                    "Must fail at Java compiler admission before guest execution: " + testCase.id());
            assertNotNull(error.getMessage(), "Missing compiler diagnostic for " + testCase.id());
            assertFalse(error.getMessage().isBlank(), "Blank compiler diagnostic for " + testCase.id());
            return;
        }

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program),
                "Java parse/type/ownership acceptance: " + testCase.id());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, testCase.id() + ".atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            Value result = assertDoesNotThrow(() -> context.eval(source),
                    "Executed Truffle guest must succeed: " + testCase.id());
            assertTrue(result.fitsInInt(), "Guest main() must return int, case=" + testCase.id()
                    + "; result=" + result);
            assertEquals(42, result.asInt(),
                    "Guest main() should return 42, case=" + testCase.id());
        }
    }
}
