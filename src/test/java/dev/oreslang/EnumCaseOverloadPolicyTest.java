package dev.oreslang;

import dev.oreslang.types.EnumCaseOverloadPolicy;
import dev.oreslang.types.EnumCaseOverloadPolicy.EnumInfo;
import dev.oreslang.types.EnumCaseOverloadPolicy.Parameter;
import dev.oreslang.types.EnumCaseOverloadPolicy.Signature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.oreslang.types.EnumCaseOverloadPolicy.Domain.*;
import static dev.oreslang.types.EnumCaseOverloadPolicy.Parameter.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-free proof tests: these do NOT assert source Foo.A parameters compile.
 * The actual parser, ownership checker, interpreter and LLVM remain gated.
 */
final class EnumCaseOverloadPolicyTest {
    private static final Map<String, EnumInfo> ENUMS = Map.of(
            "root.Foo", new EnumInfo("root.Foo", List.of("A", "B", "C"), true),
            "root.Bar", new EnumInfo("root.Bar", List.of("A", "B"), true),
            "other.Foo", new EnumInfo("other.Foo", List.of("A", "B"), true),
            "root.Unsafe", new EnumInfo("root.Unsafe", List.of("A"), false));

    private static Signature call(Parameter... params) {
        return new Signature(MODULE, "root.module", "foo", List.of(params));
    }

    @Test
    void disjointCasesAreLegalAndSelectAtCompileTime() {
        Signature a = call(enumCase("root.Foo", "A"), fixed("int"));
        Signature b = call(enumCase("root.Foo", "B"), fixed("int"));
        List<Signature> family = List.of(a, b);
        assertEquals(a, EnumCaseOverloadPolicy.selectStatic(
                family, ENUMS, List.of(enumCase("root.Foo", "A"), fixed("int"))));
        assertEquals(b, EnumCaseOverloadPolicy.selectStatic(
                family, ENUMS, List.of(enumCase("root.Foo", "B"), fixed("int"))));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.selectStatic(
                        family, ENUMS, List.of(enumType("root.Foo"), fixed("int"))));
    }

    @Test
    void wholeEnumFallbackIsStaticNotRuntimeCaseDispatch() {
        Signature whole = call(enumType("root.Foo"), fixed("int"));
        Signature a = call(enumCase("root.Foo", "A"), fixed("int"));
        Signature b = call(enumCase("root.Foo", "B"), fixed("int"));
        List<Signature> family = List.of(whole, a, b);
        assertEquals(a, EnumCaseOverloadPolicy.selectStatic(
                family, ENUMS, List.of(enumCase("root.Foo", "A"), fixed("int"))));
        assertEquals(whole, EnumCaseOverloadPolicy.selectStatic(
                family, ENUMS, List.of(enumCase("root.Foo", "C"), fixed("int"))));
        assertEquals(whole, EnumCaseOverloadPolicy.selectStatic(
                family, ENUMS, List.of(enumType("root.Foo"), fixed("int"))));
    }

    @Test
    void crossedRefinementsCannotBeOrderedAndFailAtDeclaration() {
        Signature left = call(enumCase("root.Foo", "A"), enumType("root.Bar"));
        Signature right = call(enumType("root.Foo"), enumCase("root.Bar", "B"));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(List.of(left, right), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.selectStatic(List.of(left, right), ENUMS,
                        List.of(enumCase("root.Foo", "A"), enumCase("root.Bar", "B"))));
    }

    @Test
    void multiplyNestedSpecificityAndDeclarationOrderAreDeterministic() {
        Signature base = call(enumType("root.Foo"), enumType("root.Bar"));
        Signature a = call(enumCase("root.Foo", "A"), enumType("root.Bar"));
        Signature ab = call(enumCase("root.Foo", "A"), enumCase("root.Bar", "B"));
        List<Parameter> proof = List.of(enumCase("root.Foo", "A"), enumCase("root.Bar", "B"));
        assertEquals(ab, EnumCaseOverloadPolicy.selectStatic(List.of(base, a, ab), ENUMS, proof));
        assertEquals(ab, EnumCaseOverloadPolicy.selectStatic(List.of(ab, base, a), ENUMS, proof));
        assertEquals(base, EnumCaseOverloadPolicy.selectStatic(
                List.of(base, a, ab), ENUMS, List.of(enumType("root.Foo"), enumType("root.Bar"))));
    }

    @Test
    void twoNominalEnumsWithIdenticalCaseNamesDoNotAlias() {
        Signature foo = call(enumType("root.Foo"));
        Signature otherFoo = call(enumType("other.Foo"));
        assertEquals(otherFoo, EnumCaseOverloadPolicy.selectStatic(List.of(foo, otherFoo),
                ENUMS, List.of(enumCase("other.Foo", "A"))));
        assertEquals(foo, EnumCaseOverloadPolicy.selectStatic(List.of(foo, otherFoo),
                ENUMS, List.of(enumCase("root.Foo", "A"))));
    }

    @Test
    void ordinaryTypeDifferencesNeverCreateOverloadSlots() {
        Signature a = call(enumCase("root.Foo", "A"), fixed("int"));
        Signature b = call(enumCase("root.Foo", "B"), fixed("string"));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(List.of(a, b), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(call(fixed("int")), call(fixed("string"))), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(call(enumType("root.Foo")), call(fixed("int"))), ENUMS));
    }

    @Test
    void duplicateAndForgedCaseIdentityAreRejected() {
        Signature a = call(enumCase("root.Foo", "A"));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(List.of(a, a), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(call(enumCase("root.Foo", "NotACase"))), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(call(enumCase("root.Unsafe", "A"))), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(call(enumType("forged.Foo"))), ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(
                        List.of(a), Map.of("root.Foo",
                                new EnumInfo("other.Foo", List.of("A"), true))));
    }

    @Test
    void directRecordConstructorsCannotBypassValidationAndInputsAreImmutable() {
        assertThrows(IllegalArgumentException.class, () ->
                new Parameter(null, "root.Foo", "A"));
        assertThrows(IllegalArgumentException.class, () ->
                new Parameter(EnumCaseOverloadPolicy.Kind.ENUM, "root.Foo", "A"));
        assertThrows(IllegalArgumentException.class, () -> fixed("Any"));
        // No source-supplied class names, structural strings, unknown or
        // noncanonical primitive aliases may become FIXED ABI evidence.
        for (String id : List.of("User", "Foo.A", "i8", "String", "f64",
                "Tuple<int>", "{x:int}", "Unknown", "void")) {
            assertThrows(IllegalArgumentException.class, () ->
                    fixed(id), "expected fixed-type rejection for " + id);
        }
        for (String id : List.of("int", "bool", "string", "float")) {
            assertDoesNotThrow(() -> fixed(id));
        }
        assertThrows(IllegalArgumentException.class, () -> enumCase("root.Foo", ""));
        assertThrows(IllegalArgumentException.class, () -> enumType("Foo"));
        assertThrows(IllegalArgumentException.class, () -> enumType("root. Foo"));
        assertThrows(IllegalArgumentException.class, () -> enumType("root.\u0000Foo"));
        assertThrows(IllegalArgumentException.class, () -> enumType("root.\uD800"));
        assertThrows(IllegalArgumentException.class, () ->
                new EnumInfo("root.X", List.of("A", "A"), true));
        ArrayList<Parameter> mutable = new ArrayList<>(List.of(enumType("root.Foo")));
        Signature signature = new Signature(MODULE, "root.module", "foo", mutable);
        mutable.clear();
        assertEquals(1, signature.arity());
        assertThrows(UnsupportedOperationException.class, () ->
                signature.params().add(enumType("root.Foo")));
    }

    @Test
    void boundWorkIsFiniteAndDomainIsPartOfIdentity() {
        ArrayList<Parameter> manyParams = new ArrayList<>();
        for (int i = 0; i <= EnumCaseOverloadPolicy.MAX_ARITY; i++) manyParams.add(fixed("int"));
        assertThrows(IllegalArgumentException.class, () ->
                new Signature(MODULE, "root.module", "foo", manyParams));
        ArrayList<Signature> tooMany = new ArrayList<>();
        for (int i = 0; i <= EnumCaseOverloadPolicy.MAX_CANDIDATES; i++) {
            tooMany.add(call(enumType("root.Foo")));
        }
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(tooMany, ENUMS));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.validateFamily(List.of(
                        call(enumType("root.Foo")),
                        new Signature(INSTANCE, "root.module", "foo",
                                List.of(enumType("root.Foo")))), ENUMS));
    }

    @Test
    void stableSlotSymbolsIncludeCaseDomainArityAndOwnerWithoutWireTags() {
        Signature a = call(enumCase("root.Foo", "A"), fixed("int"));
        Signature b = call(enumCase("root.Foo", "B"), fixed("int"));
        Signature whole = call(enumType("root.Foo"), fixed("int"));
        assertEquals("eos26_4d4f44554c4511_726f6f742e6d6f64756c653_666f6fa2_C8_726f6f742e466f6f1_41F3_696e74",
                a.symbolId());
        assertNotEquals(a.symbolId(), b.symbolId());
        assertNotEquals(a.symbolId(), whole.symbolId());
        assertNotEquals(a.symbolId(),
                new Signature(INSTANCE, "root.module", "foo", a.params()).symbolId());
        assertNotEquals(a.symbolId(),
                new Signature(MODULE, "root.other", "foo", a.params()).symbolId());
        assertNotEquals(a.symbolId(),
                call(enumCase("other.Foo", "A"), fixed("int")).symbolId());
        assertEquals(a.symbolId(),
                new Signature(MODULE, "root.module", "foo", a.params()).symbolId());
    }

    @Test
    void staticProofCannotFabricateANarrowingOrWrongArity() {
        Signature a = call(enumCase("root.Foo", "A"));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.selectStatic(List.of(a), ENUMS,
                        List.of(enumType("root.Foo"))));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.selectStatic(List.of(a), ENUMS, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                EnumCaseOverloadPolicy.selectStatic(List.of(a), ENUMS,
                        List.of(enumCase("root.Foo", "B"))));
    }
}
