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

    @Test void roleConstructorsCannotDeclareAmbiguousDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Source", Role.READABLE, "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Sink", Role.WRITABLE, "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> new Stage("Transform", Role.TRANSFORM, "int", null));
    }
}
