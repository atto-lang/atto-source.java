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
    void sourceTopsAreNotTheInternalInferenceHole() {
        assertNotEquals(Types.Any.INSTANCE, Types.Unknown.INSTANCE);
        assertNotEquals(Types.UnknownValue.INSTANCE, Types.Unknown.INSTANCE);
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
    void directInternalInferenceHoleDoesNotImpersonateKnownValue() {
        reject("fnc bad(int value): int { return unrelated(value); }", "unknown name");
        assertFalse(Types.isAssignable(Types.UnknownValue.INSTANCE, Types.Primitive.INT));
    }

    @Test
    void sourceTopsCannotBeShadowedByGenericParameters() {
        reject("fnc identity<Any>(Any value): Any { return value; }", "reserved type name");
        reject("fnc identity<Unknown>(Unknown value): Unknown { return value; }", "reserved type name");
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
                new Types.Record(Map.of("handle", new Types.Named("Mutex", List.of(Types.Primitive.INT)))),
                Types.Any.INSTANCE));
    }
}
