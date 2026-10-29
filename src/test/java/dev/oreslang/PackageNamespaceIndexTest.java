package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceIndex;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackageNamespaceIndexTest {
    @TempDir Path temporary;

    private static String id(Path path) throws IOException {
        return path.toRealPath().toString().replace('\\', '/');
    }

    private Path manifest(Path dir, String name, List<String> files) throws IOException {
        Files.createDirectories(dir);
        StringBuilder toml = new StringBuilder("""
                schema_version = "2"
                [project]
                root = "."
                [packages.%s]
                files = [
                """.formatted(name));
        for (String file : files) toml.append("  \"").append(file).append("\",\n");
        toml.append("]\n");
        Path path = dir.resolve(".ores.pkg.toml");
        Files.writeString(path, toml.toString());
        return path;
    }

    private void add(Map<String, String> sources, Path root, String name,
                     String namespace, String body) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        String source = "namespace " + namespace + ";\n" + body + "\n";
        Files.writeString(path, source);
        sources.put(id(path), source);
    }

    @Test
    void oneImportBindsFourManifestOwnedProvidersAndPreservesThreeNamespaces() throws IOException {
        Path checkout = temporary.resolve("checkout");
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            String namespace = i < 4 ? "billing" : i < 7 ? "identity" : "telemetry";
            add(sources, checkout, "src/unit" + i + ".ores", namespace,
                    "pub fnc exported_" + i + "(): int { return " + i + "; }");
        }
        Path manifest = manifest(checkout, "checkout",
                java.util.stream.IntStream.range(0, 10)
                        .mapToObj(i -> "src/unit" + i + ".ores").toList());

        Path consumer = temporary.resolve("client/main.ores");
        add(sources, temporary.resolve("client"), "main.ores", "client", """
                import namespace billing as payments from "checkout";
                pub fnc answer(): int { return 42; }
                """);
        String clientId = id(consumer);
        String packageId = PackageNamespaceIndex.packageId(manifest, "checkout");
        PackageNamespaceIndex index = PackageNamespaceIndex.load(
                List.of(manifest), sources, Map.of("checkout", packageId));
        var imported = index.resolve(clientId, "checkout", "billing");
        assertEquals(4, imported.providerUnitIds().size());
        assertEquals(4, imported.exports().size());
        assertEquals(packageId, imported.packageId());
        assertEquals("billing", imported.namespace());
        assertTrue(imported.exports().containsKey("function:exported_0:0"));
        assertTrue(imported.exports().containsKey("function:exported_3:0"));
        assertEquals(null, index.ownerOf(clientId));

        var compiler = new IncrementalCompiler();
        var build = compiler.compile(sources, Map.of(), index);
        assertEquals(10, build.units().values().stream()
                .filter(unit -> packageId.equals(unit.packageId())).count());
        assertEquals(4, build.units().get(clientId).dependencies().size());
        assertEquals("file:" + clientId, build.units().get(clientId).packageId());

        var again = compiler.compile(sources, Map.of(), index);
        assertTrue(again.reused(clientId));
        assertEquals(11, again.reusedUnits().size());
    }

    @Test
    void sameNamespaceNameDoesNotCrossPackageInstanceBoundary() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path alpha = temporary.resolve("alpha");
        Path beta = temporary.resolve("beta");
        add(sources, alpha, "src/a.ores", "billing",
                "pub fnc alphaOnly(): int { return 1; }");
        add(sources, beta, "src/b.ores", "billing",
                "pub fnc betaOnly(): int { return 2; }");
        Path a = manifest(alpha, "work", List.of("src/a.ores"));
        Path b = manifest(beta, "work", List.of("src/b.ores"));
        String aid = PackageNamespaceIndex.packageId(a, "work");
        String bid = PackageNamespaceIndex.packageId(b, "work");
        assertNotEquals(aid, bid);

        var index = PackageNamespaceIndex.load(List.of(a, b), sources,
                Map.of("alpha", aid, "beta", bid));
        var fromA = index.resolve(id(alpha.resolve("src/a.ores")), "+/", "billing");
        var fromB = index.resolve(id(beta.resolve("src/b.ores")), "+/", "billing");
        assertTrue(fromA.exports().containsKey("function:alphaOnly:0"));
        assertTrue(!fromA.exports().containsKey("function:betaOnly:0"));
        assertTrue(fromB.exports().containsKey("function:betaOnly:0"));
        assertTrue(!fromB.exports().containsKey("function:alphaOnly:0"));
        assertThrows(IllegalArgumentException.class,
                () -> index.resolve(id(alpha.resolve("src/a.ores")), "unapproved", "billing"));
    }

    @Test
    void duplicateExportIsRejectedBeforeLinking() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/first.ores", "billing",
                "pub fnc charge(): int { return 1; }");
        add(sources, root, "src/second.ores", "billing",
                "pub fnc charge(): int { return 2; }");
        Path m = manifest(root, "checkout", List.of("src/first.ores", "src/second.ores"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(m), sources, Map.of()));
        assertTrue(error.getMessage().contains("ambiguous exported namespace symbol"));
    }

    @Test
    void rejectsUnlistedMissingAndEscapingPackageFiles() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/first.ores", "billing",
                "pub fnc charge(): int { return 1; }");
        Path m = manifest(root, "checkout", List.of("src/first.ores", "src/missing.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(m), sources, Map.of()));
        manifest(root, "checkout", List.of("../outside.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(m), sources, Map.of()));
        manifest(root, "checkout", List.of("src/first.ores", "src/first.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(m), sources, Map.of()));
    }

    @Test
    void syntaxIsDistinctAndUnverifiedSingleFileLinkerFailsClosed() {
        var program = Parser.parse("""
                namespace client;
                import namespace billing as payments from "checkout";
                pub fnc answer(): int { return 42; }
                """);
        var imported = program.imports().getFirst();
        assertEquals("billing", imported.sourceNamespace());
        assertEquals("payments", imported.namespace());
        assertEquals("checkout", imported.path());

        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("client.ores", """
                        namespace client;
                        import namespace billing from "checkout";
                        pub fnc answer(): int { return 42; }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        import namespace __root__ from "checkout";
                        pub fnc answer(): int { return 42; }
                        """));
    }
}
