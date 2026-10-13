package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest execution and fail-closed static overload checks for root and module callables. */
final class NominalFreeCallableOverloadTest {
    @Test
    void distinctEqualValueEnumsSelectDifferentSameArityFunctions() throws Exception {
        String output = run("""
                define enum OfPrimitives {
                    Foo = 1
                }
                define enum AlsoPrimitives {
                    Foo = 1
                }

                pub fnc x(OfPrimitives value): int { return 11; }
                pub fnc x(AlsoPrimitives value): int { return 22; }

                pub routine main(): void {
                    stdio.stdout.write(x(OfPrimitives.Foo));
                    stdio.stdout.write(":");
                    stdio.stdout.write(x(AlsoPrimitives.Foo));
                    stdio.stdout.write(":");
                    stdio.stdout.write(x(OfPrimitives.Foo()));
                    return;
                }
                """);
        assertEquals("11:22:11", output);
    }

    @Test
    void primitiveAndEnumTagsSelectSeparateOverloadsInModules() throws Exception {
        String output = run("""
                define enum Color {
                    Red = 1
                    Blue = 2
                }
                define module math as
                    pub fnc pick(int number): int { return number + 1; }
                    pub routine pick(bool flag): int { return flag ? 20 : 21; }
                    pub fnc pick(Color c): int { return 30; }
                end
                pub routine main(): void {
                    stdio.stdout.write(math.pick(3));
                    stdio.stdout.write(":");
                    stdio.stdout.write(math.pick(true));
                    stdio.stdout.write(":");
                    stdio.stdout.write(math.pick(Color.Red));
                    return;
                }
                """);
        assertEquals("4:20:30", output);
    }

    @Test
    void sameEnumWithDifferentVariantsIsNotASecondOverloadIdentity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define enum E { First = 1, Second = 2 }
                        fnc f(E e): int { return 1; }
                        routine f(E e): int { return 2; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void structuralArgumentsCannotChooseSameArityOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc pick(int value): int { return 1; }
                        fnc pick(int other): int { return 2; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void familyWithUnconstrainedOverlapIsRejectedBeforeExecution() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define enum A { One = 1 }
                        fnc pick(A a, int count): int { return count; }
                        fnc pick<T>(A a, T count): int { return 1; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 2"), error::getMessage);
    }

    @Test
    void returnTypeCannotSelectASecondOverload() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define enum E { One = 1 }
                        fnc pick(E value): int { return 1; }
                        fnc pick(E value): bool { return true; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void typedEnumValueKeepsItsOwnerForStaticSelection() throws Exception {
        String output = run("""
                define enum A { Same = 1 }
                define enum B { Same = 1 }
                fnc which(A a): int { return 10; }
                fnc which(B b): int { return 20; }
                pub routine main(): void {
                    const A a = A.Same;
                    const B b = B.Same;
                    stdio.stdout.write(which(b));
                    stdio.stdout.write(":");
                    stdio.stdout.write(which(a));
                    return;
                }
                """);
        assertEquals("20:10", output);
    }

    @Test
    void compilerRetainsIdentityBoundTargetInsteadOfRuntimeReselection() {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(
                Parser.parse("""
                        define enum A { Foo = 1 }
                        define enum B { Foo = 1 }
                        fnc dispatch(A a): int { return 1; }
                        fnc dispatch(B b): int { return 2; }
                        pub routine main(): void {
                            const A value = A.Foo;
                            stdio.stdout.write(dispatch(value));
                            return;
                        }
                        """));
        assertEquals(1, checked.resolvedFreeCalls().size());
        var binding = checked.resolvedFreeCalls().entrySet().iterator().next();
        assertEquals("A", binding.getValue().parameters().getFirst().type().name());
        // AST records compare structurally; compiler selections must *not*
        // authorize a distinct but equal-looking call site.
        Ast.CallExpr impersonator = new Ast.CallExpr(
                binding.getKey().callee(),
                binding.getKey().typeArguments(),
                binding.getKey().typeArgumentsPresent(),
                binding.getKey().arguments());
        assertEquals(binding.getKey(), impersonator);
        assertFalse(checked.resolvedFreeCalls().containsKey(impersonator));
        assertThrows(UnsupportedOperationException.class,
                () -> checked.resolvedFreeCalls().clear());
    }

    @Test
    void nominalOverloadFamilyRejectsDifferentSchedulerEnvelopes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define enum A { Foo = 1 }
                        define enum B { Foo = 1 }
                        fnc dispatch(A a): int { return 1; }
                        async fnc dispatch(B b): int { return 2; }
                        """)));
        assertTrue(error.getMessage().contains("scheduling envelopes"), error::getMessage);
    }

    @Test
    void typeAliasesCannotForgeANewEnumOverloadIdentity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define enum A { Foo = 1 }
                        type Alias = A;
                        fnc dispatch(A a): int { return 1; }
                        fnc dispatch(Alias a): int { return 2; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    @Test
    void canonicalPrimitiveAliasesCannotOverloadOneAnother() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc dispatch(i8 value): int { return 1; }
                        fnc dispatch(int value): int { return 2; }
                        """)));
        assertTrue(error.getMessage().contains("already has arity 1"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nominal-overload.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
