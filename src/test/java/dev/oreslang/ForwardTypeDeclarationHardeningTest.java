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
    void refinedForwardAliasesCannotEraseZordeBoundsBeforeTypedLowering() {
        for (String declaration : new String[]{
                "define type Code = int with min 0 and max 5;",
                "define type Code = int with value gte 0 and value lte 5;",
                "define type Code = int as positive integer;",
                "define type Code = string with min_length 2 and max_length 4 and chars ascii_letters;",
                "define type Code = string with chars ascii_uppercase;",
                "define type Code = string with chars lowercase;",
                "define type Code = string with chars han;"
        }) {
            String source = """
                    declare type Code;
                    fnc identity(Code value): Code { return value; }
                    """ + declaration;
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("Zorde exact-value validation"),
                    error.getMessage());
        }
    }

    @Test
    void acceptsExactForwardAliasesAtFileRootWithCanonicalAndLegacyPub() {
        String source = """
                declare type Port;
                fnc echo(Port n): Port { return n; }
                define type Port = int;
                declare pub type Id;
                define pub type Id = int;
                declare pub type ExternalId;
                pub define type ExternalId = int;
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(1, parsed.modules().size());
        assertEquals(4, parsed.modules().getFirst().declarations().size(),
                "forward signatures must not become runtime AST nodes");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parsed));
    }

    @Test
    void declarationIsNotAValueAndDoesNotChangeGuestExecution() throws Exception {
        String source = """
                declare pub type ResultCode;
                pub fnc main(): void {
                  stdio.println(7);
                  return;
                }
                define pub type ResultCode = int;
                """;
        Ast.Program program = Parser.parse(source);
        assertEquals(2, program.modules().getFirst().declarations().size());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        org.graalvm.polyglot.Source guest = org.graalvm.polyglot.Source
                .newBuilder(OresLanguage.ID, source, "forward-type-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context
                .newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("7", output.toString(java.nio.charset.StandardCharsets.UTF_8).trim());
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
    void sharedNativeScalarForwardFixtureExecutesOnJavaGuest() throws Exception {
        // The exact same UTF-8 source is included as
        // oreslang-source.llvm/tests/fixtures/forward-scalar-alias-parity.ores.
        // Guest execution (not just TypeChecker) must produce exit value 7.
        String source;
        try (java.io.InputStream input = getClass().getResourceAsStream(
                "/parity/forward-scalar-alias-parity.ores")) {
            assertNotNull(input, "missing shared JVM/LLVM Oreslang fixture");
            source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        org.graalvm.polyglot.Source guest = org.graalvm.polyglot.Source
                .newBuilder(OresLanguage.ID, source, "forward-scalar-alias-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context
                .newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            org.graalvm.polyglot.Value result = context.eval(guest);
            assertTrue(result.fitsInInt(), "guest main must return an int");
            assertEquals(7, result.asInt());
        }
    }

    @Test
    void nativeScalarIntersectionAlsoTypeChecksOnJava() {
        // Identical Oreslang source form exercised by the native executable
        // source matrix. Java and LLVM must still be run separately before
        // making observable-execution parity claims.
        for (String body : new String[]{
                """
                declare pub type Code;
                define pub type Code = int;
                fnc id(Code code): Code { return code; }
                pub fnc main(): int { return id(7); }
                """,
                """
                declare type Flag;
                define type Flag = bool;
                fnc ready(Flag value): Flag { return value; }
                pub fnc main(): int { return ready(true) ? 7 : 0; }
                """,
                """
                define module foo as
                  declare type Code;
                  define type Code = int;
                  fnc id(Code code): Code { return code; }
                  pub fnc main(): int { return id(7); }
                end
                """
        }) {
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(body)), body);
        }
    }

    @Test
    void rejectsCrossKindTypeNamespaceCollisionsEvenWithoutForwards() {
        for (String source : new String[]{
                "define type Box = int; define class Box as end",
                "define type Named = int; define interface Named as end",
                "define trait Named as end define interface Named as end",
                "define contract Named as end define interface Named as end"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("duplicate type namespace")
                            || error.getMessage().contains("duplicate interface"),
                    error.getMessage());
        }
        // Distinct modules retain separate type namespaces.
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define module one as
                  define type Box = int;
                end
                define module two as
                  define class Box as end
                end
                """)));
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
        rejects("pub declare type Token;", "forward declarations must start");
        rejects("@Ret<int> declare type Token;", "forward declarations must start");
        rejects("declare async type Token;", "forward declarations accept only");
    }

    @Test
    void wildcardInferenceHoleCannotBeDeclaredAsGenericParameter() {
        // '_' belongs to type-argument inference, not binder identity. Every
        // declaration family shares the same generic binder admission rule.
        for (String source : new String[]{
                "define type Bad<_> = int;",
                "define class Bad<_> as end",
                "define interface Bad<_> as end",
                "define trait Bad<_> as end",
                "declare type Bad<_>; define type Bad<T> = T;",
                "declare pub trait Bad<_>; define pub trait Bad<T> as end",
                """
                define module example as
                  declare interface Bad<_>;
                  define interface Bad<T> as end
                end
                """
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("generic argument inference hole"),
                    () -> "expected precise binder diagnostic, got: " + error.getMessage());
        }
    }

    @Test
    void wildcardCannotIntroduceANominalOrForwardTypeName() {
        for (String source : new String[]{
                "define type _ = int;",
                "define class _ as end",
                "define actor _ as end",
                "define interface _ as end",
                "define trait _ as end",
                "define contract _ as end",
                "declare type _; define type Good = int;",
                "declare pub interface _; define pub interface Good as end",
                "define module m as declare trait _; define trait Good as end end"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("cannot be used as a declared type name"),
                    () -> "unexpected nominal type binder diagnostic: " + error.getMessage());
        }
    }

    @Test
    void ordinaryNamedGenericsStayAdmittedWithMatchingForwardSignatures() {
        String source = """
                declare pub type Box<T>;
                define pub type Box<T> = Option<T>;
                declare trait Printable<T>;
                define trait Printable<T> as
                  fnc print(T value) => String;
                end
                declare interface Pair<A,B>;
                define interface Pair<A,B> as end
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    private static void rejects(String source, String fragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), source);
        assertTrue(error.getMessage().contains(fragment),
                () -> "expected diagnostic fragment '" + fragment + "', got: " + error.getMessage());
    }
}
