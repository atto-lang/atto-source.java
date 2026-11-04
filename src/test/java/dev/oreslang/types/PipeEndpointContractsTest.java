package dev.oreslang.types;

import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Unknown;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PipeEndpointContractsTest {
    @Test
    void existingActorLinksChooseAuthenticatedTransportWithoutABridge() {
        var a = PipeEndpointContracts.authenticatedActor(Primitive.INT);
        var b = PipeEndpointContracts.authenticatedActor(Primitive.INT);
        var link = PipeEndpointContracts.connect(a, b);
        assertEquals(PipeEndpointContracts.Transport.AUTHENTICATED_ACTOR, link.transport());
        assertEquals(Primitive.INT, link.payload());
        assertDoesNotThrow(() -> PipeEndpointContracts.requireExecutable(link));
    }

    @Test
    void heterogeneousTransformChecksEachAdjacentPortIndependently() {
        var bytes = new Named("Bytes", List.of());
        var decoded = new Named("DecodedMessage", List.of());
        var source = PipeEndpointContracts.source(bytes);
        var transform = PipeEndpointContracts.transform(bytes, decoded);
        var sink = PipeEndpointContracts.sink(decoded);
        assertEquals(bytes, PipeEndpointContracts.connect(source, transform).payload());
        assertEquals(decoded, PipeEndpointContracts.connect(transform, sink).payload());
        assertEquals(PipeEndpointContracts.Transport.BRIDGE_REQUIRED,
                PipeEndpointContracts.connect(source, transform).transport());
        var blocked = assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.requireExecutable(
                        PipeEndpointContracts.connect(transform, sink)));
        assertTrue(blocked.getMessage().contains("requires bounded stream/channel bridge"));
    }

    @Test
    void inputOnlyCannotActAsSourceAndOutputOnlyCannotActAsSink() {
        var sink = PipeEndpointContracts.sink(Primitive.INT);
        var source = PipeEndpointContracts.source(Primitive.INT);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.connect(sink, source))
                .getMessage().contains("no output channel"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.connect(source, source))
                .getMessage().contains("no input channel"));
    }

    @Test
    void actorDomainMustMatchExactlyNotOnlyBeAssignable() {
        var a = new Record(Map.of("id", Primitive.INT), false);
        var b = new Record(Map.of("id", Primitive.INT), true);
        // Record readonly views may be otherwise structurally assignable,
        // but they do not attest an identical actor mailbox ABI.
        var denied = assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.requireAuthenticatedActorConnection(a, b));
        assertTrue(denied.getMessage().contains("ActorMail domains must match exactly"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.requireAuthenticatedActorConnection(
                        Primitive.INT, Primitive.FLOAT))
                .getMessage().contains("must match exactly"));
    }

    @Test
    void unresolvedNestedOrBorrowedPayloadsAreRejectedBeforeConnection() {
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.source(Unknown.INSTANCE));
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.source(new Named("List",
                        List.of(Unknown.INSTANCE))));
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.source(new Borrow(Primitive.INT, false)));
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.sink(Primitive.VOID));
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.source(new ClassNamespace("Utility")));
    }

    @Test
    void repeatedNestedReferencesCannotBypassTraversalBudget() {
        // Identity-based visited accounting alone did not constrain the
        // number of duplicate references queued by one large generic type.
        var repeated = java.util.Collections.nCopies(1025, Primitive.INT);
        var denied = assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.source(new Named("Wide", repeated)));
        assertTrue(denied.getMessage().contains("complexity budget"));
    }

    @Test
    void userFacingEndpointKindCannotMintAuthenticatedActorAuthority() {
        var input = PipeEndpointContracts.transform(Primitive.INT, Primitive.INT);
        var actor = PipeEndpointContracts.authenticatedActor(Primitive.INT);
        var link = PipeEndpointContracts.connect(input, actor);
        assertEquals(PipeEndpointContracts.Transport.BRIDGE_REQUIRED, link.transport());
        assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.requireExecutable(link));
    }

    @Test
    void invalidDeclaredRolesAreRejectedBeforeAnyRoutePublication() {
        assertThrows(IllegalArgumentException.class,
                () -> new PipeEndpointContracts.Endpoint(
                        Primitive.INT, null, PipeEndpointContracts.Role.SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> new PipeEndpointContracts.Endpoint(
                        Primitive.INT, Primitive.STRING,
                        PipeEndpointContracts.Role.AUTHENTICATED_ACTOR));
    }
    @Test
    void pubSubAndDirectionalCapabilitiesCannotTravelAsPipeData() {
        for (String name : List.of(
                "Topic", "Subscription", "TopicPublisher", "TopicSubscriber",
                "ActorGroup", "ActorStream", "Channel", "ReadChannel",
                "WriteChannel", "HasInputChannel", "HasOutputChannel")) {
            var capability = new Named(name, List.of(Primitive.INT));
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> PipeEndpointContracts.authenticatedActor(capability),
                    "actor DATA must not implicitly transport capability " + name);
            assertTrue(rejected.getMessage().contains("capability"), rejected.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> PipeEndpointContracts.transform(Primitive.INT, capability));
        }
    }

    @Test
    void nestedPubSubCapabilitiesAreRejectedBeforeGraphPublication() {
        var topic = new Named("Topic", List.of(Primitive.STRING));
        var nested = new Named("Envelope", List.of(
                new Record(Map.of("meta", Primitive.INT, "topic", topic), false)));
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> PipeEndpointContracts.authenticatedActor(nested));
        assertTrue(rejected.getMessage().contains("capability"));

        var ordinary = new Named("Envelope", List.of(
                new Record(Map.of("meta", Primitive.INT,
                        "payload", Primitive.STRING), false)));
        assertDoesNotThrow(() -> PipeEndpointContracts.requireAuthenticatedActorConnection(
                ordinary, ordinary),
                "data-only immutable payloads remain valid actor mail domains");
    }

    @Test
    void existingAuthenticatedActorRefDataIsNotBannedByNameHeuristic() {
        // ActorRef is independently governed by Sendable rules. This
        // endpoint-only guard must not accidentally revoke it.
        var actorRef = new Named("ActorRef", List.of(
                new Named("Worker", List.of())));
        assertDoesNotThrow(() -> PipeEndpointContracts.authenticatedActor(actorRef));
    }

}
