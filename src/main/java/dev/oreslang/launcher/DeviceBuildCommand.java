package dev.oreslang.launcher;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Developer-only LLVM AOT entrypoint for locally compiled artifacts.
 * Does NOT claim SLSA level, device attestation, provenance signing or upload.
 * An installed LLVM compiler and clang are required; there is no fallback.
 */
final class DeviceBuildCommand {
    private DeviceBuildCommand() { }

    static int run(String[] argv) throws Exception {
        if (argv.length != 3 || !"build".equals(argv[0])) {
            System.err.println("usage: oreslang build SOURCE.ores OUTPUT (LLVM host subset only)");
            return 2;
        }
        final Path source;
        final Path output;
        final Path driver;
        final Path compiler;
        final Path clang;
        try {
            Path input = Path.of(argv[1]).toAbsolutePath().normalize();
            if (!argv[1].endsWith(".ores") || Files.isSymbolicLink(input))
                throw new IllegalArgumentException("source must be a non-symlink .ores file");
            source = input.toRealPath();
            if (!Files.isRegularFile(source))
                throw new IllegalArgumentException("source is not a regular file");
            Path requestedOutput = Path.of(argv[2]).toAbsolutePath().normalize();
            if (requestedOutput.getFileName() == null || requestedOutput.getParent() == null)
                throw new IllegalArgumentException("invalid output path");
            Path outputParent = requestedOutput.getParent().toRealPath();
            output = outputParent.resolve(requestedOutput.getFileName());
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("output must be a fresh path to avoid stale artifacts");
            driver = executableFromEnv("ORESLANG_LLVM_AOT_DRIVER");
            compiler = executableFromEnv("ORESLANG_LLVMC");
            clang = executableFromEnv("CLANG");
            if (source.equals(output) || driver.equals(output) || compiler.equals(output) || clang.equals(output))
                throw new IllegalArgumentException("output aliases source or toolchain");
        } catch (IllegalArgumentException | java.io.IOException error) {
            System.err.println("oreslang build: " + error.getMessage());
            return 2;
        }

        String sourceBefore = sha256(source);
        String driverBefore = sha256(driver);
        String compilerBefore = sha256(compiler);
        String clangBefore = sha256(clang);

        ProcessBuilder pb = new ProcessBuilder(driver.toString(), source.toString(), output.toString());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        // Never leak ORESLANG_API_TOKEN, cloud credentials, GitHub tokens, etc.
        Map<String,String> env = pb.environment();
        env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("ORESLANG_LLVMC", compiler.toString());
        env.put("CLANG", clang.toString());
        env.put("LC_ALL", "C");
        String epoch = System.getenv("SOURCE_DATE_EPOCH");
        if (epoch != null && epoch.matches("[0-9]{1,12}")) env.put("SOURCE_DATE_EPOCH", epoch);

        Process proc = pb.start();
        proc.getOutputStream().close();
        boolean finished;
        try {
            finished = proc.waitFor(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!finished) {
            proc.destroyForcibly();
            System.err.println("oreslang build: local compiler timed out");
            return 1;
        }
        if (proc.exitValue() != 0) {
            System.err.println("oreslang build: LLVM AOT compiler failed; no fallback");
            return 1;
        }
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0) {
            System.err.println("oreslang build: no real executable artifact produced");
            return 1;
        }
        if (!sourceBefore.equals(sha256(source)) || !driverBefore.equals(sha256(driver))
                || !compilerBefore.equals(sha256(compiler)) || !clangBefore.equals(sha256(clang))) {
            System.err.println("oreslang build: mutable source or toolchain detected");
            Files.deleteIfExists(output);
            return 1;
        }
        System.out.printf("{\"schema\":\"oreslang.local-build/1\",\"trust\":\"untrusted-self-report\","
                + "\"backend\":\"llvm\",\"sourceSha256\":\"%s\",\"compilerSha256\":\"%s\","
                + "\"clangSha256\":\"%s\",\"driverSha256\":\"%s\",\"artifactSha256\":\"%s\"}%n",
                sourceBefore, compilerBefore, clangBefore, driverBefore, sha256(output));
        return 0;
    }

    private static Path executableFromEnv(String name) throws java.io.IOException {
        String text = System.getenv(name);
        if (text == null || text.isBlank() || !Path.of(text).isAbsolute())
            throw new IllegalArgumentException(name + " must be an absolute path");
        Path path = Path.of(text).toRealPath();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path))
            throw new IllegalArgumentException(name + " is not an executable file");
        return path;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
