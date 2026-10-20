package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ModelGraphAdmission;
import dev.oreslang.compiler.ModelGraphAdmission.Port;
import dev.oreslang.compiler.ModelGraphAdmission.Signature;
import dev.oreslang.compiler.ModelGraphAdmission.Limits;
import dev.oreslang.compiler.ModelGraphAdmission.ModelGraph;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Pure compile-time HIR admission; no actual Oreslang guest model execution. */
final class ModelGraphAdmissionTest {
    private static final Port USD = new Port("f64", List.of(), "USD");
    private static final Port KG = new Port("f64", List.of(), "kg");
    private static final Port VEC = new Port("f64", List.of(4L), "");
    private static final Limits DEFAULT = new Limits(64, 64, 4096);
    private static final String DIGEST = "0".repeat(64);

    private static Ast.ModelDecl source(String code) {
        return assertInstanceOf(Ast.ModelDecl.class,
                Parser.parse(code).modules().getFirst().declarations().getFirst());
    }

    private static Signature op(String name, Port input, Port output, boolean vjp) {
        return new Signature(name, input, output, "pure", "v1",
                DIGEST, "verified-ores", 0, vjp);
    }

    private static void rejected(String diagnostic, Ast.ModelDecl source,
                                 Map<String, Signature> registry, Limits limits) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ModelGraphAdmission.admit(source, registry, limits));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void nonMlModelGraphProducesDeterministicTypedPorts() {
        Ast.ModelDecl source = source("""
                define model Tax(rate: float = 0.15) as
                    Validate()
                    Apply(rate)
                    Round()
                end
                """);
        Map<String, Signature> trusted = Map.of(
                "Validate", op("Validate", USD, USD, false),
                "Apply", op("Apply", USD, USD, false),
                "Round", op("Round", USD, USD, false));
        ModelGraph first = ModelGraphAdmission.admit(source, trusted, DEFAULT);
        ModelGraph second = ModelGraphAdmission.admit(source, trusted, DEFAULT);
        assertEquals(first, second);
        assertEquals("Tax", first.name());
        assertEquals(3, first.nodes().size());
        assertEquals(32, first.upperBoundBytes()); // 1 input + 3 outputs, 8 bytes each
        assertEquals(USD, first.input());
        assertEquals(USD, first.output());
        assertFalse(first.nodes().getFirst().vjp()); // forward-only model is admissible
    }

    @Test
    void refusesUnregisteredOrEffectfulOperations() {
        Ast.ModelDecl s = source("define model X as Unsafe() end");
        rejected("MODEL_UNKNOWN_OP", s, Map.of(), DEFAULT);
        rejected("MODEL_EFFECTFUL_OP", s,
                Map.of("Unsafe", new Signature("Unsafe", USD, USD, "io", "v1",
                        DIGEST, "verified-ores", 0, true)), DEFAULT);
    }

    @Test
    void strictPortTypeAndUnitsDoNotImplicitlyConvert() {
        Ast.ModelDecl s = source("define model X as A() B() end");
        rejected("MODEL_PORT_TYPE_MISMATCH", s,
                Map.of("A", op("A", USD, USD, true), "B", op("B", KG, KG, true)), DEFAULT);
        rejected("MODEL_PORT_TYPE_MISMATCH", s,
                Map.of("A", op("A", USD, USD, true), "B", op("B", VEC, VEC, true)), DEFAULT);
    }

    @Test
    void rejectsUnsafeFormalDefaultsAndForgedOperationRegistryIdentity() {
        Ast.ModelDecl bad = source("define model X(rate: int = 0.15) as A() end");
        rejected("MODEL_FORMAL_DEFAULT_TYPE_MISMATCH", bad,
                Map.of("A", op("A", USD, USD, false)), DEFAULT);
        Ast.ModelDecl normal = source("define model X as A() end");
        rejected("MODEL_UNKNOWN_OP", normal,
                Map.of("A", op("NotA", USD, USD, false)), DEFAULT);
    }

    @Test
    void allBuffersAndScratchAreChargedWithoutOverflow() {
        Ast.ModelDecl s = source("define model X as A() end");
        rejected("MODEL_MEMORY_BUDGET", s, Map.of("A",
                new Signature("A", VEC, VEC, "pure", "v1",
                        DIGEST, "verified-ores", 100, false)),
                new Limits(64, 64, 150)); // input32 + output32 + scratch100 = 164
        rejected("MODEL_SIZE_OVERFLOW", s, Map.of("A", op("A", null,
                new Port("f64", List.of(Long.MAX_VALUE), ""), false)), DEFAULT);
        Port empty = new Port("f64", List.of(Long.MAX_VALUE, 0L), "");
        ModelGraph zero = ModelGraphAdmission.admit(s,
                Map.of("A", op("A", null, empty, false)), DEFAULT);
        assertEquals(0, zero.upperBoundBytes());
    }

    @Test
    void rejectsInvalidAbiAndUnsupportedDimensions() {
        Ast.ModelDecl s = source("define model X as A() end");
        rejected("MODEL_INVALID_OP_ABI", s,
                Map.of("A", new Signature("A", USD, USD, "pure", "",
                        DIGEST, "verified-ores", 0, false)), DEFAULT);
        rejected("MODEL_INVALID_EXTENT", s,
                Map.of("A", op("A", null, new Port("f64", List.of(-1L), ""), false)), DEFAULT);
        rejected("MODEL_INVALID_PORT", s,
                Map.of("A", op("A", null, new Port("f64", List.of(1L), "bad unit"), false)), DEFAULT);
    }
    @Test
    void staticConfigurationParticipatesInGraphIdentity() {
        Map<String, Signature> trusted = Map.of(
                "Apply", op("Apply", USD, USD, false));
        ModelGraph literalA = ModelGraphAdmission.admit(
                source("define model X as Apply(1) end"), trusted, DEFAULT);
        ModelGraph literalB = ModelGraphAdmission.admit(
                source("define model X as Apply(2) end"), trusted, DEFAULT);
        assertNotEquals(literalA, literalB);
        assertNotEquals(literalA.nodes().getFirst().config(),
                literalB.nodes().getFirst().config());

        ModelGraph defaultA = ModelGraphAdmission.admit(
                source("define model X(rate: float = 0.15) as Apply(rate) end"),
                trusted, DEFAULT);
        ModelGraph defaultB = ModelGraphAdmission.admit(
                source("define model X(rate: float = 0.20) as Apply(rate) end"),
                trusted, DEFAULT);
        assertNotEquals(defaultA, defaultB);
        assertNotEquals(defaultA.formalConfig(), defaultB.formalConfig());
    }


    @Test
    void implementationDigestAndProvenanceAreValidatedAndBound() {
        Ast.ModelDecl s = source("define model X as A() end");
        rejected("MODEL_INVALID_IMPL_DIGEST", s,
                Map.of("A", new Signature("A", USD, USD, "pure", "v1",
                        "not-a-digest", "verified-ores", 0, false)), DEFAULT);
        rejected("MODEL_INVALID_OP_PROVENANCE", s,
                Map.of("A", new Signature("A", USD, USD, "pure", "v1",
                        DIGEST, "guest-asserted", 0, false)), DEFAULT);
        String other = "1".repeat(64);
        ModelGraph first = ModelGraphAdmission.admit(s,
                Map.of("A", new Signature("A", USD, USD, "pure", "v1",
                        DIGEST, "verified-ores", 0, false)), DEFAULT);
        ModelGraph second = ModelGraphAdmission.admit(s,
                Map.of("A", new Signature("A", USD, USD, "pure", "v1",
                        other, "verified-ores", 0, false)), DEFAULT);
        assertNotEquals(first, second);
        assertNotEquals(first.nodes().getFirst().implDigest(),
                second.nodes().getFirst().implDigest());
    }


}
