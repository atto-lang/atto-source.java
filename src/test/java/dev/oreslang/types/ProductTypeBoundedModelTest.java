package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded exhaustive checks of alias-safe product-type assignability invariants. */
final class ProductTypeBoundedModelTest {
    private static final Types.Primitive[] SCALARS = {
            Types.Primitive.INT, Types.Primitive.FLOAT,
            Types.Primitive.STRING, Types.Primitive.BOOL,
            Types.Primitive.DECIMAL
    };

    @Test
    void recordFieldStorageIdentitySurvivesEveryPrimitiveProjection() {
        for (Types.Primitive source : SCALARS) {
            for (Types.Primitive target : SCALARS) {
                Types.Record readonly = new Types.Record(Map.of("key", source), true);
                Types.Record readonlyTarget = new Types.Record(Map.of("key", target), true);
                Types.Record writable = new Types.Record(Map.of("key", source), false);
                Types.Record writableTarget = new Types.Record(Map.of("key", target), false);
                assertEquals(source == target, Types.isAssignable(readonly, readonlyTarget),
                        "readonly field projection must not coerce storage: " + source + " => " + target);
                assertEquals(source == target, Types.isAssignable(writable, writableTarget),
                        "writable field must be invariant: " + source + " => " + target);
                assertFalse(Types.isAssignable(readonly, writableTarget),
                        "read-only alias must never gain write permission");
                assertEquals(source == target,
                        Types.isAssignable(new Types.Tuple(List.of(source)), new Types.Tuple(List.of(target))),
                        "tuple slot must not coerce stored representation");
            }
        }
    }

    @Test
    void exactTupleArityAndSequenceKindNeverConvert() {
        for (int arity = 0; arity <= 5; arity++) {
            List<Types.Type> slots = java.util.Collections.nCopies(arity, Types.Primitive.INT);
            Types.Tuple tuple = new Types.Tuple(slots);
            assertTrue(Types.isAssignable(tuple, new Types.Tuple(slots)));
            assertFalse(Types.isAssignable(tuple,
                    new Types.Tuple(java.util.Collections.nCopies(arity + 1, Types.Primitive.INT))));
            assertFalse(Types.isAssignable(tuple, new Types.Tuple(slots, Types.TupleKind.FIXED_ARRAY)));
            assertFalse(Types.isAssignable(tuple, new Types.Tuple(slots, Types.TupleKind.FIXED_LIST)));
        }
    }

    @Test
    void borrowingCannotCreateAuthorityOrChangeStorageType() {
        for (Types.Primitive source : SCALARS) {
            for (Types.Primitive target : SCALARS) {
                Types.Borrow shared = new Types.Borrow(source, false);
                assertFalse(Types.isAssignable(shared, new Types.Borrow(target, true)),
                        "shared borrow may not be upgraded");
                assertEquals(source == target,
                        Types.isAssignable(shared, new Types.Borrow(target, false)),
                        "borrow must preserve target representation");
            }
        }
    }
}
