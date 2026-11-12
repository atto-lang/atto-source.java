package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the internal pre-HIR handoff with deliberately caller-assembled
 * static receipts. It does NOT attest any physical filesystem snapshot.
 */
final class ActorTypedMethodSnapshotIdentityTest {
    private static final String PACKAGE = "snapshot@1.0.0";
    private static final String NS = "namespace foo;\n";
    private static final String ACTOR =
            NS + "declare pub actor Foo implements ActorApi;";
    private static final String CONTRACT =
            NS + "define pub contract ActorApi for actor as mtd bar(int x): int; end";
    private static final String METHOD =
            NS + "pub mtd (Foo f) bar(int x): int { return x; }";

    private static Map<String, Ast.Program> parsed() {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/actor.atto", Parser.parse(ACTOR));
        units.put("src/contract.atto", Parser.parse(CONTRACT));
        units.put("src/method.atto", Parser.parse(METHOD));
        return units;
    }

    /** Caller-selected hash SHAPES are not a physical manifest attestation. */
    private static PackageNamespaceBuild.ActorAudit syntheticAudit(
            Map<String, Ast.Program> original) {
        var witness = ActorContractWitness.linkSourceSet(PACKAGE, original);
        var index = ActorMethodIndex.collect(PACKAGE, original, witness.boundMethods());
        Map<String, String> sha = new LinkedHashMap<>();
        for (String file : original.keySet()) sha.put(file, "a".repeat(64));
        var descriptors = ActorDescriptorIndex.collect(
                PACKAGE, original, witness, index, sha, "b".repeat(64));
        return new PackageNamespaceBuild.ActorAudit(
                witness, index, descriptors, sha, "b".repeat(64));
    }

    @Test
    void exactAstInstancesFromOneSnapshotCanEnterStaticPrehir() {
        var snapshot = parsed();
        var proof = syntheticAudit(snapshot);
        var result = ActorTypedMethodIR.checkSnapshot(PACKAGE, snapshot, proof);
        assertEquals(1, result.methods().size());
        assertEquals(1, result.actors().size());
        assertFalse(result.actors().values().iterator().next().executable());
    }

    @Test
    void reparsedSameByteMethodCannotReuseAnotherSnapshotsWitness() {
        var snapshot = parsed();
        var proof = syntheticAudit(snapshot);
        var substitute = new LinkedHashMap<>(snapshot);
        substitute.put("src/method.atto", Parser.parse(METHOD));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorTypedMethodIR.checkSnapshot(PACKAGE, substitute, proof));
        assertTrue(error.getMessage().contains("ACTOR_TYPED_IR_SOURCE_AST_IDENTITY_MISMATCH"),
                error.getMessage());
    }

    @Test
    void reparsedSameByteActorCannotReuseAnotherSnapshotsWitness() {
        var snapshot = parsed();
        var proof = syntheticAudit(snapshot);
        var substitute = new LinkedHashMap<>(snapshot);
        substitute.put("src/actor.atto", Parser.parse(ACTOR));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorTypedMethodIR.checkSnapshot(PACKAGE, substitute, proof));
        assertTrue(error.getMessage().contains("ACTOR_TYPED_IR_SOURCE_AST_IDENTITY_MISMATCH"),
                error.getMessage());
    }

    @Test
    void unrelatedFilesCannotSupplyMethodIdentityEvenWhenKeysMatch() {
        var snapshot = parsed();
        var proof = syntheticAudit(snapshot);
        var substitute = new LinkedHashMap<>(snapshot);
        substitute.put("src/method.atto", Parser.parse(NS
                + "pub mtd (Foo f) other(int x): int { return x; }"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorTypedMethodIR.checkSnapshot(PACKAGE, substitute, proof));
        assertTrue(error.getMessage().contains("ACTOR_TYPED_IR_SOURCE_AST_IDENTITY_MISMATCH"),
                error.getMessage());
    }
}
