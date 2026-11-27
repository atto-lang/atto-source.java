package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Qualified nominal authority must not be treated as ordinary actor DATA. */
final class ActorCapabilityNameTest {
    @Test void moduleAndLinkerPrefixesNeverConcealCapabilityKind() {
        assertEquals("NetworkCodec", TypeChecker.actorCapabilitySimpleName("NetworkCodec"));
        assertEquals("NetworkCodec", TypeChecker.actorCapabilitySimpleName("wire.NetworkCodec"));
        assertEquals("ActorMessageCodec", TypeChecker.actorCapabilitySimpleName("wire::ActorMessageCodec"));
        assertEquals("Pointer", TypeChecker.actorCapabilitySimpleName("pkg::memory.Pointer"));
        assertEquals("ActorRef", TypeChecker.actorCapabilitySimpleName("runtime.actor.ActorRef"));
        assertEquals("SupervisorControlCodec",
            TypeChecker.actorCapabilitySimpleName("runtime::SupervisorControlCodec"));
        assertEquals("SharedMutex", TypeChecker.actorCapabilitySimpleName("concurrent.SharedMutex"));
        assertEquals("RegionTransfer", TypeChecker.actorCapabilitySimpleName("storage.RegionTransfer"));
    }

    @Test void OrdinaryDataNamesAreNotTruncatedIntoCapabilities() {
        assertEquals("NetworkCodecData", TypeChecker.actorCapabilitySimpleName("wire.NetworkCodecData"));
        assertEquals("ActorRefSnapshot", TypeChecker.actorCapabilitySimpleName("data::ActorRefSnapshot"));
        assertEquals("MessageData", TypeChecker.actorCapabilitySimpleName("schema.MessageData"));
    }
}
