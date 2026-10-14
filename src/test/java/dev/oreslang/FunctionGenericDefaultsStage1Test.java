package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FunctionGenericDefaultsStage1Test {
    @Test
    void parserPreservesOrderedGenericDefaultMetadata() {
        Ast.Program parsed = Parser.parse("""
                fnc select<A, B=int, C=B, D=Array<C>, E=string>(A input): int {
                  return 7;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) parsed.modules().getFirst()
                .declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance).findFirst().orElseThrow();
        assertEquals(List.of("A", "B", "C", "D", "E"), fn.genericParameters());
        assertNull(fn.genericSpecs().getFirst().defaultType());
        assertEquals("int", fn.genericSpecs().get(1).defaultType().name());
        assertEquals("B", fn.genericSpecs().get(2).defaultType().name());
        assertEquals("Array", fn.genericSpecs().get(3).defaultType().name());
        assertEquals("string", fn.genericSpecs().get(4).defaultType().name());
    }

    @Test
    void omissionNamedOverridesAndDependentDefaultsTypecheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc select<A, B=int, C=B, D=Array<C>, E=string>(A input): int {
                    return 7;
                  }

                  fnc use(): void {
                    val int first = select<int>(7);
                    val int second = select<int, string>(7);
                    val int third = select<A=int, D=Array<string>>(7);
                    val int fourth = select<E=bool, A=int>(7);
                    val int fifth = select<>(7);
                    return;
                  }
                end
                """)));
    }

    @Test
    void inferenceBeatsDefaultTypeAndExplicitOverridesAreFixed() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc combine<A, B=string>(A input, B secondary): int {
                    return 7;
                  }
                  fnc use(): void {
                    val int inferred = combine<int>(3, true);
                    val int explicit = combine<A=int, B=bool>(3, true);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc combine<A, B=string>(A input, B secondary): int {
                            return 7;
                          }
                          fnc bad(): int { return combine<A=int, B=string>(7, true); }
                        end
                        """)));
    }

    @Test
    void bareInferenceHoleMustNotUseDefault() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc foo<A, B=int>(A input): int { return 7; }
                          fnc bad(): int { return foo<int, _>(7); }
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot infer generic parameter"), error.getMessage());
    }

    @Test
    void defaultCanDependOnlyOnEarlierGenericParameters() {
        for (String header : List.of(
                "fnc foo<A=B, B=int>() : void { return; }",
                "fnc foo<A, B=B>() : void { return; }",
                "fnc foo<A=Array<B>, B=int>() : void { return; }")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(header)));
            assertTrue(error.getMessage().contains("forward or self-referential"), error.getMessage());
        }
    }

    @Test
    void duplicateGenericDeclarationNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc foo<A, A=int>() : void { return; }"));
    }

    @Test
    void opaqueAndDynamicDefaultsStayGatedUntilTypeSemanticsAreSafe() {
        for (String defaultType : List.of("Unknown", "Any")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc foo<T=" + defaultType + ">() : void { return; }")));
            assertTrue(error.getMessage().contains("safe opaque/dynamic semantics"), error.getMessage());
        }
    }

    @Test
    void preexistingFunctionDeclConstructorProducesNoDefaults() {
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "foo", Ast.CallableKind.FNC, Ast.Visibility.PRIVATE,
                false, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of("T"), List.of(),
                Ast.TypeRef.simple("void"), List.of(), List.of());
        assertEquals("T", fn.genericSpecs().getFirst().name());
        assertNull(fn.genericSpecs().getFirst().defaultType());
    }
}
