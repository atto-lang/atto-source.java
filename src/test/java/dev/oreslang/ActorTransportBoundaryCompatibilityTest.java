package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compiler admission is intentionally distinct from Rust-style Send/Sync.
 * ActorRef is an explicitly routed, runtime-owned capability; SharedMutex
 * crosses only shared-actor boundaries and remains subject to runtime policy.
 */
final class ActorTransportBoundaryCompatibilityTest {
    @Test
    void actorRefsCanBeCarriedDirectlyAndNestedInMailboxData() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<int> mail): void { return; }
                end
                define actor Relay as
                  receive(ActorMail<Option<ActorRef<Worker>>> mail): void {
                    return;
                  }
                end
                actor fnc pass_ref(ActorRef<Worker> ref): void { return; }
                pub routine main(): void {
                  val worker = spawn Worker();
                  val relay = spawn Relay();
                  relay.send(Some(worker));
                  pass_ref(worker);
                  return;
                }
                """)));
    }

    @Test
    void forgedActorRefsWithNonActorOrMissingTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(ActorRef<int> invalid): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class NotAnActor as
                  val int value = 1;
                end
                actor fnc bad(ActorRef<NotAnActor> invalid): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(ActorRef<MissingActor> invalid): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(ActorRef<ActorRef<int>> invalid): void { return; }
                """)));
    }

    @Test
    void sharedMutexCapabilitiesRemainLegalForSharedActorsOnly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor SharedWorker as
                  receive(ActorMail<SharedMutex<int>> mail): void { return; }
                end
                actor fnc accepts_shared_lock(SharedMutex<int> lock): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define isoactor PrivateWorker as
                  receive(ActorMail<SharedMutex<int>> mail): void { return; }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor fnc invalid(Option<SharedMutex<int>> lock): void { return; }
                """)));
    }

    @Test
    void nominalValuesWithoutTransportContractsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(HostOpaque value): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Option<HostOpaque> value): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class MutableBox as
                  val int value = 1;
                end
                actor fnc bad(MutableBox value): void { return; }
                """)));
    }

    @Test
    void channelsAndFuturesNeverGainActorTransportByWrapping() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Option<Channel<int>> value): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Result<int, Future<int>> value): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<Channel<int>> mail): void { return; }
                end
                """)));
    }
}
