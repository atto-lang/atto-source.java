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
        assertEquals(topology.sourceUnits().size(), topology.sourceSha256().size());
        for (String sourceId : topology.sourceUnits()) {
            assertTrue(topology.sourceSha256().get(sourceId).matches("[0-9a-f]{64}"));
        }
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
        // Source metadata is not a verified contract witness: the method
        // body still requires full typed lowering and effect checking.
        assertTrue(get.unloweredExternal());
        assertFalse(get.declarationEligible());
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
    void changingStateSourceInvalidatesItsSourceReceiptOnRescan() throws IOException {
        var compiler = fixture();
        var before = compiler.inspectActorTopology();
        String source = "src/foo/state.atto";
        String oldHash = before.sourceSha256().get(source);
        write(source, STATE.replace("let int x: 5;", "let int x: 6;"));
        var after = compiler.inspectActorTopology();
        assertNotEquals(oldHash, after.sourceSha256().get(source));
        assertEquals(before.sourceSha256().get("src/foo/actor.atto"),
                after.sourceSha256().get("src/foo/actor.atto"));
        var binding = after.attachedStates().get(new ActorStateBinder.Slot(
                "counter-demo@0.1.0", "foo", "Counter"));
        var literal = assertInstanceOf(Ast.LiteralExpr.class,
                binding.schema().fields().getFirst().initializer());
        assertEquals(6L, literal.value());
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
    void inlineAndDeclaredActorIndicesRemainSeparatedWithoutRuntimeAuthority() throws IOException {
        var compiler = fixture();
        write("src/foo/inline.atto", """
                namespace foo;
                pub define actor Inline as
                    pub value(): int { return 42; }
                end
                """);
        var topology = compiler.inspectActorTopology();
        var inline = key("Inline");
        assertTrue(topology.actorMethods().hasActor(inline));
        assertTrue(topology.actorMethods().actor(inline).publicNominalWitness());
        var value = topology.actorMethods().method(
                new ActorMethodIndex.MethodKey(inline, "value", 0));
        assertNotNull(value);
        assertEquals("src/foo/inline.atto", value.declaringUnit());
        assertFalse(value.unloweredExternal());
        assertTrue(topology.actorMethods().hasActor(key("Counter")));
        assertNull(topology.actorMethods().method(
                new ActorMethodIndex.MethodKey(key("Counter"), "value", 0)));
    }

    @Test
    void inlineActorCannotAcquireDeclaredActorAttachedState() throws IOException {
        var compiler = packageBuild();
        write("src/foo/inline.atto", """
                namespace foo;
                pub define actor Counter as end
                """);
        write("src/foo/state.atto", STATE);
        var error = assertThrows(IllegalArgumentException.class, compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("inline actor definitions cannot acquire"),
                error.getMessage());
    }

    @Test
    void sameNominalNameInAnotherManifestIsNeverAReceiverWitness() throws IOException {
        var original = fixture().inspectActorTopology();
        Path foreignRoot = root.resolve("foreign");
        Files.createDirectories(foreignRoot.resolve("src"));
        Files.writeString(foreignRoot.resolve(".atto.pkg.toml"), """
                schema_version = "1"
                [project]
                name = "foreign-package"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        Files.writeString(foreignRoot.resolve("src/counter.atto"), ACTOR);
        var foreignBuild = new PackageNamespaceBuild(OresProjectConfig.load(
                foreignRoot.resolve(".atto.pkg.toml"), Map.of()));
        var foreign = foreignBuild.inspectActorTopology();
        var originalKey = key("Counter");
        var foreignKey = new ActorMethodIndex.ActorKey(
                "foreign-package@0.1.0", "foo", "Counter");
        assertTrue(original.actorMethods().hasActor(originalKey));
        assertFalse(foreign.actorMethods().hasActor(originalKey));
        assertTrue(foreign.actorMethods().hasActor(foreignKey));
        assertFalse(foreign.attachedStates().containsKey(new ActorStateBinder.Slot(
                "foreign-package@0.1.0", "foo", "Counter")));
        assertNull(foreign.actorMethods().method(
                new ActorMethodIndex.MethodKey(foreignKey, "get", 0)));
    }

    @Test
    void descriptorLinksPrivateStateAndHooksWithoutAuthorizingExecution() throws IOException {
        var topology = fixture().inspectActorTopology();
        var descriptor = topology.actorDescriptors().descriptor(key("Counter"));
        assertNotNull(descriptor);
        assertFalse(descriptor.guestExecutable());
        assertEquals(key("Counter"), descriptor.actor());
        assertEquals("src/foo/actor.atto", descriptor.declaration().declaringUnit());
        assertEquals(5L, descriptor.stateFields().get("x").initialI64());
        assertEquals(1, descriptor.stateFields().size());
        assertEquals(2, descriptor.methods().size());
        var get = descriptor.methods().get(
                new ActorMethodIndex.MethodKey(key("Counter"), "get", 0));
        assertNotNull(get);
        assertTrue(get.unloweredExternal());
        assertFalse(get.declarationEligible());
        assertEquals(2, descriptor.runtimeIngress().size());
        assertEquals(0, descriptor.runtimeIngress().get("init").arity());
        assertEquals(1, descriptor.runtimeIngress().get("receive").arity());
        assertEquals("src/foo/methods.atto",
                descriptor.runtimeIngress().get("receive").declaringUnit());
        assertFalse(descriptor.methods().keySet().stream().anyMatch(
                key -> key.methodName().equals("init") || key.methodName().equals("receive")));
        assertTrue(descriptor.sourceSetSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void descriptorReceiptIsStableOnUnchangedInventoryAndChangesOnSourceEdit() throws IOException {
        var compiler = fixture();
        String before = compiler.inspectActorTopology()
                .actorDescriptors().descriptor(key("Counter")).sourceSetSha256();
        String same = compiler.inspectActorTopology()
                .actorDescriptors().descriptor(key("Counter")).sourceSetSha256();
        assertEquals(before, same);
        write("src/foo/state.atto", STATE.replace("let int x: 5;", "let int x: 6;"));
        var after = compiler.inspectActorTopology().actorDescriptors()
                .descriptor(key("Counter"));
        assertNotEquals(before, after.sourceSetSha256());
        assertEquals(6L, after.stateFields().get("x").initialI64());
        assertFalse(after.guestExecutable());
    }

    @Test
    void namespaceSeparatedDescriptorsCannotBorrowAnotherActorsStateOrMethods()
            throws IOException {
        var compiler = fixture();
        write("src/other/counter.atto", """
                namespace other;
                declare pub actor Counter;
                """);
        var topology = compiler.inspectActorTopology();
        var local = topology.actorDescriptors().descriptor(key("Counter"));
        var foreignKey = new ActorMethodIndex.ActorKey(
                "counter-demo@0.1.0", "other", "Counter");
        var foreign = topology.actorDescriptors().descriptor(foreignKey);
        assertNotNull(local);
        assertNotNull(foreign);
        assertEquals(5L, local.stateFields().get("x").initialI64());
        assertTrue(foreign.stateFields().isEmpty());
        assertTrue(foreign.methods().isEmpty());
        assertTrue(foreign.runtimeIngress().isEmpty());
        assertEquals(2, topology.actorDescriptors().descriptors().size());
        assertNull(topology.actorDescriptors().descriptor(
                new ActorMethodIndex.ActorKey("foreign@1", "foo", "Counter")));
    }

    @Test
    void privateIngressDoesNotBecomePublicViaDescriptorNameOrReturnType()
            throws IOException {
        var compiler = fixture();
        var descriptor = compiler.inspectActorTopology().actorDescriptors()
                .descriptor(key("Counter"));
        assertNull(descriptor.methods().get(
                new ActorMethodIndex.MethodKey(key("Counter"), "init", 0)));
        assertNull(descriptor.methods().get(
                new ActorMethodIndex.MethodKey(key("Counter"), "receive", 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.stateFields().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.methods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.runtimeIngress().clear());
    }

    @Test
    void descriptorStateLayoutRejectsForgedInlineAndDuplicateActorNames() throws IOException {
        var compiler = fixture();
        write("src/foo/second_actor.atto", ACTOR);
        var error = assertThrows(IllegalArgumentException.class,
                compiler::inspectActorTopology);
        assertTrue(error.getMessage().contains("duplicate")
                || error.getMessage().contains("ambiguous"), error.getMessage());
    }

    @Test
    void sourceRuntimeAttemptStillRequiresCompilerIssuedActorPermit() throws IOException {
        var topology = fixture().inspectActorTopology();
        assertNotNull(topology.actorDescriptors().descriptor(key("Counter")));
        var declared = Parser.parse(ACTOR);
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(declared));
        assertTrue(rejection.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                rejection.getMessage());
    }

    @Test
    void returnedTopologyMapsAreImmutable() throws IOException {
        var topology = fixture().inspectActorTopology();
        assertThrows(UnsupportedOperationException.class,
                () -> topology.sourceSha256().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.actorDescriptors().descriptors().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.attachedStates().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.receiverMethods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> topology.actorMethods().methods().clear());
    }
}
