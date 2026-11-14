package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.RealtimePrimitiveCompiler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes bytecode emitted from strictly parsed AttoLang source. This proves
 * static classfile lowering, NOT Graal Native Image or a disabled JVM JIT.
 */
final class StrictAttoPrimitiveBytecodeTest {
    @TempDir Path root;

    private static String source(String operator) {
        return "pub fnc check(i32 left, i32 right): bool { return left "
                + operator + " right; }";
    }

    @Test
    void canonicalEqualityAndInequalityEmitStaticPrimitiveClassfiles() throws Exception {
        String[] operators = {"eq", "neq", "!eq"};
        String[] expected = {"true|false", "false|true", "false|true"};
        for (int i = 0; i < operators.length; i++) {
            Path output = root.resolve("compiled" + i);
            var artifact = OresCompiler.compileRealtimePrimitiveStrictAtto(
                    source(operators[i]), "check", 2,
                    new RealtimePrimitiveCompiler.Budget(100, 8), output);

            // Compare canonical paths: the compiler resolves symlinks (macOS /var -> /private/var).
            assertEquals(output.resolve("RtPrimitiveModule.class").toRealPath(), artifact.classFile().toRealPath());
            byte[] bytes = Files.readAllBytes(artifact.classFile());
            assertTrue(bytes.length > 8);
            assertEquals((byte) 0xca, bytes[0]);
            assertEquals((byte) 0xfe, bytes[1]);
            assertEquals((byte) 0xba, bytes[2]);
            assertEquals((byte) 0xbe, bytes[3]);
            assertEquals(0, bytes[6], "Java 8 classfile major version high byte");
            assertEquals(52, bytes[7] & 0xff, "Java 8 classfile major version");
            assertEquals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)),
                    artifact.bytecodeSha256());
            String intermediate = Files.readString(
                    output.resolve("RtPrimitiveModule.java"), StandardCharsets.UTF_8);
            assertTrue(intermediate.contains(i == 0 ? " == " : " != "));
            assertFalse(intermediate.contains(" eq "));
            assertFalse(intermediate.contains(" neq "));
            assertFalse(intermediate.contains(" !eq "));

            // The test harness calls a statically compiled class method.
            // The emitted guest class performs no source interpretation,
            // reflection, dynamic invocation or runtime code generation.
            Path runner = output.resolve("Run.java");
            Files.writeString(runner, """
                    class Run {
                      public static void main(String[] args) {
                        System.out.print(RtPrimitiveModule.check(7,7));
                        System.out.print("|");
                        System.out.print(RtPrimitiveModule.check(7,8));
                      }
                    }
                    """);
            var javac = ToolProvider.getSystemJavaCompiler();
            assertNotNull(javac, "test requires a build JDK");
            assertEquals(0, javac.run(null, null, null, "--release", "8",
                    "-proc:none", "-cp", output.toString(),
                    "-d", output.toString(), runner.toString()));

            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xint", // disable HotSpot JIT: generated guest bytecode is self-contained
                    "-cp", output.toString(), "Run")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                fail("generated Java-bytecode guest timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), stdout);
            assertEquals(expected[i], stdout, operators[i]);
        }
    }

    @Test
    void strictRejectedLegacySourceCannotLeaveCompilationArtifacts() {
        for (String operator : new String[]{"==", "!="}) {
            Path output = root.resolve("legacy" + operator.length() + operator.charAt(0));
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileRealtimePrimitiveStrictAtto(
                            source(operator), "check", 2,
                            new RealtimePrimitiveCompiler.Budget(100, 8), output));
            assertFalse(Files.exists(output.resolve("RtPrimitiveModule.class")));
            assertFalse(Files.exists(output.resolve("RtPrimitiveModule.java")));
        }
    }

    @Test
    void strictNumericMismatchCannotBeConvertedDuringEmission() {
        Path output = root.resolve("mixed");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileRealtimePrimitiveStrictAtto(
                        "pub fnc bad(): bool { return 7 eq 7.0; }",
                        "bad", 0, new RealtimePrimitiveCompiler.Budget(100, 8), output));
        assertFalse(Files.exists(output.resolve("RtPrimitiveModule.class")));
    }
}
