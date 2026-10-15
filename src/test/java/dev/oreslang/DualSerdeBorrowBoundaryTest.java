package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network and mailbox serialization contracts are distinct declarations.
 * A codec implementation is not itself a serialized message or capability.
 */
final class DualSerdeBorrowBoundaryTest {
    @Test
    void serializerAnnotationsCannotSilentlyGrantCrossDomainAuthority() {
        for (String annotation : new String[]{
                "Serializable", "NetworkCodec", "ActorMessageCodec",
                "SupervisorControlCodec"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse("""
                            @%s
                            define class Payload as
                              pub val int value = 7;
                            end
                            """.formatted(annotation)));
            assertTrue(failure.getMessage().contains("requires compile-time"),
                    () -> annotation + ": " + failure.getMessage());
        }
    }

    @Test
    void separateGenericCodecInterfacesParseWithReadLoansAndOwnedResults() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                define interface NetworkCodec<T> as
                  fnc encode_network(borrow T value) => Result<List<int>, EncodeError>;
                  fnc decode_network(borrow List<int> bytes) => Result<T, DecodeError>;
                end

                define interface ActorMessageCodec<T> as
                  fnc encode_actor_message(borrow T value) => Result<List<int>, EncodeError>;
                  fnc decode_actor_message(borrow List<int> bytes) => Result<T, DecodeError>;
                end
                """));
        assertEquals(2, program.modules().getFirst().declarations().size());
        var network = assertInstanceOf(Ast.InterfaceDecl.class,
                program.modules().getFirst().declarations().get(0));
        var actor = assertInstanceOf(Ast.InterfaceDecl.class,
                program.modules().getFirst().declarations().get(1));
        assertEquals("NetworkCodec", network.name());
        assertEquals("ActorMessageCodec", actor.name());
        assertEquals(2, network.members().size());
        assertEquals(2, actor.members().size());
    }

    @Test
    void neitherCodecIsAnActorDataCapabilityEvenThroughNesting() {
        for (String type : new String[]{
                "NetworkCodec<int>",
                "ActorMessageCodec<int>",
                "Pointer<int>",
                "Stream<int>",
                "Observable<int>",
                "Option<NetworkCodec<int>>",
                "Result<ActorMessageCodec<int>, String>",
                "Array<Pointer<int>>",
                "List<Observable<int>>"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            actor fnc bad(%s value): void { return; }
                            """.formatted(type))), type);
            assertTrue(failure.getMessage().contains("actor")
                            || failure.getMessage().contains("transport"),
                    () -> type + ": " + failure.getMessage());
        }
    }

    @Test
    void moduleQualifiedCodecTypeCannotBypassActorDataPolicy() {
        for (String type : new String[]{
                "CodecBox.NetworkCodec<int>",
                "CodecBox.ActorMessageCodec<int>",
                "Option<CodecBox.NetworkCodec<int>>",
                "Result<CodecBox.ActorMessageCodec<int>, String>"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define module CodecBox as
                              pub define interface NetworkCodec<T> as end
                              pub define interface ActorMessageCodec<T> as end
                            end
                            actor fnc bad(%s value): void { return; }
                            """.formatted(type))), type);
            assertTrue(failure.getMessage().contains("cannot transport"),
                    () -> type + ": " + failure.getMessage());
        }
    }

    @Test
    void qualifiedSharedMutexCannotBypassIsolatedActorRestriction() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define isoactor Worker as
                          receive(ActorMail<RuntimeBox.SharedMutex<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot use SharedMutex"),
                () -> failure.getMessage());
    }

    @Test
    void qualifiedRuntimeHandlesAreRejectedAsActorData() {
        for (String type : new String[] {
                "RuntimeBox.TcpConnection",
                "RuntimeBox.Future<int>",
                "RuntimeBox.Channel<int>",
                "RuntimeBox.ActorRef<int>",
                "RuntimeBox.ActorGroup",
                "RuntimeBox.ProxyActor",
                "Option<RuntimeBox.ActorRef<int>>"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "actor fnc bad(" + type + " value): void { return; }")), type);
            assertTrue(failure.getMessage().contains("cannot transport")
                            || failure.getMessage().contains("cannot use"),
                    () -> type + ": " + failure.getMessage());
        }
    }

    @Test
    void moduleQualifiedCodecCannotBeReceivedAsSupervisorActorData() {
        for (String type : new String[]{
                "CodecBox.NetworkCodec<int>",
                "CodecBox.ActorMessageCodec<int>",
                "Option<CodecBox.NetworkCodec<int>>"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define module CodecBox as
                              pub define interface NetworkCodec<T> as end
                              pub define interface ActorMessageCodec<T> as end
                            end
                            define actor Worker as
                              receive(ActorMail<%s> mail): void { return; }
                            end
                            """.formatted(type))), type);
            assertTrue(failure.getMessage().contains("cannot transport"),
                    () -> type + ": " + failure.getMessage());
        }
    }

    @Test
    void unknownNominalsCannotLaunderUnverifiedActorDataShapes() {
        for (String type : new String[]{
                "OpaqueHandle",
                "OpaqueHandle<int>",
                "Option<OpaqueHandle>",
                "Result<OpaqueHandle, String>",
                "Namespace.ExternalCapability",
                "List<Namespace.ExternalCapability>"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "actor fnc bad(" + type + " value): void { return; }")),
                    type);
            assertTrue(failure.getMessage().contains("unresolved nominal type"),
                    () -> type + ": " + failure.getMessage());
        }
    }

    @Test
    void knownCompilerDataIdentifiersRemainAdmissible() {
        for (String type : new String[]{"ActorId", "ActorKind", "Symbol"}) {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                    "actor fnc inspect(" + type + " value): void { return; }")), type);
        }
    }

    @Test
    void supervisorToActorMailboxDeclCannotCarryCodecOrPointer() {
        for (String type : new String[]{
                "NetworkCodec<int>", "ActorMessageCodec<int>",
                "Pointer<int>", "Option<Pointer<int>>",
                "Array<NetworkCodec<int>>"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Worker as
                              receive(ActorMail<%s> mail): void { return; }
                            end
                            """.formatted(type))), type);
        }
    }

    @Test
    void plainDataMailAndNetworkByteTypesRemainAdmissible() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("hello");
                  return;
                }
                """)));
    }
}
