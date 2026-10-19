package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor mailbox capability transport is distinct from Send/Sync and from
 * the frozen-data completion path of actor callables.
 */
final class ActorTransportSendSyncParityTest {
    @Test
    void concreteActorRefsRemainLegalAsMailboxRoutingCapabilities() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Endpoint as
                  receive(ActorMail<String> mail): void { return; }
                end

                define actor Router as
                  receive(ActorMail<ActorRef<Endpoint>> mail): void { return; }
                end

                actor fnc forward(ActorRef<Endpoint> target): void { return; }
                """)));
    }

    @Test
    void untrustedMailboxesRejectActorRefsIncludingNestedReferences() {
        for (String payload : new String[] {
                "ActorRef<Endpoint>",
                "Option<ActorRef<Endpoint>>",
                "List<ActorRef<Endpoint>>",
                "Result<int, ActorRef<Endpoint>>"
        }) {
            String source = """
                    define actor Endpoint as
                      receive(ActorMail<String> mail): void { return; }
                    end

                    define untrusted actor Worker as
                      receive(ActorMail<%s> mail): void { return; }
                    end
                    """.formatted(payload);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), payload);
            assertTrue(error.getMessage().contains("cannot transport ActorRef to an untrusted actor"),
                    error.getMessage());
        }
    }

    @Test
    void trustedActorMailboxesRetainExplicitActorRefRouting() {
        for (String kind : new String[] { "shared", "private" }) {
            String source = """
                    define actor Endpoint as
                      receive(ActorMail<String> mail): void { return; }
                    end

                    define %s actor Router as
                      receive(ActorMail<ActorRef<Endpoint>> mail): void { return; }
                    end
                    """.formatted(kind);
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), kind);
        }
    }

    @Test
    void unknownActorRefTargetCannotForgeRoutingCapability() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Router as
                          receive(ActorMail<ActorRef<MissingActor>> mail): void { return; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("not a known actor class"),
                error.getMessage());
    }

    @Test
    void nestedUnknownActorRefTargetCannotForgeRoutingCapability() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Router as
                          receive(ActorMail<Option<ActorRef<MissingActor>>> mail): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("not a known actor class"),
                error.getMessage());
    }

    @Test
    void liveActorRefsCannotLeakThroughFrozenCallableCompletions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Endpoint as
                          receive(ActorMail<String> mail): void { return; }
                        end

                        actor fnc leak(ActorRef<Endpoint> ref): ActorRef<Endpoint> {
                          return ref;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot return live ActorRef"),
                error.getMessage());
    }

    @Test
    void nestedLiveCapabilitiesCannotLeakThroughFrozenCallableCompletions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Endpoint as
                          receive(ActorMail<String> mail): void { return; }
                        end

                        actor fnc leak(): Option<List<ActorRef<Endpoint>>> {
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot return live ActorRef"),
                error.getMessage());
    }

    @Test
    void sourceLevelActorMailAndUnknownHostValuesNeverBecomeOrdinaryWireData() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(ActorMail<int> envelope): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(UnknownOpaqueHost host): void { return; }
                """)));
    }
}
