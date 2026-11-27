package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.oreslang.types.ParametricBundleRefinement.*;
import static org.junit.jupiter.api.Assertions.*;

final class ParametricBundleRefinementTest {
    private final List<Formal<Object>> schema = List.of(
            new Formal<>("type", v -> v instanceof String),
            new Formal<>("size", v -> v instanceof Integer i && i >= 0));
    private static Argument<Object> a(String n, Object v) { return new Argument<>(n, v); }
    private final List<Argument<Object>> seed = List.of(a("size", 5), a("type", "int"));

    @Test void refinementPreservesSeedAndCanonicalizes() {
        var result = refine(schema, seed, List.of(a("size", 6)));
        assertEquals(Map.of("type", "int", "size", 6), result);
        assertEquals(5, seed.get(0).value());
        assertEquals(List.of("type", "size"), new ArrayList<>(result.keySet()));
        assertEquals(result, refine(schema, List.of(a("type", "int"), a("size", 5)), List.of(a("size", 6))));
        assertThrows(UnsupportedOperationException.class, () -> result.put("size", 7));
        assertEquals(result, refine(schema, List.of(a("type", "int")), List.of(a("size", 6))));
        assertEquals(5, refine(schema, seed, List.of()).get("size"));
    }

    @Test void ordinaryApplicationRejectsEvenEqualOverlappingBindings() {
        assertEquals(Map.of("type", "int", "size", 6),
                apply(schema, List.of(a("type", "int")), List.of(a("size", 6))));
        assertThrows(IllegalArgumentException.class, () -> apply(schema, seed, List.of(a("size", 5))));
        assertThrows(IllegalArgumentException.class, () -> apply(schema, seed, List.of(a("size", 6))));
        assertThrows(IllegalArgumentException.class, () -> apply(schema, seed, List.of(a("unknown", 1))));
        assertEquals(5, apply(schema, seed, List.of()).get("size"));
    }

    @Test void inheritedInferenceRequiresExactlyTheRequestedEvidence() {
        assertEquals(Map.of("type", "int", "size", 3),
                refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", 3))));
        assertEquals(5, seed.get(0).value());
        assertEquals(0, refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", 0))).get("size"));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", 3), a("size", 3))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(a("size", 6)), List.of("size"), List.of(a("size", 3))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", 3), a("type", "int"))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size", "size"), List.of(a("size", 3))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed.subList(1, 2), List.of(), List.of("size"), List.of(a("size", 3))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", -1))));
        assertThrows(IllegalArgumentException.class, () -> refineInferred(schema, seed, List.of(), List.of("size"), List.of(a("size", "3"))));
    }

    @Test void rejectsDuplicatesUnknownKeysAndInvalidKinds() {
        assertThrows(IllegalArgumentException.class, () -> refine(schema, seed, List.of(a("size", 6), a("size", 6))));
        assertThrows(IllegalArgumentException.class, () -> refine(schema, List.of(a("size", 5), a("size", 5)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> refine(schema, seed, List.of(a("unknown", 1))));
        assertThrows(IllegalArgumentException.class, () -> refine(schema, seed, List.of(a("size", "6"))));
        assertThrows(IllegalArgumentException.class, () -> refine(schema, seed, List.of(a("size", -1))));
        assertThrows(IllegalArgumentException.class, () -> refine(List.of(schema.get(0), schema.get(0)), seed, List.of()));
    }
}
