package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compile-time actor receiver obligations; never grants a runtime ActorContext. */
final class ActorReceiverContractSignatureTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void publicActorMethodSatisfiesReceiverSignature() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    pub count(): int { return 1; }
                  end
                end
                """));
    }

    @Test
    void privateReceiverRequirementCanUsePrivateActorMethod() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                  mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    count(): int { return 1; }
                  end
                end
                """));
    }

    @Test
    void missingMethodIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("missing required receiver method"));
    }

    @Test
    void privateMethodDoesNotSatisfyPublicReceiverContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    count(): int { return 1; }
                  end
                end
                """));
        assertTrue(error.getMessage().contains("must be public"));
    }

    @Test
    void wrongReturnTypeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    pub count(): String { return "one"; }
                  end
                end
                """));
        assertTrue(error.getMessage().contains("incompatible receiver contract return type"));
    }

    @Test
    void wrongParameterTypeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd add(Cart cart)(int amount): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    pub add(String amount): int { return 1; }
                  end
                end
                """));
        assertTrue(error.getMessage().contains("incompatible parameter"));
    }

    @Test
    void undeclaredActorReceiverIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  mtd count(Cart cart)(): int;
                end
                """));
        assertTrue(error.getMessage().contains("unrequired actor"));
    }

    @Test
    void duplicateSameArityReceiverSlotIsRejectedWithoutTypeOverloading() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  mtd apply(Cart cart)(int amount): void;
                  mtd apply(Cart cart)(String amount): void;
                end
                """));
        assertTrue(error.getMessage().contains("duplicate contract receiver method slot"));
    }

    @Test
    void genericContractReceiverFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi<T> as
                  actor Cart;
                  mtd count(Cart cart)(): int;
                end
                """));
        assertTrue(error.getMessage().contains("type-substitution witness"));
    }

    @Test
    void receiverContractMustBeNominalNotBorrowed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define contract CartApi as
                  actor Cart;
                  mtd count(borrow Cart cart)(): int;
                end
                """));
        assertTrue(error.getMessage().contains("non-generic nominal actor"));
    }

    @Test
    void receiverContractMayInheritActorRequirement() {
        assertDoesNotThrow(() -> check("""
                define contract Base as
                  actor Cart;
                end
                define contract CartApi extends Base as
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                    pub count(): int { return 1; }
                  end
                end
                """));
    }

    @Test
    void runtimeOnlyTeardownCannotMasqueradeAsMailboxContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd tear_down(Cart cart)(): void;
                end
                """));
        assertTrue(error.getMessage().contains("actor-local lifecycle contract hook 'tear_down' must be private"));
    }

    @Test
    void compilerOwnedStateLoanCannotBeRequiredAsUserMethod() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd handle_state(Cart cart)(): void;
                end
                """));
        assertTrue(error.getMessage().contains("reserved actor lifecycle/control hook"));
    }

    @Test
    void actorReceiverMethodCannotBeImplementedByFreeModuleFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as
                  end
                  pub fnc count(): int { return 1; }
                end
                """));
        assertTrue(error.getMessage().contains("missing required receiver method"));
    }

    @Test
    void publicReceiverContractsHaveDistinctExportFingerprints() {
        var header = new ExportInterfaceCodec.Header("", "test-compiler", "test-abi", "safe");
        var a = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                """), header);
        var b = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): String;
                end
                """), header);
        assertNotEquals(a.sha256(), b.sha256());
    }

    @Test
    void importedReceiverContractLinksToLocalActorMethod() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> source = new LinkedHashMap<>();
        source.put("api.atto", """
                pub define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                """);
        source.put("impl.atto", """
                import contract CartApi from "./api.atto";
                define module carts conforms CartApi as
                  pub define actor Cart as
                    pub count(): int { return 1; }
                  end
                end
                """);
        assertDoesNotThrow(() -> compiler.compile(source));
    }

    @Test
    void portableReceiverContractFixturesReachRealParserAndTypeChecker() throws IOException {
        Path root = Path.of("contracts", "actor-receiver-v1");
        assertDoesNotThrow(() -> check(Files.readString(root.resolve(
                "accept_existing_actor_method.atto"))));
        Map<String, String> failures = Map.of(
                "reject_missing_method.atto", "missing required receiver method",
                "reject_private_method.atto", "must be public",
                "reject_wrong_return.atto", "incompatible receiver contract return type",
                "reject_unrequired_receiver.atto", "unrequired actor");
        for (Map.Entry<String, String> fixture : failures.entrySet()) {
            String program = Files.readString(root.resolve(fixture.getKey()));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> check(program), fixture.getKey());
            assertTrue(error.getMessage().contains(fixture.getValue()),
                    () -> fixture.getKey() + ": " + error.getMessage());
        }
    }

    @Test
    void changingReceiverSlotInvalidatesImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> original = new LinkedHashMap<>();
        original.put("api.atto", """
                pub define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                """);
        original.put("consumer.atto", """
                import * as api from "./api.atto";
                pub routine main(): void { return; }
                """);
        compiler.compile(original);
        Map<String, String> changed = new LinkedHashMap<>(original);
        changed.put("api.atto", """
                pub define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): String;
                end
                """);
        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("consumer.atto"));
    }
}
