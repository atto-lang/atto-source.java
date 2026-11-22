package atto.compiler.m1;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Real M1 source compiler negative admission; NOT a runnable mutex guest. */
public final class AttoMutexConstructorM1Probe {
    private AttoMutexConstructorM1Probe() {}

    private static void check(Path fixture, String diagnostic, Path directory) throws Exception {
        byte[] source = Files.readAllBytes(fixture);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        Path output = directory.resolve(diagnostic + ".java");
        PrintStream prior = System.err;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        int exit;
        try (PrintStream log = new PrintStream(capture, true, StandardCharsets.UTF_8)) {
            System.setErr(log);
            try {
                exit = AttoObjectM1JavaCompiler.compile(fixture, sha, output);
            } finally {
                System.setErr(prior);
            }
        }
        if (exit != 2 || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                || !capture.toString(StandardCharsets.UTF_8).contains(diagnostic)) {
            throw new AssertionError("mutex negative did not fail closed: " + diagnostic
                    + " exit=" + exit + " stderr=" + capture.toString(StandardCharsets.UTF_8));
        }
        System.out.println("ATTO_JAVA_M1_MUTEX_CLASS_NEGATIVE_PASS=" + diagnostic);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("two source fixtures required");
        Path dir = Files.createTempDirectory("atto-mutex-m1-");
        try {
            check(Path.of(args[0]), "E_NEW_REQUIRED", dir);
            check(Path.of(args[1]), "E_LOCK_UNSUPPORTED", dir);
        } finally {
            try (var paths = Files.list(dir)) {
                for (Path path : paths.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(dir);
        }
    }
}
