package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InterfaceFncKeywordTest {
    @Test
    void obsoleteFnStillRejects() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("define interface Api %s run() => void; end").formatted(legacy)));
        assertTrue(error.getMessage().contains("fnc"));
    }

    private static Ast.InterfaceDecl declaration(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.InterfaceDecl.class::isInstance)
                .map(Ast.InterfaceDecl.class::cast).findFirst().orElseThrow();
    }

    @Test
    void instanceSignatureSpellingsHaveIdenticalAst() {
        for (String kind : new String[]{"interface", "trait"}) {
            String base = "define " + kind + " Api as ";
            var bare = declaration(base + "run(int x) => int; end");
            assertEquals(bare, declaration(base + "fnc run(int x) => int; end"));
            assertEquals(bare, declaration(base + "mtd run(int x) => int; end"));
            var generic = declaration(base + "id<T>(T x) => T; end");
            assertEquals(generic, declaration(base + "fnc id<T>(T x) => T; end"));
            assertEquals(generic, declaration(base + "mtd id<T>(T x) => T; end"));
        }
    }

    @Test
    void concreteClassConformanceDoesNotDependOnSignatureSpelling() {
        for (String prefix : new String[]{"", "fnc ", "mtd "}) {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                    "define interface Api as " + prefix + "run(int x): int; end\n"
                    + "define class Good implements Api as pub run(int x): int { return x; } end")));
            var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                    "define interface Api as " + prefix + "run(int x): int; end\n"
                    + "define class Missing implements Api as end")));
            assertTrue(error.getMessage().contains("does not implement interface"), error.getMessage());
        }
    }

    @Test
    void contextualMarkerDoesNotConsumeMethodNamedMtdOrFieldType() {
        var bare = declaration("define interface Api as mtd(): int; end");
        assertEquals(bare, declaration("define interface Api as fnc mtd(): int; end"));
        assertEquals(bare, declaration("define interface Api as mtd mtd(): int; end"));
        var fields = declaration("define interface Api as mtd value; method other; Box<T> third; end");
        assertEquals(3, fields.members().size());
        assertTrue(fields.members().stream().allMatch(Ast.InterfaceFieldDecl.class::isInstance));
    }

    @Test
    void duplicateMarkerAndForeignReceiverSyntaxReject() {
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define interface Api as fnc mtd run(): int; end"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define interface Api as mtd run(Actor actorRef)(): int; end"));
    }

    @Test
    void multiParameterGenericsAndMalformedHeadersRetainAdmissionBoundaries() {
        var bare = declaration("define interface Api as pair<A,B>(A a, B b): A; end");
        assertEquals(bare, declaration("define interface Api as fnc pair<A,B>(A a, B b): A; end"));
        assertEquals(bare, declaration("define interface Api as mtd pair<A,B>(A a, B b): A; end"));
        for (String signature : new String[]{"run<T,>(T x): T;", "run<T,T>(T x): T;", "run<>(int x): int;"}) {
            for (String prefix : new String[]{"", "fnc ", "mtd "}) {
                assertThrows(IllegalArgumentException.class,
                        () -> Parser.parse("define interface Api as " + prefix + signature + " end"));
            }
        }
    }

    @Test
    void obsoleteMethodIntroducerIsNotAdmitted() {
        for (String kind : new String[]{"interface", "trait"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("define " + kind + " Api as method run(): int; end"));
        }
        for (String kind : new String[]{"class", "actor"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("define " + kind + " C as pub method run(): int { return 42; } end"));
        }
        var named = declaration("define interface Api as method(): int; end");
        assertEquals(named, declaration("define interface Api as mtd method(): int; end"));
        assertEquals(named, declaration("define interface Api as fnc method(): int; end"));
    }

    @Test
    void moduleContractFunctionAndActorReceiverRemainDistinct() {
        var ordinary = declaration("define contract Api as fnc run(): int; end");
        assertInstanceOf(Ast.InterfaceFunctionDecl.class, ordinary.members().getFirst());
        var receiver = declaration("define contract Api as mtd run(Worker worker)(): int; end");
        assertInstanceOf(Ast.InterfaceActorMethodDecl.class, receiver.members().getFirst());
    }
}
