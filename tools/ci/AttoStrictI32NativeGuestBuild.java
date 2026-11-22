import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.RealtimePrimitiveCompiler;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build-time-only, fail-closed compiler driver for the narrow strict i32 subset.
 * Never ship this host compiler driver or an Atto interpreter in the native
 * guest classpath. Production guest code is the emitted RtPrimitiveModule.
 */
public final class AttoStrictI32NativeGuestBuild {
    private AttoStrictI32NativeGuestBuild() { }

    private static RealtimePrimitiveCompiler.Budget budget() {
        return new RealtimePrimitiveCompiler.Budget(1000, 8);
    }

    private static void rejectsWithoutArtifacts(String label, String source,
                                                Path output) throws Exception {
        try {
            OresCompiler.compileRealtimePrimitiveStrictAtto(
                    source, "main", 0, budget(), output);
            throw new AssertionError("strict source unexpectedly accepted: " + label);
        } catch (IllegalArgumentException expected) {
            if (Files.exists(output.resolve("RtPrimitiveModule.class"))
                    || Files.exists(output.resolve("RtPrimitiveModule.java"))) {
                throw new AssertionError("rejected source published artifacts: " + label, expected);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected output directory");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();

        // Exactly-sized integer literal is checked before build-time lowering.
        var artifact = OresCompiler.compileRealtimePrimitiveStrictAtto(
                "pub fnc main(): i32 { return 42; }",
                "main", 0, budget(), output);
        if (!Files.isRegularFile(artifact.classFile())
                || artifact.sourceReport().reachableFunctions() != 1
                || artifact.bytecodeSha256().length() != 64) {
            throw new AssertionError("missing statically checked guest bytecode");
        }
        if (!artifact.classFile().toRealPath().equals(
                output.resolve("RtPrimitiveModule.class").toRealPath())) {
            throw new AssertionError("compiler generated a different bytecode destination");
        }
        System.out.println("CHECKED_GUEST_BYTECODE_SHA256=" + artifact.bytecodeSha256());
        System.out.println("CHECKED_GUEST_ASSURANCE=" + artifact.sourceReport().assurance());

        // Neither unknown host I/O nor untyped legacy equality may bypass
        // the strict frontend into an executable artifact.
        rejectsWithoutArtifacts("ambient-stdio",
                "pub fnc main(): i32 { stdio.println(42); return 42; }",
                output.resolve("reject-io"));
        rejectsWithoutArtifacts("legacy-equality",
                "pub fnc main(i32 x): bool { return x == x; }",
                output.resolve("reject-legacy"));
        System.out.println("STATIC_I32_NATIVE_GUEST_NEGATIVE_ADMISSION=2_FAIL_CLOSED");
    }
}
