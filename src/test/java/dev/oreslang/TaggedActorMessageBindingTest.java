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
    void anyReorderingOrTagChangeBreaksWireCompatibility() {
        var original = binding();
        var reordered = TaggedActorMessageBinding.fromRegistry(
                TaggedMessageContracts.verify(Parser.parse(SOURCE.replace(
                    "Status.Message2\n                Status.Message3",
                    "Status.Message3\n                Status.Message2"))),
                "WorkerMessages");
        assertNotEquals(original.fingerprint(), reordered.fingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> reordered.decode(original.encode("Status.Message2", "x")));
        var retagged = TaggedActorMessageBinding.fromRegistry(
                TaggedMessageContracts.verify(Parser.parse(SOURCE.replace(
                        "Message2: string = 2", "Message2: string = 10"))), "WorkerMessages");
        assertThrows(IllegalArgumentException.class,
                () -> retagged.decode(original.encode("Status.Message2", "x")));
    }

    @Test
    void valuelessEnumCaseFailsClosedUntilUnitCodecExists() {
        var registry = TaggedMessageContracts.verify(Parser.parse("""
                define enum Flags as
                    Ping = 1
                end
                define pattern All as
                    Flags.Ping
                end
                """));
        assertThrows(IllegalArgumentException.class,
                () -> TaggedActorMessageBinding.fromRegistry(registry, "All"));
    }
}
