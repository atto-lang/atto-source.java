package dev.oreslang.types;

import dev.oreslang.types.PortableCodecRegistry.Descriptor;
import dev.oreslang.types.PortableCodecRegistry.Field;
import dev.oreslang.types.PortableCodecRegistry.Format;
import dev.oreslang.types.PortableCodecRegistry.ObjectShape;
import dev.oreslang.types.PortableCodecRegistry.Purpose;
import dev.oreslang.types.PortableCodecRegistry.Registry;
import dev.oreslang.types.PortableCodecRegistry.Scalar;
import dev.oreslang.types.PortableCodecRegistry.ScalarKind;
import dev.oreslang.types.PortableCodecRegistry.Sequence;
import dev.oreslang.types.PortableCodecRegistry.SequenceKind;
import dev.oreslang.types.PortableCodecRegistry.Shape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Codec descriptor tests: schema registry is NOT a runtime serialization test. */
final class PortableCodecRegistryTest {
    private static Scalar utf8() { return new Scalar(ScalarKind.UTF8); }
    private static Scalar integer() { return new Scalar(ScalarKind.I64); }
    private static Field jsonField(String name, Shape shape) {
        return new Field(name, 0, shape);
    }

    @Test
    void jsonFieldOrderDoesNotChangeCanonicalDescriptor() {
        Registry first = new Registry();
        Descriptor a = first.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                "app.User", "app.UserWire", 1, new ObjectShape(List.of(
                        jsonField("name", utf8()), jsonField("age", integer()))));
        Registry second = new Registry();
        Descriptor b = second.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                "app.User", "app.UserWire", 1, new ObjectShape(List.of(
                        jsonField("age", integer()), jsonField("name", utf8()))));
        assertEquals(a.canonicalShape(), b.canonicalShape());
        assertEquals(a.fingerprintHex(), b.fingerprintHex());
        assertEquals(64, a.fingerprintHex().length());
        assertSame(a, first.requirePinned(Format.JSON, Purpose.NETWORK_DATA, "app.User", 1));
        assertSame(a, first.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                "app.User", "app.UserWire", 1, new ObjectShape(List.of(
                        jsonField("age", integer()), jsonField("name", utf8()))));
        assertEquals(1, first.size());
    }

    @Test
    void schemaIdentityCannotBeReusedForDifferentTypeOrShape() {
        Registry registry = new Registry();
        registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                "app.User", "app.UserWire", 1, utf8());
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.Other", "app.UserWire", 1, utf8()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.User", "app.OtherWire", 1, utf8()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.User", "app.UserWire", 1, integer()));
        assertEquals(1, registry.size());
        assertThrows(IllegalArgumentException.class,
                () -> registry.requirePinned(Format.JSON, Purpose.NETWORK_DATA, "app.Other", 1));
    }

    @Test
    void formatVersionAndPurposeAreDomainSeparated() {
        Registry registry = new Registry();
        Descriptor net = registry.registerCompilerDescriptor(Format.MESSAGEPACK_V2,
                Purpose.NETWORK_DATA, "app.Value", "app.ValueWire", 1, integer());
        Descriptor actor = registry.registerCompilerDescriptor(Format.MESSAGEPACK_V2,
                Purpose.ACTOR_DATA, "app.Value", "app.ValueWire", 1, integer());
        Descriptor v2 = registry.registerCompilerDescriptor(Format.MESSAGEPACK_V2,
                Purpose.NETWORK_DATA, "app.Value", "app.ValueWire", 2, integer());
        assertNotEquals(net.fingerprintHex(), actor.fingerprintHex());
        assertNotEquals(net.fingerprintHex(), v2.fingerprintHex());
        assertNotSame(net, actor);
        assertEquals(3, registry.size());
    }

    @Test
    void actorDataCannotBeRegisteredAsJsonOrProtobuf() {
        Registry registry = new Registry();
        for (Format format : new Format[]{Format.JSON, Format.PROTOBUF}) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.registerCompilerDescriptor(format, Purpose.ACTOR_DATA,
                            "app.Payload", "app.PayloadWire", 1, integer()));
        }
        assertEquals(0, registry.size());
    }

    @Test
    void unsupportedWireKindsDoNotAcquireImplicitCodecs() {
        Registry registry = new Registry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.MESSAGEPACK_V2,
                        Purpose.ACTOR_DATA, "app.Payload", "app.PayloadWire", 1,
                        new ObjectShape(List.of(jsonField("foo", integer())))));
        for (Format format : new Format[]{Format.JSON, Format.MESSAGEPACK_V2}) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.registerCompilerDescriptor(format, Purpose.NETWORK_DATA,
                            "app.Payload", "app.PayloadWire", 1, new Scalar(ScalarKind.BYTES)));
        }
        assertEquals(0, registry.size());
    }

    @Test
    void protobufFieldTagsAreBoundToSchema() {
        Registry registry = new Registry();
        ObjectShape valid = new ObjectShape(List.of(
                new Field("code", 1, integer()),
                new Field("name", 2, utf8()),
                new Field("blob", 3, new Scalar(ScalarKind.BYTES))));
        Descriptor desc = registry.registerCompilerDescriptor(Format.PROTOBUF,
                Purpose.NETWORK_DATA, "app.Packet", "app.PacketProto", 1, valid);
        assertEquals(Format.PROTOBUF, desc.format());
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.PROTOBUF,
                        Purpose.NETWORK_DATA, "app.Packet", "app.PacketProto", 1,
                        new ObjectShape(List.of(new Field("code", 2, integer())))));
        for (int badTag : new int[]{0, -1, 19_000, 19_999, 536_870_912}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Registry().registerCompilerDescriptor(Format.PROTOBUF,
                            Purpose.NETWORK_DATA, "app.Bad", "app.BadProto", 1,
                            new ObjectShape(List.of(new Field("bad", badTag, integer())))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Registry().registerCompilerDescriptor(Format.PROTOBUF,
                        Purpose.NETWORK_DATA, "app.Bad", "app.BadProto", 1,
                        new ObjectShape(List.of(new Field("a", 1, integer()),
                                new Field("b", 1, utf8())))));
        assertThrows(IllegalArgumentException.class,
                () -> new Registry().registerCompilerDescriptor(Format.PROTOBUF,
                        Purpose.NETWORK_DATA, "app.Bad", "app.BadProto", 1, utf8()));
    }

    @Test
    void rejectsDuplicateKeysUnqualifiedIdsAndInvalidShapeDepth() {
        Registry registry = new Registry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.Data", "app.DataWire", 1, new ObjectShape(List.of(
                                jsonField("same", utf8()), jsonField("same", integer())))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "Data", "app.DataWire", 1, integer()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.Data", "app.DataWire", 0, integer()));

        Shape nested = integer();
        for (int i = 0; i < 66; i++) {
            nested = new Sequence(SequenceKind.LIST, nested, -1);
        }
        Shape oversized = nested;
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                        "app.Deep", "app.DeepWire", 1, oversized));
        assertEquals(0, registry.size());
    }

    @Test
    void fixedSequenceIsExplicitAndNotAccidentallyProtobufRepeated() {
        Shape fixed = new Sequence(SequenceKind.FIXED_ARRAY, integer(), 3);
        Registry registry = new Registry();
        Descriptor json = registry.registerCompilerDescriptor(Format.JSON,
                Purpose.NETWORK_DATA, "app.Triple", "app.TripleWire", 1, fixed);
        assertTrue(json.canonicalShape().contains("FIXED_ARRAY,3"));
        assertThrows(IllegalArgumentException.class,
                () -> new Sequence(SequenceKind.LIST, integer(), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Registry().registerCompilerDescriptor(Format.PROTOBUF,
                        Purpose.NETWORK_DATA, "app.Bad", "app.BadProto", 1,
                        new ObjectShape(List.of(new Field("nums", 1, fixed)))));
    }

    @Test
    void concurrentRegistrationPublishesOneCoherentSchema() throws Exception {
        Registry registry = new Registry();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Descriptor>> tasks = java.util.stream.IntStream.range(0, 24)
                    .<Callable<Descriptor>>mapToObj(i -> () ->
                            registry.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                                    "app.User", "app.UserWire", 1,
                                    new ObjectShape(List.of(jsonField("name", utf8()),
                                            jsonField("age", integer())))))
                    .toList();
            List<Future<Descriptor>> results = pool.invokeAll(tasks);
            Descriptor initial = results.getFirst().get();
            for (Future<Descriptor> result : results) {
                assertSame(initial, result.get());
            }
            assertEquals(1, registry.size());
        }
    }

    @Test
    void differentCompilerInstancesNeverShareMutableDescriptorTables() {
        Registry first = new Registry();
        Registry second = new Registry();
        first.registerCompilerDescriptor(Format.JSON, Purpose.NETWORK_DATA,
                "app.User", "app.UserWire", 1, utf8());
        assertEquals(1, first.size());
        assertEquals(0, second.size());
        assertThrows(IllegalArgumentException.class,
                () -> second.requirePinned(Format.JSON, Purpose.NETWORK_DATA, "app.User", 1));
    }

}
