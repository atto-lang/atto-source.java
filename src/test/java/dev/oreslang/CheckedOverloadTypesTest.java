package dev.oreslang;

import dev.oreslang.ast.TypedOverloadResolver.TypeKey;
import dev.oreslang.types.CheckedOverloadTypes;
import dev.oreslang.types.Types;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CheckedOverloadTypesTest {
    @Test
    void resolvedPrimitiveAndStringLiteralsShareCanonicalRuntimeType() {
        assertEquals(TypeKey.primitive("int"),
                CheckedOverloadTypes.discriminator(Types.Primitive.INT, Map.of()));
        assertEquals(TypeKey.primitive("float"),
                CheckedOverloadTypes.discriminator(Types.Primitive.FLOAT, Map.of()));
        assertEquals(TypeKey.primitive("decimal"),
                CheckedOverloadTypes.discriminator(Types.Primitive.DECIMAL, Map.of()));
        assertEquals(TypeKey.primitive("bool"),
                CheckedOverloadTypes.discriminator(Types.Primitive.BOOL, Map.of()));
        assertEquals(TypeKey.primitive("String"),
                CheckedOverloadTypes.discriminator(new Types.StringLiteral("ready"), Map.of()));
        assertEquals(TypeKey.primitive("String"),
                CheckedOverloadTypes.discriminator(new Types.StringLiteral("failed"), Map.of()));
        assertEquals(TypeKey.primitive("String"),
                CheckedOverloadTypes.discriminator(Types.Primitive.STRING, Map.of()));
    }

    @Test
    void checkedNominalEnumIdentityIsNotFieldOrVariantShape() {
        Map<String, String> enums = Map.of(
                "Color", "graphics.Color",
                "Direction", "graphics.Direction");
        assertEquals(TypeKey.nominalEnum("graphics.Color"),
                CheckedOverloadTypes.discriminator(new Types.Named("Color", List.of()), enums));
        assertEquals(TypeKey.nominalEnum("graphics.Direction"),
                CheckedOverloadTypes.discriminator(new Types.Named("Direction", List.of()), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(new Types.Named("Color",
                        List.of(Types.Primitive.INT)), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(new Types.Named("UnknownClass", List.of()), enums));
    }

    @Test
    void borrowsStructuralRecordsUnionsUnknownsAndNullCannotBypassTypeSafety() {
        Map<String, String> enums = Map.of("Color", "__root__.Color");
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(
                        new Types.Borrow(new Types.Named("Color", List.of()), false), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(new Types.Generic("T"), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(
                        new Types.Record(Map.of("field", Types.Primitive.INT)), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(
                        new Types.Union(List.of(Types.Primitive.INT, Types.Primitive.FLOAT)), enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(Types.Primitive.NULL, enums));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedOverloadTypes.discriminator(Types.Primitive.VOID, enums));
        assertEquals(TypeKey.unknown(),
                CheckedOverloadTypes.discriminator(Types.Unknown.INSTANCE, enums));
    }
}
