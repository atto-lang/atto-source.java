package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class IncrementalMacroAbiTest {
    private static final String CONSUMER = """
            import * as producer from "./producer.ores";

            pub fnc consumer_value() -> int {
                return 7;
            }
            """;

    private static Map<String, String> sources(String producer) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        sources.put("producer.ores", producer);
        sources.put("consumer.ores", CONSUMER);
        return Map.copyOf(sources);
    }

    @Test
    void publicMacroApiChangeInvalidatesDependentCompilation() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String firstProducer = """
                define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult first =
                assertDoesNotThrow(() -> compiler.compile(sources(firstProducer)));
        assertTrue(first.rebuilt("producer.ores"));
        assertTrue(first.rebuilt("consumer.ores"));

        String renamedPublicMacro = """
                define pub macro passthrough(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult second =
                assertDoesNotThrow(() -> compiler.compile(sources(renamedPublicMacro)));

        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.rebuilt("consumer.ores"),
                "public compile-time macro API changes must invalidate import dependents");
        assertFalse(second.reused("consumer.ores"));
    }

    @Test
    void publicMacroBodyIdentityChangeInvalidatesDependentCompilation() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String firstProducer = """
                define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult first =
                assertDoesNotThrow(() -> compiler.compile(sources(firstProducer)));
        assertTrue(first.rebuilt("producer.ores"));
        assertTrue(first.rebuilt("consumer.ores"));

        String changedBodyIdentity = """
                define pub macro identity(Syntax<Expr> item) -> Syntax<Expr> {
                    return item;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult second =
                assertDoesNotThrow(() -> compiler.compile(sources(changedBodyIdentity)));

        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.rebuilt("consumer.ores"),
                "public macro implementation identity changes must invalidate import dependents");
        assertFalse(second.reused("consumer.ores"));
    }

    @Test
    void publicMacroFormattingOnlyChangeKeepsDependentAbiStable() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String firstProducer = """
                define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        assertDoesNotThrow(() -> compiler.compile(sources(firstProducer)));

        String formattingOnly = """
                define   pub   macro identity(
                    Syntax<Expr> value
                ) -> Syntax<Expr>
                {
                    return      value ;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult second =
                assertDoesNotThrow(() -> compiler.compile(sources(formattingOnly)));

        assertTrue(second.rebuilt("producer.ores"),
                "source edit still rebuilds its own unit");
        assertTrue(second.reused("consumer.ores"),
                "canonical public macro identity must ignore formatting-only source changes");
        assertFalse(second.rebuilt("consumer.ores"));
    }

    @Test
    void publicMacroSemanticIntrinsicChangeInvalidatesDependentAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String firstProducer = """
                define pub macro reference(Identifier value) -> Syntax<Expr> {
                    return hygienic(value);
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        assertDoesNotThrow(() -> compiler.compile(sources(firstProducer)));

        String changedProducer = """
                define pub macro reference(Identifier value) -> Syntax<Expr> {
                    return capture_fnc(value);
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult second =
                assertDoesNotThrow(() -> compiler.compile(sources(changedProducer)));

        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.rebuilt("consumer.ores"),
                "semantic compiler-intrinsic changes must invalidate import dependents");
        assertFalse(second.reused("consumer.ores"));
    }

    @Test
    void privateMacroChangeDoesNotInvalidateDependentAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String firstProducer = """
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        assertDoesNotThrow(() -> compiler.compile(sources(firstProducer)));

        String renamedPrivateMacro = """
                define macro passthrough(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }

                pub fnc api_value() -> int {
                    return 1;
                }
                """;
        IncrementalCompiler.BuildResult second =
                assertDoesNotThrow(() -> compiler.compile(sources(renamedPrivateMacro)));

        assertTrue(second.rebuilt("producer.ores"));
        assertTrue(second.reused("consumer.ores"),
                "private macro implementation/API must not perturb imported runtime ABI");
        assertFalse(second.rebuilt("consumer.ores"));
    }
}
