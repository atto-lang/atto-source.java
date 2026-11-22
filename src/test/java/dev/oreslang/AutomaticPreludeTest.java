package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.AutomaticPrelude;
import dev.oreslang.types.CoreTypeRegistry;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for compiler-owned implicit core bindings and affine loans. */
final class AutomaticPreludeTest {
    @Test
    void coreNamesResolveWithoutAllocAndSpecializedCollectionsRemainExplicit() {
        for (String name : new String[]{"Option", "Result", "Some", "None", "Ok", "Err",
                "Array", "Slice", "Tuple", "Error", "Pair"}) {
            var binding = AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.CORE);
            assertTrue(binding.isPresent(), "missing core prelude " + name);
            assertTrue(binding.get().canonical().startsWith("core/"));
        }
        for (String name : new String[]{"List", "Map", "Set", "Vec", "Box"}) {
            assertTrue(AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.CORE).isEmpty(), name);
            assertEquals(AutomaticPrelude.Scope.ALLOC,
                    AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.HOSTED)
                            .orElseThrow().scope(), name);
        }
        for (String name : new String[]{"HashMap", "HashSet", "Entry"}) {
            assertTrue(AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.HOSTED).isEmpty(), name);
        }
        assertTrue(AutomaticPrelude.lookup("Object", AutomaticPrelude.Profile.HOSTED).isEmpty());
        assertFalse(AutomaticPrelude.protectedIdentity("HashMap"));
    }

    @Test
    void catalogIsUniqueImmutableAndCanonical() {
        var all = AutomaticPrelude.catalog();
        assertEquals(all.size(), new HashSet<>(all.keySet()).size());
        for (var entry : all.values()) {
            assertTrue(entry.canonical().endsWith("/" + entry.name()));
            assertFalse(entry.canonical().contains(".."));
        }
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test
    void coreVariantNamesAndCarrierArityAreCompilerOwned() {
        for (String name : new String[]{"Some", "None", "Ok", "Err"}) {
            assertTrue(AutomaticPrelude.coreConstructor(name));
            assertTrue(AutomaticPrelude.protectedIdentity(name));
        }
        assertFalse(AutomaticPrelude.coreConstructor("Result"));
        assertTrue(AutomaticPrelude.coreSumCarrier("Option", 1));
        assertTrue(AutomaticPrelude.coreSumCarrier("Result", 2));
        assertFalse(AutomaticPrelude.coreSumCarrier("Option", 2));
        assertFalse(AutomaticPrelude.coreSumCarrier("Result", 1));
        assertFalse(AutomaticPrelude.coreSumCarrier("Object", 1));
    }


    @Test
    void coreTypeRegistryIsTheCanonicalOwnershipAndDropSource() {
        var option = CoreTypeRegistry.lookup("Option").orElseThrow();
        assertEquals("core/option/Option", option.canonicalIdentity());
        assertEquals(CoreTypeRegistry.Scope.CORE, option.scope());
        assertEquals(CoreTypeRegistry.Kind.TYPE, option.kind());
        assertEquals(1, option.minGenericArity());
        assertEquals(1, option.maxGenericArity());
        assertEquals(CoreTypeRegistry.Ownership.PAYLOAD_DEPENDENT, option.ownership());
        assertEquals(CoreTypeRegistry.Allocation.NONE, option.allocation());
        assertEquals(CoreTypeRegistry.DropPolicy.ACTIVE_PAYLOAD_ONCE, option.dropPolicy());
        assertEquals(CoreTypeRegistry.SendPolicy.PAYLOAD_DEPENDENT, option.sendPolicy());
        assertEquals(CoreTypeRegistry.LoweringStatus.JAVA_INTRINSIC, option.javaStatus());

        var result = CoreTypeRegistry.lookup("Result").orElseThrow();
        assertEquals(2, result.minGenericArity());
        assertEquals(2, result.maxGenericArity());
        assertEquals(CoreTypeRegistry.DropPolicy.ACTIVE_PAYLOAD_ONCE, result.dropPolicy());

        var slice = CoreTypeRegistry.lookup("Slice").orElseThrow();
        assertEquals(CoreTypeRegistry.Ownership.BORROWED_VIEW, slice.ownership());
        assertEquals(CoreTypeRegistry.Allocation.BORROWED_VIEW, slice.allocation());
        assertEquals(CoreTypeRegistry.SendPolicy.NEVER_BORROWED, slice.sendPolicy());

        var map = CoreTypeRegistry.lookup("Map").orElseThrow();
        assertEquals(CoreTypeRegistry.Scope.ALLOC, map.scope());
        assertEquals(2, map.minGenericArity());
        assertEquals(CoreTypeRegistry.Allocation.ALLOCATOR_REQUIRED, map.allocation());
        assertEquals(CoreTypeRegistry.DropPolicy.OWNED_STORAGE, map.dropPolicy());

        var string = CoreTypeRegistry.lookup("String").orElseThrow();
        assertEquals(CoreTypeRegistry.Scope.CORE, string.scope());
        assertEquals(CoreTypeRegistry.Ownership.COPY, string.ownership());
        assertEquals(CoreTypeRegistry.LoweringStatus.JAVA_LEGACY_SCALAR, string.javaStatus());
        assertEquals(CoreTypeRegistry.lookup("string").orElseThrow().canonicalIdentity(),
                string.canonicalIdentity(), "string and String are spelling aliases of one scalar type");
        assertEquals(CoreTypeRegistry.Allocation.NONE, string.allocation(),
                "Java String remains the immutable scalar/view, not allocator-owned alloc::String");
        assertTrue(AutomaticPrelude.lookup("String", AutomaticPrelude.Profile.CORE).isPresent());
        assertTrue(AutomaticPrelude.lookup("String", AutomaticPrelude.Profile.ALLOC).isPresent());

        assertTrue(CoreTypeRegistry.lookup("HashMap").orElseThrow().scope()
                == CoreTypeRegistry.Scope.EXPLICIT);
        assertTrue(CoreTypeRegistry.lookup("Object").isEmpty());
        assertEquals("core/primitive/i64",
                CoreTypeRegistry.lookup("int").orElseThrow().canonicalIdentity());
        assertEquals(CoreTypeRegistry.lookup("int").orElseThrow().canonicalIdentity(),
                CoreTypeRegistry.lookup("Int").orElseThrow().canonicalIdentity());
        assertEquals(CoreTypeRegistry.Ownership.COPY,
                CoreTypeRegistry.lookup("bool").orElseThrow().ownership());
        assertEquals(CoreTypeRegistry.SendPolicy.ALWAYS,
                CoreTypeRegistry.lookup("bool").orElseThrow().sendPolicy());
        assertEquals(0, CoreTypeRegistry.lookup("int").orElseThrow().minGenericArity());
        assertTrue(AutomaticPrelude.lookup("int", AutomaticPrelude.Profile.CORE).isPresent());
        assertFalse(CoreTypeRegistry.lookup("Option").orElseThrow().acceptsArity(2));
        assertFalse(CoreTypeRegistry.lookup("Result").orElseThrow().acceptsArity(1));
        assertTrue(CoreTypeRegistry.coreSumCarrier("Option", 1));
        assertTrue(CoreTypeRegistry.coreSumCarrier("Result", 2));
        assertFalse(CoreTypeRegistry.coreSumCarrier("Option", 2));
        assertFalse(CoreTypeRegistry.coreSumCarrier("Result", 1));
    }

    @Test
    void coreRegistryGenericArityIsEnforcedByTheRealTypeChecker() {
        for (String source : new String[]{
                "fnc invalid(): Option { return None; }",
                "fnc invalid(): Option<int, string> { return None; }",
                "fnc invalid(): Result<int> { return Ok(1); }",
                "fnc invalid(): Result<int, string, bool> { return Ok(1); }"
        }) {
            var program = assertDoesNotThrow(() -> Parser.parse(source),
                    "the arity gate must run after the production parser");
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(program), source);
            assertTrue(error.getMessage().contains("E_CORE_TYPE_ARITY"), error::getMessage);
        }
    }

    private static void assertPreludeSpoofRejected(String source) {
        var program = assertDoesNotThrow(() -> Parser.parse(source),
                "must reach the real typechecker, not be merely syntactically rejected");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("E_PRELUDE_SPOOF"), error::getMessage);
        IllegalArgumentException standaloneBorrowChecker = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program),
                "the standalone loan checker must reject forged core identities too");
        assertTrue(standaloneBorrowChecker.getMessage().contains("E_PRELUDE_SPOOF"),
                standaloneBorrowChecker::getMessage);
    }

    @Test
    void nominalDeclarationsCannotForgeTheCoreSum() {
        assertPreludeSpoofRejected("""
                define class Option as
                  val int value = 1;
                end
                """);
        assertPreludeSpoofRejected("""
                define class Result as
                  val int value = 1;
                end
                """);
    }

    @Test
    void aUserFunctionCannotReplaceAnAdtConstructor() {
        assertPreludeSpoofRejected("""
                fnc Some(int value): int { return value; }
                """);
        assertPreludeSpoofRejected("""
                fnc Err(int value): int { return value; }
                """);
    }


    @Test
    void coreProfileRejectsAllocatorTypesWithoutMisclassifyingLegacyString() {
        for (String source : new String[]{
                "fnc uses_list(List<int> value): int { return 0; }",
                "fnc uses_map(Map<int, int> value): int { return 0; }",
                "fnc uses_box(Box<int> value): int { return 0; }"
        }) {
            var program = assertDoesNotThrow(() -> Parser.parse(source));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypesWithDiagnostics(
                            program, false, AutomaticPrelude.Profile.CORE));
            assertTrue(error.getMessage().contains("E_ALLOC_PROFILE_REQUIRED"), error::getMessage);
            IllegalArgumentException compilerError = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source, AutomaticPrelude.Profile.CORE));
            assertTrue(compilerError.getMessage().contains("E_ALLOC_PROFILE_REQUIRED"),
                    compilerError::getMessage);
        }

        var legacyScalar = assertDoesNotThrow(() ->
                Parser.parse("fnc legacy(): String { return \"ok\"; }"));
        assertDoesNotThrow(() -> TypeChecker.checkTypesWithDiagnostics(
                legacyScalar, false, AutomaticPrelude.Profile.CORE),
                "the historical Java immutable String scalar is not alloc::String");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "fnc legacy(): String { return \"ok\"; }", AutomaticPrelude.Profile.CORE),
                "compiler entrypoint must preserve the historical immutable String scalar");
    }


    @Test
    void linkedForeignClassOriginCannotForgeCoreIdentityThroughAnAlias() {
        Ast.Program forgedSource = assertDoesNotThrow(() ->
                Parser.parse("define pub class Option as val int value = 1; end"));
        Ast.ClassDecl forged = forgedSource.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        Ast.Program importer = assertDoesNotThrow(() ->
                Parser.parse("pub fnc main(): int { return 42; }"));

        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        importer, Map.of("ForeignOption", forged), Map.of(), Map.of()));
        assertTrue(type.getMessage().contains("E_PRELUDE_SPOOF"), type::getMessage);

        IllegalArgumentException borrow = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithImportedClasses(
                        importer, Map.of("ForeignOption", forged)));
        assertTrue(borrow.getMessage().contains("E_PRELUDE_SPOOF"), borrow::getMessage);
    }

    @Test
    void unqualifiedImportAliasCannotForgeAnAdtType() {
        assertPreludeSpoofRejected("""
                import module Result from "./foreign.atto";
                pub fnc main(): int { return 42; }
                """);
    }
}
