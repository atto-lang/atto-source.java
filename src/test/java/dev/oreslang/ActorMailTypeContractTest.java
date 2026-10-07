package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMailTypeContractTest {

    @Test
    void typedPublicMethodsDefineGeneratedActorEndpointTable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub notify(String value): void {
                    stdio.println(value);
                    return;
                  }

                  pub add(int left, int right): int {
                    return left + right;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  worker.send.notify("hello");
                  val result = await worker.call.add(20, 22);
                  worker.stop();
                  await worker.done;
                  return;
                }
                """)));
    }

    @Test
    void typedDispatchArgumentsMustMatchEndpointSignature() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub notify(String value): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send.notify(42);
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("actor endpoint")
                        || failure.getMessage().contains("argument"),
                failure.getMessage());
    }

    @Test
    void actorWithoutEndpointCannotAcceptArbitraryDispatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send.missing("hello");
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("no public endpoint"),
                failure.getMessage());
    }

    @Test
    void receiveIsReservedForCompilerOwnedMailboxDispatch() {
        IllegalArgumentException privateHandler = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(Object mail): void { return; }
                        end
                        """)));
        assertTrue(privateHandler.getMessage().contains("reserved"),
                privateHandler.getMessage());

        IllegalArgumentException publicHandler = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(Object mail): void { return; }
                        end
                        """)));
        assertTrue(publicHandler.getMessage().contains("reserved"),
                publicHandler.getMessage());
    }

    @Test
    void endpointArgumentsAndResultsMustBeActorBoundarySendable() {
        IllegalArgumentException closureArgument = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub consume(Object value): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send.consume(|| -> { return 1; });
                          return;
                        }
                        """)));
        assertTrue(closureArgument.getMessage().contains("sendable"),
                closureArgument.getMessage());

        IllegalArgumentException closureResult = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub callback(): (() => int) {
                            return || -> { return 1; };
                          }
                        end
                        """)));
        assertTrue(closureResult.getMessage().contains("sendable"),
                closureResult.getMessage());
    }

    @Test
    void oneWayProxyCannotDiscardRequestReplyResult() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub add(int left, int right): int {
                            return left + right;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send.add(20, 22);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("worker.call.add"),
                failure.getMessage());
    }

    @Test
    void actorRefNeverExposesEndpointsAsOrdinaryMethods() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub notify(String value): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.notify("hello");
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("unknown ActorRef")
                        || failure.getMessage().contains("actor refs"),
                failure.getMessage());
    }
}
