package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closed manifest source inventory and namespace-actor metadata tests.
 * NO guest actor scheduling, critical state loan or AOT execution proof.
 */
final class ManifestNamespaceActorTopologyTest {
    @TempDir Path root;

    private static final String ACTOR = """
            namespace foo;
            declare pub actor Counter;
            """;
    private static final String STATE = """
            namespace foo;
            define state on actor Counter as {
                let int x: 5;
            }
            """;
    private static final String METHODS = """
            namespace foo;
            external mtd (Counter c) init(): void { return; }
            external mtd (Counter c) receive(ActorMail<int> mail): void { return; }
            pub mtd (Counter c) increment(): void { return; }
            pub mtd (Counter c) get(): int { return 5; }
            """;

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private PackageNamespaceBuild packageBuild() throws IOException {
        var manifest = root.resolve(".atto.pkg.toml");
        if (!Files.exists(manifest)) {
            write(".atto.pkg.toml", """
                    schema_version = "1"
                    [project]
                    name = "counter-demo"
                    version = "0.1.0"
                    root = "."
                    [source]
                    roots = ["src"]
                    """);
        }
        return new PackageNamespaceBuild(OresProjectConfig.load(manifest, Map.of()));
    }

    private PackageNamespaceBuild fixture() throws IOException {
        write("src/foo/actor.atto", ACTOR);
        write("src/foo/state.atto", STATE);
        write("src/foo/methods.atto", METHODS);
        return packageBuild();
    }

    private static ActorMethodIndex.ActorKey key(String actor) {
        return new ActorMethodIndex.ActorKey("counter-demo@0.1.0", "foo", actor);
    }

    @Test
    void completeManifestIndexesActorStateAndOutOfLineMethodsButNotIngress() throws IOException {
        var topology = fixture().inspectActorTopology();
        assertEquals("counter-demo@0.1.0", topology.packageIdentity());
        assertEquals(3, topology.sourceUnits().size());
        assertTrue(topology.actorMethods().hasActor(key("Counter")));
        assertTrue(topology.actorMethods().actor(key("Counter")).publicNominalWitness());
        assertEquals("src/foo/actor.atto", topology.actorMethods().declaringUnit(key("Counter")));

        var schema = topology.attachedStates().get(
                new ActorStateBinder.Slot("counter-demo@0.1.0", "foo", "Counter"));
        assertNotNull(schema);
        assertEquals("src/foo/actor.atto", schema.actorUnit());
        assertEquals("src/foo/state.atto", schema.stateUnit());
        assertEquals("x", schema.schema().fields().getFirst().name());
        var initial = assertInstanceOf(Ast.LiteralExpr.class,
                schema.schema().fields().getFirst().initializer());
        assertEquals(5L, initial.value());

        assertEquals(4, topology.receiverMethods().size());
        var methods = topology.actorMethods();
        var get = methods.method(new ActorMethodIndex.MethodKey(key("Counter"), "get", 0));
        assertNotNull(get);
        assertEquals("int", get.returnType().name());
        assertEquals("src/foo/methods.atto", get.declaringUnit());
        assertTrue(get.declarationEligible());
        assertNotNull(methods.method(new ActorMethodIndex.MethodKey(key("Counter"), "increment", 0)));
        assertNull(methods.method(new ActorMethodIndex.MethodKey(key("Counter"), "init", 0)));
        assertNull(methods.method(new ActorMethodIndex.MethodKey(key("Counter"), "receive", 1)));
        var init = topology.receiverMethods().get(new ActorReceiverBinder.SourceSlot(
                "counter-demo@0.1.0", "foo", "Counter", "init", 0));
        assertNotNull(init);
        assertTrue(init.nominalBinding().method().runtimeIngress());
        assertEquals("src/foo/methods.atto", init.methodUnit());
    }

    @Test
    void topologyCannotMintExecutableActorContext() throws IOException {
        fixture().inspectActorTopology();
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(ACTOR)));
        assertTrue(error.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void deletingOriginalActorFileRevokesStateAndReceiverAuthority() throws IOException {
        var compiler = fixture();
        assertTrue(compiler.inspectActorTopology().actorMethods().hasActor(key("Counter")));
        Files.delete(root.resolve("src/foo/actor.atto"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("has no nominal actor"),
                error.getMessage());
    }

    @Test
    void movingActorOutsideManifestRootsCannotProvideNominalWitness() throws IOException {
        var compiler = fixture();
        Files.createDirectories(root.resolve("ignored"));
        Files.move(root.resolve("src/foo/actor.atto"), root.resolve("ignored/actor.atto"));
        assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
    }

    @Test
    void duplicateAttachedStateSchemaFailsClosed() throws IOException {
        var compiler = fixture();
        write("src/foo/state_again.atto", STATE);
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("duplicate attached state schema"),
                error.getMessage());
    }

    @Test
    void wrongNamespaceStateCannotAttachToOtherNamespaceActor() throws IOException {
        var compiler = fixture();
        write("src/foo/state.atto", STATE.replace("namespace foo;", "namespace other;"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("has no nominal actor"),
                error.getMessage());
    }

    @Test
    void wrongNamespaceMethodsDoNotInheritActorAuthority() throws IOException {
        var compiler = fixture();
        write("src/foo/methods.atto", METHODS.replace("namespace foo;", "namespace other;"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("has no nominal actor"),
                error.getMessage());
    }

    @Test
    void duplicateMethodSlotFromAnotherFileIsRejected() throws IOException {
        var compiler = fixture();
        write("src/foo/duplicate.atto", """
                namespace foo;
                pub mtd (Counter c) increment(): int { return 10; }
                """);
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("duplicate external actor method slot"),
                error.getMessage());
    }

    @Test
    void ordinaryPublicInitCannotSpoofRuntimeIngress() throws IOException {
        var compiler = fixture();
        write("src/foo/methods.atto", METHODS.replace(
                "external mtd (Counter c) init(): void",
                "pub mtd (Counter c) init(): void"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("requires explicit private 'external mtd'"),
                error.getMessage());
    }

    @Test
    void unverifiedImplementedContractIsNotTreatedAsAProvenWitness() throws IOException {
        var compiler = fixture();
        write("src/foo/actor.atto", ACTOR.replace(
                "declare pub actor Counter;",
                "declare pub actor Counter implements ActorXYZ;"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("ACTOR_CONTRACT_WITNESS_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void privateActorCannotProvidePublicReceiverSlot() throws IOException {
        var compiler = fixture();
        write("src/foo/actor.atto", ACTOR.replace(
                "declare pub actor Counter;", "declare actor Counter;"));
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("cannot expose private actor"),
                error.getMessage());
    }

    @Test
    void misplacedVisibilityIsNotAcceptedFromRealManifestUnit() throws IOException {
        var compiler = fixture();
        write("src/foo/actor.atto", ACTOR.replace(
                "declare pub actor Counter;", "pub declare actor Counter;"));
        assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
    }

    @Test
    void changingManifestAfterBuilderCreationRequiresFreshConfig() throws IOException {
        var compiler = fixture();
        compiler.inspectActorTopology();
        write(".atto.pkg.toml", Files.readString(root.resolve(".atto.pkg.toml")) + "\n# changed\n");
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("manifest changed"),
                error.getMessage());
    }

    @Test
    void symlinkedManifestSourceIsNotAValidActorWitness() throws IOException {
        var compiler = fixture();
        Path original = root.resolve("src/foo/actor.atto");
        Path outside = root.resolve("other-actor.atto");
        Files.move(original, outside);
        try {
            Files.createSymbolicLink(original, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
            Assumptions.assumeTrue(false, "symlinks unavailable in test filesystem");
        }
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("symlink"), error.getMessage());
    }

    @Test
    void copiedNominalTypeInAnotherNamespaceHasNoStateOrMethodAuthority() throws IOException {
        var compiler = fixture();
        write("src/other/counter.atto", """
                namespace other;
                declare pub actor Counter;
                """);
        var topology = compiler.inspectActorTopology();
        assertTrue(topology.actorMethods().hasActor(key("Counter")));
        var foreign = new ActorMethodIndex.ActorKey("counter-demo@0.1.0", "other", "Counter");
        assertTrue(topology.actorMethods().hasActor(foreign));
        assertNull(topology.actorMethods().method(
                new ActorMethodIndex.MethodKey(foreign, "get", 0)));
        assertFalse(topology.attachedStates().containsKey(
                new ActorStateBinder.Slot("counter-demo@0.1.0", "other", "Counter")));
    }

    @Test
    void undefinedExternalPackageImportCannotEnterTrustedInventory() throws IOException {
        var compiler = fixture();
        write("src/foo/misc.atto", "namespace foo;\nimport rogue/library;\n");
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("untracked package source import")
                || error.getMessage().contains("import"), error.getMessage());
    }

    @Test
    void returnedTopologyMapsAreImmutable() throws IOException {
        var topology = fixture().inspectActorTopology();
        assertThrows(UnsupportedOperationException.class,
                () -> topology.attachedStates().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.receiverMethods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.actorMethods().methods().clear());
    }
}
