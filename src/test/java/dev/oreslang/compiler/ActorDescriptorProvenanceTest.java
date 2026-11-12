package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Direct static descriptor construction is NOT itself an authenticated source-set API. */
final class ActorDescriptorProvenanceTest {
    private static final String NS = "namespace foo;\n";
    private static final String DECL = NS + "declare pub actor Foo implements ActorApi;";
    private static final String CONTRACT = NS
            + "define pub contract ActorApi for actor as mtd bar(int x): int; end";
    private static final String METHOD = NS
            + "pub mtd (Foo f) bar(int x): int { return x; }";

    private static Map<String, Ast.Program> parsed() {
        Map<String, Ast.Program> source = new LinkedHashMap<>();
        source.put("src/actor.atto", Parser.parse(DECL));
        source.put("src/contract.atto", Parser.parse(CONTRACT));
        source.put("src/method.atto", Parser.parse(METHOD));
        return source;
    }

    private static ActorDescriptorIndex derive(
            String packageId, Map<String, Ast.Program> sources,
            Map<String, String> hashes, String manifestHash) {
        var witness = ActorContractWitness.linkSourceSet("demo@1.0.0", sources);
        var index = ActorMethodIndex.collect("demo@1.0.0", sources,
                witness.boundMethods());
        return ActorDescriptorIndex.collect(packageId, sources,
                witness, index, hashes, manifestHash);
    }

    private static Map<String, String> shapeHashes(Map<String, Ast.Program> sources) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String source : sources.keySet()) result.put(source, "a".repeat(64));
        return result;
    }

    @Test
    void missingUnitDigestCannotMintActorDescriptor() {
        var sources = parsed();
        var hashes = shapeHashes(sources);
        hashes.remove("src/contract.atto");
        var error = assertThrows(IllegalArgumentException.class,
                () -> derive("demo@1.0.0", sources, hashes, "b".repeat(64)));
        assertTrue(error.getMessage().contains("PROVENANCE_MISMATCH"),
                error.getMessage());
    }

    @Test
    void malformedSha256CannotMintActorDescriptor() {
        var sources = parsed();
        var hashes = shapeHashes(sources);
        hashes.put("src/method.atto", "not-a-digest");
        var error = assertThrows(IllegalArgumentException.class,
                () -> derive("demo@1.0.0", sources, hashes, "b".repeat(64)));
        assertTrue(error.getMessage().contains("invalid verified source fingerprint"),
                error.getMessage());
    }

    @Test
    void foreignPackageNameCannotUseAnotherPackagesWitness() {
        var sources = parsed();
        var error = assertThrows(IllegalArgumentException.class,
                () -> derive("foreign@1.0.0", sources,
                        shapeHashes(sources), "b".repeat(64)));
        assertTrue(error.getMessage().contains("PROVENANCE_MISMATCH"),
                error.getMessage());
    }

    @Test
    void ordinaryInterfaceDoesNotProduceActorDescriptor() {
        var sources = parsed();
        sources.put("src/contract.atto", Parser.parse(NS
                + "define pub interface ActorApi as fnc bar(int x): int; end"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> derive("demo@1.0.0", sources,
                        shapeHashes(sources), "b".repeat(64)));
        assertTrue(error.getMessage().contains("missing nominal actor interface contract"),
                error.getMessage());
    }
}
