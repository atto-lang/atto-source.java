package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceIndex;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
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
    void changingPackageInstanceInvalidatesConsumerWithIdenticalProviderFilesAndAbi()
            throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/charge.ores", "billing",
                "pub fnc charge(): int { return 1; }");
        Path importer = temporary.resolve("client/main.ores");
        add(sources, temporary.resolve("client"), "main.ores", "client", """
                import namespace billing as payments from "checkout";
                pub fnc answer(): int { return payments.charge(); }
                """);
        String importerId = id(importer);
        Path m = manifest(root, "first", List.of("src/charge.ores"));
        String first = PackageNamespaceIndex.packageId(m, "first");
        var indexA = PackageNamespaceIndex.load(List.of(m), sources, Map.of("checkout", first));
        var compiler = new IncrementalCompiler();

        var initial = compiler.compile(sources, Map.of(), indexA);
        assertTrue(initial.rebuilt(importerId));
        var unchanged = compiler.compile(sources, Map.of(), indexA);
        assertTrue(unchanged.reused(importerId));

        // Same real source file and ABI; only manifest package identity
        // and the exact alias -> PackageInstanceId resolution change.
        manifest(root, "second", List.of("src/charge.ores"));
        String second = PackageNamespaceIndex.packageId(m, "second");
        assertNotEquals(first, second);
        var indexB = PackageNamespaceIndex.load(List.of(m), sources, Map.of("checkout", second));
        var relinked = compiler.compile(sources, Map.of(), indexB);
        assertTrue(relinked.rebuilt(importerId));
        assertTrue(relinked.reusedUnits().isEmpty());
        assertEquals(second, indexB.resolve(importerId, "checkout", "billing").packageId());
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
    void samePackageNamespaceTracksAllOtherProvidersButNotItself() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/billing/a.ores", "billing", """
                import namespace billing from "+/";
                pub fnc a(): int { return 1; }
                """);
        add(sources, root, "src/billing/b.ores", "billing",
                "pub fnc b(): int { return 2; }");
        Path m = manifest(root, "checkout", List.of("src/billing/a.ores", "src/billing/b.ores"));
        var index = PackageNamespaceIndex.load(List.of(m), sources, Map.of());
        String a = id(root.resolve("src/billing/a.ores"));
        String b = id(root.resolve("src/billing/b.ores"));
        assertEquals(Set.of(a, b), index.resolve(a, "+/", "billing").providerUnitIds());

        var build = new IncrementalCompiler().compile(sources, Map.of(), index);
        assertEquals(Set.of(b), build.units().get(a).dependencies());
        assertEquals(PackageNamespaceIndex.packageId(m, "checkout"),
                build.units().get(a).packageId());
    }

    @Test
    void namedModuleMembersAreNotFlattenedIntoPackageNamespace() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/math.ores", "billing", """
                define module math as
                  pub fnc add(int value): int { return value + 1; }
                end
                """);
        Path m = manifest(root, "checkout", List.of("src/math.ores"));
        var index = PackageNamespaceIndex.load(List.of(m), sources, Map.of());
        var binding = index.resolve(id(root.resolve("src/math.ores")), "+/", "billing");
        assertTrue(binding.exports().containsKey("module:math:-1"));
        assertTrue(!binding.exports().containsKey("function:add:1"));
    }

    @Test
    void directIndexRejectsNoncanonicalManifestMemberAliases() throws IOException {
        Path project = temporary.resolve("direct-index-path-aliases");
        Map<String, String> sources = new LinkedHashMap<>();
        add(sources, project, "src/billing/charge.ores", "billing",
                "pub fnc charge(): int { return 5; }");
        Path manifest = manifest(project, "checkout", List.of("src/billing/charge.ores"));
        var admitted = PackageNamespaceIndex.load(List.of(manifest), sources, Map.of());
        assertEquals(1, admitted.resolve(id(project.resolve("src/billing/charge.ores")),
                "+/", "billing").providerUnitIds().size());
        for (String alias : List.of(
                "./src/billing/charge.ores",
                "src//billing/charge.ores",
                "src/billing/./charge.ores",
                "src/billing/../billing/charge.ores")) {
            manifest(project, "checkout", List.of(alias));
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> PackageNamespaceIndex.load(List.of(manifest), sources, Map.of()), alias);
            assertTrue(denied.getMessage().contains("noncanonical namespace provider filename"),
                    denied.getMessage());
        }
    }

    @Test
    void nestedPackageManifestBlocksAncestorMemberOwnership() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path root = temporary.resolve("checkout");
        add(sources, root, "src/nested/file.ores", "billing",
                "pub fnc charge(): int { return 1; }");
        Path m = manifest(root, "checkout", List.of("src/nested/file.ores"));
        manifest(root.resolve("src/nested"), "nested", List.of("file.ores"));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(m), sources, Map.of()));
        assertTrue(rejected.getMessage().contains("nested manifest boundary"));
    }

    @Test
    void directIndexCannotClaimNestedProjectRootOrAncestorPackage() throws IOException {
        Path project = temporary.resolve("nested-root-index");
        Map<String, String> sources = new LinkedHashMap<>();
        add(sources, project, "sources/pkg/worker.ores", "billing",
                "pub fnc worker(): int { return 7; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "2"
                [project]
                root = "sources/pkg"
                [packages.checkout]
                files = ["worker.ores"]
                """);
        // Direct PackageNamespaceIndex.load is a supported entry point.
        // An explicit subdirectory project.root is valid in isolation.
        var admitted = PackageNamespaceIndex.load(List.of(manifest), sources, Map.of());
        assertEquals(1, admitted.resolve(id(project.resolve("sources/pkg/worker.ores")),
                "+/", "billing").providerUnitIds().size());

        Path intermediate = project.resolve("sources/.ores.pkg.toml");
        Files.writeString(intermediate, "schema_version = \"2\"\n");
        IllegalArgumentException atParent = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(manifest), sources, Map.of()));
        assertTrue(atParent.getMessage().contains("nested manifest boundary"));
        Files.delete(intermediate);

        Path atRoot = project.resolve("sources/pkg/.ores.pkg.toml");
        Files.writeString(atRoot, "schema_version = \"2\"\n");
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(manifest), sources, Map.of()));
        assertTrue(nested.getMessage().contains("nested manifest boundary"));
    }

    @Test
    void directIndexRejectsDanglingNestedManifestSymlink() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path project = temporary.resolve("dangling-index-boundary");
        add(sources, project, "src/nested/file.ores", "billing",
                "pub fnc charge(): int { return 1; }");
        Path manifest = manifest(project, "checkout", List.of("src/nested/file.ores"));
        Path marker = project.resolve("src/nested/.ores.pkg.toml");
        try {
            Files.createSymbolicLink(marker, Path.of("missing-future-manifest.toml"));
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(manifest), sources, Map.of()));
        assertTrue(denied.getMessage().contains("nested manifest boundary"));
    }

    @Test
    void directIndexRejectsOversizedAndMalformedUtf8ManifestBeforeParsing() throws IOException {
        Path directory = temporary.resolve("direct-manifest-budget");
        Path ownedManifest = manifest(directory, "checkout", List.of("src/file.ores"));
        Files.write(ownedManifest, new byte[1024 * 1024 + 1]);
        IllegalArgumentException oversized = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(ownedManifest), Map.of(), Map.of()));
        assertTrue(oversized.getMessage().contains("exceeds byte budget"));

        Files.write(ownedManifest, new byte[] {(byte) 0xC3, (byte) 0x28});
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceIndex.load(List.of(ownedManifest), Map.of(), Map.of()));
        assertTrue(malformed.getMessage().contains("valid UTF-8"));
    }

    @Test
    void directIndexRejectsSymlinkedManifestEntryWithoutCanonicalizingAwayAlias()
            throws IOException {
        Path original = manifest(temporary.resolve("direct-manifest-real"),
                "checkout", List.of("src/file.ores"));
        Path alias = temporary.resolve("direct-manifest-alias/.ores.pkg.toml");
        Files.createDirectories(alias.getParent());
        try {
            Files.createSymbolicLink(alias, original);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceIndex.load(List.of(alias), Map.of(), Map.of()));
        assertTrue(denied.getMessage().contains("unaliased path"));
    }

    @Test
    void runtimeLoaderRejectsNamespaceImportsWithoutVerifiedManifest() throws IOException {
        Path entry = temporary.resolve("entry.ores");
        Files.writeString(entry, """
                namespace client;
                import namespace billing from "checkout";
                pub fnc answer(): int { return 42; }
                """);
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        // The intermediate index and subsequent runtime linker may use
        // different diagnostics; both must reject manifest-less imports.
        assertTrue(rejection.getMessage().contains("package index")
                || rejection.getMessage().contains("runtime linking")
                || rejection.getMessage().contains("explicit v2 package manifest"));
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
