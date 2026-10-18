package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enforce the separation between erased local callable values and portable
 * Sendable/Serializable actor DATA. Actor capabilities and user codec objects
 * never become admitted just because a nominal type name resolves.
 */
final class AnyCallableActorBoundaryTest {
    @Test
    void anyCallableIsAnErasedSupertypeOnlyOfReifiableFunctionValues() {
        Types.Function signature = new Types.Function(List.of(Types.Primitive.INT),
                Types.Primitive.INT);
        Types.Named impostor = new Types.Named("app.FakeCallable", List.of());
        assertTrue(Types.isAssignable(signature, Types.AnyCallable.INSTANCE));
        assertTrue(Types.isAssignable(Types.AnyCallable.INSTANCE, Types.AnyCallable.INSTANCE));
        assertTrue(Types.isAssignable(Types.AnyCallable.INSTANCE, Types.Unknown.INSTANCE));
        assertFalse(Types.isAssignable(Types.AnyCallable.INSTANCE, signature),
                "an erased callable cannot regain its signature implicitly");
        assertFalse(Types.isAssignable(impostor, Types.AnyCallable.INSTANCE),
                "nominal call method cannot create callable subtyping");
        assertFalse(Types.isAssignable(Types.Primitive.INT, Types.AnyCallable.INSTANCE));
    }

    @Test
    void reservedAnyCallableCannotBeUserDefinedOrParameterized() {
        for (String declaration : new String[] {
                "type AnyCallable = int;",
                "define class AnyCallable as end",
                "define interface AnyCallable as end",
                "define enum AnyCallable as A, B end",
                "define struct AnyCallable as end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(declaration)),
                    declaration);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "fnc bad(AnyCallable<int> cb): void { return; }")));
    }

    @Test
    void intrinsicNameHasNoModuleOrScopeQualificationBypass() {
        assertEquals("AnyCallable", TypeChecker.actorBoundarySimpleName("AnyCallable"));
        assertEquals("ActorRef", TypeChecker.actorBoundarySimpleName("ActorRef"));
        assertEquals("NetworkCodec", TypeChecker.actorBoundarySimpleName("NetworkCodec"));
        assertEquals("A", TypeChecker.actorBoundarySimpleName("A"));
        assertEquals("AnyCallable", TypeChecker.actorBoundarySimpleName("pkg.AnyCallable"));
        assertEquals("AnyCallable", TypeChecker.actorBoundarySimpleName("pkg::AnyCallable"));
        assertEquals("ActorMessageCodec",
                TypeChecker.actorBoundarySimpleName("pkg.net::ActorMessageCodec"));
    }

    @Test
    void erasedCallablesAndCodecObjectsNeverCrossActorData() {
        for (String type : new String[] {
                "AnyCallable", "List<AnyCallable>",
                "ActorRef<int>", "ActorHandle<int>", "ActorControlHandle",
                "ActorGroupJoinCapability", "Pointer<int>",
                "NetworkCodec<int, JsonWire>", "ActorMessageCodec<int>",
                "Serializable<int, JsonWire>", "Sendable<int>",
                "transport.NetworkCodec<int, JsonWire>",
                "transport.AnyCallable", "transport.ActorRef<int>",
                "Option<AnyCallable>", "Result<int, AnyCallable>",
                "Option<int>", "Result<int, String>",
                "Future<int>", "Stream<int>", "RegionTransfer<int>",
                "UnverifiedRemoteValue"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "actor fnc transmit(" + type + " value): void { return; }")), type);
            assertNotNull(error.getMessage(), type);
        }
    }

    @Test
    void mailboxPayloadMustNotContainAnErasedCallableOrAuthority() {
        for (String type : new String[] {
                "AnyCallable", "List<AnyCallable>", "ActorRef<int>",
                "NetworkCodec<int, JsonWire>", "Option<AnyCallable>"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Receiver as
                              receive(ActorMail<%s> mail): void { return; }
                            end
                            """.formatted(type))), type);
        }
    }

    @Test
    void scalarsStillPassWhileErasedCallablesStayLocal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc apply(AnyCallable value): void { return; }
                fnc noOp(): void { return; }
                fnc local(): void {
                  apply(noOp);
                  return;
                }
                define actor Receiver as
                  receive(ActorMail<String> mail): void { return; }
                end
                """)));
    }
}
