package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.V3SourceContractFingerprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial parser-independent, generated-AST admission for v3 source
 * contract hashing. Does not confer trusted ABI or nominal type identity.
 */
final class V3SourceContractFingerprintBudgetTest {
    private static Ast.Param formal(Ast.TypeRef type) {
        return new Ast.Param(type, "value");
    }

    @Test
    void wideGeneratedTypeIsRejectedAtFrontierBeforeBulkEnqueue() {
        Ast.TypeRef scalar = Ast.TypeRef.simple("int");
        List<Ast.TypeRef> args = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            args.add(scalar);
        }
        Ast.TypeRef malicious = new Ast.TypeRef("GeneratedWide", args, false);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> V3SourceContractFingerprint.sha256Hex(
                        List.of(formal(malicious)), List.of()));
        assertTrue(failure.getMessage().contains("budget"), failure::toString);
    }

    @Test
    void manyShallowFormalRootsAlsoRespectGlobalBudget() {
        List<Ast.Param> params = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            params.add(new Ast.Param(Ast.TypeRef.simple("int"), "value" + i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> V3SourceContractFingerprint.sha256Hex(params, List.of()));
    }

    @Test
    void deepGeneratedTypeIsRejectedWithoutRecursiveTraversal() {
        Ast.TypeRef type = Ast.TypeRef.simple("int");
        for (int i = 0; i < 70; i++) {
            type = new Ast.TypeRef("Depth", List.of(type), false);
        }
        final Ast.TypeRef overdeep = type;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> V3SourceContractFingerprint.sha256Hex(
                        List.of(formal(overdeep)), List.of()));
        assertTrue(failure.getMessage().contains("budget"), failure::toString);
    }

    @Test
    void ordinaryScalarContractProducesStableHash() {
        List<Ast.Param> params = List.of(
                new Ast.Param(Ast.TypeRef.simple("int"), "a"),
                new Ast.Param(Ast.TypeRef.simple("bool"), "enabled"));
        String first = V3SourceContractFingerprint.sha256Hex(params, List.of());
        String again = V3SourceContractFingerprint.sha256Hex(params, List.of());
        assertEquals(first, again);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void formalTypeOrderAffectsContractHash() {
        List<Ast.Param> first = List.of(
                new Ast.Param(Ast.TypeRef.simple("int"), "a"),
                new Ast.Param(Ast.TypeRef.simple("bool"), "b"));
        List<Ast.Param> second = List.of(
                new Ast.Param(Ast.TypeRef.simple("bool"), "b"),
                new Ast.Param(Ast.TypeRef.simple("int"), "a"));
        assertNotEquals(
                V3SourceContractFingerprint.sha256Hex(first, List.of()),
                V3SourceContractFingerprint.sha256Hex(second, List.of()));
    }
}
