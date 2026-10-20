package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.ModelGraphCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FirstClassModelGraphTest {
    // Pinned to common-source contracts/v1/model-graph/executable-v1-conformance.json.
    // A common corpus revision bump requires an explicit backend review/update.
    private static final String SHARED_EXECUTABLE_CORPUS_REVISION =
            "model-core-i64-v1.2026-10-20.1";

    private static final String PRICE_MODEL = """
            fnc ValidatePrice(float input): float {
              return input;
            }

            fnc ApplyTax(float tax_rate, float input): float {
              return input + input * tax_rate;
            }

            fnc RoundCurrency(float input): float {
              return input;
            }

            pub define model PriceEstimate(
              tax_rate: float = 0.25
            ) as
              ValidatePrice()
              ApplyTax(tax_rate)
              RoundCurrency()
            end
            """;

    @Test
    void sharedExecutableCorpusRevisionIsExplicitlyPinned() {
        assertEquals("model-core-i64-v1.2026-10-20.1",
                SHARED_EXECUTABLE_CORPUS_REVISION);
    }

    @Test
    void parserKeepsModelContextualAndBuildsDedicatedAst() {
        Ast.Program program = Parser.parse(PRICE_MODEL + """
                fnc model(int value): int {
                  return value;
                }
                """);

        Ast.ModelDecl model = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ModelDecl.class::isInstance)
                .map(Ast.ModelDecl.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("PriceEstimate", model.name());
        assertEquals(Ast.Visibility.PUBLIC, model.visibility());
        assertEquals(1, model.parameters().size());
        assertEquals("tax_rate", model.parameters().getFirst().name());
        assertEquals(3, model.operations().size());
        assertEquals("ApplyTax", model.operations().get(1).name());

        assertTrue(program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .anyMatch(function -> function.name().equals("model")),
                "'model' must remain a contextual keyword rather than a globally reserved identifier");
    }

    @Test
    void canonicalGraphDigestIsDeterministicAndBindsOperationSemantics() {
        Ast.Program first = OresCompiler.parseAndTypeCheck(PRICE_MODEL);
        Ast.Program second = OresCompiler.parseAndTypeCheck(PRICE_MODEL);

        ModelGraphCompiler.CompiledModel a =
                ModelGraphCompiler.compile(first).find("PriceEstimate");
        ModelGraphCompiler.CompiledModel b =
                ModelGraphCompiler.compile(second).find("PriceEstimate");

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.graphDigest(), b.graphDigest());
        assertEquals(a.inputType(), b.inputType());
        assertEquals(a.outputType(), b.outputType());
        assertFalse(a.freestandingEligible(),
                "interpreter execution still allocates a ModelInstance; freestanding is an AOT gate");
        assertFalse(a.noDynamicAllocation(),
                "the current Java runtime must not claim an allocation-free execution path");
        assertTrue(a.deterministic());

        String changedSource = PRICE_MODEL.replace(
                "return input + input * tax_rate;",
                "return input + input * tax_rate + 1.0;");
        ModelGraphCompiler.CompiledModel changed =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(changedSource))
                        .find("PriceEstimate");

        assertNotEquals(a.graphDigest(), changed.graphDigest(),
                "verified operation semantics must participate in graph identity even when the function ABI is unchanged");
        assertNotEquals(a.nodes().get(1).implementationDigest(),
                changed.nodes().get(1).implementationDigest());

        Map<String, Object> defaults = a.bindConfiguration(java.util.List.of());
        Map<String, Object> explicit = a.bindConfiguration(java.util.List.of(0.5d));
        assertNotEquals(
                a.configuredGraphDigest(defaults),
                a.configuredGraphDigest(explicit),
                "static construction values that change computation must change configured graph identity");
        assertEquals(
                a.configuredGraphDigest(defaults),
                b.configuredGraphDigest(b.bindConfiguration(java.util.List.of())),
                "configured graph identity must be deterministic across equivalent compiler inputs");
    }

    @Test
    void negativeI64ConfigurationMatchesNativeCanonicalIdentity() throws Exception {
        String source = """
                fnc Identity(int input): int {
                  return input;
                }

                fnc AddDelta(int delta, int input): int {
                  return input + delta;
                }

                pub define model PriceAdjust(delta: int = -2) as
                  Identity()
                  AddDelta(delta)
                end
                """;

        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(source))
                        .find("PriceAdjust");
        assertNotNull(model);
        assertEquals(
                "dec8b5f587a47648fa8973f5901e9c25e049b08179a44dc682439ee0dab8f53e",
                model.graphDigest(),
                "negative i64 defaults must share the native canonical graph formula");
        assertEquals(
                "e52dd64a55cdf48bbe33c2d50a7c3beb9685c6ad0aeb030a44dbac4796bb7f56",
                model.configuredGraphDigest(model.bindConfiguration(java.util.List.of())),
                "negative default configuration must share native configured identity");

        String output = run(source + """
                pub routine main(): void {
                  stdio.stdout.write(PriceAdjust().run(40));
                  stdio.stdout.write("|");
                  stdio.stdout.write(PriceAdjust(-3).run(40));
                  return;
                }
                """);
        assertEquals("38|37", output);
    }

    @Test
    void signedI64MinimumHasCanonicalCrossBackendIdentity() throws Exception {
        String source = """
                fnc Identity(int input): int {
                  return input;
                }

                fnc AddDelta(int delta, int input): int {
                  return input + delta;
                }

                pub define model PriceAdjust(delta: int = -9223372036854775808) as
                  Identity()
                  AddDelta(delta)
                end
                """;

        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(source))
                        .find("PriceAdjust");
        assertNotNull(model);
        // This model name intentionally differs from the PriceAdjust shared
        // golden, so verify endpoint acceptance/execution here; the canonical
        // endpoint bytes are also covered by configured digest determinism.
        Map<String, Object> configuration = model.bindConfiguration(java.util.List.of());
        assertEquals(Long.MIN_VALUE, configuration.get("delta"));
        assertEquals(
                "ff37639d560a799bc3a77d1b05789855a991b8d7e1fb6cac3e15d6d0331e707a",
                model.graphDigest());
        assertEquals(
                "c71df957be7cee30a59acf586c719d36279a6ed027072cabcb58fa89527e7cf4",
                model.configuredGraphDigest(configuration));

        String output = run(source + """
                pub routine main(): void {
                  stdio.stdout.write(PriceAdjust().run(0));
                  return;
                }
                """);
        assertEquals("-9223372036854775808", output);
    }

    @Test
    void negativeLiteralStaticArgumentsAreAdmittedButExpressionsRemainClosed() throws Exception {
        String output = run("""
                fnc AddDelta(int delta, int input): int {
                  return input + delta;
                }

                define model Fixed as
                  AddDelta(-2)
                end

                pub routine main(): void {
                  stdio.stdout.write(Fixed().run(5));
                  return;
                }
                """);
        assertEquals("3", output);

        assertThrowsMessage("MODEL_NONCONSTANT_CONFIG", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int {
                          return input + delta;
                        }
                        define model Bad as
                          AddDelta(-(1 + 1))
                        end
                        """));
    }

    @Test
    void commonI64SubsetHasPinnedJavaLlvmGraphIdentity() {
        String source = """
                fnc Identity(int input): int {
                  return input;
                }

                fnc AddDelta(int delta, int input): int {
                  return input + delta;
                }

                pub define model PriceAdjust(delta: int = 2) as
                  Identity()
                  AddDelta(delta)
                end
                """;

        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(source))
                        .find("PriceAdjust");
        assertNotNull(model);
        assertEquals(
                "a65fbdcab7c63eccdb5ec19409decb2da18ca9598d4c36a773f92e8b6521531d",
                model.graphDigest(),
                "portable model graph identity must match the LLVM native golden");
        assertEquals(
                "4531d20945630eed7bb451d63ae73ca5637559c438f6e89be18bbcd96abe9473",
                model.configuredGraphDigest(model.bindConfiguration(java.util.List.of())),
                "configured model identity must match the LLVM native golden");
        assertEquals(
                "d1a687b328765fa99037eebc8cc08a3245b5b3e903f74535ffa0cad7da30cc32",
                model.nodes().get(0).implementationDigest());
        assertEquals(
                "61ca0d20c6c6a1a6bc88efb761fd767510ac676fcaa59f0cea2617a1af17fb63",
                model.nodes().get(1).implementationDigest());
    }

    @Test
    void modelStringIdentityUsesValidatedUtf8Bytes() {
        String source = """
                fnc Append(string suffix, string input): string {
                  return input + suffix;
                }

                define model Label(string suffix = "ok") as
                  Append(suffix)
                end
                """;
        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(source)).find("Label");
        assertNotNull(model);

        String emoji = "\uD83D\uDE80";
        Map<String, Object> configured = model.bindConfiguration(java.util.List.of(emoji));
        assertDoesNotThrow(() -> model.configuredGraphDigest(configured),
                "valid Unicode scalar values must canonicalize as UTF-8");

        IllegalArgumentException nul = assertThrows(
                IllegalArgumentException.class,
                () -> model.configuredGraphDigest(
                        model.bindConfiguration(java.util.List.of("a\0b"))));
        assertTrue(nul.getMessage().contains("MODEL_INVALID_CONST"));

        String unpaired = String.valueOf('\uD800');
        IllegalArgumentException surrogate = assertThrows(
                IllegalArgumentException.class,
                () -> model.configuredGraphDigest(
                        model.bindConfiguration(java.util.List.of(unpaired))));
        assertTrue(surrogate.getMessage().contains("MODEL_INVALID_CONST"));
    }

    @Test
    void realGuestExecutesNonMlModelWithDefaultAndExplicitStaticConfiguration() throws Exception {
        String output = run(PRICE_MODEL + """
                pub routine main(): void {
                  val PriceEstimate default_price = PriceEstimate();
                  val PriceEstimate half_tax = PriceEstimate(0.5);
                  stdio.stdout.write(default_price.run(80.0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(half_tax.run(80.0));
                  return;
                }
                """);

        assertEquals("100.0|120.0", output);
    }

    @Test
    void moduleQualifiedModelConstructionUsesTheSameNominalRuntimePath() throws Exception {
        String output = run("""
                define module pricing
                  fnc Validate(float input): float { return input; }
                  fnc Scale(float factor, float input): float {
                    return input * factor;
                  }
                  pub define model Scaler(factor: float = 2.0) as
                    Validate()
                    Scale(factor)
                  end
                end

                pub routine main(): void {
                  val scaler = pricing.Scaler(3.0);
                  stdio.stdout.write(scaler.run(7.0));
                  return;
                }
                """);

        assertEquals("21.0", output);
    }

    @Test
    void unqualifiedConstructionInsideModuleUsesLexicalModelEvenWhenNamesAreAmbiguous() throws Exception {
        String output = run("""
                define module left as
                  fnc Identity(float input): float { return input; }
                  fnc Scale(float factor, float input): float { return input * factor; }
                  pub define model Scaler(factor: float = 2.0) as
                    Identity()
                    Scale(factor)
                  end
                  pub fnc evaluate(float input): float {
                    val Scaler local = Scaler(3.0);
                    return local.run(input);
                  }
                end

                define module right as
                  fnc Identity(float input): float { return input; }
                  fnc Scale(float factor, float input): float { return input * factor; }
                  pub define model Scaler(factor: float = 10.0) as
                    Identity()
                    Scale(factor)
                  end
                end

                pub routine main(): void {
                  stdio.stdout.write(left.evaluate(7.0));
                  return;
                }
                """);

        assertEquals("21.0", output);
    }

    @Test
    void modelConstructionAndRunAreStrictlyTyped() {
        assertThrowsMessage("MODEL_NONCONSTANT_CONFIG", () ->
                OresCompiler.parseAndTypeCheck(PRICE_MODEL + """
                        fnc choose_rate(): float { return 0.25; }
                        pub routine main(): void {
                          val PriceEstimate p = PriceEstimate(choose_rate());
                          return;
                        }
                        """));

        assertThrowsMessage("MODEL_RUN_ARITY", () ->
                OresCompiler.parseAndTypeCheck(PRICE_MODEL + """
                        pub routine main(): void {
                          val PriceEstimate p = PriceEstimate();
                          p.run();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(PRICE_MODEL + """
                        pub routine main(): void {
                          val PriceEstimate p = PriceEstimate();
                          val f = p.run;
                          return;
                        }
                        """));
    }

    @Test
    void graphAdmissionRejectsEffectsNonconstantStaticArgsPortMismatchAndRecursion() {
        assertThrowsMessage("MODEL_EFFECTFUL_BODY", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc Impure(float input): float {
                          stdio.stdout.write(input);
                          return input;
                        }
                        define model Bad as
                          Impure()
                        end
                        """));

        assertThrowsMessage("MODEL_NONCONSTANT_CONFIG", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc rate(): float { return 0.25; }
                        fnc Scale(float factor, float input): float {
                          return input * factor;
                        }
                        define model Bad as
                          Scale(rate())
                        end
                        """));

        assertThrowsMessage("MODEL_PORT_TYPE_MISMATCH", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc MakeInt(float input): int { return 1; }
                        fnc NeedFloat(float input): float { return input; }
                        define model Bad as
                          MakeInt()
                          NeedFloat()
                        end
                        """));

        assertThrowsMessage("MODEL_RECURSIVE_COMPOSITION", () ->
                OresCompiler.parseAndTypeCheck("""
                        define model RecursiveA as
                          RecursiveB()
                        end
                        define model RecursiveB as
                          RecursiveA()
                        end
                        """));
    }

    @Test
    void modelInstancesAreNotImplicitActorTransportValues() {
        assertThrowsMessage("MODEL_ACTOR_TRANSFER_UNSUPPORTED", () ->
                OresCompiler.parseAndTypeCheck(PRICE_MODEL + """
                        actor fnc consume(PriceEstimate model): void {
                          return;
                        }
                        """));
    }

    @Test
    void modelOwnsBothValueAndNominalTypeNames() {
        assertThrowsMessage("MODEL_TYPE_NAME_COLLISION", () ->
                OresCompiler.parseAndTypeCheck("""
                        type PriceEstimate = float;
                        fnc Identity(float input): float { return input; }
                        define model PriceEstimate as
                          Identity()
                        end
                        """));

        assertThrowsMessage("MODEL_TYPE_NAME_COLLISION", () ->
                OresCompiler.parseAndTypeCheck("""
                        define interface PriceEstimate
                          fnc read() => float;
                        end
                        fnc Identity(float input): float { return input; }
                        define model PriceEstimate as
                          Identity()
                        end
                        """));
    }

    @Test
    void modelOperationsDoNotAcquireAmbientFunctionsFromOtherModules() {
        assertThrowsMessage("MODEL_UNKNOWN_OP", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc RootOnly(float input): float { return input; }

                        define module local as
                          define model Bad as
                            RootOnly()
                          end
                        end
                        """));
    }

    @Test
    void explicitWidthNumericTypesDoNotCollapseIntoGenericModelScalars() {
        assertThrowsMessage("MODEL_INVALID_OP_SIGNATURE", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc Narrow(i32 input): i32 { return input; }
                        define model NarrowModel as
                          Narrow()
                        end
                        """));
    }

    @Test
    void graphAdmissionBudgetsConfigurationAndStaticArguments() {
        StringBuilder parameters = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            if (i != 0) parameters.append(", ");
            parameters.append("p").append(i).append(": int = ").append(i);
        }
        assertThrowsMessage("MODEL_CONFIG_BUDGET", () ->
                OresCompiler.parseAndTypeCheck(
                        "fnc Identity(int input): int { return input; }\n"
                                + "define model TooWide(" + parameters + ") as\n"
                                + "  Identity()\n"
                                + "end\n"));

        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i != 0) arguments.append(", ");
            arguments.append(i);
        }
        assertThrowsMessage("MODEL_STATIC_ARG_BUDGET", () ->
                OresCompiler.parseAndTypeCheck(
                        "define model TooManyArgs as\n"
                                + "  Wide(" + arguments + ")\n"
                                + "end\n"));
    }

    @Test
    void treeShakerRetainsModelAndItsVerifiedOperations() {
        TreeShaker.Result result = OresCompiler.compileForBuild(PRICE_MODEL + """
                fnc dead(float input): float { return input + 99.0; }

                pub routine main(): void {
                  val PriceEstimate p = PriceEstimate();
                  stdio.stdout.write(p.run(80.0));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(result.retained(Parser.ROOT_MODULE + ".PriceEstimate"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".ValidatePrice"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".ApplyTax"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".RoundCurrency"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".dead"));

        assertTrue(result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .anyMatch(Ast.ModelDecl.class::isInstance));
    }

    @Test
    void publicModelGraphDigestInvalidatesIncrementalDependentsOnSemanticChange() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = new LinkedHashMap<>();
        first.put("pricing.ores", PRICE_MODEL);
        first.put("consumer.ores", """
                import * as pricing from "./pricing.ores";
                pub routine main(): void { return; }
                """);

        var initial = compiler.compile(first);
        assertTrue(initial.rebuilt("pricing.ores"));
        assertTrue(initial.rebuilt("consumer.ores"));

        Map<String, String> changed = new LinkedHashMap<>(first);
        changed.put("pricing.ores", PRICE_MODEL.replace(
                "return input + input * tax_rate;",
                "return input + input * tax_rate + 1.0;"));

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("pricing.ores"));
        assertTrue(result.rebuilt("consumer.ores"),
                "a public model graph semantic change must invalidate importers even when operation signatures are unchanged");
    }

    private static void assertThrowsMessage(String expected, org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, executable);
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains(expected),
                () -> "expected diagnostic " + expected + " but got: " + failure.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "first-class-model.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
