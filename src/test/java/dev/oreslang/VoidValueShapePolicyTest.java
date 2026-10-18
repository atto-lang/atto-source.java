package dev.oreslang;

import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Primitive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VoidValueShapePolicyTest {
    @Test
    void onlyExplicitTaggedCompletionWrappersCanHoldVoid() {
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Named("Future", List.of(Primitive.VOID)), "future"));
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Named("Option", List.of(Primitive.VOID)), "option"));
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Named("Result", List.of(Primitive.VOID, Primitive.STRING)), "result"));
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Named("Future", List.of(
                        new Types.Named("Option", List.of(Primitive.VOID)))), "nested"));
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Named("Option", List.of(
                        new Types.Named("Result", List.of(Primitive.VOID, Primitive.STRING)))), "nested"));
    }

    @Test
    void voidCannotHideInUnknownUserGenericOrResultErrorType() {
        rejects("generic argument", new Types.Named("Box", List.of(Primitive.VOID)));
        rejects("Result error", new Types.Named("Result", List.of(
                Primitive.STRING, Primitive.VOID)));
        rejects("generic argument", new Types.Named("Future", List.of(
                new Types.Named("Box", List.of(Primitive.VOID)))));
    }

    @Test
    void voidCannotHideInsideArrayTupleRecordOrUnion() {
        rejects("collection element", new Types.ListType(Primitive.VOID));
        rejects("tuple element", new Types.Tuple(List.of(Primitive.INT, Primitive.VOID)));
        rejects(".nested", new Types.Record(Map.of(
                "nested", new Types.ListType(Primitive.VOID))));
        rejects("union case", new Types.Union(List.of(Primitive.INT, Primitive.VOID)));
    }

    @Test
    void callableMayReturnVoidButMayNotAcceptVoidParameter() {
        assertDoesNotThrow(() -> Types.requireValueShape(
                new Types.Function(List.of(Primitive.INT), Primitive.VOID), "callable"));
        rejects("callable parameter", new Types.Function(List.of(Primitive.VOID),
                Primitive.INT));
    }

    private static void rejects(String fragment, Types.Type type) {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> Types.requireValueShape(type, "slot"));
        assertTrue(rejected.getMessage().contains(fragment),
                rejected::getMessage);
    }
}
