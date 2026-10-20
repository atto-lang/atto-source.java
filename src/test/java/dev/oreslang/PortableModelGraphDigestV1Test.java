package dev.oreslang;

import dev.oreslang.compiler.PortableModelGraphDigestV1;
import dev.oreslang.compiler.PortableModelGraphDigestV1.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class PortableModelGraphDigestV1Test {
    private static final NominalPort PRICE = new NominalPort("finance.Price@v1", 16);
    private static final Profile PROFILE = new Profile(true, "none", true);

    private static Signature sig(String digest, List<String> kinds, List<Port> inputs) {
        return new Signature(inputs, PRICE, "pure", false, 0, "ores.model.op.v1",
                digest, "verified-ores", kinds, PROFILE);
    }

    private static Graph graph(String taxBps, String taxDigest) {
        Node source = new Node("source", "finance.PriceInput", List.of(), List.of(),
                sig("1".repeat(64), List.of(), List.of()));
        Node tax = new Node("tax", "finance.ApplyTaxBps", List.of("source"),
                List.of(new Constant("i64", taxBps)),
                sig(taxDigest, List.of("i64"), List.of(PRICE)));
        Node round = new Node("round", "finance.RoundCents", List.of("tax"), List.of(),
                sig("3".repeat(64), List.of(), List.of(PRICE)));
        return new Graph(1, "finance.PriceEstimate@v1",
                List.of(source, tax, round), List.of("round"));
    }

    @Test
    void matchesSharedGoldenVectors() {
        Graph base = graph("1500", "2".repeat(64));
        assertEquals(544, PortableModelGraphDigestV1.canonicalBytes(base).length);
        assertEquals("e4b3337bd3e8b71d8f062afc7fcad5646fa8ad567bfe009e3d02f599b3ee833c",
                PortableModelGraphDigestV1.digest(base));
        assertEquals("1dcfd1569621a30e53c969b9bf0e2add7fe94a04c2bf4474fae821555996d21b",
                PortableModelGraphDigestV1.digest(graph("1501", "2".repeat(64))));
        assertEquals("cdb96a21464b7fbeeaa3c7fade033780785c7849bf15a6033634b060b597e5ce",
                PortableModelGraphDigestV1.digest(graph("1500", "4".repeat(64))));
    }

    @Test
    void temporaryNodeIdsDoNotChangePortableIdentity() {
        Graph base = graph("1500", "2".repeat(64));
        List<Node> n = base.nodes();
        Graph renamed = new Graph(1, base.modelTypeId(), List.of(
                new Node("a", n.get(0).op(), List.of(), n.get(0).constArgs(), n.get(0).signature()),
                new Node("b", n.get(1).op(), List.of("a"), n.get(1).constArgs(), n.get(1).signature()),
                new Node("c", n.get(2).op(), List.of("b"), n.get(2).constArgs(), n.get(2).signature())
        ), List.of("c"));
        assertEquals(PortableModelGraphDigestV1.digest(base),
                PortableModelGraphDigestV1.digest(renamed));
    }

    @Test
    void invalidIdentityInputsFailClosed() {
        Graph base = graph("1500", "2".repeat(64));
        Node tax = base.nodes().get(1);
        Graph forward = new Graph(1, base.modelTypeId(), List.of(
                base.nodes().get(0),
                new Node(tax.id(), tax.op(), List.of("round"), tax.constArgs(), tax.signature()),
                base.nodes().get(2)), base.outputs());
        assertEquals("MODEL_FORWARD_OR_CYCLIC_EDGE",
                assertThrows(IllegalArgumentException.class,
                        () -> PortableModelGraphDigestV1.digest(forward)).getMessage());
        assertEquals("MODEL_CONST_OVERFLOW",
                assertThrows(IllegalArgumentException.class,
                        () -> PortableModelGraphDigestV1.digest(graph("9223372036854775808", "2".repeat(64)))).getMessage());
    }
    @Test
    void orderedV1AllowsOnlyUnaryExternalInputBoundary() {
        Signature unary = sig("5".repeat(64), List.of(), List.of(PRICE));
        Graph allowed = new Graph(1, "finance.PriceTransform@v1",
                List.of(new Node("n0", "ValidatePrice", List.of(), List.of(), unary)),
                List.of("n0"));
        assertEquals(64, PortableModelGraphDigestV1.digest(allowed).length());

        Signature binary = sig("6".repeat(64), List.of(), List.of(PRICE, PRICE));
        Graph denied = new Graph(1, "finance.Bad@v1",
                List.of(new Node("n0", "BinaryInput", List.of(), List.of(), binary)),
                List.of("n0"));
        assertEquals("MODEL_PORT_ARITY_MISMATCH",
                assertThrows(IllegalArgumentException.class,
                        () -> PortableModelGraphDigestV1.digest(denied)).getMessage());
    }


}
