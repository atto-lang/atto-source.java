package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compile-time proof that Any and source Unknown cannot become an unchecked any. */
final class AnyUnknownSoundnessTest {
    private static void accept(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void reject(String source, String message) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test
    void emptyArraysCarryDistinctUnresolvedIdentities() {
        Types.InferenceHole a = Types.newInferenceHole(Types.HoleOrigin.EMPTY_ARRAY);
        Types.InferenceHole b = Types.newInferenceHole(Types.HoleOrigin.EMPTY_ARRAY);
        assertNotEquals(a, b);
        assertFalse(Types.isAssignable(a, Types.Primitive.INT));
        assertFalse(Types.isAssignable(Types.Primitive.INT, a));
        assertFalse(Types.isAssignable(a, Types.Any.INSTANCE));
        assertTrue(Types.containsOpaqueTop(new Types.ListType(a)));
    }

    @Test
    void emptyArrayInferenceRequiresConcreteContext() {
        accept("fnc empty(): Array<int> { return []; }");
        accept("fnc empty(): Array<string> { return []; }");
        reject("fnc bad(): int { return []; }", "return value");
        reject("""
                fnc bad(): void {
                    val xs = [];
                    xs.add(77);
                    return;
                }
                """, "collection member argument");
    }

    @Test
    void emptyArrayAliasCannotResolveToConflictingElementTypes() {
        reject("""
                fnc bad(): void {
                    val raw = [];
                    val Array<int> ints = raw;
                    val Array<string> words = raw;
                    return;
                }
                """, "initializer for");
    }

    @Test
    void solvedEmptyArrayCannotBeWrittenWithConflictingElementType() {
        reject("""
                fnc bad(): void {
                    val raw = [];
                    val Array<int> ints = raw;
                    raw.add("bad");
                    return;
                }
                """, "collection member argument");
    }

    @Test
    void optionNoneAndResultUnfilledArmsUseDistinctContextualHoles() {
        accept("fnc noValue(): Option<int> { return None; }");
        accept("fnc success(): Result<int, string> { return Ok(42); }");
        accept("fnc failure(): Result<int, string> { return Err(\"failed\"); }");
        reject("fnc bad(): int { return None; }", "return value");
    }

    @Test
    void optionNoneCannotAcquireContradictoryTypesThroughAliases() {
        reject("""
                fnc bad(): void {
                    val absent = None;
                    val Option<int> a = absent;
                    val Option<string> b = absent;
                    return;
                }
                """, "initializer for");
    }

    @Test
    void nestedLegacyHolesCannotImpersonateConcreteGenericTypes() {
        assertFalse(Types.isAssignable(
                new Types.ListType(Types.Unknown.INSTANCE),
                new Types.ListType(Types.Primitive.INT)));
        assertFalse(Types.isAssignable(
                new Types.Named("Option", List.of(Types.Unknown.INSTANCE)),
                new Types.Named("Option", List.of(Types.Primitive.INT))));
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("payload", Types.Unknown.INSTANCE)),
                new Types.Record(Map.of("payload", Types.Primitive.INT))));
    }

    @Test
    void negativeTypeProofsRejectAllOpaqueSources() {
        Types.Type notInt = Types.notOf(Types.Primitive.INT);
        Types.InferenceHole hole = Types.newInferenceHole(Types.HoleOrigin.EMPTY_ARRAY);
        for (Types.Type opaque : List.of(
                Types.Any.INSTANCE,
                Types.UnknownValue.INSTANCE,
                Types.Unknown.INSTANCE,
                hole,
                new Types.Generic("T"))) {
            assertFalse(Types.isAssignable(opaque, notInt),
                    () -> "unproven type must not satisfy !int: " + opaque);
            assertFalse(Types.areProvablyDisjoint(opaque, Types.Primitive.INT),
                    () -> "opaque type cannot prove disjointness: " + opaque);
        }
        assertFalse(Types.isAssignable(Types.Primitive.INT,
                Types.notOf(Types.UnknownValue.INSTANCE)));
        assertFalse(Types.isAssignable(Types.Primitive.INT,
                Types.notOf(Types.Any.INSTANCE)));
        assertFalse(Types.isAssignable(Types.Primitive.INT,
                Types.notOf(hole)));
        assertTrue(Types.isAssignable(Types.Primitive.STRING, notInt));
    }

    @Test
    void nestedOpaqueValuesCannotProveNegativeStructuralShapes() {
        Types.Type excluded = new Types.Record(Map.of("foo", Types.Primitive.INT));
        Types.Type hidden = new Types.Record(Map.of("foo", Types.UnknownValue.INSTANCE));
        Types.Type unresolved = new Types.Record(Map.of(
                "foo", Types.newInferenceHole(Types.HoleOrigin.OPTION_NONE)));
        assertFalse(Types.isAssignable(hidden, Types.notOf(excluded)));
        assertFalse(Types.isAssignable(unresolved, Types.notOf(excluded)));
        assertFalse(Types.isAssignable(
                new Types.ListType(Types.UnknownValue.INSTANCE),
                Types.notOf(new Types.ListType(Types.Primitive.INT))));
    }

    @Test
    void compilerRejectsOpaqueArgumentToNegativeContract() {
        reject("""
                fnc take(!int value): void {}
                fnc bad(Any value): void { take(value); }
                """, "argument");
        reject("""
                fnc take(!int value): void {}
                fnc bad(Unknown value): void { take(value); }
                """, "argument");
    }

    @Test
    void sourceTopsAreNotTheInternalInferenceHole() {
        assertNotEquals(Types.Any.INSTANCE, Types.Unknown.INSTANCE);
        assertNotEquals(Types.UnknownValue.INSTANCE, Types.Unknown.INSTANCE);
        assertEquals("Any", Types.Any.INSTANCE.toString());
        assertEquals("Unknown", Types.UnknownValue.INSTANCE.toString());
        assertEquals("<incomplete compiler inference>", Types.Unknown.INSTANCE.toString());
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, Types.Primitive.INT));
        assertTrue(Types.isAssignable(Types.Primitive.INT, Types.Unknown.INSTANCE));
        assertTrue(Types.isAssignable(Types.Primitive.INT, Types.Any.INSTANCE));
        assertTrue(Types.isAssignable(Types.Primitive.INT, Types.UnknownValue.INSTANCE));
        assertTrue(Types.isAssignable(Types.Any.INSTANCE, Types.UnknownValue.INSTANCE));
        assertFalse(Types.isAssignable(Types.UnknownValue.INSTANCE, Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(Types.Any.INSTANCE, Types.Primitive.INT));
        assertFalse(Types.isAssignable(Types.UnknownValue.INSTANCE, Types.Primitive.INT));
        assertFalse(Types.isAssignable(new Types.Generic("T"), Types.Primitive.INT));
        assertFalse(Types.isAssignable(Types.Primitive.INT, new Types.Generic("T")));
    }

    @Test
    void sourceTopsRejectGenericArgumentsAndPreserveContainerInvariance() {
        reject("fnc bad(Any<int> value): void { return; }", "does not accept type arguments");
        reject("fnc bad(Unknown<int> value): void { return; }", "does not accept type arguments");
        assertFalse(Types.isAssignable(
                new Types.ListType(Types.Primitive.INT),
                new Types.ListType(Types.Any.INSTANCE)));
        assertFalse(Types.isAssignable(
                new Types.Named("Box", List.of(Types.Primitive.INT)),
                new Types.Named("Box", List.of(Types.Any.INSTANCE))));
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("foo", Types.UnknownValue.INSTANCE)),
                new Types.Record(Map.of("foo", Types.Primitive.INT))));
    }

    @Test
    void knownValuesCanWidenToAnyAndUnknown() {
        accept("""
                fnc box(int value): Any { return value; }
                fnc obscure(Any value): Unknown { return value; }
                fnc pass(Unknown value): Unknown { return value; }
                """);
    }

    @Test
    void topTypesDoNotNarrowImplicitly() {
        reject("fnc bad(Any value): int { return value; }", "return");
        reject("fnc bad(Unknown value): int { return value; }", "return");
        reject("fnc bad(Unknown value): Any { return value; }", "return");
        reject("fnc bad(Unknown value): bool { return value; }", "return");
    }

    @Test
    void topTypesCannotInvokeOrReadUncheckedMembers() {
        reject("fnc bad(Any value): void { value.foo(); return; }", "narrow or validate");
        reject("fnc bad(Unknown value): void { value.foo(); return; }", "narrow or validate");
        reject("fnc bad(Any value): void { val item = value.foo; return; }", "narrow or validate");
        reject("fnc bad(Unknown value): void { val item = value.foo; return; }", "narrow or validate");
        reject("fnc bad(Any value): void { value(); return; }", "validated callable");
    }

    @Test
    void primitiveRuntimeChecksAreAllowedButErasedGenericChecksRemainClosed() {
        accept("""
                fnc checked(Any value): bool {
                  return value is int;
                }
                fnc checkedUnknown(Unknown value): bool {
                  return value is string;
                }
                """);
        reject("fnc bad(Any value): bool { return value is Array<int>; }",
                "until generic runtime type arguments are reified");
        reject("fnc bad(Unknown value): bool { return value is Unknown; }",
                "validate against a concrete runtime type");
    }

    @Test
    void genericTypeParametersCannotMasqueradeAsReifiedRuntimeTypes() {
        reject("fnc bad<T>(T value): bool { return value is T; }", "reified runtime type witness");
        reject("fnc bad<T>(T value): T { return value as T; }", "reified runtime type witness");
    }

    @Test
    void aliasesCannotBypassRuntimeReificationGuards() {
        reject("""
                type Numbers = Array<int>;
                fnc bad(Any value): bool { return value is Numbers; }
                """, "require reified witnesses");
        reject("""
                type Numbers = Array<int>;
                fnc bad(Unknown value): Numbers { return value as Numbers; }
                """, "require reified witnesses");
        reject("""
                type Safe = Any;
                fnc bad(Unknown value): bool { return value is Safe; }
                """, "without verified runtime type information");
        reject("""
                type Shape = {foo: int};
                fnc bad(Any value): bool { return value is Shape; }
                """, "explicit structural matching");
        reject("""
                type Nested = Array<Array<int>>;
                fnc bad(Any value): bool { return value is Nested; }
                """, "require reified witnesses");
    }

    @Test
    void opaqueUnionNormalizationRespectsSafeWidening() {
        assertEquals(Types.Any.INSTANCE,
                Types.unionOf(Types.Any.INSTANCE, Types.Primitive.INT));
        assertEquals(Types.UnknownValue.INSTANCE,
                Types.unionOf(Types.UnknownValue.INSTANCE, Types.Any.INSTANCE));
        assertEquals(Types.UnknownValue.INSTANCE,
                Types.unionOf(Types.UnknownValue.INSTANCE, Types.Primitive.INT));
        assertInstanceOf(Types.Union.class,
                Types.unionOf(Types.Any.INSTANCE,
                        new Types.Borrow(Types.Primitive.INT, false)));
    }

    @Test
    void mutableStructuralFieldsDoNotWidenToWritableAny() {
        Types.Record mutableInt = new Types.Record(Map.of("foo", Types.Primitive.INT));
        Types.Record mutableAny = new Types.Record(Map.of("foo", Types.Any.INSTANCE));
        Types.Record readonlyAny = new Types.Record(Map.of("foo", Types.Any.INSTANCE), true);
        Types.Record readonlyInt = new Types.Record(Map.of("foo", Types.Primitive.INT), true);
        assertFalse(Types.isAssignable(mutableInt, mutableAny));
        assertTrue(Types.isAssignable(mutableInt, readonlyAny));
        assertFalse(Types.isAssignable(readonlyInt, mutableInt));
        assertFalse(Types.isAssignable(readonlyInt, mutableAny));
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("value", Types.Primitive.INT)),
                new Types.Record(Map.of("value", Types.Primitive.FLOAT))));
        assertTrue(Types.isAssignable(
                new Types.Record(Map.of("foo", Types.Primitive.INT, "bar", Types.Primitive.BOOL)),
                new Types.Record(Map.of("foo", Types.Primitive.INT), true)));
    }

    @Test
    void forgedMethodSlotNamesDoNotDisableWritableFieldInvariance() {
        String key = "instance$run$arity1$generics0";
        Types.Function wide = new Types.Function(
                List.of(Types.Any.INSTANCE), Types.Primitive.INT);
        Types.Function narrow = new Types.Function(
                List.of(Types.Primitive.INT), Types.Primitive.INT);
        assertTrue(Types.isAssignable(wide, narrow));
        assertFalse(Types.isAssignable(narrow, wide));
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of(key, wide)),
                new Types.Record(Map.of(key, narrow))));
        assertTrue(Types.isAssignable(
                new Types.Record(Map.of(key, wide)),
                new Types.Record(Map.of(key, narrow), true)));
    }

    @Test
    void structuralParametersAreReadOnlyEvenWhenCallerOwnsMutableObject() {
        reject("""
                define module app
                  define interface HasValue
                    value: int;
                  end
                  fnc invalid(structural HasValue v): void {
                    v.value = 7;
                    return;
                  }
                end
                """, "readonly structural/inferred record view");
    }

    @Test
    void nestedFieldsCannotMutateThroughReadonlyStructuralParameter() {
        reject("""
                define module app
                  define interface Outer
                    inner: {foo: int};
                  end
                  fnc invalid(structural Outer parent): void {
                    parent.inner.foo = 9;
                    return;
                  }
                end
                """, "cannot mutate a nested field through a readonly structural view");
    }

    @Test
    void readonlyNestedRecordAliasesCannotRestoreMutation() {
        reject("""
                define module app
                  define interface Nested
                    inner: {foo: int};
                  end
                  fnc bad(structural Nested p): void {
                    val alias = p.inner;
                    alias.foo = 7;
                    return;
                  }
                end
                """, "readonly structural/inferred record view");
    }

    @Test
    void conditionalJoinsKeepSumTypesInsteadOfUnknownWildcard() {
        accept("""
                fnc choose(bool flag): int | string {
                  return flag ? 7 : "seven";
                }
                """);
        reject("""
                fnc bad(bool flag): int {
                  return flag ? 7 : "seven";
                }
                """, "return value");
    }

    @Test
    void directInternalInferenceHoleDoesNotImpersonateKnownValue() {
        // Unknown stdlib attributes currently produce a legacy inference
        // sentinel; they cannot be silently returned as a proven int.
        reject("fnc bad(): int { return std.not_a_declared_field; }", "return");
        assertFalse(Types.isAssignable(Types.UnknownValue.INSTANCE, Types.Primitive.INT));
    }

    @Test
    void dynamicTupleWritesCannotTargetIncompatibleSlots() {
        reject("""
                fnc bad(int index): void {
                  let mut tuple = (10, "hello");
                  tuple[index] = 77;
                  return;
                }
                """, "dynamic tuple assignment requires homogeneous writable slot types");
    }

    @Test
    void dynamicReadsMustNotManufactureAnUncheckedInt() {
        reject("""
                fnc bad(int index): int {
                  val tuple = (10, "hello");
                  return tuple[index];
                }
                """, "return");
        reject("""
                fnc bad(string field): int {
                  val obj = infer struct{foo: 1, bar: "hello"};
                  return obj[field];
                }
                """, "return");
    }

    @Test
    void unresolvedForeignValuesCannotSmuggleThroughLiteralAggregates() {
        reject("""
                fnc bad(): Array<int> {
                  return [std.not_a_declared_field];
                }
                """, "array/list element has an unresolved compiler-internal type");
        reject("""
                fnc bad(): void {
                  val rec = infer struct{foo: std.not_a_declared_field};
                  return;
                }
                """, "struct field 'foo' has an unresolved compiler-internal type");
        reject("""
                fnc bad(): void {
                  val pair = (42, std.not_a_declared_field);
                  return;
                }
                """, "tuple element has an unresolved compiler-internal type");
    }

    @Test
    void sourceTopsCannotBeShadowedByAliasesOrGenericParameters() {
        reject("fnc identity<Any>(Any value): Any { return value; }", "reserved type name");
        reject("fnc identity<Unknown>(Unknown value): Unknown { return value; }", "reserved type name");
        reject("type Any = int; fnc ok(): void { return; }", "reserved type name");
        reject("type Unknown = int; fnc ok(): void { return; }", "reserved type name");
        reject("define class Any as end", "reserved type name");
        reject("define interface Unknown end", "reserved type name");
    }

    @Test
    void unregisteredNominalTypesCannotProduceFakeRuntimeMatches() {
        reject("fnc bad(Any value): bool { return value is UnregisteredNominal; }",
                "no verified Oreslang runtime type descriptor");
    }

    @Test
    void noUnverifiedPayloadCanEvadeActorOrChannelChecks() {
        reject("fnc bad(Channel<Any> ch): void { return; }", "cannot conceal an Any/Unknown payload");
        reject("fnc bad(Channel<Array<Unknown>> ch): void { return; }", "cannot conceal an Any/Unknown payload");
        assertFalse(Types.isAssignable(new Types.Borrow(Types.Primitive.INT, false), Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(new Types.Function(List.of(), Types.Primitive.INT), Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(new Types.Generic("T"), Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(
                new Types.Named("ActorRef", List.of(new Types.Named("Worker", List.of()))),
                Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(
                new Types.Named("Channel", List.of(Types.Primitive.INT)),
                Types.UnknownValue.INSTANCE));
        assertFalse(Types.isAssignable(
                new Types.Named("std.Channel", List.of(Types.Primitive.INT)),
                Types.Any.INSTANCE));
        assertFalse(Types.isAssignable(
                new Types.Record(Map.of("handle", new Types.Named("Mutex", List.of(Types.Primitive.INT)))),
                Types.Any.INSTANCE));
    }
}
