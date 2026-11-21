package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMailTypeContractTest {

    @Test
    void typedReceiveDefinesActorRefSendPayloadAndEnvelopeMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    // Mailbox envelopes are read-only; retain that capability
                    // when projecting a payload rather than creating a mutable alias.
                    const String value = mail.value;
                    const int sequence = mail.sequence;
                    const ActorId recipient = mail.recipient;
                    const Option<ActorId> sender = mail.sender;
                    stdio.println(value);
                    stdio.println(sequence);
                    stdio.println(recipient);
                    stdio.println(sender);
                    return;
                  }
                end

                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("hello");
                  return;
                }
                """)));
    }

    @Test
    void actorRefSendMustMatchReceivePayload() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(42);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("ActorRef.send mailbox value"),
                failure.getMessage());
    }

    @Test
    void actorWithoutReceiveCannotAcceptMailboxSend() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send("hello");
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("no receive(ActorMail<T>)"),
                failure.getMessage());
    }

    @Test
    void receiveIsActorLocalAndHasCanonicalShape() {
        IllegalArgumentException publicHandler = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(ActorMail<String> mail): void { return; }
                        end
                        """)));
        assertTrue(publicHandler.getMessage().contains("actor-local"),
                publicHandler.getMessage());

        IllegalArgumentException wrongParameter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(String mail): void { return; }
                        end
                        """)));
        assertTrue(wrongParameter.getMessage().contains("ActorMail<T>"),
                wrongParameter.getMessage());

        IllegalArgumentException wrongReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): int { return 1; }
                        end
                        """)));
        assertTrue(wrongReturn.getMessage().contains("must return void"),
                wrongReturn.getMessage());
    }

    @Test
    void actorMailRequiresExplicitSendablePayloadType() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail mail): void { return; }
                        end
                        """)));

        IllegalArgumentException closurePayload = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<Fnc<void>> mail): void { return; }
                        end
                        """)));
        assertTrue(closurePayload.getMessage().contains("sendable")
                        || closurePayload.getMessage().contains("actor-boundary"),
                closurePayload.getMessage());
    }

    @Test
    void actorRefsCrossMailboxesAsCapabilitiesButSmartPointersDoNotYet() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Registry as
                  receive(ActorMail<ActorRef<Registry>> mail): void {
                    return;
                  }
                end

                pub routine main(): void {
                  val registry = spawn Registry();
                  registry.send(registry);
                  return;
                }
                """)));

        IllegalArgumentException boxed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<Box<int>> mail): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(boxed.getMessage().contains("Box")
                        && boxed.getMessage().contains("actor boundary"),
                boxed.getMessage());
    }

    @Test
    void receiveCannotBeInvokedLikeAnOrdinaryActorMethod() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.receive(mail);
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("reserved actor lifecycle hook"),
                failure.getMessage());
    }

    @Test
    void actorRefTrySendAndSendAsyncUseTheSameTypedMailboxDomain() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  val bool accepted = worker.try_send("hello");
                  val Future<void> pending = worker.send_async("world");
                  await pending;
                  return;
                }
                """)));

        for (String method : java.util.List.of("try_send", "send_async")) {
            IllegalArgumentException mismatch = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Worker as
                              receive(ActorMail<String> mail): void { return; }
                            end
                            pub routine main(): void {
                              val worker = spawn Worker();
                              worker.METHOD(42);
                              return;
                            }
                            """.replace("METHOD", method))));
            assertTrue(mismatch.getMessage().contains("ActorRef." + method + " mailbox value"),
                    mismatch.getMessage());
        }
    }


    @Test
    void immediateAndAwaitableActorSendMethodsCannotEscapeTheirCheckedCallSites() {
        for (String method : java.util.List.of("try_send", "send_async")) {
            IllegalArgumentException rejection = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Worker as
                              receive(ActorMail<int> mail): void { return; }
                            end
                            pub routine main(): void {
                              val worker = spawn Worker();
                              val escaped = worker.METHOD;
                              return;
                            }
                            """.replace("METHOD", method))));
            assertTrue(rejection.getMessage().contains("direct-call-only"),
                    rejection.getMessage());
        }
    }


    @Test
    void builtinMailboxMetadataIsCopyButMutableNonCopyPayloadAliasesRemainRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<List<int>> mail): void {
                            val List<int> leaked_mutable_alias = mail.value;
                            return;
                          }
                        end
                        """)));
        // This must fail specifically at the ownership projection gate.
        // Accepting an unrelated sendability/parser rejection would falsely
        // certify that a mutable reference cannot be laundered from ActorMail.
        assertTrue(failure.getMessage().contains("mutable projected alias"),
                failure.getMessage());
    }


    @Test
    void vmActorMailboxProtocolNamesCannotBeShadowedByClassesOrTypeAliases() {
        for (String name : java.util.List.of(
                "ActorMail", "ActorOutput", "ActorOutputStream",
                "ActorRef", "ActorId", "ActorKind")) {
            IllegalArgumentException nominal = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "define class " + name + " as end")));
            assertTrue(nominal.getMessage().contains("reserved actor protocol type"),
                    name + ": " + nominal.getMessage());

            IllegalArgumentException alias = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "type " + name + " = int;")));
            assertTrue(alias.getMessage().contains("reserved actor protocol type"),
                    name + ": " + alias.getMessage());
        }
    }

}
