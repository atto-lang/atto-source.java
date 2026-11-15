package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.oreslang.runtime.ActorPipelineContract.*;
import static org.junit.jupiter.api.Assertions.*;

final class ActorPipelineContractTest {
    private static Stage read(String name, String type) {
        return new Stage(name, Role.READABLE, null, type);
    }
    private static Stage transform(String name, String in, String out) {
        return new Stage(name, Role.TRANSFORM, in, out);
    }
    private static Stage write(String name, String in) {
        return new Stage(name, Role.WRITABLE, in, null);
    }

    @Test void admitsTypedSourceTransformSink() {
        var links = validate(List.of(
                read("A1", "Event"), transform("A2", "Event", "Result"),
                write("A3", "Result")));
        assertEquals(2, links.size());
        assertEquals("Event", links.get(0).payloadType());
        assertEquals("Result", links.get(1).payloadType());
    }

    @Test void rejectsSupervisorAndOrdinaryActorsInAnyPosition() {
        for (int at = 0; at < 3; at++) {
            var normal = new java.util.ArrayList<>(List.of(
                    read("A1", "int"), transform("A2", "int", "int"), write("A3", "int")));
            normal.set(at, new Stage("Daddy", Role.SUPERVISOR_ACTOR, "int", "int"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> validate(normal)).getMessage().contains("supervisor"));
            normal.set(at, new Stage("Actor", Role.ORDINARY_ACTOR, "int", "int"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> validate(normal)).getMessage().contains("ordinary"));
        }
    }

    @Test void rejectsWrongOrderingAndPayloadMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(List.of(write("Sink", "int"), write("Sink2", "int"))));
        assertThrows(IllegalArgumentException.class,
                () -> validate(List.of(read("S1", "int"), read("S2", "int"), write("S3", "int"))));
        assertThrows(IllegalArgumentException.class,
                () -> validate(List.of(read("S1", "int"), transform("T", "string", "string"),
                        write("Sink", "string"))));
        assertThrows(IllegalArgumentException.class,
                () -> validate(List.of(read("S1", "int"))));
    }

    @Test void explicitDuplexCanActAsBidirectionalMiddleOrEndpoint() {
        var duplex = new Stage("D", Role.DUPLEX, "int", "int");
        assertEquals(2, validate(List.of(
                read("Source", "int"), duplex, write("Sink", "int"))).size());
        assertEquals(1, validate(List.of(
                duplex, write("Sink", "int"))).size());
    }

    @Test void validPipelinesAndSingleMutationAreIndependentlyChecked() {
        // Deterministic admission oracle: 256 positive multi-stage pipelines,
        // each paired with distinct negative payload and forbidden-role edits.
        var random = new java.util.Random(20261025L);
        String[] names = {"int", "string", "Event", "Result", "Packet"};
        for (int iteration = 0; iteration < 256; iteration++) {
            int n = 2 + random.nextInt(6);
            var chain = new java.util.ArrayList<Stage>();
            String previousOut = null;
            for (int index = 0; index < n; index++) {
                Role role = index == 0
                        ? (random.nextBoolean() ? Role.READABLE : Role.DUPLEX)
                        : index == n - 1
                        ? (random.nextBoolean() ? Role.WRITABLE : Role.DUPLEX)
                        : (random.nextBoolean() ? Role.TRANSFORM : Role.DUPLEX);
                String inbound = index == 0
                        ? (role == Role.DUPLEX ? names[random.nextInt(names.length)] : null)
                        : previousOut;
                String outbound = role == Role.WRITABLE ? null
                        : names[random.nextInt(names.length)];
                chain.add(new Stage("Actor" + index, role, inbound, outbound));
                previousOut = outbound;
            }
            var connections = validate(chain);
            assertEquals(n - 1, connections.size());
            for (int edge = 0; edge < connections.size(); edge++)
                assertEquals(chain.get(edge).outboundType(), connections.get(edge).payloadType());

            var wrongType = new java.util.ArrayList<>(chain);
            Stage sink = wrongType.get(n - 1);
            wrongType.set(n - 1, new Stage(sink.actorType(), sink.role(),
                    "INVALID_PAYLOAD", sink.outboundType()));
            assertThrows(IllegalArgumentException.class, () -> validate(wrongType));

            var forbidden = new java.util.ArrayList<>(chain);
            forbidden.set(random.nextInt(n), new Stage("Forbidden",
                    Role.SUPERVISOR_ACTOR, null, null));
            assertThrows(IllegalArgumentException.class, () -> validate(forbidden));
        }
    }

    @Test void roleConstructorsCannotDeclareAmbiguousDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Source", Role.READABLE, "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Sink", Role.WRITABLE, "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Transform", Role.TRANSFORM, "int", null));
    }
}
