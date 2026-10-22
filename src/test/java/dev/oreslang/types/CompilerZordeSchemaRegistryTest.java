package dev.oreslang.types;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static dev.oreslang.types.Types.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-local schema admission STAGING tests. Proof types remain unavailable
 * to Oreslang source, actor transport, user FFI, and executable codegen.
 */
final class CompilerZordeSchemaRegistryTest {
    private static final String SCHEMAS =
            "namespace app; @Range(min=1,max=10) type Port = int; "
            + "@MinLength(1) define newtype User(string);";

    @Test void sameRegistryCarriesExactCompilationSealAndDescriptor() {
        var registry = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                SCHEMAS, "service.api", 7);
        assertEquals("service.api", registry.unitId());
        assertEquals(7, registry.generation());
        assertEquals(2, registry.size());
        assertEquals(64, registry.sourceDigest().length());
        assertEquals("", registry.descriptor("app.Port").nominalIdentity());
        Type a = registry.checkedTypeForAnalysis("app.Port", 5L);
        Type b = registry.checkedTypeForAnalysis("app.Port", 8L);
        assertEquals(a, b);
        assertTrue(isAssignable(a, b));
        assertTrue(containsCompilerCheckedScalar(a));
        assertFalse(isAssignable(Primitive.INT, a));
        assertFalse(isAssignable(a, Primitive.INT));
        assertEquals("string", registry.descriptor("app.User").base());
        assertNotNull(registry.checkedTypeForAnalysis("app.User", "😀"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.checkedTypeForAnalysis("app.Port", 11L));
        assertThrows(IllegalArgumentException.class,
                () -> registry.checkedTypeForAnalysis("app.Port", 5.0));
        assertThrows(IllegalArgumentException.class,
                () -> registry.checkedTypeForAnalysis("app.User", ""));
        assertThrows(IllegalArgumentException.class,
                () -> registry.checkedTypeForAnalysis("app.DoesNotExist", 5L));
    }

    @Test void sameNamesHashesAndUserProvidedGenerationDoNotMintCrossBuildProofs() {
        var left = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                SCHEMAS, "service.api", 7);
        var anotherBuild = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                SCHEMAS, "service.api", 7);
        var newerGeneration = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                SCHEMAS, "service.api", 8);
        var changedSource = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                "namespace app; @Range(min=2,max=10) type Port = int;",
                "service.api", 7);
        Type proof = left.checkedTypeForAnalysis("app.Port", 5L);
        Type sameSource = anotherBuild.checkedTypeForAnalysis("app.Port", 5L);
        Type changed = changedSource.checkedTypeForAnalysis("app.Port", 5L);
        Type newer = newerGeneration.checkedTypeForAnalysis("app.Port", 5L);
        assertNotEquals(proof, sameSource);
        assertNotEquals(proof, newer);
        assertNotEquals(proof, changed);
        assertFalse(isAssignable(proof, sameSource));
        assertFalse(isAssignable(proof, newer));
        assertFalse(isAssignable(proof, changed));
        assertFalse(isAssignable(
                new Named("Option", List.of(proof)),
                new Named("Option", List.of(sameSource))));
        assertFalse(isAssignable(
                new Types.Record(java.util.Map.of("value", proof)),
                new Types.Record(java.util.Map.of("value", newer))));
        assertFalse(isAssignable(new Named("Valid", List.of(Primitive.INT)), proof));
        assertEquals(left.descriptor("app.Port").fingerprint(),
                anotherBuild.descriptor("app.Port").fingerprint());
        assertEquals(left.sourceDigest(), anotherBuild.sourceDigest());
        assertEquals(left.sourceDigest(), newerGeneration.sourceDigest());
        assertNotEquals(left.sourceDigest(), changedSource.sourceDigest());

        // A public descriptor of identical data is NOT the admitted object.
        RefinementRules.Descriptor original = left.descriptor("app.Port");
        var userDescriptor = new RefinementRules.Descriptor(
                original.version(), original.base(), original.property(),
                original.min(), original.max(), original.excluded(),
                original.nominalIdentity(), original.fingerprint());
        assertEquals(original, userDescriptor);
        assertThrows(IllegalArgumentException.class, () ->
                Types.checkedScalarTypeFromRegistry(
                        Primitive.INT, "app.Port", userDescriptor, left));
        assertThrows(IllegalArgumentException.class, () ->
                Types.checkedScalarTypeFromRegistry(
                        Primitive.STRING, "app.Port", original, left));
        assertThrows(IllegalArgumentException.class, () ->
                Types.checkedScalarTypeFromRegistry(
                        Primitive.INT, "app.Port",
                        anotherBuild.descriptor("app.Port"), left));
        // The legacy analysis factory has no registry seal and cannot spoof it.
        Type legacy = Types.checkedScalarTypeForCompilerAnalysis(
                Primitive.INT, "app.Port", original, 5L);
        assertFalse(isAssignable(legacy, proof));
        assertFalse(isAssignable(proof, legacy));
    }

    @Test void stagingRejectsDynamicOrUnresolvedSchemasAndInvalidUnitMetadata() {
        for (String source : new String[] {
                "pub fnc main(): void { return; }",
                "namespace app; const S = userValidator(); type Port = Zorde.infer<S>;",
                "namespace app; type Port = int with value gte 1; type Port = int with value gte 2;",
                "namespace app; const S = Zorde.int().min(x); type Port = Zorde.infer<S>;",
                "namespace app; type Port = int where untrusted(x);"
        }) {
            assertThrows(RuntimeException.class,
                    () -> CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                            source, "service.api", 1), source);
        }
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                        SCHEMAS, "../escape", 1));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                        SCHEMAS, "service.api", -1));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                        SCHEMAS, "", 1));
    }

    @Test void guestProofSyntaxStillCannotExecuteOrForgeRegistry() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(
                        "type Proof = Valid<int, Schema>;"));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(
                        "type Proof = attacker.Valid<int, Schema>;"));
        assertFalse(Modifier.isPublic(
                CompilerZordeSchemaRegistry.class.getModifiers()));
        assertTrue(java.util.Arrays.stream(
                CompilerZordeSchemaRegistry.class.getDeclaredConstructors())
                .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
    }
}
