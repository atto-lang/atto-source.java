package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parse-only admission: executable checked effects remain explicitly disabled. */
final class CheckedEffectDeclarationsSyntaxTest {
    private static Ast.FunctionDecl function(String source) {
        return (Ast.FunctionDecl) Parser.parse(source).modules()
                .getFirst().declarations().getFirst();
    }

    @Test
    void retainsThreeIndependentEffectSetsOnFunction() {
        Ast.FunctionDecl f = function(
                "fnc risky(): int panics PanicError throws ParseError raises DomainError { return 1; }");
        assertEquals(List.of(Ast.TypeRef.simple("ParseError")), f.effects().throwsTypes());
        assertEquals(List.of(Ast.TypeRef.simple("DomainError")), f.effects().raisesTypes());
        assertEquals(List.of(Ast.TypeRef.simple("PanicError")), f.effects().panicsTypes());
    }

    @Test
    void preservesCanonicalActorReadinessTerminationType() {
        Ast.FunctionDecl f = function(
                "fnc wait_ready_contract(): int throws ActorTerminated { return 1; }");
        assertEquals(List.of(Ast.TypeRef.simple("ActorTerminated")),
                f.effects().throwsTypes());
    }

    @Test
    void ordinaryFunctionsStayPureWithCompatibilityConstructors() {
        Ast.FunctionDecl fn = function("fnc pure(): int { return 1; }");
        assertTrue(fn.effects().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(
                Parser.parse("fnc pure(): int { return 1; }")));
    }

    @Test
    void syntaxRejectsDuplicateCategoriesAndMissingTypes() {
        for (String source : List.of(
                "fnc f(): int throws ParseError throws DomainError { return 1; }",
                "fnc f(): int panics FatalError panics OtherError { return 1; }",
                "fnc f(): int raises { return 1; }",
                "fnc f(): int throws ParseError, ParseError { return 1; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void preservesEffectsOnRoutinesAndInstanceOrStaticMethods() {
        Ast.FunctionDecl routine = function(
                "routine risky(): int raises DomainError { return 1; }");
        assertEquals(List.of(Ast.TypeRef.simple("DomainError")),
                routine.effects().raisesTypes());

        Ast.Program program = Parser.parse(
                "define class Work as "
              + "  pub act(): int throws ParseError { return 1; } "
              + "  static fnc invoke(): int panics FatalError { return 1; } "
              + "end");
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst()
                .declarations().getFirst();
        assertEquals(2, klass.methods().size());
        assertEquals(List.of(Ast.TypeRef.simple("ParseError")),
                klass.methods().getFirst().effects().throwsTypes());
        assertEquals(List.of(Ast.TypeRef.simple("FatalError")),
                klass.methods().get(1).effects().panicsTypes());
    }

    @Test
    void retainsInterfaceContractEffects() {
        Ast.Program program = Parser.parse(
                "define interface Service as "
              + "fnc handle(): int throws ParseError raises DomainError; "
              + "end");
        Ast.InterfaceDecl iface = (Ast.InterfaceDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.InterfaceFunctionDecl method = (Ast.InterfaceFunctionDecl) iface.members().getFirst();
        assertEquals(List.of(Ast.TypeRef.simple("ParseError")),
                method.effects().throwsTypes());
        assertEquals(List.of(Ast.TypeRef.simple("DomainError")),
                method.effects().raisesTypes());
    }

    @Test
    void typecheckRejectsDeclarationEffectsBeforeTheyCanBeErased() {
        for (String source : List.of(
                "fnc f(): int throws ParseError { return 1; }",
                "routine f(): int raises DomainError { return 1; }",
                "define class Work as pub f(): int panics FatalError { return 1; } end",
                "define interface Service as fnc f(): int throws ParseError; end")) {
            var err = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
            assertTrue(err.getMessage().contains("parsed but not yet enabled"), source);
        }
    }

    @Test
    void linkedClassAndContractCannotSmuggleUncheckedEffects() {
        Ast.Program source = Parser.parse("fnc root(): int { return 1; }");
        Ast.ClassDecl linkedClass = (Ast.ClassDecl) Parser.parse(
                "define class Remote as pub work(): int panics FatalError { return 1; } end")
                .modules().getFirst().declarations().getFirst();
        var fromClass = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        source, java.util.Map.of("Remote", linkedClass),
                        java.util.Map.of(), java.util.Map.of()));
        assertTrue(fromClass.getMessage().contains("parsed but not yet enabled"));

        Ast.InterfaceDecl linkedContract = (Ast.InterfaceDecl) Parser.parse(
                "define contract API as fnc request(): int raises DomainError; end")
                .modules().getFirst().declarations().getFirst();
        var fromContract = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        source, java.util.Map.of(), java.util.Map.of(),
                        java.util.Map.of("API", linkedContract)));
        assertTrue(fromContract.getMessage().contains("parsed but not yet enabled"));
    }

    @Test
    void effectMetadataCannotBeMutatedOrDuplicated() {
        var list = new java.util.ArrayList<>(List.of(Ast.TypeRef.simple("E")));
        Ast.EffectSpec spec = new Ast.EffectSpec(list, List.of(), List.of());
        list.clear();
        assertEquals(1, spec.throwsTypes().size());
        assertThrows(UnsupportedOperationException.class, () -> spec.throwsTypes().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EffectSpec(List.of(Ast.TypeRef.simple("E"),
                        Ast.TypeRef.simple("E")), List.of(), List.of()));
    }

    @Test
    void sourcePanicStatementRemainsDisabledEvenIfDeclared() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("fnc f(): int panics FatalError { panic FatalError; }"));
        assertTrue(error.getMessage().contains("source effect is not yet enabled"));
    }
}
