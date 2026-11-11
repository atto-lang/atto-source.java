package dev.oreslang;

import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Manifest-derived namespace membership, not ambient directory coincidence. */
final class PackageNamespaceBuildTest {
    @TempDir Path root;

    private static final String CLAIM = """
            namespace shop conforms ShopApi;
            define contract ShopApi for namespace as
              actor Cart;
            end
            """;
    private static final String CART = """
            namespace shop;
            pub define actor Cart as
              pub count(): int { return 42; }
            end
            """;

    private static void write(Path base, String name, String content) throws IOException {
        Path file = base.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private OresProjectConfig config(String name) throws IOException {
        write(root, ".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "%s"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """.formatted(name));
        return OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of());
    }

    private PackageNamespaceBuild compiler() throws IOException {
        OresProjectConfig settings = config("shop-app");
        write(root, "src/shop/contracts.atto", CLAIM);
        write(root, "src/shop/cart.atto", CART);
        return new PackageNamespaceBuild(settings);
    }

    @Test
    void manifestBuildDiscoversCompleteNamespaceAndNominalMethodSlot() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        var build = compiler.build();
        assertEquals("shop-app@0.1.0", build.packageIdentity());
        assertEquals(2, build.sourceUnits().size());
        assertEquals("src/shop/cart.atto", build.sourceUnits().getFirst());
        var key = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "shop", "Cart");
        assertTrue(build.actorMethods().hasActor(key));
        assertTrue(build.actorMethods().actor(key).publicNominalWitness());
        assertEquals("src/shop/cart.atto", build.actorMethods().declaringUnit(key));
        var method = build.actorMethods().method(new ActorMethodIndex.MethodKey(key, "count", 0));
        assertNotNull(method);
        assertTrue(method.declarationEligible());
        assertEquals("int", method.returnType().name());
        assertEquals(2, build.compilation().units().size());
    }

    @Test
    void sameManifestBuildCanReuseUnchangedSourceFiles() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        compiler.build();
        var unchanged = compiler.build();
        assertTrue(unchanged.compilation().reused("src/shop/cart.atto"));
        assertTrue(unchanged.compilation().reused("src/shop/contracts.atto"));
    }

    @Test
    void deletingActorFileRevokesWitnessBeforeIncrementalReuse() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        compiler.build();
        Files.delete(root.resolve("src/shop/cart.atto"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("missing public non-generic actor"),
                error.getMessage());
    }

    @Test
    void sourceOutsideManifestRootsCannotSatisfyNamespaceContract() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        Files.delete(root.resolve("src/shop/cart.atto"));
        write(root, "untrusted/cart.atto", CART);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }

    @Test
    void symlinkedSourceFileCannotBeSuppliedAsNamespaceWitness() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        Path actor = root.resolve("src/shop/cart.atto");
        Path outside = root.resolve("cart-outside.atto");
        Files.move(actor, outside);
        try {
            Files.createSymbolicLink(actor, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            Assumptions.assumeTrue(false, "filesystem cannot create symlink test");
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("symlinked"));
    }

    @Test
    void symlinkedSubdirectoryCannotIntroduceOutsideActor() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        Path outside = root.resolve("external");
        write(outside, "rogue.atto", CART);
        try {
            Files.createSymbolicLink(root.resolve("src/shop/other"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            Assumptions.assumeTrue(false, "filesystem cannot create symlink test");
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("symlinked"));
    }

    @Test
    void sourceRootCannotEscapeManifestProjectRoot() throws IOException {
        Path outside = Files.createTempDirectory("outside-atto-package");
        try {
            write(root, ".atto.pkg.toml", """
                    schema_version = "1"
                    [project]
                    name = "escape"
                    version = "0.1"
                    root = "."
                    [source]
                    roots = ["../%s"]
                    """.formatted(outside.getFileName()));
            var settings = OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of());
            PackageNamespaceBuild build = new PackageNamespaceBuild(settings);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, build::build);
            assertTrue(error.getMessage().contains("source root escapes package"));
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void missingManifestCannotBePassedOffAsManifestBackedProof() throws IOException {
        write(root, "src/shop/cart.atto", CART);
        OresProjectConfig withoutManifest = OresProjectConfig.discover(root, Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PackageNamespaceBuild(withoutManifest));
        assertTrue(error.getMessage().contains("explicit .atto.pkg.toml"));
    }

    @Test
    void wrongNamespaceMethodCannotBeResolvedAsLocalActorMethod() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/other/worker.atto", """
                namespace different;
                pub define actor Worker as
                  pub count(): int { return 99; }
                end
                """);
        var index = compiler.build().actorMethods();
        var other = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "different", "Worker");
        var shop = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "shop", "Worker");
        assertTrue(index.hasActor(other));
        assertFalse(index.hasActor(shop));
        assertNull(index.method(new ActorMethodIndex.MethodKey(shop, "count", 0)));
    }

    @Test
    void privateActorCannotBeClaimedAsPublicNominalContractWitness() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/other/private.atto", """
                namespace other;
                define actor PrivateWorker as
                  pub count(): int { return 1; }
                end
                """);
        var index = compiler.build().actorMethods();
        var actor = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "other", "PrivateWorker");
        assertTrue(index.hasActor(actor));
        assertFalse(index.actor(actor).publicNominalWitness());
    }

    @Test
    void actorMethodIndexDoesNotExposeLifecycleControlSlots() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/shop/cart.atto", """
                namespace shop;
                pub define actor Cart as
                  pub count(): int { return 42; }
                  tear_down(): void { return; }
                end
                """);
        var index = compiler.build().actorMethods();
        var key = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "shop", "Cart");
        assertNotNull(index.method(new ActorMethodIndex.MethodKey(key, "count", 0)));
        assertNull(index.method(new ActorMethodIndex.MethodKey(key, "tear_down", 0)));
    }

    @Test
    void projectIdDoesNotBecomeImplicitActorIdentityAcrossProjects() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        var index = compiler.build().actorMethods();
        var fake = new ActorMethodIndex.ActorKey("other-app@0.1.0", "shop", "Cart");
        assertFalse(index.hasActor(fake));
    }

    @Test
    void invalidUtf8IsRejectedBeforeParserOrCache() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        Files.write(root.resolve("src/shop/cart.atto"), new byte[] {(byte)0xc3, 0x28});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("invalid UTF-8"));
    }

    @Test
    void modifiedManifestInvalidatesPreviouslyApprovedPackageBoundary() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        compiler.build();
        write(root, ".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "changed-app"
                version = "0.2.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("manifest changed"));
    }

    @Test
    void duplicateActorDefinitionAcrossFilesIsNotAnIndexedActor() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/shop/cart-second.atto", CART);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("ambiguous nominal actor"));
    }

    @Test
    void actorMethodIndexIncludesExplicitNameAndArityOnly() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/shop/cart.atto", """
                namespace shop;
                pub define actor Cart as
                  pub count(): int { return 42; }
                  pub count(int value): int { return value; }
                end
                """);
        var index = compiler.build().actorMethods();
        var actor = new ActorMethodIndex.ActorKey("shop-app@0.1.0", "shop", "Cart");
        var zero = index.method(new ActorMethodIndex.MethodKey(actor, "count", 0));
        var one = index.method(new ActorMethodIndex.MethodKey(actor, "count", 1));
        assertNotNull(zero);
        assertNotNull(one);
        assertEquals(1, one.argumentTypes().size());
        assertEquals("int", one.argumentTypes().getFirst().name());
        assertNull(index.method(new ActorMethodIndex.MethodKey(actor, "count", 2)));
    }

    @Test
    void manifestBackedSourceUnitsAreOrderedLexically() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/other/z.atto", "namespace other; pub fnc z(): int { return 1; }");
        write(root, "src/other/a.atto", "namespace other; pub fnc a(): int { return 1; }");
        var result = compiler.build();
        assertEquals(result.sourceUnits().stream().sorted().toList(), result.sourceUnits());
    }

    @Test
    void externalReceiverMethodBodiesRemainNonExecutableUntilMailboxLowering() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/shop/external.atto", """
                namespace shop;
                define pub mtd (Cart cart) count(): int {
                  return 42;
                }
                """);
        assertThrows(IllegalArgumentException.class, compiler::build,
                "external actor method syntax must not become a free function");
    }

    @Test
    void MethodIndexResultCannotBeMutatedIntoACompilerProof() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        var registry = compiler.build().actorMethods().actors();
        assertThrows(UnsupportedOperationException.class,
                () -> registry.clear());
    }

    @Test
    void unpinnedBareSourceImportCannotBorrowAmbientDependencyAuthority() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, "src/shop/extra.atto", """
                namespace shop;
                import fnc outside from "vendor/foreign";
                pub fnc local(): int { return 1; }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                compiler::build);
        assertTrue(error.getMessage().contains("untracked package source import"));
    }

    @Test
    void sourceRootsWithDuplicateOverlappingFilesAreRejected() throws IOException {
        PackageNamespaceBuild compiler = compiler();
        write(root, ".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "shop-app"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src", "src/shop"]
                """);
        PackageNamespaceBuild overlapping = new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                overlapping::build);
        assertTrue(error.getMessage().contains("overlapping package source roots"));
    }
}
