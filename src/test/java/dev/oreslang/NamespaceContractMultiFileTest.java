package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The complete explicit compile(Map<unitId, source>) set, not a directory
 * glob or one source file, is the namespace conformance proof boundary.
 */
final class NamespaceContractMultiFileTest {
    private static final String CONTRACT = """
            namespace shop conforms ShopApi;
            define contract ShopApi for namespace as
              actor Cart;
              fnc tick(int amount): int;
            end
            """;
    private static final String ACTOR = """
            namespace shop;
            pub define actor Cart;
            """;
    private static final String FUNCTION = """
            namespace shop;
            pub fnc tick(int amount): int { return amount; }
            pub fnc main(): int { return 42; }
            """;

    private static Map<String, String> complete() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop/contracts.atto", CONTRACT);
        sources.put("shop/cart.atto", ACTOR);
        sources.put("shop/tick.atto", FUNCTION);
        return sources;
    }

    @Test
    void parserRetainsExplicitNamespaceContractObligation() {
        Ast.Program parsed = Parser.parse(CONTRACT);
        assertEquals("shop", parsed.namespace());
        assertEquals(1, parsed.namespaceContracts().size());
        assertEquals("ShopApi", parsed.namespaceContracts().getFirst().name());
        Ast.InterfaceDecl iface = (Ast.InterfaceDecl) parsed.modules().getFirst().declarations().getFirst();
        assertTrue(iface.namespaceContract());
        assertTrue(iface.moduleContract());
    }

    @Test
    void threeFilesTogetherSatisfyNamespaceContract() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(complete()));
    }

    @Test
    void directSingleFileCheckerCannotCertifyConformance() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(CONTRACT)));
        assertTrue(error.getMessage().contains("complete, validated multi-file"));
    }

    @Test
    void deletingActorWitnessCannotReusePreviouslyAcceptedBuild() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(complete()));
        Map<String, String> incomplete = complete();
        incomplete.remove("shop/cart.atto");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(incomplete));
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }

    @Test
    void removingFunctionWitnessIsRejected() {
        Map<String, String> sources = complete();
        sources.remove("shop/tick.atto");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing compatible public fnc"));
    }

    @Test
    void privateActorDoesNotSatisfyNamespace() {
        Map<String, String> sources = complete();
        sources.put("shop/cart.atto", ACTOR.replace("pub define", "define"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }

    @Test
    void differentNamespaceCannotSupplyActorWitness() {
        Map<String, String> sources = complete();
        sources.put("shop/cart.atto", ACTOR.replace("namespace shop;", "namespace other;"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }

    @Test
    void duplicateActorAcrossFilesIsRejectedBeforeCacheReuse() {
        Map<String, String> sources = complete();
        sources.put("shop/cart-duplicate.atto", ACTOR);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("duplicate type"));
    }

    @Test
    void duplicateNamespaceConformanceHeadersAreRejected() {
        Map<String, String> sources = complete();
        sources.put("shop/second.atto", "namespace shop conforms ShopApi;");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("multiple conformance headers"));
    }

    @Test
    void namespaceCannotClaimOrdinaryModuleContract() {
        Map<String, String> sources = complete();
        sources.put("shop/contracts.atto", CONTRACT.replace("ShopApi for namespace", "ShopApi"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("local 'for namespace' contract"));
    }

    @Test
    void moduleCannotClaimNamespaceOnlyContract() {
        String source = """
                namespace shop;
                define contract ShopApi for namespace as
                  actor Cart;
                end
                define module carts conforms ShopApi as
                  pub define actor Cart;
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("namespace contracts cannot be used by modules"));
    }

    @Test
    void missingContractDefinitionCannotBeSatisfiedByClassName() {
        Map<String, String> sources = Map.of(
                "shop/claim.atto", "namespace shop conforms ShopApi;",
                "shop/cart.atto", ACTOR);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("local 'for namespace' contract"));
    }

    @Test
    void mismatchedFunctionReturnTypeFailsConformance() {
        Map<String, String> sources = complete();
        sources.put("shop/tick.atto",
                FUNCTION.replace("): int {", "): String {")
                        .replace("return amount;", "return \"oops\";"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing compatible public fnc"));
    }

    @Test
    void siblingNamespaceAbiChangeInvalidatesDependentUnits() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        compiler.compile(complete());
        Map<String, String> changed = complete();
        changed.put("shop/cart.atto", ACTOR.replace("pub define actor Cart;",
                "pub define actor Cart as\n  pub ping(): int { return 1; }\nend"));
        var rebuilt = compiler.compile(changed);
        assertTrue(rebuilt.rebuilt("shop/contracts.atto"));
        assertTrue(rebuilt.rebuilt("shop/tick.atto"));
    }

    @Test
    void portableAttoCorpusChecksActualMultiFileSources() throws IOException {
        Path root = Path.of("contracts", "namespace-v1");
        Map<String, String> sources = new LinkedHashMap<>();
        for (String file : new String[] {"contracts.atto", "cart.atto", "tick.atto"}) {
            sources.put("shop/" + file,
                    Files.readString(root.resolve("shop").resolve(file)));
        }
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(sources));

        for (Map.Entry<String, String> mutation : Map.of(
                "reject_private_actor", "missing public non-generic actor",
                "reject_wrong_namespace", "missing public non-generic actor").entrySet()) {
            Map<String, String> negative = new LinkedHashMap<>(sources);
            negative.put("shop/cart.atto",
                    Files.readString(root.resolve(mutation.getKey()).resolve("cart.atto")));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new IncrementalCompiler().compile(negative), mutation.getKey());
            assertTrue(error.getMessage().contains(mutation.getValue()),
                    () -> mutation.getKey() + ": " + error.getMessage());
        }
    }

    @Test
    void namespaceContractCannotBeDeclaredInsideModule() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        namespace shop;
                        define module nested as
                          define contract ShopApi for namespace as
                            actor Cart;
                          end
                        end
                        """));
        assertTrue(error.getMessage().contains("file root"));
    }

    @Test
    void actorAndCallableWithSameNameInDifferentFilesCollide() {
        Map<String, String> sources = complete();
        sources.put("shop/tick.atto", """
                namespace shop;
                pub fnc Cart(): int { return 1; }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("duplicate runtime value"));
    }

    @Test
    void namespaceAbiEdgesDoNotInventRuntimeInitializationCycles() {
        var result = new IncrementalCompiler().compile(complete());
        assertEquals(3, result.initializationGroups().size());
        assertTrue(result.initializationGroups().stream().allMatch(group -> group.size() == 1),
                "namespace-only ABI edges must never change runtime init barriers");
    }

    @Test
    void namespaceFieldRequirementsFailClosedUntilCrossFileTypedBindingProof() {
        Map<String, String> sources = complete();
        sources.put("shop/contracts.atto", CONTRACT.replace(
                "  fnc tick(int amount): int;", "  value: int;"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("supports only actor and fnc requirements"));
    }

    @Test
    void ordinaryClassCannotImpersonateNominalActorAcrossFiles() {
        Map<String, String> sources = complete();
        sources.put("shop/cart.atto", """
                namespace shop;
                pub define class Cart as
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }

    @Test
    void namespaceActorAndFieldWithSamePublicNameCannotCoexist() {
        Map<String, String> sources = complete();
        sources.put("shop/field.atto", """
                namespace shop;
                pub val int Cart = 1;
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("duplicate runtime value"));
    }

    @Test
    void changedPublicFunctionParameterTypeInvalidatesContract() {
        Map<String, String> sources = complete();
        sources.put("shop/tick.atto", FUNCTION.replace(
                "tick(int amount)", "tick(String amount)")
                .replace("return amount;", "return 42;"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("incompatible parameter"));
    }

    @Test
    void ordinaryModuleCannotProvideNamespaceRootFunction() {
        Map<String, String> sources = complete();
        sources.put("shop/tick.atto", """
                namespace shop;
                define module actions as
                  pub fnc tick(int amount): int { return amount; }
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing compatible public fnc"));
    }

    @Test
    void functionDeclarationMustNotBeAmbiguousAcrossFiles() {
        Map<String, String> sources = complete();
        sources.put("shop/tick-dupe.atto", FUNCTION);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("duplicate function slot"));
    }

    @Test
    void namespaceContractDoesNotTreatExplicitModulesAsRootWitnesses() {
        Map<String, String> sources = complete();
        sources.put("shop/cart.atto", """
                namespace shop;
                define module carts as
                  pub define actor Cart;
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("missing public non-generic actor"));
    }
}
