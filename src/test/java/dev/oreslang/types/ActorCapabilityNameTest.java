package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Capability gate must operate on the resolved type identity, not merely
 * the source spelling. The same helper is used for mailbox admission and
 * recursive shared-safety checks.
 */
final class ActorCapabilityNameTest {
    @Test void sourceAndLinkerNamespaceDelimitersCannotHideHandles() {
        assertEquals("Pointer", TypeChecker.actorCapabilitySimpleName("Pointer"));
        assertEquals("Pointer", TypeChecker.actorCapabilitySimpleName("Codec.Pointer"));
        assertEquals("Pointer", TypeChecker.actorCapabilitySimpleName("Codec::Pointer"));
        assertEquals("ActorRef", TypeChecker.actorCapabilitySimpleName("actor::wire.ActorRef"));
        assertEquals("NetworkCodec", TypeChecker.actorCapabilitySimpleName("net::NetworkCodec"));
        assertEquals("SupervisorControlCodec",
                TypeChecker.actorCapabilitySimpleName("internal::SupervisorControlCodec"));
        assertEquals("Stream", TypeChecker.actorCapabilitySimpleName("stream.Stream"));
    }

    @Test void unrelatedDataTypesAreNotRewrittenIntoCapabilities() {
        assertEquals("MessageData", TypeChecker.actorCapabilitySimpleName("schema::MessageData"));
        assertEquals("NetworkCodecData",
                TypeChecker.actorCapabilitySimpleName("schema.NetworkCodecData"));
        assertEquals("Result", TypeChecker.actorCapabilitySimpleName("core::Result"));
    }
}
