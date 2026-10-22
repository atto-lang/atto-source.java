package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed admission only. These do NOT exercise a compiler-minted
 * Valid<T, SchemaId> or establish runtime validation/ownership semantics.
 */
final class ZordeValidAdmissionGateTest {

    @Test void nestedAndQualifiedProofTypesCannotBeParsedAsOrdinaryGenerics() {
        String[] denied = {
                "type Proof = Valid<int, UserSchema>;",
                "type Proof = Zorde.Valid<int, UserSchema>;",
                "type Proof = std.zorde.Valid<int, UserSchema>;",
                "type Proof = Option<Valid<int, UserSchema>>;",
                "type Proof = Result<int, Valid<int, UserSchema>>;",
                "type Proof = borrow Valid<int, UserSchema>;",
                "type Proof = Valid;",
                "pub fnc main(): void { new Valid<int, UserSchema>(1); return; }"
        };
        for (String source : denied) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("Zorde Valid<T, SchemaId> proof"),
                    () -> "rejection must be from the proof security gate, not incidental parser failure: "
                            + failure.getMessage());
        }
    }

    @Test void userDeclarationsCannotShadowTheReservedProofIdentity() {
        String[] denied = {
                "type Valid = int;",
                "type Holder<Valid> = int;",
                "define class Valid as end"
        };
        for (String source : denied) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
            assertTrue(failure.getMessage().contains("Zorde Valid<T, SchemaId>"),
                    () -> "proof identity shadowing must be diagnosed explicitly: "
                            + failure.getMessage());
        }
    }

    @Test void syntheticAstCannotBypassTheSourceParserGate() {
        Ast.TypeRef counterfeit = new Ast.TypeRef("Valid",
                List.of(Ast.TypeRef.simple("int"), Ast.TypeRef.simple("Schema")), false);
        Ast.TypeAliasDecl wrapper = new Ast.TypeAliasDecl(
                "Untrusted", List.of(), counterfeit);
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl(Parser.ROOT_MODULE, List.of(wrapper))));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithDiagnostics(program));
        assertTrue(error.getMessage().contains("Zorde Valid<T, SchemaId>"),
                error::getMessage);
    }

    @Test void ordinaryTypesAndUnrelatedNamesStillCompile() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "type Validated = int; type Ordinary = Result<int, string>; "
                + "pub fnc main(): void { return; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "// Valid<int, Schema> is only commentary\n"
                + "pub fnc main(): void { val string label = \"Valid<int, Schema>\"; return; }"));
    }
}
