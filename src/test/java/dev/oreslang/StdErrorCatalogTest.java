package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class StdErrorCatalogTest {
    @TempDir Path temp;

    // Keep this deliberately simple/explicit: no host reflection or Java
    // exception classes may silently define guest error type identity.
    private static final Pattern TYPE = Pattern.compile(
            "(?m)^pub define class (\\w+)(?: extends (\\w+))? as\\R"
            + "  pub code\\(\\): int \\{ return (\\d+); \\}\\Rend$");
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\\{\"name\":\"(\\w+)\",\"parent\":(null|\"\\w+\"),\"code\":(\\d+)\\}");

    record Entry(String parent, int code) { }

    @Test
    void packagedCatalogMatchesStableManifestAndIsAcyclic() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        assertEquals(source, CoreLibrarySources.source("std/error.ores"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("error.ores"));
        assertFalse(source.contains("java:"));
        assertFalse(Pattern.compile("(?m)^\\s*rt\\s").matcher(source).find());
        assertFalse(Pattern.compile("(?m)^\\s*import\\s").matcher(source).find());
        TypeChecker.check(Parser.parse(source));

        Map<String, Entry> parsed = new HashMap<>();
        Set<Integer> codes = new HashSet<>();
        Matcher declarations = TYPE.matcher(source);
        while (declarations.find()) {
            String name = declarations.group(1);
            String parent = declarations.group(2);
            int code = Integer.parseInt(declarations.group(3));
            assertNull(parsed.put(name, new Entry(parent, code)), name);
            assertTrue(codes.add(code), "duplicate error code " + code);
        }
        assertEquals(74, parsed.size(), "every catalog type must have an executable Oreslang code method");
        assertEquals(new Entry(null, 0), parsed.get("Error"));
        assertEquals(new Entry("IOError", 3090), parsed.get("FileSystemError"));
        assertEquals(new Entry("IOError", 4000), parsed.get("NetworkError"));
        long leaves = parsed.keySet().stream().filter(name ->
                parsed.keySet().stream().noneMatch(child -> name.equals(parsed.get(child).parent()))
                ).count();
        assertEquals(64, leaves);
        for (String name : parsed.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = name;
            while (current != null) {
                assertTrue(visited.add(current), "cycle in error ancestry: " + name);
                Entry entry = parsed.get(current);
                assertNotNull(entry, "missing parent " + current);
                current = entry.parent();
            }
        }

        String manifest = Files.readString(Path.of("contracts/v1/error-catalog.json"));
        assertTrue(manifest.contains("\"schemaVersion\": 1"));
        assertTrue(manifest.contains("\"std/error\""));
        Map<String, Entry> expected = new HashMap<>();
        Matcher catalog = CATALOG_ENTRY.matcher(manifest);
        while (catalog.find()) {
            String parent = catalog.group(2);
            assertNull(expected.put(catalog.group(1),
                    new Entry(parent.equals("null") ? null : parent.substring(1, parent.length() - 1),
                            Integer.parseInt(catalog.group(3)))));
        }
        assertEquals(parsed, expected, "manifest/source drift or malformed catalog record");
        assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source("std/error/../core"));
        assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source("std/unknown-error"));
    }

    @Test
    void importedSubtypeMatchesBeforeItsSupertypeAndDispatchesLeafCode() throws Exception {
        Path entry = temp.resolve("error-match.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class IOError from "std/error";
                import class FileSystemError from "std/error";
                import class FileNotFoundError from "std/error";

                fnc classify(Error error): int {
                  match error over
                    on FileNotFoundError specific -> { return specific.code(); }
                    on FileSystemError file -> { return -10; }
                    on IOError io -> { return -20; }
                    on Error other -> { return -30; }
                  end
                }

                pub routine main(): void {
                  stdio.stdout.write(classify(new FileNotFoundError()));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("3001", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void ancestorFirstMatchingIsRejectedRatherThanSilentlyShadowingLeaf() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source + """
                    
                    fnc classify(Error error): int {
                      match error over
                        on Error broad -> { return 1; }
                        on FileNotFoundError dead -> { return 2; }
                      end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("unreachable match arm"), failure::getMessage);
    }

    @Test
    void effectSyntaxRemainsFailClosedUntilGuestLoweringExists() {
        for (String keyword : new String[]{"throw", "raise", "panic", "recover"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(): void { " + keyword + " 1; return; }"));
            assertTrue(failure.getMessage().contains("not yet enabled"), failure::getMessage);
        }
    }
}
