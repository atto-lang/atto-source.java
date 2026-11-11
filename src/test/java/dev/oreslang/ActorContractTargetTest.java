package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actor-targeted method contracts are nominal obligations, not state capabilities. */
final class ActorContractTargetTest {
    private static final String VALID = """
            define contract FooContract for actor as
              mtd abc(): void;
              pub mtd xyz(): void;
            end

            pub define actor Foo implements FooContract as
              pub abc(): void { return; }
              pub xyz(): void { return; }
            end
            """;

    @Test
    void actorCanImplementBothRequiredMethodSlots() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(VALID)));
    }

    @Test
    void actorContractTargetSurvivesParsing() {
        var parsed = Parser.parse(VALID);
        Ast.InterfaceDecl contract = null;
        for (Ast.ModuleDecl module : parsed.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.InterfaceDecl iface
                        && iface.name().equals("FooContract")) contract = iface;
            }
        }
        assertTrue(contract != null && contract.actorContract());
        assertTrue(!contract.moduleContract());
        assertTrue(!contract.namespaceContract());
        assertEquals(2, contract.members().size());
    }

    @Test
    void missingRequiredMethodIsRejected() {
        String broken = VALID.replace(
                "  pub xyz(): void { return; }", "");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(broken)));
        assertTrue(error.getMessage().contains("does not implement interface")
                        || error.getMessage().contains("missing"));
    }

    @Test
    void privateActorMethodDoesNotFulfillPublicContractSlot() {
        String broken = VALID.replace(
                "pub abc(): void { return; }", "abc(): void { return; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(broken)));
        assertTrue(error.getMessage().contains("does not implement interface")
                        || error.getMessage().contains("missing"));
    }

    @Test
    void ordinaryClassCannotImplementActorOnlyContract() {
        String source = """
                define contract FooContract for actor as
                  mtd abc(): void;
                end
                pub define class Foo implements FooContract as
                  pub abc(): void { return; }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("only be implemented by actors"));
    }

    @Test
    void moduleCannotClaimActorOnlyContract() {
        String source = """
                define contract FooContract for actor as
                  mtd abc(): void;
                end
                define module foo conforms FooContract as
                  pub fnc abc(): void { return; }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("unknown module contract"));
    }

    @Test
    void namespaceCannotClaimActorContractInsteadOfNamespaceTarget() {
        Map<String, String> sources = Map.of("shop/contract.atto", """
                namespace shop conforms FooContract;
                define contract FooContract for actor as
                  mtd abc(): void;
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("local 'for namespace' contract"));
    }

    @Test
    void actorContractRejectsFunctionInsteadOfMethodSignature() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define contract FooContract for actor as
                          fnc abc(): void;
                        end
                        """));
        assertTrue(error.getMessage().contains("require 'mtd'"));
    }

    @Test
    void actorContractRejectsDataFields() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define contract FooContract for actor as
                          counter: int;
                        end
                        """));
        assertTrue(error.getMessage().contains("actor contract fields"));
    }

    @Test
    void actorContractGenericRequirementsFailClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract FooContract<T> for actor as
                          mtd abc(): void;
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot use generics or inheritance"));
    }

    @Test
    void duplicateActorContractMethodSlotIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract FooContract for actor as
                          mtd abc(int a): void;
                          mtd abc(String a): void;
                        end
                        """)));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void methodSignatureChangesChangeActorContractExportFingerprint() {
        var header = new ExportInterfaceCodec.Header("", "test-compiler", "test-abi", "safe");
        var a = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract FooContract for actor as
                  mtd abc(): int;
                end
                """), header);
        var b = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract FooContract for actor as
                  mtd abc(): String;
                end
                """), header);
        assertNotEquals(a.sha256(), b.sha256());
    }

    @Test
    void lifecycleTeardownCannotBecomePublicActorContractMethod() {
        String source = """
                define contract FooContract for actor as
                  mtd tear_down(): void;
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("reserved lifecycle/control/receive hook"));
    }

    @Test
    void lifecycleTeardownCannotBePublicEvenWithoutAnActorContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub define actor Foo as
                          pub tear_down(): void { return; }
                        end
                        """));
        assertTrue(error.getMessage().contains("tear_down is a runtime-only lifecycle hook"));
    }

    @Test
    void privateTeardownHookRemainsAdmittedForSupervisorExecution() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub define actor Foo as
                  tear_down(): void { return; }
                end
                """)));
    }

    @Test
    void compilerOwnedStateLoanCannotBeARequiredPublicMethod() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract FooContract for actor as
                          mtd handle_state(): void;
                        end
                        """)));
        assertTrue(error.getMessage().contains("reserved lifecycle/control/receive hook"));
    }

    @Test
    void receiveHandlerRequiresDedicatedTypedActorMessageContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract FooContract for actor as
                          mtd receive(): void;
                        end
                        """)));
        assertTrue(error.getMessage().contains("reserved lifecycle/control/receive hook"));
    }

    @Test
    void suspendingActorMethodCannotSilentlyFulfillSynchronousContract() {
        String source = """
                define contract FooContract for actor as
                  mtd abc(): void;
                end
                pub define actor Foo implements FooContract as
                  pub suspends abc(): void { return; }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("unsupported execution effects/generics"));
    }

    @Test
    void abstractOrUndeclaredActorMethodCannotBeAContractWitness() {
        String source = """
                define contract FooContract for actor as
                  mtd abc(): void;
                end
                pub define actor Foo implements FooContract as
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("directly declared public mailbox method"));
    }

    @Test
    void onStopAndOnRestartRequireCompilerOwnedControlLowering() {
        for (String name : new String[] {"on_stop", "on_restart"}) {
            String source = "pub define actor Foo as\n"
                    + "  pub " + name + "(): void { return; }\n"
                    + "end\n";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), name);
            assertTrue(error.getMessage().contains("generation-fenced lowering"),
                    () -> name + ": " + error.getMessage());
        }
    }

    @Test
    void reservedControlHooksCannotBeActorContractRequirements() {
        for (String name : new String[] {"on_stop", "on_restart", "on_panic"}) {
            String source = "define contract FooContract for actor as\n"
                    + "  mtd " + name + "(): void;\n"
                    + "end\n";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), name);
            assertTrue(error.getMessage().contains("reserved lifecycle/control/receive hook"),
                    () -> name + ": " + error.getMessage());
        }
    }

    @Test
    void actualAttoCorpusReachesParserAndTypeChecker() throws IOException {
        Path root = Path.of("contracts", "actor-contract-target-v1");
        String positive = Files.readString(root.resolve("accept_actor_contract.atto"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(positive)));
        String missing = Files.readString(root.resolve("reject_missing_method.atto"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(missing)));
        assertTrue(error.getMessage().contains("does not implement interface")
                        || error.getMessage().contains("missing"));
    }
}
