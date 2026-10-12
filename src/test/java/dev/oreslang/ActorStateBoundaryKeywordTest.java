package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed source boundaries until sealed handle_state and RW scheduler
 * lowering are compiled end-to-end. Do not treat lexing as codegen support.
 */
final class ActorStateBoundaryKeywordTest {
    @Test
    void recognizesOnlyExactHyphenatedReservedKeywords() {
        List<Token> tokens = new Lexer(
                "critical-read critical-write transform-pipe nb-select").scan();
        assertEquals(List.of(Token.Type.CRITICAL_READ, Token.Type.CRITICAL_WRITE,
                Token.Type.TRANSFORM_PIPE, Token.Type.NB_SELECT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());

        tokens = new Lexer("a-b a -> b critical - read critical-reader").scan();
        assertEquals(List.of(Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.IDENT, Token.Type.ARROW, Token.Type.IDENT,
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    void stateIsNotAnOrdinaryActorField() {
        for (String declaration : List.of("let int state = 3;", "final state: int = 3;")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Foo as " + declaration + " end"));
            assertTrue(failure.getMessage().contains("reserved runtime-owned storage"),
                    failure.getMessage());
        }
        assertDoesNotThrow(() -> Parser.parse("""
                define class Bar as
                    let int state = 3;
                end
                """));
    }

    @Test
    void directActorStateReadsAndWritesAreCompilerRejected() {
        for (String statement : List.of("val i = self.state.v;",
                "self.state.v = 3;", "val i = (self).state.v;")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Foo as receive(): void { "
                            + statement + " } end"));
            assertTrue(failure.getMessage().contains("self.state is not accessible"),
                    failure.getMessage());
        }
    }

    @Test
    void handleStateIntrinsicCannotBeShadowedOrOverridden() {
        for (String declaration : List.of(
                "pub handle_state(): int { return 3; }",
                "pub static fnc handle_state(): int { return 3; }")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Foo as " + declaration + " end"));
            assertTrue(failure.getMessage().contains("sealed runtime intrinsic"),
                    failure.getMessage());
        }
    }

    @Test
    void actorReceiverAliasesCannotReadState() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Foo as
                            pub inspect(): int {
                                val alias = self;
                                return alias.state;
                            }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("actor state is sealed"),
                failure.getMessage());
    }

    @Test
    void semanticCheckerRejectsSynthesizedReservedActorFields() {
        Ast.FieldDecl forged = new Ast.FieldDecl(
                "state", Ast.Visibility.PRIVATE, Ast.BindingKind.LET,
                Ast.TypeRef.simple("int"), null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(syntheticActor(List.of(forged), List.of())));
        assertTrue(failure.getMessage().contains("reserved runtime-owned storage"),
                failure.getMessage());
    }

    @Test
    void semanticCheckerRejectsSynthesizedSealedMethodsEvenStatic() {
        for (boolean isStatic : List.of(false, true)) {
            Ast.MethodDecl forged = new Ast.MethodDecl(
                    "handle_state", Ast.Visibility.PRIVATE, isStatic,
                    false, false, null, List.of(), List.of(),
                    Ast.TypeRef.simple("void"), List.of(), List.of());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(syntheticActor(List.of(), List.of(forged))));
            assertTrue(failure.getMessage().contains("handle_state is sealed"),
                    failure.getMessage());
        }
    }

    @Test
    void linkedActorCannotInheritSyntheticStaticHandleState() {
        Ast.Program parentSource = Parser.parse("pub define actor Parent as end");
        Ast.ClassDecl parsedParent = (Ast.ClassDecl)
                parentSource.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl forgedStatic = new Ast.MethodDecl(
                "handle_state", Ast.Visibility.PRIVATE, true,
                false, false, null, List.of(), List.of(),
                Ast.TypeRef.simple("void"), List.of(), List.of());
        Ast.ClassDecl linkedActor = new Ast.ClassDecl(
                parsedParent.name(), parsedParent.visibility(), parsedParent.isAbstract(),
                parsedParent.actorKind(), parsedParent.genericParameters(),
                parsedParent.parents(), parsedParent.interfaces(),
                parsedParent.fields(), null, List.of(forgedStatic));
        Ast.Program consumer = Parser.parse("""
                import class Parent from './parent';
                define actor Child extends Parent as
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClasses(
                        consumer, Map.of("Parent", linkedActor), Map.of()));
        assertTrue(failure.getMessage().contains("handle_state is sealed"),
                failure.getMessage());
    }

    private static Ast.Program syntheticActor(
            List<Ast.FieldDecl> fields, List<Ast.MethodDecl> methods) {
        Ast.Program parsed = Parser.parse("define actor Foo as end");
        Ast.ModuleDecl root = parsed.modules().getFirst();
        Ast.ClassDecl original = (Ast.ClassDecl) root.declarations().getFirst();
        Ast.ClassDecl forged = new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(),
                original.parents(), original.interfaces(), fields, null, methods);
        return new Ast.Program(parsed.namespace(), parsed.imports(),
                List.of(new Ast.ModuleDecl(root.name(), root.annotations(),
                        root.contracts(), List.of(forged))));
    }

    @Test
    void linkedSealedStateNeverCrossesCompilationUnitBoundary() {
        Ast.Program parent = Parser.parse("""
                pub define actor Parent as
                    state { secret: int = 42; }
                end
                """);
        Ast.ClassDecl imported = (Ast.ClassDecl)
                parent.modules().getFirst().declarations().getFirst();
        Ast.Program consumer = Parser.parse("""
                import class Parent from './parent';
                define actor Child extends Parent as
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithImportedClasses(
                        consumer, Map.of("Parent", imported), Map.of()));
        assertTrue(failure.getMessage().contains("linked actor sealed state"),
                failure.getMessage());
    }

    @Test
    void annotationExpansionDoesNotEraseSealedActorState() {
        Ast.Program parsed = Parser.parse("""
                define actor Counter as
                    state { value: int = 0; }
                end
                """);
        Ast.Program expanded = dev.oreslang.ast.AnnotationExpander.expand(parsed);
        Ast.ClassDecl klass = (Ast.ClassDecl)
                expanded.modules().getFirst().declarations().getFirst();
        assertNotNull(klass.actorState());
        assertEquals("value", klass.actorState().fields().getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(expanded));
    }

    @Test
    void unknownHyphenatedSpellingsCannotBecomeIdentifiers() {
        List<Token.Type> tokens = new Lexer(
                "critical-reader critical-read-other nb-selectable foo-bar").scan()
                .stream().map(Token::type).toList();
        assertEquals(List.of(
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.NB, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), tokens);
    }

    @Test
    void sealedStateHasItsOwnAstNamespaceAndStillFailsClosedAtTypeCheck() {
        Ast.Program program = Parser.parse("""
                define actor Counter as
                    state {
                        value: int = 0
                        other: int = 2
                    }
                end
                """);
        Ast.ClassDecl actor = (Ast.ClassDecl)
                program.modules().getFirst().declarations().getFirst();
        assertNotNull(actor.actorState());
        assertEquals(List.of("value", "other"),
                actor.actorState().fields().stream().map(Ast.FieldDecl::name).toList());
        assertTrue(actor.fields().isEmpty(), "sealed slots must not be ordinary actor fields");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertTrue(failure.getMessage().contains("lowering is disabled"), failure.getMessage());
    }

    @Test
    void invalidSealedStateDeclsCannotReachCodegen() {
        for (String source : List.of(
                "state { value: int; }",
                "state { value: int = 0; value: int = 1; }",
                "state { state: int = 0; }",
                "pub state { value: int = 0; }",
                "state { value: int = 0; } state { other: int = 0; }",
                "let int value = 0; state { value: int = 1; }",
                "value(): int { return 0; } state { value: int = 1; }")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Counter as " + source + " end"),
                    source);
        }
    }

    @Test
    void unsupportedReadWriteCallablesStillFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc critical-write update(): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define transform-pipe actor Foo as
                end
                """));
    }
}
