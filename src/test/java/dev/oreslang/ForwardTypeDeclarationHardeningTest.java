package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Same-file forward declaration admission: never invent phantom runtime types. */
final class ForwardTypeDeclarationHardeningTest {
    @Test
    void contextualDeclareRemainsAnOrdinaryIdentifierOutsideDeclarations() {
        assertTrue(new Lexer("fnc declare(): int { return 1; }").scan().stream()
                .anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("declare")));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                fnc declare(): int { return 1; }
                """)));
    }

    @Test
    void acceptsExactForwardAliasesAtFileRootWithCanonicalAndLegacyPub() {
        String source = """
                declare type Port;
                define type Port = int;
                declare pub type Id;
                define pub type Id = int;
                declare pub type ExternalId;
                pub define type ExternalId = int;
                fnc echo(Port n): Port { return n; }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(1, parsed.modules().size());
        assertEquals(4, parsed.modules().getFirst().declarations().size(),
                "forward signatures must not become runtime AST nodes");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parsed));
    }

    @Test
    void acceptsTraitAndInterfaceOnlyWhenDefinitionKindMatches() {
        String source = """
                declare trait Printable;
                define trait Printable as
                  fnc print(): void;
                end
                declare pub interface Named;
                define pub interface Named as
                  name: String;
                end
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(2, parsed.modules().getFirst().declarations().size());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parsed));
    }

    @Test
    void acceptsModuleScopedGenericForwardsWithoutChangingOtherModules() {
        String source = """
                define module first as
                  declare pub type Pair<T>;
                  define pub type Pair<T> = Option<T>;
                  declare pub trait Show<T>;
                  define pub trait Show<T> as
                    fnc show(T value) => String;
                  end
                end
                define module second as
                  define type Pair = int;
                end
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(2, parsed.modules().size());
        assertEquals(2, parsed.modules().getFirst().declarations().size());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parsed));
    }

    @Test
    void rejectsUnresolvedForwardsAndCrossModuleAccidentalLinking() {
        rejects("""
                declare type Orphan;
                fnc ok(): int { return 1; }
                """, "unresolved forward");
        rejects("""
                define module one as
                  declare interface Named;
                end
                define module two as
                  define interface Named as end
                end
                """, "unresolved forward");
        rejects("""
                define module first as
                  declare type X;
                end
                define module second as
                  define type X = int;
                end
                """, "unresolved forward");
    }

    @Test
    void rejectsCrossKindForwardsAndClassOrContractImpersonation() {
        rejects("""
                declare trait Named;
                define interface Named as end
                """, "kind mismatch");
        rejects("""
                declare interface Named;
                define trait Named as end
                """, "kind mismatch");
        rejects("""
                declare interface Named;
                define contract Named as end
                """, "kind mismatch");
        rejects("""
                declare type Named;
                define class Named as end
                """, "value declaration");
    }

    @Test
    void rejectsVisibilityChangesInBothDirections() {
        rejects("""
                declare pub type Token;
                define type Token = int;
                """, "visibility mismatch");
        rejects("""
                declare type Token;
                define pub type Token = int;
                """, "visibility mismatch");
        rejects("""
                declare pub trait Named;
                define trait Named as end
                """, "visibility mismatch");
    }

    @Test
    void rejectsDuplicateConflictingAndMismatchedGenericSignatures() {
        rejects("""
                declare type Token;
                declare type Token;
                define type Token = int;
                """, "duplicate forward");
        rejects("""
                declare type Pair<T>;
                define type Pair<U> = U;
                """, "generic parameter mismatch");
        rejects("""
                declare interface Dictionary<K, V>;
                define interface Dictionary<K> as end
                """, "generic parameter mismatch");
        rejects("""
                declare type Token;
                define type Token = int;
                define interface Token as end
                """, "ambiguous forward");
    }

    @Test
    void rejectsBodiesAndUnsupportedDeclarationKinds() {
        rejects("declare actor Worker;", "supports only");
        rejects("declare class User;", "supports only");
        rejects("declare enum Status;", "supports only");
        rejects("declare type Token = int;", "cannot supply a body");
        rejects("declare interface Named as end", "cannot supply a body");
        rejects("declare pub pub type Token;", "duplicate");
        rejects("pub declare type Token;", "expected");
        rejects("@Ret<int> declare type Token;", "forward declarations must start");
        rejects("declare async type Token;", "forward declarations accept only");
    }

    private static void rejects(String source, String fragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), source);
        assertTrue(error.getMessage().contains(fragment),
                () -> "expected diagnostic fragment '" + fragment + "', got: " + error.getMessage());
    }
}
