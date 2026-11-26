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
import java.util.Set;
import java.util.stream.Collectors;
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
    private static final Set<String> REQUIRED_CASES = Set.of(
            "core_num.atto", "core_cmp.atto", "core_option_result.atto", "core_mem.atto",
            "alloc_arena.atto", "std_io.atto", "std_path_posix.atto", "std_path_windows.atto",
            "aliases.atto", "neg_unknown_core.atto", "neg_unknown_std.atto", "neg_traversal.atto",
            "neg_nested.atto", "neg_private.atto", "neg_dotted_selector.atto", "neg_ores_selector.atto");

    private static void requireCorpus(List<Path> cases) {
        Set<String> names = cases.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        assertTrue(names.containsAll(REQUIRED_CASES), () -> "missing required library cases: "
                + REQUIRED_CASES.stream().filter(n -> !names.contains(n)).sorted().toList());
    }

    private static void requireExpectation(String name, boolean reject, Integer exit) {
        if (REQUIRED_CASES.contains(name)) {
            assertEquals(name.startsWith("neg_"), reject, "unexpected fixture polarity: " + name);
        }
        if (!reject) assertNotNull(exit, "missing explicit expected exit: " + name);
    }

    private static void requireRejection(int exit, String output, List<String> errors) {
        assertTrue(exit > 0 && exit < 128, "expected ordinary nonzero rejection, exit: " + exit);
        assertFalse(output.isBlank(), "rejection lacked a diagnostic");
        for (String error : errors) assertTrue(output.contains(error), output);
    }

    @Test
    void corpusGuardRejectsEveryMissingCaseAndInvalidOutcome() {
        List<Path> complete = REQUIRED_CASES.stream().map(Path::of).toList();
        requireCorpus(complete);
        var extra = new ArrayList<>(complete);
        extra.add(Path.of("extra.atto"));
        requireCorpus(extra);
        assertThrows(AssertionError.class, () -> requireCorpus(List.of()));
        for (Path missing : complete) {
            assertThrows(AssertionError.class, () -> requireCorpus(
                    complete.stream().filter(p -> !p.equals(missing)).toList()));
        }
        requireExpectation("core_num.atto", false, 42);
        requireExpectation("neg_private.atto", true, null);
        assertThrows(AssertionError.class, () -> requireExpectation("core_num.atto", true, null));
        assertThrows(AssertionError.class, () -> requireExpectation("neg_private.atto", false, 0));
        assertThrows(AssertionError.class, () -> requireExpectation("extra.atto", false, null));
        requireRejection(1, "type error", List.of("type"));
        for (int invalid : List.of(0, 137, 139)) {
            assertThrows(AssertionError.class, () -> requireRejection(invalid, "type error", List.of("type")));
        }
        assertThrows(AssertionError.class, () -> requireRejection(1, "", List.of()));
        assertThrows(AssertionError.class, () -> requireRejection(1, "wrong", List.of("type")));
    }

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
        // AttoLang names only: ores.* never selects a v1 unit.
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("ores.core/num"));
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("ores.std/io"));
        // atto.std/<legacy> reaches the pre-v1 module.
        assertFalse(CoreLibrarySources.isLayeredLibraryImport("atto.std/assert"));
        assertTrue(CoreLibrarySources.isStdImport("atto.std/assert"));
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
        requireCorpus(cases);
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
        requireExpectation(path.getFileName().toString(), reject, exit);
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
            requireRejection(process.exitValue(), err + out, errors);
            return;
        }
        assertEquals(exit.intValue(), process.exitValue(), err);
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
