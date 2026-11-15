package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-one module actor contracts: only nominal actor presence is admitted.
 * Standalone receiver-method binding is not enabled by this test.
 */
final class ModuleActorContractRequirementTest {

    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void publicActorSatisfiesNominalModuleRequirement() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                end

                define module cart_mod conforms CartApi as
                  pub define actor Cart as
                  end
                end
                """));
    }

    @Test
    void missingRequiredActorIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void ordinaryClassCannotSatisfyActorRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                  pub define class Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void privateActorCannotSatisfyPublicRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                  define actor Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void inheritedActorRequirementIsChecked() {
        assertDoesNotThrow(() -> check("""
                define contract Base as
                  actor Cart;
                end
                define contract Derived extends Base as
                  fnc status(): int;
                end
                define module cart_mod conforms Derived as
                  pub define actor Cart as
                  end
                  pub fnc status(): int { return 1; }
                end
                """));
    }

    @Test
    void duplicateActorRequirementsAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("duplicate contract actor requirement"));
    }

    @Test
    void nominalInterfaceCannotRequireModuleActor() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define interface Invalid as
                  actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("actor requirements belong only in module contracts"));
    }


    @Test
    void genericActorCannotSatisfyNonGenericActorRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                  pub define actor Cart<T> as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("generic actor"));
        assertTrue(error.getMessage().contains("cannot satisfy non-generic"));
    }

    @Test
    void directActorAndCallableNameCollisionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract Invalid as
                  actor Cart;
                  fnc Cart(): int;
                end
                """));
        assertTrue(error.getMessage().contains("conflicts with a required field or callable"));
    }

    @Test
    void directActorAndFieldNameCollisionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract Invalid as
                  actor Cart;
                  Cart: int;
                end
                """));
        assertTrue(error.getMessage().contains("conflicts with a required field or callable"));
    }

    @Test
    void inheritedFunctionCannotAliasRequiredActorName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract ActorBase as
                  actor Cart;
                end
                define contract Invalid extends ActorBase as
                  fnc Cart(): int;
                end
                """));
        assertTrue(error.getMessage().contains("conflicts with a required field or callable"));
    }

    @Test
    void inheritedFieldCannotAliasRequiredActorName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract FieldBase as
                  Cart: int;
                end
                define contract Invalid extends FieldBase as
                  actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("conflicts with a required field or callable"));
    }

    @Test
    void actorAndDistinctPublicFunctionCanConformTogether() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                  fnc status(): int;
                end
                define module cart_mod conforms CartApi as
                  pub define actor Cart as
                  end
                  pub fnc status(): int { return 1; }
                end
                """));
    }

    @Test
    void actorRequirementCannotReuseGenericTypeParameter() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract Invalid<T> as
                  actor T;
                end
                """));
        assertTrue(error.getMessage().contains("cannot reuse a generic type-parameter name"));
    }

    @Test
    void importedContractRequiresCorrectActorInImplementingModule() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("actor_api.atto", """
                pub define contract CartApi as
                  actor Cart;
                end
                """);
        sources.put("cart_impl.atto", """
                import contract CartApi from "./actor_api.atto";
                define module cart_mod conforms CartApi as
                  pub define actor Cart as
                  end
                end
                """);
        assertDoesNotThrow(() -> compiler.compile(sources));
    }

    @Test
    void importedContractRejectsClassInPlaceOfActor() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("actor_api.atto", """
                pub define contract CartApi as
                  actor Cart;
                end
                """);
        sources.put("cart_impl.atto", """
                import contract CartApi from "./actor_api.atto";
                define module cart_mod conforms CartApi as
                  pub define class Cart as
                  end
                end
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }


    @Test
    void actorFromAnotherModuleCannotSatisfyLocalModuleRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract ActorApi as
                  actor Cart;
                end
                define module other_mod as
                  pub define actor Cart as
                  end
                end
                define module cart_mod conforms ActorApi as
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void standaloneActorBindingIsNotYetAccepted() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module cart_mod as
                  pub define actor Cart as cart;
                end
                """));
    }

    @Test
    void externalReceiverMethodDoesNotAccidentallyBecomeFreeFunction() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module cart_mod as
                  pub define actor Cart as
                  end
                  define pub mtd receive(Cart a)(): void { return; }
                end
                """));
    }

    @Test
    void changingActorRequirementInvalidatesImportedContractConsumer() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> original = new LinkedHashMap<>();
        original.put("actor_api.atto", """
                pub define contract ActorApi as
                  actor Cart;
                end
                """);
        original.put("consumer.atto", """
                import * as api from "./actor_api.atto";
                pub routine main(): void { return; }
                """);
        compiler.compile(original);

        Map<String, String> changed = new LinkedHashMap<>(original);
        changed.put("actor_api.atto", """
                pub define contract ActorApi as
                  actor Worker;
                end
                """);
        var rebuilt = compiler.compile(changed);
        assertTrue(rebuilt.rebuilt("actor_api.atto"));
        assertTrue(rebuilt.rebuilt("consumer.atto"),
                "changing a public actor obligation must invalidate imported contract ABI");
    }

    @Test
    void portableActorContractCorpusUsesRealParserAndTypeChecker() throws IOException {
        Path corpus = Path.of("contracts", "actor-module-v1");
        String accepted = Files.readString(corpus.resolve("accept_public_actor.atto"));
        assertDoesNotThrow(() -> check(accepted));

        Map<String, String> rejected = Map.of(
                "reject_private_actor.atto", "missing required public actor",
                "reject_class_impostor.atto", "missing required public actor",
                "reject_generic_actor.atto", "cannot satisfy non-generic",
                "reject_inherited_name_collision.atto", "conflicts with a required field or callable");
        for (Map.Entry<String, String> fixture : rejected.entrySet()) {
            String source = Files.readString(corpus.resolve(fixture.getKey()));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> check(source), fixture.getKey());
            assertTrue(error.getMessage().contains(fixture.getValue()),
                    () -> fixture.getKey() + ": " + error.getMessage());
        }
    }

    @Test
    void changingRequiredActorChangesExportedContractAbi() {
        var header = new ExportInterfaceCodec.Header("", "test-compiler", "test-abi", "safe");
        var cart = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract Api as
                  actor Cart;
                end
                """), header);
        var worker = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract Api as
                  actor Worker;
                end
                """), header);
        assertNotEquals(cart.sha256(), worker.sha256());
    }
}
