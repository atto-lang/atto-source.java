package dev.oreslang.types;

import dev.oreslang.compiler.ZordeSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import static dev.oreslang.types.Types.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Internal type-identity admission only; Oreslang Valid<T,S> syntax is still
 * rejected and there is no guest proof construction, transfer or codegen.
 */
final class ZordeCheckedTypeIdentityTest {

    private static Type checkedPort(String source, long value) {
        var schema = ZordeSchemaGenerator.planSource(source).schema("app.Port");
        return checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.Port", schema, value);
    }

    @Test void proofCarrierCannotBeNamedOrConstructedByPublicApi() {
        var plan = ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=1,max=10) type Port = int;");
        Type checked = checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.Port", plan.schema("app.Port"), 3L);
        assertTrue(containsCompilerCheckedScalar(checked));
        assertFalse(checked instanceof Named);
        assertTrue(checked.toString().contains("app.Port"));
        var permitted = Type.class.getPermittedSubclasses();
        var internal = java.util.Arrays.stream(permitted)
                .filter(t -> t.getName().endsWith("$CheckedScalarType"))
                .findFirst().orElseThrow();
        assertTrue(Modifier.isPrivate(internal.getModifiers()));
        assertTrue(java.util.Arrays.stream(internal.getDeclaredConstructors())
                .allMatch(c -> Modifier.isPrivate(c.getModifiers())));

        Type fake = new Named("Valid", List.of(Primitive.INT, Primitive.STRING));
        Type namespaceSpoof = new Named("attacker.Valid",
                List.of(Primitive.INT, Primitive.STRING));
        assertFalse(isAssignable(fake, checked));
        assertFalse(isAssignable(namespaceSpoof, checked));
        assertFalse(isAssignable(checked, fake));
        assertFalse(isAssignable(Primitive.INT, checked));
        assertFalse(isAssignable(checked, Primitive.INT));
    }

    @Test void unknownGenericAndNestedTypeCannotForgeOrEraseProof() {
        Type checked = checkedPort(
                "namespace app; @Range(min=1,max=10) type Port = int;", 3L);
        assertTrue(isAssignable(checked, checked));
        assertFalse(isAssignable(Unknown.INSTANCE, checked));
        assertFalse(isAssignable(checked, Unknown.INSTANCE));
        assertFalse(isAssignable(new Generic("T"), checked));
        assertFalse(isAssignable(checked, new Generic("T")));

        Type concreteOption = new Named("Option", List.of(checked));
        Type unknownOption = new Named("Option", List.of(Unknown.INSTANCE));
        Type genericOption = new Named("Option", List.of(new Generic("T")));
        assertTrue(containsCompilerCheckedScalar(concreteOption));
        assertTrue(isAssignable(concreteOption, concreteOption));
        assertFalse(isAssignable(unknownOption, concreteOption));
        assertFalse(isAssignable(concreteOption, unknownOption));
        assertFalse(isAssignable(genericOption, concreteOption));
        assertFalse(isAssignable(concreteOption, genericOption));
        assertFalse(isAssignable(Unknown.INSTANCE, concreteOption));
        assertFalse(isAssignable(concreteOption, Unknown.INSTANCE));

        Type actualRecord = new Record(Map.of("proof", checked));
        Type forgedRecord = new Record(Map.of("proof", Unknown.INSTANCE));
        assertFalse(isAssignable(forgedRecord, actualRecord));
        assertFalse(isAssignable(actualRecord, forgedRecord));
        assertFalse(isAssignable(new Borrow(Unknown.INSTANCE, false),
                new Borrow(checked, false)));
        assertFalse(isAssignable(new Borrow(checked, false),
                new Borrow(Unknown.INSTANCE, false)));
        assertFalse(isAssignable(new Function(List.of(Unknown.INSTANCE), Primitive.INT),
                new Function(List.of(checked), Primitive.INT)));
        assertThrows(IllegalArgumentException.class,
                () -> unionOf(List.of(Unknown.INSTANCE, checked)));
        assertThrows(IllegalArgumentException.class, () -> notOf(checked));

        Type knownUnion = unionOf(List.of(checked, Primitive.INT));
        assertTrue(isAssignable(checked, knownUnion));
        assertFalse(isAssignable(Unknown.INSTANCE, knownUnion));
    }

    @Test void identityIncludesSchemaNameDigestAndExactScalarKind() {
        var source = "namespace app; @Range(min=1,max=10) type Port = int; "
                + "@Range(min=1,max=10) type SameValues = int;";
        var plan = ZordeSchemaGenerator.planSource(source);
        var port = checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.Port", plan.schema("app.Port"), 5L);
        var sameContractDifferentName = checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.SameValues", plan.schema("app.SameValues"), 5L);
        var newerSchema = checkedPort(
                "namespace app; @Range(min=2,max=10) type Port = int;", 5L);
        var portCopy = checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.Port", plan.schema("app.Port"), 9L);
        assertEquals(port, portCopy);
        assertEquals(port.hashCode(), portCopy.hashCode());
        assertNotEquals(port, sameContractDifferentName);
        assertNotEquals(port, newerSchema);
        assertFalse(isAssignable(port, sameContractDifferentName));
        assertFalse(isAssignable(port, newerSchema));

        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.STRING, "app.Port", plan.schema("app.Port"), "5"));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.INT, "app.Port", plan.schema("app.Port"), 11L));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.INT, "Port", plan.schema("app.Port"), 5L));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.INT, "app.Port", plan.schema("app.Port"), 5.0));
    }

    @Test void stringSchemaMustCheckUnicodeAndNominalIdentity() {
        var plan = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) define newtype User(string);");
        var descriptor = plan.schema("app.User");
        var unicode = checkedScalarTypeForCompilerAnalysis(
                Primitive.STRING, "app.User", descriptor, "😀");
        assertTrue(isAssignable(unicode, unicode));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.STRING, "app.User", descriptor, ""));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.STRING, "app.User", descriptor,
                        String.valueOf((char) 0xD800)));
        assertThrows(IllegalArgumentException.class,
                () -> checkedScalarTypeForCompilerAnalysis(
                        Primitive.STRING, "attacker.User", descriptor, "😀"));
    }
}
