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
            "model-core-i64-v1.2026-10-20.10";

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
        assertEquals("model-core-i64-v1.2026-10-20.10",
                SHARED_EXECUTABLE_CORPUS_REVISION);
    }

    @Test
    void vendoredSharedCorpusDrivesJavaAdmissionAndDiagnostics() throws Exception {
        SharedCorpus corpus = sharedCorpus();
        assertEquals(SHARED_EXECUTABLE_CORPUS_REVISION, corpus.revision());
        assertEquals(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1, corpus.profile());
        assertTrue(corpus.cases().size() >= 15);

        for (SharedCase item : corpus.cases()) {
            if (item.javaMode().equals("reject")) {
                assertNotNull(item.diagnostic(), item.id());
                if (item.id().equals("duplicate-operation-parameter")) {
                    // Main's parser rejects duplicate parameter names before
                    // model admission runs; either fail-closed diagnostic is valid.
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                            () -> ModelGraphCompiler.compile(
                                    OresCompiler.parseAndTypeCheck(item.source())));
                    assertTrue(String.valueOf(failure.getMessage()).contains(item.diagnostic())
                                    || String.valueOf(failure.getMessage()).contains("duplicate parameter name"),
                            failure::toString);
                    continue;
                }
                assertThrowsMessage(item.diagnostic(), () ->
                        ModelGraphCompiler.compile(
                                OresCompiler.parseAndTypeCheck(item.source())));
                continue;
            }

            ModelGraphCompiler.Catalog catalog =
                    ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(item.source()));
            assertFalse(catalog.isEmpty(), item.id());
            ModelGraphCompiler.CompiledModel model = catalog.models().getFirst();
            assertTrue(model.supportsPortableProfile(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1),
                    item.id());
            if (item.graphDigest() != null) {
                assertEquals(item.graphDigest(), model.graphDigest(), item.id());
            }
            if (item.configuredDigest() != null) {
                assertNotNull(item.configurationI64(),
                        "configured digest requires an explicit corpus configuration: " + item.id());
                long configured = Long.parseLong(item.configurationI64());
                assertEquals(item.configuredDigest(),
                        model.configuredGraphDigest(
                                model.bindConfiguration(java.util.List.of(configured))),
                        item.id());
            }
            if (item.javaMode().equals("execute") && item.semanticResult() != null) {
                assertEquals(Long.parseLong(item.semanticResult()),
                        evalLong(item.source()), item.id());
            }
        }
    }

    @Test
    void vendoredSharedIdentityCasesDriveJavaGraphIdentity() throws Exception {
        SharedCorpus corpus = sharedCorpus();
        for (SharedIdentity item : corpus.identities()) {
            ModelGraphCompiler.CompiledModel baseline =
                    ModelGraphCompiler.compile(
                            OresCompiler.parseAndTypeCheck(item.baselineSource()))
                            .models().getFirst();
            ModelGraphCompiler.CompiledModel variant =
                    ModelGraphCompiler.compile(
                            OresCompiler.parseAndTypeCheck(item.variantSource()))
                            .models().getFirst();
            if (item.expectSame()) {
                assertEquals(baseline.graphDigest(), variant.graphDigest(), item.id());
                if (item.expectedGraphDigest() != null) {
                    assertEquals(item.expectedGraphDigest(), baseline.graphDigest(), item.id());
                }
            } else {
                assertNotEquals(baseline.graphDigest(), variant.graphDigest(), item.id());
                if (item.variantGraphDigest() != null) {
                    assertEquals(item.variantGraphDigest(), variant.graphDigest(), item.id());
                }
                if (item.variantOperationDigest() != null) {
                    assertFalse(variant.nodes().isEmpty(), item.id());
                    assertEquals(item.variantOperationDigest(),
                            variant.nodes().getLast().implementationDigest(), item.id());
                }
            }
        }
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
    void publicModelVisibilitySpellingsPreserveGraphIdentity() {
        String canonical = PRICE_MODEL.replace(
                "pub define model PriceEstimate(",
                "define pub model PriceEstimate(");
        ModelGraphCompiler.CompiledModel legacyModel =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(PRICE_MODEL))
                        .find("PriceEstimate");
        ModelGraphCompiler.CompiledModel canonicalModel =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(canonical))
                        .find("PriceEstimate");
        assertNotNull(canonicalModel);
        assertEquals(Ast.Visibility.PUBLIC, canonicalModel.declaration().visibility());
        assertEquals(legacyModel.graphDigest(), canonicalModel.graphDigest());
        assertEquals(legacyModel.configuredGraphDigest(
                        legacyModel.bindConfiguration(java.util.List.of())),
                canonicalModel.configuredGraphDigest(
                        canonicalModel.bindConfiguration(java.util.List.of())));
    }

    @Test
    void parserRejectsOversizedModelDeclarationsBeforeCompilerAdmission() {
        // Only parse here: unknown operation names are permitted in the AST
        // and resolved later. These are strict parser allocation boundaries.
        String sixtyFourParameters = String.join(", ",
                java.util.stream.IntStream.range(0, 64)
                        .mapToObj(i -> "p" + i + ": int")
                        .toList());
        String sixtyFiveParameters = sixtyFourParameters + ", extra: int";
        assertDoesNotThrow(() -> Parser.parse(
                "define model Bounded(" + sixtyFourParameters + ") as\nOp()\nend"));
        assertThrowsMessage("MODEL_CONFIG_BUDGET", () -> Parser.parse(
                "define model Bounded(" + sixtyFiveParameters + ") as\nOp()\nend"));

        String thirtyTwoArguments = "0,".repeat(31) + "0";
        String thirtyThreeArguments = thirtyTwoArguments + ",0";
        String fullOperation = "Op(" + thirtyTwoArguments + ")\n";
        assertDoesNotThrow(() -> Parser.parse(
                "define model Bounded as\nOp(" + thirtyTwoArguments + ")\nend"));
        assertThrowsMessage("MODEL_STATIC_ARG_BUDGET", () -> Parser.parse(
                "define model Bounded as\nOp(" + thirtyThreeArguments + ")\nend"));

        assertDoesNotThrow(() -> Parser.parse(
                "define model Bounded as\n" + "Op()\n".repeat(4096) + "end"));
        assertThrowsMessage("MODEL_NODE_BUDGET", () -> Parser.parse(
                "define model Bounded as\n" + "Op()\n".repeat(4097) + "end"));

        assertDoesNotThrow(() -> Parser.parse(
                "define model Bounded as\n" + fullOperation.repeat(512) + "end"));
        assertThrowsMessage("MODEL_STATIC_ARG_BUDGET", () -> Parser.parse(
                "define model Bounded as\n" + fullOperation.repeat(512)
                        + "Op(0)\nend"));
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
                "f5b7956076ea5bc9696f4cd23d68c3e4c67d7ec90023a64c834d14d304249c1d",
                model.graphDigest(),
                "negative i64 defaults must share the native canonical graph formula");
        assertEquals(
                "15ca34acbdc5478bc2971ae7895d708aca2d324be418819a2e60b3c04bc20a5c",
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
                "cb008cb043de50f1663ba8faa77de94c50d92514fa2b95cbd72de5225cf5cb80",
                model.graphDigest());
        assertEquals(
                "27ed4d26451fa960a8271495ee8ac96bbb67bc3369ca45cf2205222a962de5a3",
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
    void duplicateModelParametersUseSharedDiagnostic() {
        assertThrowsMessage("MODEL_DUPLICATE_PARAMETER", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc Identity(int input): int { return input; }
                        define model Bad(delta: int, delta: int) as
                          Identity()
                        end
                        """));
    }

    @Test
    void unknownStaticFormalUsesSharedDiagnostic() {
        assertThrowsMessage("MODEL_UNKNOWN_FORMAL", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int { return input + delta; }
                        define model Bad as
                          AddDelta(missing)
                        end
                        """));
    }

    @Test
    void signedI64OverflowUsesStableModelDiagnostic() {
        assertThrowsMessage("MODEL_CONST_OVERFLOW", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int { return input + delta; }
                        define model Bad(delta: int = 9223372036854775808) as
                          AddDelta(delta)
                        end
                        """));
        assertThrowsMessage("MODEL_CONST_OVERFLOW", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int { return input + delta; }
                        define model Bad(delta: int = -9223372036854775809) as
                          AddDelta(delta)
                        end
                        """));
        assertThrowsMessage("MODEL_CONST_OVERFLOW", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int { return input + delta; }
                        define model Bad as
                          AddDelta(9223372036854775808)
                        end
                        """));
        assertThrowsMessage("MODEL_CONST_OVERFLOW", () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc AddDelta(int delta, int input): int { return input + delta; }
                        define model Bad as
                          AddDelta(-9223372036854775809)
                        end
                        """));
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
                "6c5e66eb67149e59c7d4d2d962b5671850ee3298f729214127d1452cb8d5ca21",
                model.graphDigest(),
                "portable model graph identity must match the LLVM native golden");
        assertEquals(
                "3975a5571cb2fddcc6206ee0b3f0f893dcb3fa9bfbe591b8c5b9f5ab8ff539e1",
                model.configuredGraphDigest(model.bindConfiguration(java.util.List.of())),
                "configured model identity must match the LLVM native golden");
        assertEquals(
                "962f6b4865f7d64c5dfea914b09abdac61af4f1a41679e8f6b3adaa2bfabc9fe",
                model.nodes().get(0).implementationDigest());
        assertEquals(
                "75370f10ccb547c3a559258c0ced1fa07cdf603b4e612702b8204250d3c4f71a",
                model.nodes().get(1).implementationDigest());
    }

    @Test
    void portableI64ProfileIsDerivedAndDoesNotFollowJavaCapabilityWidth() {
        String portable = """
                fnc Identity(int input): int { return input; }
                fnc AddDelta(int delta, int input): int { return input + delta; }
                define model PriceAdjust(delta: int = 2) as
                  Identity()
                  AddDelta(delta)
                end
                """;
        ModelGraphCompiler.CompiledModel i64 =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(portable))
                        .find("PriceAdjust");
        assertTrue(i64.supportsPortableProfile(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1));
        assertEquals(java.util.Set.of(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1),
                i64.portableProfiles());

        ModelGraphCompiler.CompiledModel wide =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(PRICE_MODEL))
                        .find("PriceEstimate");
        assertFalse(wide.supportsPortableProfile(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1),
                "Java float execution must not imply portable i64 conformance");

        String division = """
                fnc Half(int input): int { return input > 0 ? input : 0; }
                define model HalfModel as
                  Half()
                end
                """;
        ModelGraphCompiler.CompiledModel javaOnly =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(division))
                        .find("HalfModel");
        assertFalse(javaOnly.supportsPortableProfile(ModelGraphCompiler.PORTABLE_PROFILE_I64_V1),
                "operators outside the shared expression grammar stay Java-only");
    }

    @Test
    void operationIdentityIgnoresParameterSpellingButBindsOperandOrder() {
        String baseline = """
                fnc Identity(int input): int { return input; }
                fnc AddDelta(int delta, int input): int { return input + delta; }
                pub define model PriceAdjust(delta: int = 2) as
                  Identity()
                  AddDelta(delta)
                end
                """;
        String renamed = """
                // Parameter spelling and source formatting are not semantic identity.
                fnc Identity(int x): int {
                  return x;
                }
                fnc AddDelta(int amount, int value): int {
                  return value + amount;
                }
                pub define model PriceAdjust(delta: int = 2) as
                  Identity()
                  AddDelta(delta)
                end
                """;
        String reordered = """
                fnc Identity(int input): int { return input; }
                fnc AddDelta(int delta, int input): int { return delta + input; }
                pub define model PriceAdjust(delta: int = 2) as
                  Identity()
                  AddDelta(delta)
                end
                """;

        ModelGraphCompiler.CompiledModel a =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(baseline))
                        .find("PriceAdjust");
        ModelGraphCompiler.CompiledModel b =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(renamed))
                        .find("PriceAdjust");
        ModelGraphCompiler.CompiledModel c =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(reordered))
                        .find("PriceAdjust");

        assertEquals(a.nodes().get(0).implementationDigest(),
                b.nodes().get(0).implementationDigest());
        assertEquals(a.nodes().get(1).implementationDigest(),
                b.nodes().get(1).implementationDigest());
        assertEquals(a.graphDigest(), b.graphDigest(),
                "alpha-renaming/formatting must not invalidate compiled model identity");

        assertNotEquals(a.nodes().get(1).implementationDigest(),
                c.nodes().get(1).implementationDigest());
        assertNotEquals(a.graphDigest(), c.graphDigest());
        assertEquals("739dfcf2d1f6ce9eed795e879f7ec72e70defb2efc4520ad056681042bd0c926",
                c.nodes().get(1).implementationDigest());
        assertEquals("f87d8d1742d0915134a88e54bb8fb66e6cd65f156a753c1c1f3570614ec0a6ba",
                c.graphDigest());
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

    private record SharedCase(
            String id,
            String expect,
            String javaMode,
            String llvmMode,
            String diagnostic,
            String llvmDiagnostic,
            String graphDigest,
            String configuredDigest,
            String semanticResult,
            String configurationI64,
            String source) { }

    private record SharedIdentity(
            String id,
            boolean expectSame,
            String expectedGraphDigest,
            String variantOperationDigest,
            String variantGraphDigest,
            String baselineSource,
            String variantSource) { }

    private record SharedCorpus(
            String revision,
            String profile,
            java.util.List<SharedCase> cases,
            java.util.List<SharedIdentity> identities) { }

    private static SharedCorpus sharedCorpus() throws Exception {
        try (var stream = FirstClassModelGraphTest.class.getResourceAsStream(
                "/model-executable-v1-conformance.cases")) {
            assertNotNull(stream, "vendored shared executable corpus");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String revision = null;
            String profile = null;
            java.util.ArrayList<SharedCase> cases = new java.util.ArrayList<>();
            java.util.ArrayList<SharedIdentity> identities = new java.util.ArrayList<>();
            String[] lines = text.split("\\R");
            assertEquals("ORESLANG_MODEL_EXECUTABLE_CASES_V1", lines[0]);
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                String[] parts = lines[i].split("\\|", -1);
                switch (parts[0]) {
                    case "revision" -> revision = parts[1];
                    case "profile" -> profile = parts[1];
                    case "case" -> {
                        assertEquals(12, parts.length, lines[i]);
                        cases.add(new SharedCase(
                                parts[1], parts[2], parts[3], parts[4],
                                dashNull(parts[5]), dashNull(parts[6]),
                                dashNull(parts[7]), dashNull(parts[8]),
                                dashNull(parts[9]), dashNull(parts[10]),
                                decodeHexAscii(parts[11])));
                    }
                    case "identity" -> {
                        assertEquals(8, parts.length, lines[i]);
                        identities.add(new SharedIdentity(
                                parts[1], Boolean.parseBoolean(parts[2]),
                                dashNull(parts[3]), dashNull(parts[4]),
                                dashNull(parts[5]), decodeHexAscii(parts[6]),
                                decodeHexAscii(parts[7])));
                    }
                    default -> fail("unknown shared corpus record: " + parts[0]);
                }
            }
            assertNotNull(revision);
            assertNotNull(profile);
            return new SharedCorpus(
                    revision, profile, java.util.List.copyOf(cases),
                    java.util.List.copyOf(identities));
        }
    }

    private static String dashNull(String value) {
        return value.equals("-") ? null : value;
    }

    private static String decodeHexAscii(String hex) {
        if ((hex.length() & 1) != 0) fail("odd shared corpus hex length");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void assertThrowsMessage(String expected, org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, executable);
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains(expected),
                () -> "expected diagnostic " + expected + " but got: " + failure.getMessage());
    }

    private static long evalLong(String program) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, program, "shared-model-corpus.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            var value = context.eval(source);
            assertTrue(value.fitsInLong(), "shared corpus main result must be i64");
            return value.asLong();
        }
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
