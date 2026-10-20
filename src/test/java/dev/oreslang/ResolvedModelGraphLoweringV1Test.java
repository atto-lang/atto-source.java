package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PortableModelGraphDigestV1;
import dev.oreslang.compiler.PortableModelGraphDigestV1.*;
import dev.oreslang.compiler.ResolvedModelGraphLoweringV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ResolvedModelGraphLoweringV1Test {
    private static final NominalPort PRICE = new NominalPort("finance.Price@v1", 16);
    private static final Profile PROFILE = new Profile(true, "none", true);

    private static Signature sig(String digest, List<String> kinds, List<Port> inputs) {
        return new Signature(inputs, PRICE, "pure", false, 0, "ores.model.op.v1",
                digest, "verified-ores", kinds, PROFILE);
    }

    private static Map<String, Signature> registry() {
        return Map.of(
                "PriceInput", sig("1".repeat(64), List.of(), List.of()),
                "ApplyTaxBps", sig("2".repeat(64), List.of("i64"), List.of(PRICE)),
                "RoundCents", sig("3".repeat(64), List.of(), List.of(PRICE)));
    }

    private static Ast.ModelDecl source(String code) {
        return assertInstanceOf(Ast.ModelDecl.class,
                Parser.parse(code).modules().getFirst().declarations().getFirst());
    }

    @Test
    void sourceLoweringMatchesSharedPortableDigestVector() {
        Graph graph = ResolvedModelGraphLoweringV1.lower(
                source("define model PriceEstimate as PriceInput() ApplyTaxBps(1500) RoundCents() end"),
                "finance.PriceEstimate@v1", registry(), Map.of());
        assertEquals(544, PortableModelGraphDigestV1.canonicalBytes(graph).length);
        assertEquals("e4b3337bd3e8b71d8f062afc7fcad5646fa8ad567bfe009e3d02f599b3ee833c",
                PortableModelGraphDigestV1.digest(graph));
    }

    @Test
    void defaultsAndOverridesResolveBeforeHashing() {
        Ast.ModelDecl model = source(
                "define model PriceEstimate(tax_bps: int = 1500) as PriceInput() ApplyTaxBps(tax_bps) RoundCents() end");
        Graph byDefault = ResolvedModelGraphLoweringV1.lower(
                model, "finance.PriceEstimate@v1", registry(), Map.of());
        Graph overridden = ResolvedModelGraphLoweringV1.lower(
                model, "finance.PriceEstimate@v1", registry(),
                Map.of("tax_bps", new Ast.ModelValue(Ast.ModelValueKind.INTEGER, "1501")));
        assertEquals("e4b3337bd3e8b71d8f062afc7fcad5646fa8ad567bfe009e3d02f599b3ee833c",
                PortableModelGraphDigestV1.digest(byDefault));
        assertEquals("1dcfd1569621a30e53c969b9bf0e2add7fe94a04c2bf4474fae821555996d21b",
                PortableModelGraphDigestV1.digest(overridden));
    }

    @Test
    void unresolvedOrWrongTypedConfigurationFailsClosed() {
        Ast.ModelDecl missing = source(
                "define model X(tax_bps: int) as PriceInput() ApplyTaxBps(tax_bps) RoundCents() end");
        assertEquals("MODEL_NONCONSTANT_CONFIG",
                assertThrows(IllegalArgumentException.class,
                        () -> ResolvedModelGraphLoweringV1.lower(
                                missing, "finance.PriceEstimate@v1", registry(), Map.of())).getMessage());

        Ast.ModelDecl wrong = source(
                "define model X(flag: bool = true) as PriceInput() ApplyTaxBps(flag) RoundCents() end");
        assertEquals("MODEL_CONST_TYPE_MISMATCH",
                assertThrows(IllegalArgumentException.class,
                        () -> ResolvedModelGraphLoweringV1.lower(
                                wrong, "finance.PriceEstimate@v1", registry(), Map.of())).getMessage());
    }
    @Test
    void unaryExternalRunInputMatchesSharedDigestVector() {
        Map<String, Signature> external = Map.of(
                "ValidatePrice", sig("1".repeat(64), List.of(), List.of(PRICE)),
                "ApplyTaxBps", sig("2".repeat(64), List.of("i64"), List.of(PRICE)),
                "RoundCents", sig("3".repeat(64), List.of(), List.of(PRICE)));
        Graph graph = ResolvedModelGraphLoweringV1.lower(
                source("define model PriceTransform as ValidatePrice() ApplyTaxBps(1500) RoundCents() end"),
                "finance.PriceTransform@v1", external, Map.of());
        assertEquals(577, PortableModelGraphDigestV1.canonicalBytes(graph).length);
        assertEquals("069288245a672efe264581df42bfec4686192fdb210dca8ce7da7dda563ecac3",
                PortableModelGraphDigestV1.digest(graph));
        assertEquals(1, graph.nodes().getFirst().signature().inputs().size());
        assertTrue(graph.nodes().getFirst().inputs().isEmpty());
    }


}
