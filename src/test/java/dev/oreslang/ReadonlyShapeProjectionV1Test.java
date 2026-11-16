package dev.oreslang;

import dev.oreslang.types.ReadonlyShapeProjectionV1;
import dev.oreslang.types.ReadonlyShapeProjectionV1.*;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReadonlyShapeProjectionV1Test {
    private static Field field(String name, Scalar type, int slot) {
        return new Field(name, type, slot, true, true, true, false);
    }
    private static Source source(Origin kind, List<Field> fields) {
        return new Source(kind, 7, 1, 9, false, fields);
    }
    private static List<RequiredField> x() {
        return List.of(new RequiredField("x", Scalar.I64));
    }
    private static Plan plan(Source s) {
        return ReadonlyShapeProjectionV1.prove(s, x());
    }
    private static void reject(Source s) {
        assertThrows(IllegalArgumentException.class, () -> plan(s));
    }
    @Test void sp001ClassAOffsetZero() {
        assertEquals(0, plan(source(Origin.CLASS, List.of(field("x", Scalar.I64, 0))))
                .field("x").sourceSlot());
    }
    @Test void sp002ClassBOffsetOneAndHostOracle84() {
        var a = plan(source(Origin.CLASS, List.of(field("x", Scalar.I64, 0))));
        var b = plan(source(Origin.CLASS, List.of(
                field("padding", Scalar.I64, 0), field("x", Scalar.I64, 1))));
        var aData = List.of(42L);
        var bData = List.of(10L, 42L);
        assertEquals(84L, aData.get(a.field("x").sourceSlot())
                + bData.get(b.field("x").sourceSlot()));
        assertEquals(7, b.sourceNominalId());
        assertEquals(9, b.loanId());
    }
    @Test void sp003ExtraPublicFieldAccepted() {
        assertEquals(1, plan(source(Origin.RECORD, List.of(
                field("extra", Scalar.I64, 0), field("x", Scalar.I64, 1))))
                .field("x").sourceSlot());
        assertEquals(1, plan(source(Origin.STRUCT, List.of(
                field("extra", Scalar.I64, 0), field("x", Scalar.I64, 1))))
                .field("x").sourceSlot());
    }
    @Test void sp004PrivateFieldRejected() {
        reject(source(Origin.CLASS, List.of(new Field("x", Scalar.I64, 0,
                false, true, true, false))));
    }
    @Test void sp005MissingFieldRejected() {
        reject(source(Origin.CLASS, List.of(field("y", Scalar.I64, 0))));
    }
    @Test void sp006WrongTypeRejected() {
        reject(source(Origin.CLASS, List.of(field("x", Scalar.BOOL, 0))));
    }
    @Test void sp007SealedActorRejected() {
        reject(new Source(Origin.CLASS, 7, 1, 9, true,
                List.of(field("x", Scalar.I64, 0))));
        reject(source(Origin.CLASS, List.of(new Field("x", Scalar.I64, 0,
                true, true, true, true))));
    }
    @Test void sp008DynamicMapRejected() {
        reject(source(Origin.MAP, List.of(field("x", Scalar.I64, 0))));
        reject(source(Origin.ACTOR, List.of(field("x", Scalar.I64, 0))));
    }
    @Test void sp009MutableFieldRejected() {
        reject(source(Origin.CLASS, List.of(new Field("x", Scalar.I64, 0,
                true, false, true, false))));
    }
    @Test void sp010StaleLayoutRejected() {
        reject(new Source(Origin.CLASS, 7, 0, 9, false,
                List.of(field("x", Scalar.I64, 0))));
    }
    @Test void sp011ExpiredLoanRejected() {
        reject(new Source(Origin.CLASS, 7, 1, 0, false,
                List.of(field("x", Scalar.I64, 0))));
    }
    @Test void sp012DuplicateSourceNameRejected() {
        reject(source(Origin.CLASS, List.of(
                field("x", Scalar.I64, 0), field("x", Scalar.I64, 1))));
    }
    @Test void sp013OverlappingSourceOffsetsRejected() {
        reject(source(Origin.CLASS, List.of(
                field("x", Scalar.I64, 0), field("y", Scalar.I64, 0))));
    }
    @Test void sp014EmptyRequiredShapeRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ReadonlyShapeProjectionV1.prove(
                        source(Origin.CLASS, List.of(field("x", Scalar.I64, 0))), List.of()));
    }
    @Test void sp015MissingSourceIdentityRejected() {
        reject(new Source(Origin.CLASS, 0, 1, 9, false,
                List.of(field("x", Scalar.I64, 0))));
    }
    @Test void sp016DuplicateRequiredFieldRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ReadonlyShapeProjectionV1.prove(
                        source(Origin.CLASS, List.of(field("x", Scalar.I64, 0))),
                        List.of(new RequiredField("x", Scalar.I64),
                                new RequiredField("x", Scalar.I64))));
    }
    @Test void matchingNonI64TypesAreNotYetAnExecutableAbi() {
        assertThrows(IllegalArgumentException.class, () ->
                ReadonlyShapeProjectionV1.prove(
                        source(Origin.CLASS, List.of(field("x", Scalar.BOOL, 0))),
                        List.of(new RequiredField("x", Scalar.BOOL))));
        assertThrows(IllegalArgumentException.class, () ->
                ReadonlyShapeProjectionV1.prove(
                        source(Origin.CLASS, List.of(field("x", Scalar.STRING, 0))),
                        List.of(new RequiredField("x", Scalar.STRING))));
    }
    @Test void callerCannotMutateProofFieldList() {
        var plan = plan(source(Origin.CLASS, List.of(field("x", Scalar.I64, 0))));
        assertThrows(UnsupportedOperationException.class, () ->
                plan.fields().add(new FieldWitness("write", Scalar.I64, 3)));
    }
}
