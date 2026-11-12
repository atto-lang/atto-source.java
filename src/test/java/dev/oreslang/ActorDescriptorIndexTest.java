package dev.oreslang;

import dev.oreslang.compiler.ActorDescriptorIndex;
import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A descriptor is a source/ABI receipt, NEVER an actor execution permit. */
final class ActorDescriptorIndexTest {
    @TempDir Path root;

    private static final String NS = "namespace foo;\n";
    private static final String CONTRACT = NS
            + "define pub contract CounterApi for actor as "
            + "mtd increment(int x): int; end\n";
    private static final String ACTOR = NS
            + "declare pub actor Counter implements CounterApi;\n";
    private static final String METHOD = NS
            + "pub mtd (Counter c) increment(int x): int { return x; }\n";
    private static final String STATE = NS
            + "define state on actor Counter as { let int count : 5; val bool ready : true; }\n";

    private void write(String relative, String content) throws IOException {
        var path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private PackageNamespaceBuild build() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "descriptor-demo"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        write("src/foo/actor.atto", ACTOR);
        write("src/foo/contract.atto", CONTRACT);
        write("src/foo/method.atto", METHOD);
        write("src/foo/state.atto", STATE);
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private static ActorDescriptorIndex.Descriptor descriptor(PackageNamespaceBuild.ActorAudit audit) {
        var key = new ActorMethodIndex.ActorKey(
                "descriptor-demo@0.1.0", "foo", "Counter");
        return audit.descriptors().actor(key);
    }

    @Test
    void descriptorBindsNominalContractMethodAndSealedStateOrigins() throws IOException {
        var audit = build().auditDeclaredActors();
        var d = descriptor(audit);
        assertNotNull(d);
        assertEquals("src/foo/actor.atto", d.actorUnit());
        assertEquals("src/foo/state.atto", d.stateUnit());
        assertEquals(1, d.contracts().size());
        assertEquals("CounterApi", d.contracts().getFirst().name());
        assertEquals("src/foo/contract.atto", d.contracts().getFirst().sourceUnit());
        assertEquals(1, d.methods().size());
        assertEquals("increment", d.methods().getFirst().name());
        assertEquals("src/foo/method.atto", d.methods().getFirst().sourceUnit());
        assertEquals(1, d.methods().getFirst().arity());
        assertEquals("int", d.methods().getFirst().parameterTypes().getFirst());
        assertEquals("int", d.methods().getFirst().returnType());
        assertEquals(2, d.state().size());
        assertEquals(0, d.state().getFirst().ordinal());
        assertEquals("count", d.state().getFirst().name());
        assertEquals("int", d.state().getFirst().typeName());
        assertEquals("5", d.state().getFirst().initialLiteral());
        assertEquals("ready", d.state().get(1).name());
        assertEquals("true", d.state().get(1).initialLiteral());
        assertEquals(64, d.abiSha256().length());
        assertEquals(64, d.sourceSha256().length());
    }

    @Test
    void repeatSnapshotHasDeterministicDescriptorDigests() throws IOException {
        var build = build();
        var first = descriptor(build.auditDeclaredActors());
        var second = descriptor(build.auditDeclaredActors());
        assertEquals(first.abiSha256(), second.abiSha256());
        assertEquals(first.sourceSha256(), second.sourceSha256());
        assertEquals(first.contracts(), second.contracts());
        assertEquals(first.methods(), second.methods());
    }

    @Test
    void changingMethodBodyChangesSourceReceiptButNotMethodAbi() throws IOException {
        var build = build();
        var first = descriptor(build.auditDeclaredActors());
        write("src/foo/method.atto", NS
                + "pub mtd (Counter c) increment(int x): int { return x + 1; }\n");
        var second = descriptor(build.auditDeclaredActors());
        assertEquals(first.abiSha256(), second.abiSha256());
        assertNotEquals(first.sourceSha256(), second.sourceSha256());
    }

    @Test
    void changedStateInitializerChangesStateAbiFingerprint() throws IOException {
        var build = build();
        var first = descriptor(build.auditDeclaredActors());
        write("src/foo/state.atto", NS
                + "define state on actor Counter as "
                + "{ let int count : 6; val bool ready : true; }");
        var second = descriptor(build.auditDeclaredActors());
        assertNotEquals(first.abiSha256(), second.abiSha256());
        assertNotEquals(first.sourceSha256(), second.sourceSha256());
    }

    @Test
    void deletingStateRemovesStateLayoutAndChangesAbi() throws IOException {
        var build = build();
        var first = descriptor(build.auditDeclaredActors());
        Files.delete(root.resolve("src/foo/state.atto"));
        var second = descriptor(build.auditDeclaredActors());
        assertNull(second.stateUnit());
        assertTrue(second.state().isEmpty());
        assertNotEquals(first.abiSha256(), second.abiSha256());
    }

    @Test
    void descriptorMapsAndNestedMethodListsAreImmutable() throws IOException {
        var audit = build().auditDeclaredActors();
        var d = descriptor(audit);
        assertThrows(UnsupportedOperationException.class,
                () -> audit.descriptors().actors().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> d.contracts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> d.methods().getFirst().parameterTypes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> d.state().clear());
    }

    @Test
    void compileCannotTreatDescriptorReceiptAsExecutablePermission() throws IOException {
        var build = build();
        assertNotNull(descriptor(build.auditDeclaredActors()));
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_CODEGEN_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void sourceAndContractMismatchFailBeforeDescriptorEmission() throws IOException {
        var build = build();
        write("src/foo/method.atto", NS
                + "pub mtd (Counter c) increment(bool x): int { return 1; }");
        var failure = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(failure.getMessage().contains("signature mismatch"),
                failure.getMessage());
    }

    @Test
    void privateActorHelperMethodIsNotPublicContractWitness() throws IOException {
        var build = build();
        write("src/foo/private.atto", NS
                + "mtd (Counter c) helper(): int { return 0; }");
        var d = descriptor(build.auditDeclaredActors());
        assertEquals(2, d.methods().size());
        assertTrue(d.methods().stream().anyMatch(m ->
                m.name().equals("helper")
                        && m.visibility() == dev.oreslang.ast.Ast.Visibility.PRIVATE));
    }

    @Test
    void removingExternalMethodInvalidatesPreviouslyIssuedDescriptor() throws IOException {
        var build = build();
        var receipt = build.auditDeclaredActors();
        assertNotNull(descriptor(receipt));
        Files.delete(root.resolve("src/foo/method.atto"));
        var failure = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(failure.getMessage().contains("missing actor contract implementation"),
                failure.getMessage());
        // Immutable previous receipt remains a snapshot, not authority.
        assertNotNull(descriptor(receipt));
    }
}
