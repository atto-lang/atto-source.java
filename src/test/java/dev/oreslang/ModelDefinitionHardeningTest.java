package dev.oreslang;

import dev.oreslang.compiler.ModelGraphCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ModelDefinitionHardeningTest {
    private static final String SCALAR_MODEL = """
            fnc Offset(float delta, float input): float {
              return input + delta;
            }
            define model Scalar(delta: float = 0.5) as
              Offset(delta)
            end
            """;

    @Test
    void rejectsWronglyTypedNullAndNonfiniteInstanceConfigurationAtBinding() {
        ModelGraphCompiler.CompiledModel model = compile(SCALAR_MODEL);
        reject("MODEL_CONFIG_TYPE_MISMATCH", () -> model.bindConfiguration(List.of("0.5")));
        reject("MODEL_INVALID_CONST", () -> model.bindConfiguration(List.of(new Object())));
        reject("MODEL_NONFINITE_CONST", () -> model.bindConfiguration(List.of(Double.NaN)));
        reject("MODEL_NONFINITE_CONST", () -> model.bindConfiguration(List.of(Double.POSITIVE_INFINITY)));
        List<Object> nullArgument = new ArrayList<>();
        nullArgument.add(null);
        reject("MODEL_INVALID_CONST", () -> model.bindConfiguration(nullArgument));
    }

    @Test
    void validatesUtf8AndKeepsConstructorConfigurationImmutable() {
        ModelGraphCompiler.CompiledModel model = compile("""
                fnc Pass(string prefix, string input): string { return input; }
                define model Label(prefix: string) as
                  Pass(prefix)
                end
                """);
        reject("MODEL_INVALID_CONST", () -> model.bindConfiguration(List.of("x".repeat(257))));
        reject("MODEL_INVALID_CONST", () -> model.bindConfiguration(List.of("a" + (char) 0 + "b")));
        reject("MODEL_INVALID_CONST", () ->
                model.bindConfiguration(List.of(String.valueOf((char) 0xD800))));
        Map<String, Object> config = model.bindConfiguration(List.of("short"));
        assertEquals("short", config.get("prefix"));
        assertThrows(UnsupportedOperationException.class, () -> config.put("prefix", "mutated"));
    }

    @Test
    void constructorValuesHaveDistinctConfiguredIdentities() {
        ModelGraphCompiler.CompiledModel model = compile(SCALAR_MODEL);
        Map<String, Object> first = model.bindConfiguration(List.of(0.5));
        Map<String, Object> second = model.bindConfiguration(List.of(0.25));
        assertEquals(model.configuredGraphDigest(first), model.configuredGraphDigest(first));
        assertNotEquals(model.configuredGraphDigest(first), model.configuredGraphDigest(second));
        assertEquals(64, model.graphDigest().length());
    }

    @Test
    void rejectsDeepPureOperationTreesBeforeDigestRecursion() {
        String expression = "input" + " + 1".repeat(150);
        reject("MODEL_EXPRESSION_BUDGET", () -> ModelGraphCompiler.compile(Parser.parse(
                "fnc Deep(int input): int { return " + expression + "; }"
                        + "define model D as Deep() end")));
    }

    @Test
    void rejectsBroadPureOperationTreesBeforeDigestRecursion() {
        String expression = "input";
        for (int i = 0; i < 11; i++) {
            expression = "(" + expression + " + " + expression + ")";
        }
        String source = "fnc Wide(int input): int { return " + expression + "; }"
                + "define model W as Wide() end";
        reject("MODEL_EXPRESSION_BUDGET", () -> ModelGraphCompiler.compile(Parser.parse(source)));
    }

    @Test
    void keepsSimpleWellTypedModelsExecutableInCompilation() {
        ModelGraphCompiler.CompiledModel model = compile(SCALAR_MODEL);
        assertEquals(1, model.nodes().size());
        assertEquals(1, model.parameters().size());
        assertFalse(model.noDynamicAllocation());
        assertFalse(model.freestandingEligible());
    }

    private static ModelGraphCompiler.CompiledModel compile(String source) {
        return ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(source))
                .models().getFirst();
    }

    private static void reject(String code, org.junit.jupiter.api.function.Executable operation) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, operation);
        assertTrue(error.getMessage().contains(code), error.getMessage());
    }

    @Test
    void parseTimeConfigurationBudgetRejectsBeforeModelGraphCompilation() {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            if (i != 0) params.append(", ");
            params.append("p").append(i).append(": int");
        }
        String source = "define model TooMany(" + params + ") as Pass() end";
        reject("MODEL_CONFIG_BUDGET", () -> Parser.parse(source));
    }

    @Test
    void parseTimeStaticArgumentBudgetRejectsBeforeModelGraphCompilation() {
        String source = "define model TooMany as Pass("
                + "1,".repeat(32) + "1) end";
        reject("MODEL_STATIC_ARG_BUDGET", () -> Parser.parse(source));
    }

    @Test
    void parseTimeNodeBudgetRejectsBeforeModelGraphCompilation() {
        String source = "define model TooMany as "
                + "Pass() ".repeat(4097) + "end";
        reject("MODEL_NODE_BUDGET", () -> Parser.parse(source));
    }
 
    @Test
    void rejectExcessiveModelDefaultNestingBeforeGeneralExpressionParsing() {
        String nested = "(".repeat(64) + "1" + ")".repeat(64);
        String source = "define model DeepDefault(config: int = " + nested
                + ") as Pass() end";
        reject("MODEL_EXPRESSION_BUDGET", () -> Parser.parse(source));
    }

    @Test
    void rejectExcessiveModelStaticArgumentTokensBeforeGeneralExpressionParsing() {
        StringBuilder expression = new StringBuilder("1");
        for (int i = 0; i < 300; i++) expression.append(" + 1");
        String source = "define model HugeExpression as Pass("
                + expression + ") end";
        reject("MODEL_EXPRESSION_BUDGET", () -> Parser.parse(source));
    }

    @Test
    void rejectsAlternatingDelimiterFamiliesBeforeGeneralExpressionParser() {
        // Each delimiter family alone remains within its individual 48-depth
        // limit; together they exceed the aggregate parser work budget.
        String nested = "(".repeat(24) + "[".repeat(24)
                + "{1}" + "]".repeat(24) + ")".repeat(24);
        String source = "define model Mixed as Pass(" + nested + ") end";
        reject("MODEL_EXPRESSION_BUDGET", () -> Parser.parse(source));
    }

    @Test
    void acceptOrdinaryParenthesizedModelStaticConstantsAtParserBoundary() {
        assertDoesNotThrow(() -> Parser.parse("""
                define model SmallDefault(offset: int = ((1))) as
                  Offset(((2)))
                end
                """));
    }
}
