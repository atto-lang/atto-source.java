package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Platform-core-v1 is a shared signed-i64 candidate corpus across Java,
 * LLVM, Cranelift and Wasm. This test exercises real Java/Graal source
 * admission and guest evaluation; it is NOT a full backend parity proof.
 * The original exact source is compiler-validated; for full-width stdout,
 * the test wraps the same expression in stdio.stdout.write. That transformed
 * program must NOT be misreported as unchanged-source guest execution.
 */
@Timeout(180)
final class PlatformCoreV1ScalarGuestParityTest {
    private record Case(String name, String blob, String expected) {}
    private static final Path ROOT=Path.of("contracts","platform-core-v1");
    private static final String MANIFEST_BLOB="8d06e0c7dff46253868954c595d2698ed5356e5f";
    private static final Case[] POSITIVE={
        new Case("arith-precedence.ores","14f0a6fde3c55ead28f361da6c0e40279344137a","32"),
        new Case("signed-minimum.ores","450ebee951733d3f0b567413e70724155d26ce13","-9223372036854775808"),
        new Case("max-minus-five.ores","ea1932dd800fdcf3ef00a01549ce6565d7691ade","9223372036854775802"),
        new Case("two-negative-factors.ores","d83e07867159bb3f5b1027a0689f7eeefa84ac88","42"),
        new Case("nested-unary.ores","c6aa564c5bd4ce38c5e5fcc1e96cec8cea58c6d3","42"),
        new Case("negative-product.ores","ba664537993dd3bb808f0d3453bd01f5435cc098","-42"),
        new Case("eager-checked-int.ores","b95fd94f36da98a8972f665eda126b46159de0f9","42")
    };
    private static final Case[] NEGATIVE={
        new Case("reject-unknown-symbol.ores","fb17d2b5a662d435c367c682abbd5af7807f1e48",null),
        new Case("reject-string-as-int.ores","494a5d02d86c7d8f6222c25e1ca33eb7f92c1fba",null),
        new Case("reject-overflow.ores","eed3113842125b1d2a295a11e3cc01a5c1e3da00",null),
        new Case("reject-xor-operator.ores","e7f5e47a28219f1f43cb1e18c7f50d45c089a6c4",null),
        new Case("reject-no-value-return.ores","8cce1a3e24bec592834089d228f1047d61f510f0",null)
    };
    @TempDir Path temp;

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-1");
        sha.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(bytes);
        return HexFormat.of().formatHex(sha.digest());
    }
    private static byte[] exactFile(Path file,String blob) throws Exception {
        assertFalse(Files.isSymbolicLink(ROOT),"symlinked fixture directory");
        assertFalse(Files.isSymbolicLink(file),"symlinked fixture file");
        assertTrue(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"missing canonical fixture: "+file);
        byte[] bytes=Files.readAllBytes(file);
        assertTrue(bytes.length>0 && bytes.length<=8192,"invalid canonical source budget");
        assertEquals(blob,gitBlob(bytes),"cross-backend Git source blob drift: "+file);
        return bytes;
    }
    private static String source(Case fixture) throws Exception {
        return new String(exactFile(ROOT.resolve(fixture.name()),fixture.blob()),StandardCharsets.UTF_8);
    }

    @Test
    void sharedManifestAndAllTwelveExactBlobFingerprintsArePinned() throws Exception {
        byte[] manifest=exactFile(ROOT.resolve("manifest.json"),MANIFEST_BLOB);
        String text=new String(manifest,StandardCharsets.UTF_8);
        assertTrue(text.contains("\"schema\": \"oreslang.platform-core.v1\""));
        assertTrue(text.contains("no backend support is asserted"));
        assertEquals(7,POSITIVE.length);
        assertEquals(5,NEGATIVE.length);
        for(Case c:POSITIVE)assertTrue(source(c).startsWith("pub fnc main(): int { return "));
        for(Case c:NEGATIVE)assertTrue(source(c).startsWith("pub fnc main(): int {"));
    }

    private String transformedStdoutGuest(Case fixture,String original) {
        String prefix="pub fnc main(): int { return ";
        String suffix="; }\n";
        assertTrue(original.startsWith(prefix)&&original.endsWith(suffix),
                "canonical one-line guest shape changed: "+fixture.name());
        String expression=original.substring(prefix.length(),original.length()-suffix.length());
        assertFalse(expression.isBlank()||expression.contains(";"),
                "unexpected multi-statement scalar source");
        return "pub fnc main():void { stdio.stdout.write("+expression+"); return; }\n";
    }

    @TestFactory
    Stream<DynamicTest> javaCompilerAndGraalEvaluateAllSevenFullWidthScalarExpressions() {
        return Stream.of(POSITIVE).map(c->DynamicTest.dynamicTest("java-platform-core-positive-"+c.name(),()->{
            String original=source(c);
            // This is real Java compiler/source validation of the ORIGINAL fixture.
            assertDoesNotThrow(()->OresCompiler.parseAndTypeCheck(original));
            Path entry=temp.resolve(c.name());
            Files.writeString(entry,original,StandardCharsets.UTF_8);
            assertEquals(1,LinkedProgramRunner.validate(entry,Map.of()).units().size());
            String wrapped=transformedStdoutGuest(c,original);
            assertDoesNotThrow(()->OresCompiler.parseAndTypeCheck(wrapped));
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            Source guest=Source.newBuilder(OresLanguage.ID,wrapped,c.name())
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try(Context context=Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()){
                context.eval(guest);
            }
            assertEquals(c.expected(),output.toString(StandardCharsets.UTF_8),
                    "real Graal guest full-width signed-i64 mismatch: "+c.name());
        }));
    }

    @TestFactory
    Stream<DynamicTest> canonicalStaticNegativeSourcesFailCompilerAdmission() {
        return Stream.of(NEGATIVE)
                .filter(c->!c.name().equals("reject-overflow.ores"))
                .map(c->DynamicTest.dynamicTest("java-platform-core-deny-"+c.name(),()->{
                    String original=source(c);
                    RuntimeException error=assertThrows(RuntimeException.class,
                            ()->OresCompiler.parseAndTypeCheck(original),
                            "invalid scalar fixture admitted: "+c.name());
                    assertNotNull(error.getMessage(),"missing compiler diagnostic");
                    assertFalse(error.getMessage().isBlank(),"blank compiler diagnostic");
                }));
    }

    @Test
    void runtimeOverflowMustNotWrapOrReturnNormally() throws Exception {
        String original=source(NEGATIVE[2]);
        // Manifest specifies a runtime checked-overflow boundary rather than
        // accepting an unrelated parser failure as guest trap evidence.
        assertDoesNotThrow(()->OresCompiler.parseAndTypeCheck(original));
        Source guest=Source.newBuilder(OresLanguage.ID,original,"reject-overflow.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try(Context context=Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()){
            assertThrows(RuntimeException.class,()->context.eval(guest),
                    "Java/Graal must not silently return a wrapped i64");
        }
    }
}
