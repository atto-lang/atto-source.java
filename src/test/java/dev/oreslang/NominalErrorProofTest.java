package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalErrorProof;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only ancestry proofs; these fixtures do NOT authenticate stdlib roots. */
final class NominalErrorProofTest {
    private static Ast.ClassDecl klass(String source) {
        return (Ast.ClassDecl) Parser.parse(source).modules()
                .getFirst().declarations().getFirst();
    }

    private static java.util.function.Function<Ast.TypeRef, Ast.ClassDecl> resolver(
            Map<String, Ast.ClassDecl> classes) {
        return name -> classes.get(name.name());
    }

    @Test
    void verifiedRootIdentityAndMultiStepNominalInheritanceAreAccepted() {
        // In production the root has to come from independently verified stdlib metadata.
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl parse = klass("define class ParseError extends Error as end");
        Ast.ClassDecl domain = klass("define class DomainError extends ParseError as end");
        var lookup = resolver(Map.of("Error", root, "ParseError", parse, "DomainError", domain));
        assertDoesNotThrow(() -> NominalErrorProof.requireNominalError(
                Ast.TypeRef.simple("Error"), root, lookup));
        assertDoesNotThrow(() -> NominalErrorProof.requireNominalError(
                Ast.TypeRef.simple("DomainError"), root, lookup));
        assertDoesNotThrow(() -> NominalErrorProof.requireNominalEffects(
                new Ast.EffectSpec(List.of(Ast.TypeRef.simple("ParseError")),
                        List.of(Ast.TypeRef.simple("DomainError")),
                        List.of(Ast.TypeRef.simple("Error"))), root, lookup));
    }

    @Test
    void counterfeitClassNamedErrorDoesNotAcquireRootAuthority() {
        Ast.ClassDecl trusted = klass("define class Error as end");
        Ast.ClassDecl forged = klass("define class Error as end");
        Ast.ClassDecl child = klass("define class BadError extends Error as end");
        var lookup = resolver(Map.of("Error", forged, "BadError", child));
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("Error"), trusted, lookup));
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("BadError"), trusted, lookup));
    }

    @Test
    void structurallySimilarClassesAndPrimitivesAreNotErrors() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl unrelated = klass("define class Other as end");
        var lookup = resolver(Map.of("Error", root, "Other", unrelated));
        for (String name : List.of("Other", "string", "int", "UnknownError")) {
            assertThrows(IllegalArgumentException.class, () ->
                    NominalErrorProof.requireNominalError(
                            Ast.TypeRef.simple(name), root, lookup), name);
        }
    }

    @Test
    void unboundGenericAndInferredPayloadsFailClosed() {
        Ast.ClassDecl root = klass("define class Error as end");
        var lookup = resolver(Map.of("Error", root));
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        new Ast.TypeRef("Error", List.of(Ast.TypeRef.simple("int")), false),
                        root, lookup));
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        new Ast.TypeRef("Error", List.of(), true), root, lookup));
    }

    @Test
    void unresolvedParentNeverProvesNominalErrorAncestry() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl child = klass("define class ChildError extends MissingBase as end");
        var lookup = resolver(Map.of("Error", root, "ChildError", child));
        var error = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("ChildError"), root, lookup));
        assertTrue(error.getMessage().contains("unresolved"));
    }

    @Test
    void ancestryCyclesCannotForgeErrorIdentity() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl a = klass("define class A extends B as end");
        Ast.ClassDecl b = klass("define class B extends A as end");
        var lookup = resolver(Map.of("Error", root, "A", a, "B", b));
        var error = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(Ast.TypeRef.simple("A"), root, lookup));
        assertTrue(error.getMessage().contains("cycle"));
    }

    @Test
    void errorCategoryDiagnosticsArePreserved() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl unrelated = klass("define class Unrelated as end");
        var lookup = resolver(Map.of("Error", root, "Unrelated", unrelated));
        for (String category : List.of("throws", "raises", "panics")) {
            Ast.EffectSpec spec = new Ast.EffectSpec(
                    category.equals("throws") ? List.of(Ast.TypeRef.simple("Unrelated")) : List.of(),
                    category.equals("raises") ? List.of(Ast.TypeRef.simple("Unrelated")) : List.of(),
                    category.equals("panics") ? List.of(Ast.TypeRef.simple("Unrelated")) : List.of());
            var error = assertThrows(IllegalArgumentException.class, () ->
                    NominalErrorProof.requireNominalEffects(spec, root, lookup));
            assertTrue(error.getMessage().contains("checked " + category), category);
        }
    }

    @Test
    void resolverCannotRebindPrimitiveTypeNameToTrustedRoot() {
        Ast.ClassDecl root = klass("define class Error as end");
        var spoofed = resolver(Map.of("int", root, "Error", root));
        var error = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("int"), root, spoofed));
        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void validAncestryDoesNotMaskAnUnresolvedSecondaryParent() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl child = klass(
                "define class MultiError extends Error, MissingBase as end");
        var lookup = resolver(Map.of("Error", root, "MultiError", child));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("MultiError"), root, lookup));
        assertTrue(failure.getMessage().contains("unresolved"));
    }

    @Test
    void rejectsQualifiedNameWithoutOwnedModuleBinding() {
        Ast.ClassDecl root = klass("define class Error as end");
        var lookup = resolver(Map.of("other.Error", root, "Error", root));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("other.Error"), root, lookup));
        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test
    void rejectsSuperclassResolutionWithMismatchedClassIdentity() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl child = klass("define class ChildError extends OtherBase as end");
        var lookup = resolver(Map.of("OtherBase", root, "Error", root, "ChildError", child));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("ChildError"), root, lookup));
        assertTrue(failure.getMessage().contains("superclass name does not match"));
    }

    @Test
    void reservedPrimitiveTypeRemainsNonNominal() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl primitiveClass = klass("define class MyInt extends Error as end");
        var lookup = resolver(Map.of("Error", root, "int", primitiveClass));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("int"), root, lookup));
        assertTrue(failure.getMessage().contains("concrete"));
    }

    @Test
    void matchingQualifiedClassSpellingStillRequiresModuleAuthentication() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl original = klass("define class DerivedError extends Error as end");
        Ast.ClassDecl qualified = new Ast.ClassDecl(
                "other.DerivedError", original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), original.fields(), original.constructor(),
                original.methods(), original.actorConcurrency());
        var lookup = resolver(Map.of("Error", root, "other.DerivedError", qualified));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("other.DerivedError"), root, lookup));
        assertTrue(failure.getMessage().contains("authenticated module ownership"));
    }

    @Test
    void inferredObjectParentCannotBeSkippedAsOrdinaryObject() {
        Ast.ClassDecl root = klass("define class Error as end");
        Ast.ClassDecl parsed = klass("define class ChildError extends Error as end");
        Ast.ClassDecl child = new Ast.ClassDecl(
                parsed.name(), parsed.visibility(), parsed.isAbstract(),
                parsed.actorKind(), parsed.genericParameters(),
                List.of(Ast.TypeRef.simple("Error"),
                        new Ast.TypeRef("Object", List.of(), true)),
                parsed.interfaces(), parsed.fields(), parsed.constructor(),
                parsed.methods(), parsed.actorConcurrency());
        var lookup = resolver(Map.of("Error", root, "ChildError", child));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("ChildError"), root, lookup));
        assertTrue(failure.getMessage().contains("concrete"));
    }

    @Test
    void rootWithUnexpectedSuperclassIsNotTrusted() {
        Ast.ClassDecl root = klass("define class Error extends UntrustedRoot as end");
        var lookup = resolver(Map.of("Error", root));
        var error = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("Error"), root, lookup));
        assertTrue(error.getMessage().contains("invalid trusted"));

        Ast.ClassDecl ordinaryRoot = klass("define class Error extends Object as end");
        assertDoesNotThrow(() -> NominalErrorProof.requireNominalError(
                Ast.TypeRef.simple("Error"), ordinaryRoot,
                resolver(Map.of("Error", ordinaryRoot))));
    }

    @Test
    void compilerBuiltinAliasesCannotActAsNominalErrors() {
        Ast.ClassDecl root = klass("define class Error as end");
        for (String builtin : List.of("i32", "u64", "f32", "Bool",
                "Symbol", "ActorId", "Future", "Channel")) {
            Ast.ClassDecl impersonation = klass(
                    "define class " + builtin + " extends Error as end");
            var lookup = resolver(Map.of("Error", root, builtin, impersonation));
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    NominalErrorProof.requireNominalError(
                            Ast.TypeRef.simple(builtin), root, lookup), builtin);
            assertTrue(failure.getMessage().contains("concrete"), builtin);
        }
    }

    @Test
    void oversizedEffectRowsFailBeforeInvokingResolver() {
        Ast.ClassDecl root = klass("define class Error as end");
        List<Ast.TypeRef> large = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> Ast.TypeRef.simple("ErrorType" + i))
                .toList();
        Ast.EffectSpec spec = new Ast.EffectSpec(large, List.of(), List.of());
        var error = assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalEffects(spec, root, ref -> {
                    throw new AssertionError("resolver must not be called for oversized effect rows");
                }));
        assertTrue(error.getMessage().contains("budget"));
    }

    @Test
    void emptyEffectRowsStillRequireValidProofInputs() {
        Ast.EffectSpec empty = Ast.EffectSpec.none();
        Ast.ClassDecl invalid = klass("define class Unrelated as end");
        var resolver = resolver(Map.of("Unrelated", invalid));
        assertThrows(NullPointerException.class, () ->
                NominalErrorProof.requireNominalEffects(empty, null, resolver));
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalEffects(empty, invalid, resolver));
        Ast.ClassDecl root = klass("define class Error as end");
        assertThrows(NullPointerException.class, () ->
                NominalErrorProof.requireNominalEffects(empty, root, null));
    }

    @Test
    void invalidTrustedRootMetadataIsRejected() {
        Ast.ClassDecl notRoot = klass("define class SomethingElse as end");
        assertThrows(IllegalArgumentException.class, () ->
                NominalErrorProof.requireNominalError(
                        Ast.TypeRef.simple("SomethingElse"), notRoot,
                        resolver(Map.of("SomethingElse", notRoot))));
    }
}
