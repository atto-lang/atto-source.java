package dev.oreslang;

import dev.oreslang.ast.TypedOverloadResolver;
import dev.oreslang.ast.Ast;
import dev.oreslang.ast.TypedOverloadResolver.Candidate;
import dev.oreslang.ast.TypedOverloadResolver.Domain;
import dev.oreslang.ast.TypedOverloadResolver.Signature;
import dev.oreslang.ast.TypedOverloadResolver.TypeKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TypedOverloadResolverTest {
    private static TypeKey primitive(String name) { return TypeKey.primitive(name); }
    private static Candidate<String> candidate(String label, TypeKey... params) {
        return new Candidate<>(label,
                new Signature(Domain.MODULE, "library.pick", List.of(params)));
    }

    private static final Set<String> ELIGIBLE = Set.of(
            "graphics.Color", "weather.Color", "graphics.Direction",
            "__root__.Color", "__root__.Direction");

    private static <T> Candidate<T> select(Domain domain, String name,
            List<TypeKey> arguments, List<Candidate<T>> candidates) {
        return TypedOverloadResolver.select(domain, name, arguments, candidates, ELIGIBLE);
    }

    @Test
    void primitiveDistinctionsRejectEvenWhenAnExactMatchExists() {
        Candidate<String> floating = candidate("float", primitive("float"));
        Candidate<String> integer = candidate("int", primitive("int"));
        for (List<Candidate<String>> order : List.of(List.of(floating, integer), List.of(integer, floating))) {
            for (String actual : List.of("int", "float")) {
                var error = assertThrows(IllegalArgumentException.class, () -> select(
                        Domain.MODULE, "library.pick", List.of(primitive(actual)), order));
                assertTrue(error.getMessage().contains("invalid same-arity"));
            }
        }
    }

    @Test
    void aritySelectionLeavesArgumentCompatibilityToTheCaller() {
        // The only slot wins even with incompatible argument types; normal
        // assignability must reject those afterwards, never choose a new slot.
        var onlyFloat = candidate("float", primitive("float"));
        for (TypeKey argument : List.of(primitive("int"), primitive("String"), TypeKey.unknown())) {
            assertSame(onlyFloat, select(Domain.MODULE, "library.pick", List.of(argument), List.of(onlyFloat)));
        }
    }

    @Test
    void anEnumDifferenceCannotExcuseAnotherPrimitiveDifference() {
        var left = candidate("left", TypeKey.nominalEnum("graphics.Color"), primitive("int"));
        var right = candidate("right", TypeKey.nominalEnum("graphics.Direction"), primitive("float"));
        assertThrows(IllegalArgumentException.class, () -> select(Domain.MODULE, "library.pick",
                left.signature().parameters(), List.of(left, right)));
    }

    @Test
    void nominalEnumTypeIdentitiesCannotBeConfusedAcrossModulesOrVariants() {
        TypeKey colors = TypeKey.nominalEnum("graphics.Color");
        TypeKey unrelated = TypeKey.nominalEnum("weather.Color");
        TypeKey directions = TypeKey.nominalEnum("graphics.Direction");
        List<Candidate<String>> candidates = List.of(
                candidate("graphics color", colors),
                candidate("weather color", unrelated),
                candidate("direction", directions));
        assertEquals("graphics color", select(
                Domain.MODULE, "library.pick", List.of(colors), candidates).declaration());
        assertEquals("weather color", select(
                Domain.MODULE, "library.pick", List.of(unrelated), candidates).declaration());
        assertEquals("direction", select(
                Domain.MODULE, "library.pick", List.of(directions), candidates).declaration());
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum("Color"));
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum("Color."));
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum("module..Color"));
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum(".Color"));
    }

    @Test
    void dynamicArgumentsAndErasedGenericTypeKeysAreNotAdmitted() {
        List<Candidate<String>> candidates = List.of(
                candidate("color", TypeKey.nominalEnum("graphics.Color")),
                candidate("direction", TypeKey.nominalEnum("graphics.Direction")));
        for (TypeKey argument : List.of(TypeKey.any(), TypeKey.unknown())) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> select(
                            Domain.MODULE, "library.pick", List.of(argument), candidates));
            assertTrue(denied.getMessage().contains("narrow or cast"));
        }
        for (TypeKey dynamic : List.of(TypeKey.any(), TypeKey.unknown())) {
            assertThrows(IllegalArgumentException.class, () ->
                    new Signature(Domain.MODULE, "library.pick", List.of(dynamic)));
        }
        assertThrows(IllegalArgumentException.class, () -> primitive("Option<int>"));
        assertThrows(IllegalArgumentException.class, () -> primitive("Result<String, int>"));
    }

    @Test
    void sourceTypeBridgeUsesCompilerPrimitiveAliasesAndResolvedEnumIds() {
        Map<String, String> nominal = Map.of(
                "Color", "first.Color",
                "second.Color", "second.Color",
                "Direction", "first.Direction");
        for (String type : List.of("i8", "u64", "bigint", "int", "uint")) {
            assertEquals(primitive("int"), TypeKey.fromSource(Ast.TypeRef.simple(type), nominal));
        }
        for (String type : List.of("f32", "f64", "float")) {
            assertEquals(primitive("float"), TypeKey.fromSource(Ast.TypeRef.simple(type), nominal));
        }
        for (String type : List.of("Bool", "boolean", "bool")) {
            assertEquals(primitive("bool"), TypeKey.fromSource(Ast.TypeRef.simple(type), nominal));
        }
        for (String type : List.of("String", "string")) {
            assertEquals(primitive("String"), TypeKey.fromSource(Ast.TypeRef.simple(type), nominal));
        }
        assertEquals(TypeKey.nominalEnum("first.Color"),
                TypeKey.fromSource(Ast.TypeRef.simple("Color"), nominal));
        assertEquals(TypeKey.nominalEnum("second.Color"),
                TypeKey.fromSource(Ast.TypeRef.simple("second.Color"), nominal));
        assertThrows(IllegalArgumentException.class, () ->
                TypeKey.fromSource(Ast.TypeRef.simple("Unresolved"), nominal));
        assertThrows(IllegalArgumentException.class, () ->
                TypeKey.fromSource(new Ast.TypeRef("Option",
                        List.of(Ast.TypeRef.simple("int")), false), nominal));
        assertThrows(IllegalArgumentException.class, () ->
                TypeKey.fromSource(new Ast.TypeRef("Color",
                        List.of(Ast.TypeRef.simple("int")), false), nominal));
    }

    @Test
    void parsedEnumSignaturesSelectNominallyButSourceDispatchRemainsGated() {
        // The enum parser is independently staged in PR #834. This test
        // verifies signature handling from parsed type references without
        // requiring that separate draft PR to have merged.
        var program = dev.oreslang.parser.Parser.parse("""
                fnc describe(Color value): int { return 3; }
                fnc describe(Direction value): int { return 4; }
                """);
        var declarations = program.modules().getFirst().declarations();
        List<Candidate<String>> candidates = new ArrayList<>();
        Map<String, String> enums =
                Map.of("Color", "__root__.Color", "Direction", "__root__.Direction");
        for (int i = 0; i < declarations.size(); i++) {
            Ast.FunctionDecl fn = (Ast.FunctionDecl) declarations.get(i);
            var params = fn.parameters().stream()
                    .map(p -> TypeKey.fromSource(p.type(), enums)).toList();
            candidates.add(new Candidate<>(fn.parameters().getFirst().type().name(),
                    new Signature(Domain.MODULE, "__root__." + fn.name(), params)));
        }
        assertEquals("Color", select(Domain.MODULE, "__root__.describe",
                List.of(TypeKey.nominalEnum("__root__.Color")), candidates).declaration());
        assertEquals("Direction", select(Domain.MODULE, "__root__.describe",
                List.of(TypeKey.nominalEnum("__root__.Direction")), candidates).declaration());
    }

    @Test
    void boolAliasesHaveOneIdentityAndDuplicateOverloadsFail() {
        assertEquals(primitive("bool"), primitive("boolean"));
        Signature first = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("boolean")));
        Signature second = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("bool")));
        assertEquals(first.symbolId(), second.symbolId());
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> select(Domain.MODULE, "library.pick",
                        List.of(primitive("bool")),
                        List.of(new Candidate<>("first", first),
                                new Candidate<>("second", second))));
        assertTrue(duplicate.getMessage().contains("duplicate typed overload"));
    }

    @Test
    void astBasedCallableSignaturesIgnoreFncRoutineKindAndSeparateMethods() {
        var parsed = dev.oreslang.parser.Parser.parse("""
                fnc execute(int value): int { return value; }
                routine execute(int value): int { return value + 1; }

                define class Worker as
                  pub handle(int value): int { return value; }
                  pub static fnc handle(int value): int { return value + 1; }
                end
                """);
        var decls = parsed.modules().getFirst().declarations();
        Signature fnc = Signature.ofFunction("__root__",
                (Ast.FunctionDecl) decls.get(0), Map.of());
        Signature routine = Signature.ofFunction("__root__",
                (Ast.FunctionDecl) decls.get(1), Map.of());
        assertEquals(fnc, routine);
        assertEquals(fnc.symbolId(), routine.symbolId());

        Ast.ClassDecl klass = (Ast.ClassDecl) decls.get(2);
        Signature instance = Signature.ofMethod("__root__",
                klass, klass.methods().get(0), Map.of());
        Signature statik = Signature.ofMethod("__root__",
                klass, klass.methods().get(1), Map.of());
        assertEquals(Domain.INSTANCE, instance.domain());
        assertEquals(Domain.STATIC, statik.domain());
        assertNotEquals(instance.symbolId(), statik.symbolId());
        assertEquals(1, instance.arity());
        assertEquals(1, statik.arity());
    }

    @Test
    void astBasedSelectorCannotPretendGenericErasureIsNominalOverloadIdentity() {
        var parsed = dev.oreslang.parser.Parser.parse("""
                fnc generic<T>(T input): void { return; }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
                Signature.ofFunction("__root__", fn, Map.of()));
    }

    @Test
    void genericDeclaringClassCannotImpersonateAnEnumSignature() {
        var parsed = dev.oreslang.parser.Parser.parse("""
                define class GenericBox<T> as
                  pub accept(T payload): int { return 1; }
                end

                define class Unrelated as
                  pub accept(int payload): int { return 2; }
                end
                """);
        Ast.ClassDecl generic = (Ast.ClassDecl)
                parsed.modules().getFirst().declarations().get(0);
        Ast.ClassDecl unrelated = (Ast.ClassDecl)
                parsed.modules().getFirst().declarations().get(1);
        // Even a malicious/incorrect lexical map cannot reinterpret a class
        // generic parameter T as a concrete enum without class substitution.
        assertThrows(IllegalArgumentException.class, () ->
                Signature.ofMethod("__root__", generic,
                        generic.methods().getFirst(), Map.of("T", "__root__.T")));
        assertThrows(IllegalArgumentException.class, () ->
                Signature.ofMethod("__root__", unrelated,
                        generic.methods().getFirst(), Map.of("T", "__root__.T")));
    }

    @Test
    void structuralParametersCannotBeMistakenForNominalEnumDiscriminants() {
        Ast.Program program = dev.oreslang.parser.Parser.parse("""
                fnc classify(structural Color value): int {
                  return 1;
                }

                define class Container as
                  pub classify(structural Color value): int {
                    return 2;
                  }
                end
                """);
        var declarations = program.modules().getFirst().declarations();
        var fn = (Ast.FunctionDecl) declarations.get(0);
        var cls = (Ast.ClassDecl) declarations.get(1);
        Map<String, String> spoof = Map.of("Color", "__root__.Color");
        assertThrows(IllegalArgumentException.class, () ->
                Signature.ofFunction("__root__", fn, spoof));
        assertThrows(IllegalArgumentException.class, () ->
                Signature.ofMethod("__root__", cls, cls.methods().getFirst(), spoof));
    }

    @Test
    void malformedUtf16CannotCollideUnderUtf8SymbolEncoding() {
        assertThrows(IllegalArgumentException.class, () ->
                new Signature(Domain.MODULE, "module." + Character.toString((char) 0xD800), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new Signature(Domain.MODULE, "module." + Character.toString((char) 0xD801), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                TypeKey.nominalEnum("module." + Character.toString((char) 0xDFFF)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeKey.nominalEnum("module." + Character.toString((char) 0xD800)));
        // A valid surrogate *pair* is preserved; no blanket non-ASCII ban.
        String valid = "module." + Character.toString(0x1F980);
        assertNotNull(new Signature(Domain.MODULE, valid, List.of()).symbolId());
    }

    @Test
    void ambiguityDiagnosticsAreOrderIndependent() {
        Candidate<String> left = candidate("left", primitive("int"), primitive("float"));
        Candidate<String> right = candidate("right", primitive("float"), primitive("int"));
        List<TypeKey> arguments = List.of(primitive("int"), primitive("int"));
        String a = assertThrows(IllegalArgumentException.class, () ->
                select(Domain.MODULE, "library.pick",
                        arguments, List.of(left, right))).getMessage();
        String b = assertThrows(IllegalArgumentException.class, () ->
                select(Domain.MODULE, "library.pick",
                        arguments, List.of(right, left))).getMessage();
        assertEquals(a, b);
    }

    @Test
    void callableDomainsAndArityAreNeverInterchanged() {
        Signature instance = new Signature(Domain.INSTANCE, "Picker.select",
                List.of(primitive("int")));
        Signature statik = new Signature(Domain.STATIC, "Picker.select",
                List.of(primitive("int")));
        Signature two = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("int"), primitive("int")));
        assertNotEquals(instance.symbolId(), statik.symbolId());
        assertNotEquals(two.symbolId(), candidate("one", primitive("int")).signature().symbolId());
        assertThrows(IllegalArgumentException.class, () -> select(
                Domain.STATIC, "Picker.select", List.of(primitive("int")),
                List.of(new Candidate<>("instance", instance))));
    }

    @Test
    void mangledSymbolIsInjectiveForDelimitersAndUnicode() {
        Signature a = new Signature(Domain.MODULE, "m.foo$bar",
                List.of(TypeKey.nominalEnum("m.Color")));
        Signature b = new Signature(Domain.MODULE, "m.foo",
                List.of(TypeKey.nominalEnum("bar$m.Color")));
        Signature c = new Signature(Domain.MODULE, "m.色",
                List.of(TypeKey.nominalEnum("m.颜色")));
        assertNotEquals(a.symbolId(), b.symbolId());
        assertNotEquals(a.symbolId(), c.symbolId());
        assertEquals(c.symbolId(), c.symbolId());
        assertTrue(a.symbolId().startsWith("$overload$v1$"));
    }

    @Test
    void randomizedCandidateOrderCannotInfluenceWinnerOrTie() {
        Candidate<String> exact = candidate("exact", TypeKey.nominalEnum("graphics.Color"), primitive("int"));
        Candidate<String> widened = candidate("direction", TypeKey.nominalEnum("graphics.Direction"), primitive("int"));
        Candidate<String> other = candidate("weather", TypeKey.nominalEnum("weather.Color"), primitive("int"));
        ArrayList<Candidate<String>> all = new ArrayList<>(List.of(exact, widened, other));
        Random random = new Random(4831L);
        for (int repetition = 0; repetition < 100; repetition++) {
            java.util.Collections.shuffle(all, random);
            assertEquals("exact", select(
                    Domain.MODULE, "library.pick",
                    List.of(TypeKey.nominalEnum("graphics.Color"), primitive("int")), all).declaration());
        }
    }

    @Test
    void everyDifferingPositionNeedsProofAndEveryEnumNeedsEligibility() {
        var color = TypeKey.nominalEnum("graphics.Color");
        var direction = TypeKey.nominalEnum("graphics.Direction");
        var left = candidate("left", color, color);
        var right = candidate("right", direction, direction);
        for (List<TypeKey> args : List.of(List.of(color, TypeKey.unknown()),
                List.of(TypeKey.any(), color), List.of(color, direction))) {
            assertThrows(IllegalArgumentException.class, () ->
                    select(Domain.MODULE, "library.pick", args, List.of(left, right)));
        }
        assertEquals(left, select(Domain.MODULE, "library.pick", List.of(color, color), List.of(left, right)));
        assertThrows(IllegalArgumentException.class, () -> TypedOverloadResolver.select(
                Domain.MODULE, "library.pick", List.of(color, color), List.of(left, right), Set.of(color.identity())));
    }

    @Test
    void boundsRejectOversizedSameArityFamilies() {
        var one = candidate("one", primitive("int"));
        assertThrows(IllegalArgumentException.class, () -> select(Domain.MODULE, "library.pick",
                List.of(primitive("int")), java.util.Collections.nCopies(129, one)));
        assertThrows(IllegalArgumentException.class, () -> select(Domain.MODULE, "library.pick",
                java.util.Collections.nCopies(65, primitive("int")), List.of(one)));
    }

    @Test
    void returnTypeAndEffectCannotSelectAnOverload() {
        // The signature intentionally has no result, fnc/routine tag,
        // async/actor effect, source location or declaration-order field.
        Signature first = candidate("first", primitive("int")).signature();
        Signature second = candidate("second", primitive("int")).signature();
        assertEquals(first, second);
    }
}
