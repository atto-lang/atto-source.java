package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSendAsyncOwnershipTest {

    @Test
    void sendAsyncMovesAffinePayloadAtSubmissionTime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<List<int>> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          let List<int> payload = new List<int>();
                          val Future<void> admission = worker.send_async(payload);
                          stdio.println(payload.size());
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("use of moved value 'payload'")
                        || failure.getMessage().contains("cannot move"),
                failure::getMessage);
    }

    @Test
    void synchronousSendUsesTheSameAffineMailboxBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<List<int>> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          let List<int> payload = new List<int>();
                          worker.send(payload);
                          stdio.println(payload.size());
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("use of moved value 'payload'")
                        || failure.getMessage().contains("cannot move"),
                failure::getMessage);
    }

    @Test
    void projectedActorRefFieldKeepsTheSameSubmissionTimeMoveBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Sink as
                          receive(ActorMail<List<int>> mail): void {
                            return;
                          }
                        end

                        define actor Producer as
                          val ActorRef<Sink> sink;

                          on_start(): void {
                            self.sink = spawn Sink();
                            return;
                          }

                          receive(ActorMail<String> mail): void {
                            let List<int> payload = new List<int>();
                            val Future<void> admission =
                                    self.sink.send_async(payload);
                            stdio.println(payload.size());
                            return;
                          }
                        end
                        """));

        assertTrue(
                failure.getMessage().contains("use of moved value 'payload'")
                        || failure.getMessage().contains("cannot move"),
                failure::getMessage);
    }

    @Test
    void sendAsyncRejectsBorrowedMailboxPayload() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<List<int>> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          let List<int> payload = new List<int>();
                          val &List<int> view = rt borrow payload;
                          val Future<void> admission = worker.send_async(view);
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("ActorRef.send_async mailbox value")
                        || failure.getMessage().contains("borrowed reference"),
                failure::getMessage);
    }

    @Test
    void mailboxTypesRejectTasksAndNativePointers() {
        for (String forbidden : java.util.List.of(
                "Task<int>",
                "NativePointer",
                "RawPointer",
                "Pointer<int>")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            define actor Worker as
                              receive(ActorMail<%s> mail): void {
                                return;
                              }
                            end
                            """.formatted(forbidden)),
                    forbidden);
            assertTrue(
                    failure.getMessage().contains("actor boundary")
                            || failure.getMessage().contains("cannot use")
                            || failure.getMessage().contains("sendable"),
                    () -> forbidden + ": " + failure.getMessage());
        }
    }

    @Test
    void copyPayloadCanBeReusedAfterSendAsyncSubmission() {
        assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<int> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          val int payload = 7;
                          val Future<void> admission = worker.send_async(payload);
                          stdio.println(payload);
                          return;
                        }
                        """));
    }

    @Test
    void awaitingSendAsyncIsARealSuspensionBoundaryForBorrows() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<int> mail): void {
                            return;
                          }
                        end

                        pub async routine main(): void {
                          val worker = spawn Worker();
                          let List<int> local = new List<int>();
                          val &List<int> view = rt borrow local;
                          await worker.send_async(1);
                          stdio.println(view.size());
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains(
                        "cannot await while an ordinary borrow is live"),
                failure::getMessage);
    }
}
