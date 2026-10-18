package dev.oreslang.types;

import java.util.List;
import java.util.Map;

/**
 * Dependency-free executable checker for the compiler's AnyCallable subtype
 * algebra. Does not replace source parser, ownership, JUnit or Native Image.
 */
public final class AnyCallableTypeHarness {
    private static int assertions;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }

    public static void main(String[] args) {
        Types.Type anyCallable = Types.AnyCallable.INSTANCE;
        Types.Type unknown = Types.Unknown.INSTANCE;
        Types.Type number = Types.Primitive.INT;
        Types.Function integerCall = new Types.Function(List.of(number), number);
        Types.Function noArg = new Types.Function(List.of(), Types.Primitive.VOID);
        Types.Function async = new Types.Function(
                List.of(number), List.of(false), true, number);
        Types.Function mutable = new Types.Function(
                List.of(number), List.of(true), false, number);
        Types.Type nominalWithCall = new Types.Named("user.WithCall", List.of());
        Types.Type anotherGeneric = new Types.Generic("T");

        for (Types.Type concrete : List.of(integerCall, noArg, async, mutable)) {
            check(Types.isAssignable(concrete, anyCallable), "callable widens to AnyCallable");
            check(!Types.isAssignable(anyCallable, concrete), "erased type cannot narrow to signature");
            check(Types.isAssignable(concrete, unknown), "concrete callable widens to value top");
        }
        check(Types.isAssignable(anyCallable, anyCallable), "AnyCallable identity");
        check(Types.isAssignable(anyCallable, unknown), "AnyCallable widens to Any");
        check(!Types.isAssignable(unknown, anyCallable), "erased Any is not proven callable");
        check(!Types.isAssignable(nominalWithCall, anyCallable), "class call property cannot implement builtin");
        check(!Types.isAssignable(number, anyCallable), "scalar is not callable");
        check(!Types.isAssignable(Types.Primitive.STRING, anyCallable), "string is not callable");
        check(!Types.isAssignable(anotherGeneric, anyCallable), "unknown generic does not prove callable");
        check(!Types.isAssignable(new Types.Borrow(integerCall, false), anyCallable),
                "borrowed callable is not an independently reifiable function value");
        check(!Types.isAssignable(new Types.ListType(integerCall), anyCallable),
                "collection containing callable is not a callable");
        check(!Types.isAssignable(new Types.Record(Map.of("call", integerCall), false), anyCallable),
                "record with call member is not a callable");
        check(!Types.isAssignable(new Types.Tuple(List.of(integerCall)), anyCallable),
                "tuple containing callable is not callable");
        check(!Types.areProvablyDisjoint(anyCallable, integerCall),
                "an erased callable cannot prove a negative overlap constraint");
        check(!Types.areProvablyDisjoint(anyCallable, number),
                "erased type information cannot be used as disjointness evidence");

        // A mutable-parameter function cannot acquire a non-mutating
        // signature by widening and then silently narrowing through erase.
        check(!Types.isAssignable(mutable, integerCall), "mutability contract is invariant");
        check(!Types.isAssignable(integerCall, mutable), "mutable argument cannot be gained");
        check(!Types.isAssignable(async, integerCall), "async effect cannot be erased in signature");
        check(!Types.isAssignable(integerCall, async), "synchronous value is not async signature");
        check(Types.isAssignable(integerCall, integerCall), "concrete callable remains invocable by signature");

        System.out.println("PASS " + assertions + " AnyCallable type-algebra assertions, JDK "
                + Runtime.version().feature());
    }
}
