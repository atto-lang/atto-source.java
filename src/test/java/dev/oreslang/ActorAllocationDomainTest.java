package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorAllocationDomainTest {

    private static final class DomainProbe extends RuntimeException {
        private final ActorRuntime.AllocationDomain domain;

        private DomainProbe(ActorRuntime.AllocationDomain domain) {
            super(domain.toString(), null, false, false);
            this.domain = domain;
        }
    }

    @Test
    void processAndExplicitSharedDomainsAreRuntimeScopedAndNotActorOwned() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertTrue(ActorRuntime.currentActorAllocationDomain().isEmpty());

            ActorRuntime.AllocationDomain process = runtime.processAllocationDomain();
            ActorRuntime.AllocationDomain shared = runtime.sharedAllocationDomain();

            assertEquals(runtime.runtimeId(), process.runtimeId());
            assertEquals(runtime.runtimeId(), shared.runtimeId());
            assertEquals(ActorRuntime.AllocationDomainKind.PROCESS, process.kind());
            assertEquals(ActorRuntime.AllocationDomainKind.RUNTIME_SHARED, shared.kind());
            assertFalse(process.actorOwned());
            assertFalse(shared.actorOwned());
            assertNull(process.actorId());
            assertNull(shared.actorId());
        }
    }

    @Test
    void actorKindsMapToStableSemanticAllocationDomains() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.<String>spawnShared(context -> (message, turn) -> {
                throw new DomainProbe(ActorRuntime.currentActorAllocationDomain().orElseThrow());
            });
            var isolated = runtime.<String>spawnPrivate(context -> (message, turn) -> {
                throw new DomainProbe(ActorRuntime.currentActorAllocationDomain().orElseThrow());
            });
            var untrusted = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                throw new DomainProbe(ActorRuntime.currentActorAllocationDomain().orElseThrow());
            });

            shared.send("probe");
            isolated.send("probe");
            untrusted.send("probe");

            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(isolated.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(untrusted.awaitTermination(2, TimeUnit.SECONDS));

            ActorRuntime.AllocationDomain sharedDomain = probe(shared);
            ActorRuntime.AllocationDomain isolatedDomain = probe(isolated);
            ActorRuntime.AllocationDomain untrustedDomain = probe(untrusted);

            assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_LOCAL, sharedDomain.kind());
            assertEquals(ActorRuntime.ActorKind.SHARED, sharedDomain.actorKind());
            assertEquals(shared.id(), sharedDomain.actorId());

            assertEquals(ActorRuntime.AllocationDomainKind.ACTOR_PRIVATE, isolatedDomain.kind());
            assertEquals(ActorRuntime.ActorKind.PRIVATE, isolatedDomain.actorKind());
            assertEquals(isolated.id(), isolatedDomain.actorId());

            assertEquals(ActorRuntime.AllocationDomainKind.UNTRUSTED_ISOLATE, untrustedDomain.kind());
            assertEquals(ActorRuntime.ActorKind.UNTRUSTED, untrustedDomain.actorKind());
            assertEquals(untrusted.id(), untrustedDomain.actorId());

            assertEquals(runtime.runtimeId(), sharedDomain.runtimeId());
            assertEquals(runtime.runtimeId(), isolatedDomain.runtimeId());
            assertEquals(runtime.runtimeId(), untrustedDomain.runtimeId());

            assertNotEquals(sharedDomain, isolatedDomain);
            assertNotEquals(sharedDomain, untrustedDomain);
            assertNotEquals(isolatedDomain, untrustedDomain);

            // Actor identity, not worker-thread identity, defines provenance.
            assertEquals(sharedDomain, sharedDomain);
            assertTrue(sharedDomain.actorOwned());
            assertTrue(isolatedDomain.actorOwned());
            assertTrue(untrustedDomain.actorOwned());
        }
    }

    @Test
    void anotherRuntimeCannotMistakeCurrentActorForItsOwnDomain() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var actor = owner.<String>spawnShared(context -> (message, turn) -> {
                ActorRuntime.AllocationDomain ownerDomain = owner.currentAllocationDomain();
                ActorRuntime.AllocationDomain foreignView = foreign.currentAllocationDomain();
                if (ownerDomain.kind() != ActorRuntime.AllocationDomainKind.ACTOR_LOCAL) {
                    throw new AssertionError("owner lost actor-local domain");
                }
                if (foreignView.kind() != ActorRuntime.AllocationDomainKind.PROCESS) {
                    throw new AssertionError("foreign runtime adopted another runtime's actor context");
                }
                throw new DomainProbe(ownerDomain);
            });

            actor.send("probe");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            ActorRuntime.AllocationDomain domain = probe(actor);
            assertEquals(owner.runtimeId(), domain.runtimeId());
            assertNotEquals(foreign.runtimeId(), domain.runtimeId());
        }
    }

    private static ActorRuntime.AllocationDomain probe(ActorRuntime.ActorRef<?> ref) {
        Throwable failure = ref.failure().orElseThrow();
        assertInstanceOf(DomainProbe.class, failure);
        return ((DomainProbe) failure).domain;
    }
}
