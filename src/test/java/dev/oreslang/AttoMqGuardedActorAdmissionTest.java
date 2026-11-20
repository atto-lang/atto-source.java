package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser and fail-closed lowering gate for the AttoMQ draft actor.
 * This deliberately does NOT claim actor execution, native AOT, authorization,
 * queue safety, or source identity for the separate private AttoMQ repository.
 */
final class AttoMqGuardedActorAdmissionTest {
    private static final String SOURCE = """
            import module queue_protocol from "./queue_protocol";

            define serial actor GuardedPartitionOwner as
              state {
                let bool admitted_present = false;
                let int admitted_producer = 0;
                let int admitted_request = 0;
                let int admitted_payload = 0;
                let bool waiting_present = false;
                let int waiting_producer = 0;
                let int waiting_request = 0;
                let int waiting_payload = 0;
                let bool closed = false;
                let bool terminal_seen = false;
              }
              receive(ActorMail<queue_protocol.Command> mail): void {
                return;
              }
            end
            """;

    @Test
    void serialActorTypedLiteralStateParsesButLoweringRemainsDisabled() {
        var program = Parser.parse(SOURCE);
        var actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("GuardedPartitionOwner", actor.name());
        assertEquals(10, actor.sealedStateFields().size());
        assertEquals("admitted_present", actor.sealedStateFields().getFirst().name());
        assertEquals("waiting_present", actor.sealedStateFields().get(4).name());
        assertEquals(1, actor.methods().size());
        assertEquals("receive", actor.methods().getFirst().name());

        // A parser-positive is not permission to silently create an ordinary
        // actor: physical sealed-state lowering must fail closed.
        var rejected = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.analyze(program));
        assertTrue(rejected.getMessage().contains(
                "ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED"), rejected::getMessage);
    }

    @Test
    void oldColonFieldsCannotPassForSealedActorState() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state {
                    admitted_request: int = 0;
                  }
                  receive(ActorMail<int> mail): void { return; }
                end
                """));
    }

    @Test
    void negativeSentinelIsNotAnApprovedScalarStateLiteral() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state {
                    let int admitted_producer = -1;
                  }
                  receive(ActorMail<int> mail): void { return; }
                end
                """));
    }

    @Test
    void duplicateSealedStateContainersAreRejected() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let bool occupied = false; }
                  state { let bool closed = false; }
                end
                """));
        assertTrue(rejected.getMessage().contains("duplicate sealed actor state container"));
    }

    @Test
    void duplicateSealedStateFieldNamesAreRejected() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state {
                    let int request_id = 0;
                    let int request_id = 1;
                  }
                end
                """));
        assertTrue(rejected.getMessage().contains("duplicate sealed actor state field"));
    }

    @Test
    void sealedStateMustUseTheSupportedScalarTypes() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let String ticket = "untrusted"; }
                end
                """));
        assertTrue(rejected.getMessage().contains("scalar int/bool fields"));
    }

    @Test
    void sealedStateInitializersMustMatchTheDeclaredType() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let int request_id = true; }
                end
                """));
        assertTrue(rejected.getMessage().contains("initializer type must match int"));
    }

    @Test
    void sealedStateCannotMixOrdinaryActorFields() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let bool occupied = false; }
                  let int spoofed_mailbox_slot = 0;
                end
                """));
        assertTrue(rejected.getMessage().contains("cannot mix ordinary actor fields"));
    }

    @Test
    void untrustedActorCodeCannotDefineHandleStateIntrinsic() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let bool occupied = false; }
                  handle_state(): void { return; }
                end
                """));
        assertTrue(rejected.getMessage().contains("handle_state is compiler-owned"));
    }

    @Test
    void actorLocalPanicHandlerCannotInterceptSupervisorControl() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor GuardedPartitionOwner as
                  state { let bool occupied = false; }
                  on_panic(): void { return; }
                end
                """));
        assertTrue(rejected.getMessage().contains("actor-local on_panic is forbidden"));
    }

    @Test
    void sealedStateFieldBudgetIsEnforcedAtParseTime() {
        var source = new StringBuilder(
                "define serial actor GuardedPartitionOwner as\n  state {\n");
        for (int field = 0; field < 65; field++) {
            source.append("    let int slot_").append(field).append(" = 0;\n");
        }
        source.append("  }\nend\n");
        var rejected = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source.toString()));
        assertTrue(rejected.getMessage().contains("exceeds 64-field bound"));
    }
}
