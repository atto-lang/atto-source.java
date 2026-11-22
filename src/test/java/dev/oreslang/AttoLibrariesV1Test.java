package dev.oreslang;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared core/alloc/std v1 guest corpus (tests/atto_libraries, identical in
 * every atto-source.* compiler). Each program runs through the real launcher
 * and linker; main's return value is the process exit code.
 */
class AttoLibrariesV1Test {
    private static final Path CORPUS = Path.of("tests", "atto_libraries");

    @Test
    void registryResolvesCanonicalAliasAndPlatformSelectors() {
        CoreLibrarySources.LibraryUnit num = CoreLibrarySources.libraryUnit("core/num");
        assertEquals(CoreLibrarySources.Layer.CORE, num.layer());
        assertEquals(num, CoreLibrarySources.libraryUnit("atto.core/num.atto"));
        assertEquals(CoreLibrarySources.Layer.ALLOC,
                CoreLibrarySources.libraryUnit("alloc/arena").layer());
        assertTrue(CoreLibrarySources.isLayeredLibraryImport("std/path"));
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("std/assert"));
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("./core/num.atto"));
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("std.fs"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.libraryUnit("core/../num"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.libraryUnit("alloc/x/arena"));
        assertTrue(CoreLibrarySources.layerMayDependOn(
                CoreLibrarySources.Layer.ALLOC, CoreLibrarySources.Layer.CORE));
        assertFalse(CoreLibrarySources.layerMayDependOn(
                CoreLibrarySources.Layer.CORE, CoreLibrarySources.Layer.ALLOC));
        assertFalse(CoreLibrarySources.layerMayDependOn(
                CoreLibrarySources.Layer.ALLOC, CoreLibrarySources.Layer.STD));
    }

    @TestFactory
    Stream<DynamicTest> sharedCorpus() throws IOException {
        List<Path> cases;
        try (Stream<Path> files = Files.list(CORPUS)) {
            cases = files.filter(p -> p.toString().endsWith(".atto")).sorted().toList();
        }
        assertFalse(cases.isEmpty(), "shared library corpus is missing");
        return cases.stream().map(path -> DynamicTest.dynamicTest(
                path.getFileName().toString(), () -> run(path)));
    }

    private static void run(Path path) throws Exception {
        List<String> flags = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Integer exit = null;
        String stdout = null;
        boolean reject = false;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.startsWith("//")) break;
            String body = line.substring(2).strip();
            if (body.equals("expect-reject")) reject = true;
            else if (body.startsWith("flags:")) flags.addAll(List.of(body.substring(6).strip().split("\\s+")));
            else if (body.startsWith("expect-exit:")) exit = Integer.parseInt(body.substring(12).strip());
            else if (body.startsWith("expect-stdout-json:")) stdout = json(body.substring(19).strip());
            else if (body.startsWith("expect-error:")) errors.add(body.substring(13).strip());
            else if (body.startsWith("expect-error-java:")) errors.add(body.substring(18).strip());
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dpolyglot.engine.WarnInterpreterOnly=false",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "dev.oreslang.launcher.OresMain"));
        command.addAll(flags);
        command.add(path.getFileName().toString());
        Process process = new ProcessBuilder(command).directory(CORPUS.toFile()).start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "guest timed out");
        if (reject) {
            assertNotEquals(0, process.exitValue(), "expected rejection");
            for (String error : errors) assertTrue((err + out).contains(error), err);
            return;
        }
        assertEquals(exit == null ? 0 : exit, process.exitValue(), err);
        if (stdout != null) assertEquals(stdout, out);
    }

    /** Minimal JSON string decoder for the corpus header (\\, \", \n). */
    private static String json(String quoted) {
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""), quoted);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c == '\\') {
                char n = quoted.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
