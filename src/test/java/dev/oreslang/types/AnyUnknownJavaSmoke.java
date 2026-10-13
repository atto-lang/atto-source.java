package dev.oreslang.types;

import java.util.List;
import java.util.Map;

/**
 * Standalone Java 21 contract smoke for the *actual checked-in* Types.java.
 * No JUnit, Maven, Truffle, Graal, or cross-org mirrored private source.
 *
 *   javac --release 21 -d build/any-unknown-smoke \
 *     src/main/java/dev/oreslang/types/Types.java \
 *     src/test/java/dev/oreslang/types/AnyUnknownJavaSmoke.java
 *   java -ea -cp build/any-unknown-smoke dev.oreslang.types.AnyUnknownJavaSmoke
 */
public final class AnyUnknownJavaSmoke {
    private AnyUnknownJavaSmoke() { }

    private static int assertions;

    private static void check(boolean pass, String description) {
        if (!pass) throw new AssertionError(description);
        assertions++;
    }

    public static void main(String[] args) {
        var integer = Types.Primitive.INT;
        var floating = Types.Primitive.FLOAT;
        var any = Types.Any.INSTANCE;
        var opaque = Types.UnknownValue.INSTANCE;

        check(Types.isAssignable(integer, any), "int widens to Any");
        check(Types.isAssignable(integer, opaque), "int widens to Unknown");
        check(Types.isAssignable(any, opaque), "Any widens to Unknown");
        check(!Types.isAssignable(any, integer), "Any cannot narrow to int");
        check(!Types.isAssignable(opaque, integer), "Unknown cannot narrow to int");
        check(!Types.isAssignable(opaque, any), "Unknown cannot narrow to Any");
        check(Types.Unknown.INSTANCE != opaque, "source Unknown distinct from inference hole");

        check(!Types.isAssignable(new Types.Generic("T"), any),
                "unresolved generic cannot hide behind Any");
        check(!Types.isAssignable(new Types.Function(List.of(), integer), any),
                "function cannot hide behind Any");
        check(!Types.isAssignable(new Types.Borrow(integer, false), opaque),
                "borrow cannot hide behind Unknown");
        check(!Types.isAssignable(Types.Primitive.VOID, any),
                "void is not a boxable value");
        check(!Types.isAssignable(
                new Types.Named("ActorRef", List.of(new Types.Named("Worker", List.of()))),
                any), "actor capability cannot hide behind Any");
        check(!Types.isAssignable(new Types.Named("Channel", List.of(integer)), opaque),
                "channel capability cannot hide behind Unknown");
        check(!Types.isAssignable(
                new Types.Record(Map.of("mutex", new Types.Named("Mutex", List.of(integer)))),
                any), "nested mutable capability cannot hide behind Any");

        check(!Types.isAssignable(new Types.ListType(integer), new Types.ListType(any)),
                "mutable collection type arguments remain invariant");
        check(!Types.isAssignable(
                new Types.Named("Box", List.of(integer)),
                new Types.Named("Box", List.of(any))),
                "nominal generic parameters remain invariant");

        var writableInt = new Types.Record(Map.of("foo", integer));
        var writableAny = new Types.Record(Map.of("foo", any));
        var readonlyAny = new Types.Record(Map.of("foo", any), true);
        var readonlyInt = new Types.Record(Map.of("foo", integer), true);
        check(!Types.isAssignable(writableInt, writableAny),
                "writable record fields must not be covariant");
        check(Types.isAssignable(writableInt, readonlyAny),
                "readonly record fields may safely widen");
        check(!Types.isAssignable(readonlyInt, writableInt),
                "readonly record must not regain write authority");
        check(!Types.isAssignable(
                new Types.Record(Map.of("foo", integer)),
                new Types.Record(Map.of("foo", floating))),
                "writable numeric widening is forbidden");
        check(Types.isAssignable(
                new Types.Record(Map.of("foo", integer, "bar", floating)),
                new Types.Record(Map.of("foo", integer), true)),
                "readonly structural width subtyping works");

        check(Types.unionOf(any, integer) == any, "safe Any union collapse");
        check(Types.unionOf(opaque, any) == opaque, "safe Unknown union collapse");
        check(Types.unionOf(any, new Types.Borrow(integer, false)) instanceof Types.Union,
                "unboxable borrow cannot disappear from union");
        check(Types.containsOpaqueTop(
                new Types.Named("Option", List.of(new Types.ListType(opaque)))),
                "deep opaque payload detection");
        check(!Types.containsOpaqueTop(new Types.ListType(integer)),
                "safe plain list does not contain opaque top");

        System.out.println("PASS: " + assertions + " Any/Unknown Java 21 type-model assertions");
    }
}
