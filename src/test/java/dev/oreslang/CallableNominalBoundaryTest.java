package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CallableNominalBoundaryTest {
    @Test
    void callableNamesCannotBeDeclaredAsNominalTypes() {
        for (String source : List.of(
                "define class Callable as end",
                "define class Fnc as end",
                "define class Function as end",
                "define class Routine as end",
                "define class Method as end",
                "define actor Callable as end",
                "define interface Callable as end",
                "define trait Callable as end",
                "define contract Callable as end"
        )) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("callable signatures"), failure::getMessage);
        }
    }

    @Test
    void noNominalHeritageFromCallableTypesOrPseudoInterfaces() {
        for (String source : List.of(
                "define class Foo extends Callable as end",
                "define class Foo extends Fnc<int, int> as end",
                "define class Foo extends Function<int, int> as end",
                "define class Foo extends Routine as end",
                "define class Foo implements Callable as end",
                "define class Foo implements Fnc<int, int> as end",
                "define class Foo implements Method as end",
                "define actor Foo extends Callable as end",
                "define actor Foo implements Callable as end",
                "define interface Foo extends Callable as end",
                "define trait Foo extends Fnc<int, int> as end",
                "define contract Foo extends Callable as end",
                "define class Foo extends typeof fnc(int) => int as end"
        )) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("not subclassable")
                    || failure.getMessage().contains("callable signatures"), failure::getMessage);
        }
    }

    @Test
    void semanticCheckerRejectsHandBuiltNominalCallableAst() {
        Ast.ClassDecl falseClass = new Ast.ClassDecl("Callable", false, List.of(),
                List.of(), List.of());
        Ast.ClassDecl falseSubclass = new Ast.ClassDecl("Child", false, List.of(),
                List.of(Ast.TypeRef.functionType(
                        List.of(Ast.TypeRef.simple("int")), Ast.TypeRef.simple("int"))),
                List.of(), List.of(), List.of());
        for (Ast.ClassDecl klass : List.of(falseClass, falseSubclass)) {
            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl(Parser.ROOT_MODULE, List.of(klass))));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(program));
            assertTrue(failure.getMessage().contains("callable identity"), failure::getMessage);
        }
    }

    @Test
    void ordinaryNominalTypesAndFirstClassFunctionSignaturesStillWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface Api as
                    fnc run(int x) => int;
                end
                define class Base as
                    pub method run(int x): int { return x; }
                end
                define class Child extends Base implements Api as
                end
                fnc increment(int x): int { return x + 1; }
                fnc apply(Fnc<int, int> f, int x): int { return f(x); }
                routine process(): void { return; }
                fnc main_test(): int { return apply(increment, 7); }
                """)));
        // Function signatures may be aliased or stored as ordinary data.
        // That is not the same as turning code into a subclassable object.
        assertDoesNotThrow(() -> Parser.parse("""
                type Callback = Fnc<int, int>;
                define class Holder as
                    val Callback callback = |int x| -> { return x + 1; };
                end
                """));
    }
}
