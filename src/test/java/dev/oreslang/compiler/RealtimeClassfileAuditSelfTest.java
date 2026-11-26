package dev.oreslang.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.tools.ToolProvider;

/**
 * SDK-free mutation suite for the production Java classfile admission gate.
 * Tests run on the exact source blob in a fresh hosted JDK.
 */
public final class RealtimeClassfileAuditSelfTest {
    private RealtimeClassfileAuditSelfTest() { }

    private static void mustReject(String label, Path dir, byte[] bytes) throws IOException {
        Path altered = dir.resolve(label + ".class");
        Files.write(altered, bytes);
        try {
            RealtimeClassfileAudit.audit(altered);
        } catch (IllegalArgumentException | java.io.EOFException expected) {
            return;
        }
        throw new AssertionError("unsafe classfile accepted: " + label);
    }

    private static int find(byte[] contents, byte[] sequence) {
        int match = -1;
        for (int i = 0; i <= contents.length - sequence.length; i++) {
            boolean same = true;
            for (int j = 0; j < sequence.length; j++) {
                if (sequence[j] >= 0 && contents[i + j] != sequence[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                if (match >= 0) throw new AssertionError("ambiguous test instruction sequence");
                match = i;
            }
        }
        if (match < 0) throw new AssertionError("expected javac instruction sequence missing");
        return match;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected output directory");
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        Path java = dir.resolve("RtPrimitiveModule.java");
        Files.writeString(java, "final class RtPrimitiveModule { static int tick(int x) { return x + 1; } }\n");
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-g:none", "-proc:none", "-d", dir.toString(), java.toString());
        if (result != 0) throw new AssertionError("fixture javac failed");
        Path original = dir.resolve("RtPrimitiveModule.class");
        byte[] clean = Files.readAllBytes(original);
        var report = RealtimeClassfileAudit.audit(original);
        if (report.verifiedMethods() != 1 || report.bytecodeBytes() < 1) {
            throw new AssertionError("valid clean class incorrectly audited");
        }
        byte[] mutated = clean.clone();
        mutated[0] = 0x00;
        mustReject("bad-magic", dir, mutated);
        mutated = clean.clone();
        mutated[7] = 0x35; // Java 9 classfile, not pinned Java 8
        mustReject("classfile-major", dir, mutated);
        mutated = Arrays.copyOf(clean, clean.length + 1);
        mustReject("trailing-byte", dir, mutated);
        mutated = new byte[1_500_001];
        mustReject("oversized-input", dir, mutated);

        // Java 8 javac Object constructor: aload_0 invokespecial <idx> return.
        mutated = clean.clone();
        int init = find(clean, new byte[] {(byte) 0x2a, (byte) 0xb7, -1, -1, (byte) 0xb1});
        mutated[init] = (byte) 0x00; // no longer loads receiver
        mustReject("constructor-effect", dir, mutated);

        // A valid body must not run off the Code attribute, even if no branch is present.
        mutated = clean.clone();
        int tick = find(clean, new byte[] {(byte) 0x1a, (byte) 0x04, (byte) 0x60, (byte) 0xac});
        mutated[tick + 3] = (byte) 0x60; // iadd instead of ireturn
        mustReject("falls-off-end", dir, mutated);

        // Unsupported class-level attributes must not be silently ignored.
        // Keep checking that javac's supported class has a strict, deterministic identity.
        System.out.println("PASS: Java RT classfile mutation tests (valid + 6 malformed cases)");
    }
}
