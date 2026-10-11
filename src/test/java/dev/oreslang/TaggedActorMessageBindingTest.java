package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TaggedActorMessageBinding;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class TaggedActorMessageBindingTest {
    private static final String SOURCE = """
            define enum Status as
                Message1: Tuple[bool, string] = 1
                Message2: string = 2
                Message3: List[int] = 3
                AliasMessage: string = 4
            end

            define pattern WorkerMessages as
                Status.Message1
                Status.Message2
                Status.Message3
                Status.AliasMessage
            end
            """;

    private static TaggedActorMessageBinding binding() {
        return TaggedActorMessageBinding.fromRegistry(
                TaggedMessageContracts.verify(Parser.parse(SOURCE)), "WorkerMessages");
    }

    @Test
    void sourceEnumAndPatternProduceClosedTypedWireContract() {
        var compiled = binding();
        assertEquals(4, compiled.variantCount());
        assertEquals(List.of("Status.Message1", "Status.Message2", "Status.Message3",
                "Status.AliasMessage"), compiled.alternatives());

        assertEquals(new TaggedActorMessageBinding.TaggedMessage(
                        "Status.Message1", 1, List.of(true, "foo")),
                compiled.decode(compiled.encode("Status.Message1", List.of(true, "foo"))));
        assertEquals(new TaggedActorMessageBinding.TaggedMessage(
                        "Status.Message2", 2, "hi"),
                compiled.decode(compiled.encode("Status.Message2", "hi")));
        assertEquals(new TaggedActorMessageBinding.TaggedMessage(
                        "Status.Message3", 3, List.of(1L, 2L)),
                compiled.decode(compiled.encode("Status.Message3", List.of(1L, 2L))));
        // Message2 and AliasMessage share the exact same string payload
        // shape but remain distinguishable nominal enum alternatives.
        assertEquals("Status.AliasMessage",
                compiled.decode(compiled.encode("Status.AliasMessage", "hi")).qualifiedCase());
        assertNotEquals(compiled.decode(compiled.encode("Status.Message2", "hi")).qualifiedCase(),
                compiled.decode(compiled.encode("Status.AliasMessage", "hi")).qualifiedCase());
    }

    @Test
    void compilerAdapterIsExactlyByteCompatibleWithProductionTaggedCodec() {
        var registry = TaggedMessageContracts.verify(Parser.parse(SOURCE));
        var compiled = TaggedActorMessageBinding.fromRegistry(registry, "WorkerMessages");
        var production = new dev.oreslang.runtime.TaggedMessageCodec(
                registry.requireDomain("WorkerMessages"));
        byte[] expected = production.encode(
                new dev.oreslang.runtime.TaggedMessageCodec.TaggedValue(
                        "Status", "Message2", 2, "portable"));
        assertArrayEquals(expected, compiled.encode("Status.Message2", "portable"));
        var decoded = compiled.decode(expected);
        assertEquals("Status.Message2", decoded.qualifiedCase());
    }

    @Test
    void rejectsWrongShapesUnlistedVariantsAndTamperedMessages() {
        var compiled = binding();
        assertThrows(IllegalArgumentException.class,
                () -> compiled.encode("Status.NotDeclared", "payload"));
        assertThrows(IllegalArgumentException.class,
                () -> compiled.encode("Status.Message3", List.of("not an int")));
        byte[] wire = compiled.encode("Status.Message2", "hello");
        byte[] overwritten = wire.clone();
        Arrays.fill(overwritten, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> compiled.decode(overwritten));
        // Decoder returns an immutable value detached from caller wire memory.
        var received = compiled.decode(wire);
        Arrays.fill(wire, (byte) 0);
        assertEquals("hello", received.payload());
    }

    @Test
    void reorderIsNominallyCompatibleButRetaggingIsNot() {
        var original = binding();
        var reordered = TaggedActorMessageBinding.fromRegistry(
                TaggedMessageContracts.verify(Parser.parse(SOURCE
                        .replace("Status.Message2", "Status.__TEMP__")
                        .replace("Status.Message3", "Status.Message2")
                        .replace("Status.__TEMP__", "Status.Message3"))),
                "WorkerMessages");
        assertEquals(
                new TaggedActorMessageBinding.TaggedMessage("Status.Message2", 2, "x"),
                reordered.decode(original.encode("Status.Message2", "x")));
        var retagged = TaggedActorMessageBinding.fromRegistry(
                TaggedMessageContracts.verify(Parser.parse(SOURCE.replace(
                        "AliasMessage: string = 4", "AliasMessage: string = 10"))), "WorkerMessages");
        assertThrows(IllegalArgumentException.class,
                () -> retagged.decode(original.encode("Status.AliasMessage", "x")));
    }

    @Test
    void valuelessEnumCaseUsesCanonicalUnitTupleCodec() {
        var registry = TaggedMessageContracts.verify(Parser.parse("""
                define enum Flags as
                    Ping = 1
                end
                define pattern All as
                    Flags.Ping
                end
                """));
        var compiled = TaggedActorMessageBinding.fromRegistry(registry, "All");
        assertEquals(new TaggedActorMessageBinding.TaggedMessage("Flags.Ping", 1, null),
                compiled.decode(compiled.encode("Flags.Ping", null)));
    }

    @Test
    void portableSourceBindingRejectsNativeOverLimitWithoutReducingJavaCodecCapacity() {
        var alternatives = new java.util.ArrayList<TaggedMessageContracts.Case>();
        for (int tag = 1; tag <= 65; tag++) {
            alternatives.add(new TaggedMessageContracts.Case(
                    "Many", "Case" + tag, tag,
                    dev.oreslang.ast.Ast.TypeRef.simple("string"),
                    dev.oreslang.runtime.MessagePackCollectionCodec.string()));
        }
        var registry = new TaggedMessageContracts.Registry(
                java.util.Map.of(), java.util.Map.of(
                        "Many", new TaggedMessageContracts.Domain("Many", alternatives)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TaggedActorMessageBinding.fromRegistry(registry, "Many"));
        assertTrue(error.getMessage().contains("1..64"), error.getMessage());

        var portable = new TaggedMessageContracts.Registry(
                java.util.Map.of(), java.util.Map.of("Many",
                        new TaggedMessageContracts.Domain("Many", alternatives.subList(0, 64))));
        var binding = TaggedActorMessageBinding.fromRegistry(portable, "Many");
        assertEquals(64, binding.variantCount());
        // Java's independent generic tagged codec may still support larger
        // host-only domains; the source actor binding must not imply LLVM parity.
        var javaOnly = new dev.oreslang.runtime.TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Many", alternatives));
        var value = new dev.oreslang.runtime.TaggedMessageCodec.TaggedValue(
                "Many", "Case65", 65, "java-host-only");
        assertEquals(value, javaOnly.decode(javaOnly.encode(value)));
    }

}
