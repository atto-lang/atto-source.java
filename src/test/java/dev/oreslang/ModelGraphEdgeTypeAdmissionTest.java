package dev.oreslang;

import dev.oreslang.compiler.PortableModelGraphDigestV1;
import dev.oreslang.compiler.PortableModelGraphDigestV1.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ModelGraphEdgeTypeAdmissionTest {
    private static final Profile PURE = new Profile(true, "none", true);
    private static final NominalPort PRICE = new NominalPort("finance.Price@v1", 16);
    private static Signature signature(List<Port> inputs) {
        return new Signature(inputs, PRICE, "pure", false, 0, "ores.model.op.v1",
                "a".repeat(64), "verified-ores", List.of(), PURE);
    }
    private static Graph withInputs(List<String> parents, List<Port> ports) {
        return new Graph(1, "finance.PriceEstimate@v1", List.of(
            new Node("source", "PriceInput", List.of(), List.of(), signature(List.of())),
            new Node("apply", "ApplyTax", parents, List.of(), signature(ports))
        ), List.of("apply"));
    }
    private static void deny(String expected, Graph graph) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PortableModelGraphDigestV1.digest(graph));
        assertEquals(expected, ex.getMessage());
    }
    @Test void rejectsMissingAndExtraParentEdges() {
        deny("MODEL_PORT_ARITY_MISMATCH", withInputs(List.of(), List.of(PRICE)));
        deny("MODEL_PORT_ARITY_MISMATCH", withInputs(List.of("source"), List.of()));
        deny("MODEL_PORT_ARITY_MISMATCH", withInputs(List.of("source", "source"), List.of(PRICE)));
    }
    @Test void rejectsNominalTypeAndBufferShapeAndUnitMismatch() {
        deny("MODEL_PORT_TYPE_MISMATCH", withInputs(List.of("source"),
                List.of(new NominalPort("finance.Weight@v1", 16))));
        deny("MODEL_PORT_TYPE_MISMATCH", withInputs(List.of("source"),
                List.of(new BufferPort("f64", List.of(2L), "contiguous", "USD"))));
    }
    @Test void rejectsUnrepresentableBufferByteCountAndAllowsZeroExtents() {
        Signature huge = new Signature(List.of(),
                new BufferPort("f64", List.of(Long.MAX_VALUE), "contiguous", null),
                "pure", false, 0, "ores.model.op.v1",
                "a".repeat(64), "verified-ores", List.of(), PURE);
        Graph overflow = new Graph(1, "models.Huge", List.of(
                new Node("large", "Huge", List.of(), List.of(), huge)), List.of("large"));
        deny("MODEL_SIZE_OVERFLOW", overflow);

        Signature empty = new Signature(List.of(),
                new BufferPort("f64", List.of(Long.MAX_VALUE, 0L), "contiguous", null),
                "pure", false, 0, "ores.model.op.v1",
                "a".repeat(64), "verified-ores", List.of(), PURE);
        Graph zero = new Graph(1, "models.Empty", List.of(
                new Node("empty", "Empty", List.of(), List.of(), empty)), List.of("empty"));
        assertEquals(64, PortableModelGraphDigestV1.digest(zero).length());

        Signature unit = new Signature(List.of(),
                new BufferPort("f64", List.of(1L), "contiguous", "bad unit"),
                "pure", false, 0, "ores.model.op.v1",
                "a".repeat(64), "verified-ores", List.of(), PURE);
        Graph invalidUnit = new Graph(1, "models.InvalidUnit", List.of(
                new Node("unit", "Unit", List.of(), List.of(), unit)), List.of("unit"));
        deny("MODEL_INVALID_PORT", invalidUnit);
    }

    @Test void admitsCompatibleEdgesAndRejectsForwardReference() {
        assertEquals(64, PortableModelGraphDigestV1.digest(
                withInputs(List.of("source"), List.of(PRICE))).length());
        deny("MODEL_FORWARD_OR_CYCLIC_EDGE", withInputs(List.of("missing"), List.of(PRICE)));
    }
}
