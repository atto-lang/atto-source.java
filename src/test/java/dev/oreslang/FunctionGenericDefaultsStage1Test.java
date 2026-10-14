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
                fnc choose<A, B=int, C=B, D=Array<C>, E=string>(A input): int {
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
                  fnc choose<A, B=int, C=B, D=Array<C>, E=string>(A input): int {
                    return 7;
                  }

                  fnc use(): void {
                    val int first = choose<int>(7);
                    val int second = choose<int, string>(7);
                    val int third = choose<A=int, D=Array<string>>(7);
                    val int fourth = choose<E=bool, A=int>(7);
                    val int fifth = choose<>(7);
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

    @Test
    void exportedGenericDefaultsArePartOfIncrementalAbi() {
        dev.oreslang.compiler.IncrementalCompiler compiler =
                new dev.oreslang.compiler.IncrementalCompiler();
        var first = compiler.compile(java.util.Map.of(
                "api.ores", "pub fnc api<T=int>(): int { return 7; }"));
        var second = compiler.compile(java.util.Map.of(
                "api.ores", "pub fnc api<T=string>(): int { return 7; }"));
        assertNotEquals(first.units().get("api.ores").abiDigest(),
                second.units().get("api.ores").abiDigest(),
                "changing a public generic default must change the exported ABI");

        var privateCompiler = new dev.oreslang.compiler.IncrementalCompiler();
        var privateFirst = privateCompiler.compile(java.util.Map.of(
                "hidden.ores", "fnc helper<T=int>(): int { return 7; }"));
        var privateSecond = privateCompiler.compile(java.util.Map.of(
                "hidden.ores", "fnc helper<T=string>(): int { return 7; }"));
        assertEquals(privateFirst.units().get("hidden.ores").abiDigest(),
                privateSecond.units().get("hidden.ores").abiDigest(),
                "a private generic default must not change the exported ABI");
    }

    @Test
    void unknownDefaultTypesFailEvenWhenCallOverridesThem() {
        for (String declaration : List.of(
                "fnc choose<T=TypoType>(): int { return 7; }",
                "fnc choose<T=Array<MissingElement>>(): int { return 7; }",
                "fnc choose<T=Option<MissingOption>>(): int { return 7; }")) {
            String source = """
                    define module app
                      %s
                      fnc use(): int { return choose<int>(); }
                    end
                    """.formatted(declaration);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(error.getMessage().contains("unresolved default type"),
                    error.getMessage());
        }
    }

    @Test
    void opaqueOrDynamicNestedDefaultCannotEscapeGates() {
        for (String declaration : List.of(
                "fnc choose<T=Array<Unknown>>(): int { return 7; }",
                "fnc choose<T=Option<Any>>(): int { return 7; }")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(declaration)));
            assertTrue(error.getMessage().contains("safe opaque/dynamic semantics"),
                    error.getMessage());
        }
    }

    @Test
    void defaultOrderPreservesExplicitAndInferredResultsAcrossCalls() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity<T=int>(T value): T { return value; }
                  fnc none<T=int>(): int { return 7; }
                  fnc check(): void {
                    val int i = identity(7);
                    val bool b = identity(true);
                    val int explicit = identity<int>(7);
                    val int fallback = none();
                    return;
                  }
                end
                """)));
    }

    @Test
    void compilerGeneratedGenericDefaultsCannotSmuggleSyntheticTypes() {
        Ast.FunctionDecl generated = new Ast.FunctionDecl(
                "generated", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of("T"), List.of(),
                Ast.TypeRef.simple("void"), List.of(), List.of(),
                List.of(new Ast.GenericParameter(
                        "T", Ast.TypeRef.simple("$invented$unsafe"))));
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("app", List.of(generated))));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertTrue(invalid.getMessage().contains("invalid synthetic type"),
                invalid.getMessage());
    }
}
