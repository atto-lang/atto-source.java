package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.TaggedMessageCodec;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TaggedMessageContractTest {
    private static final String DECLARATIONS = """
            define enum Status as
                Message1: Tuple[bool, string] = 1
                Message2: string = 2
                Message3: FixedArray[string, string, string] = 3
                Ready = 4
            end

            define pattern Messages as
                Status.Message1
                Status.Message2
                Status.Message3
            end
            """;

    @Test
    void parserPreservesTagsShapesAndImplicitNewlineAlternatives() {
        Ast.Program program = Parser.parse(DECLARATIONS);
        var registry = TaggedMessageContracts.verify(program);
        assertEquals(List.of(1, 2, 3, 4),
                registry.enums().get("Status").cases().stream()
                        .map(TaggedMessageContracts.Case::tag).toList());
        assertEquals(3, registry.patterns().get("Messages").cases().size());
        assertEquals("Status.Message2",
                registry.patterns().get("Messages").cases().get(1).qualifiedName());
    }

    @Test
    void defaultEnumTagsIncrementAndExplicitTagsCanReserveGaps() {
        var program = Parser.parse("""
                define enum Codes as
                    First
                    Next
                    Third = 8
                    Fourth
                end
                """);
        assertEquals(List.of(1, 2, 8, 9),
                TaggedMessageContracts.verify(program).enums().get("Codes").cases()
                        .stream().map(TaggedMessageContracts.Case::tag).toList());
    }

    @Test
    void rejectsPipeUnknownCaseDuplicateTagsAndNonSerializablePayload() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define pattern Bad as
                    Status.Message1 | Status.Message2
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Bad as
                    A = 1
                    B = 1
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Bad as
                    Newer = 10
                    Older = 9
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> TaggedMessageContracts.verify(Parser.parse(
                DECLARATIONS + """
                    define pattern Other as
                        Status.Missing
                    end
                    """)));
        assertThrows(IllegalArgumentException.class, () -> TaggedMessageContracts.verify(Parser.parse("""
                define enum Bad as
                    Foo: ActorId = 1
                end
                """)));
    }

    @Test
    void closedMessagePackDecoderPreservesNominalIdentityAndRejectsWrongCase() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLARATIONS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Messages"));
        var original = new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello");
        byte[] encoded = codec.encode(original);
        assertEquals("9402d98f6f7265736c616e672e656e756d2e363461373862626130343533393535323739306336633537666561306630353337346338373864623836313134393130356261366631373332633837326539342e34626264346332316663303037643839636136386637663061396136313438666162313266376539313361353931636633626131383033333061353931383634c420085917a950bf1470f59d654358e5da6f1744d6eadd4c4128f334ddbb6e032ddb93a653746174757302a568656c6c6f",
                java.util.HexFormat.of().formatHex(encoded),
                "Java and native must produce byte-identical tagged MessagePack v2");
        assertEquals(original, codec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Status", "Ready", 4, null)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Status", "Message2", 3, "hello")));
        var narrower = new TaggedMessageCodec(new TaggedMessageContracts.Domain(
                "OnlyFirst", List.of(registry.requireCase("Status.Message1"))));
        assertThrows(IllegalArgumentException.class, () -> narrower.decode(encoded));
        // A shape digest is not a message authentication code: changing
        // one valid string to another valid string is allowed. Instead,
        // tamper the type-binding digest, which must always be rejected.
        byte[] alteredDigest = encoded.clone();
        boolean found = false;
        for (int i = 0; i < alteredDigest.length - 34; i++) {
            if ((alteredDigest[i] & 255) == 0xc4
                    && (alteredDigest[i + 1] & 255) == 32) {
                alteredDigest[i + 2] ^= 1;
                found = true;
                break;
            }
        }
        assertTrue(found);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(alteredDigest));
    }

    @Test
    void nominalCaseRejectsTagOrOwnerForgeryEvenWhenShapeDigestIsValid() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLARATIONS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Messages"));
        byte[] authentic = codec.encode(
                new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello"));
        int ownerStart = -1;
        byte[] owner = "Status".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 1; i + owner.length < authentic.length; i++) {
            if ((authentic[i - 1] & 255) != 0xa6) continue;
            boolean matches = true;
            for (int j = 0; j < owner.length; j++) {
                if (authentic[i + j] != owner[j]) { matches = false; break; }
            }
            if (matches) { ownerStart = i; break; }
        }
        assertTrue(ownerStart > 0);
        byte[] forgedOwner = authentic.clone();
        forgedOwner[ownerStart] = 'T'; // Still valid UTF-8 and shape S
        assertThrows(IllegalArgumentException.class, () -> codec.decode(forgedOwner));

        byte[] forgedTag = authentic.clone();
        assertEquals(2, forgedTag[ownerStart + owner.length]);
        forgedTag[ownerStart + owner.length] = 3; // Still a valid shape I
        assertThrows(IllegalArgumentException.class, () -> codec.decode(forgedTag));
        assertEquals(new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello"),
                     codec.decode(authentic));
    }

    @Test
    void typecheckerAcceptsCheckedVariantConstructionAndRejectsOtherDomainCases() {
        var valid = DECLARATIONS + """
                define actor Worker as
                    receive(ActorMail<Messages> mail): void {
                        self.end();
                        return;
                    }
                end

                pub async routine main(): void {
                    val a = await spawn Worker();
                    a.send("hello" as Status.Message2);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "a.send(\"hello\" as Status.Message2);",
                        "a.send(Status.Ready());"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "a.send(\"hello\" as Status.Message2);",
                        "a.send(7 as Status.Message2);"))));
    }
    @Test
    void compilerGeneratedLlvmIntegerCasesMatchJavaWireBytes() {
        var program = Parser.parse("""
                define enum Signals as
                    Ping = 1
                    Add: int = 2
                    Sub: int = 3
                end
                define pattern WorkerMessages as
                    Signals.Sub
                    Signals.Ping
                    Signals.Add
                end
                """);
        var codec = new TaggedMessageCodec(
                TaggedMessageContracts.verify(program).requireDomain("WorkerMessages"));
        var add = new TaggedMessageCodec.TaggedValue("Signals", "Add", 2, 19L);
        var sub = new TaggedMessageCodec.TaggedValue("Signals", "Sub", 3, 4L);
        // Exact immutable canonical vectors are consumed by the LLVM guest
        // codegen fixture. Both have shape T[S,I,I], yet different schemas.
        assertEquals("9402d98f6f7265736c616e672e656e756d2e393135616365616630616462333361333834366638356336343566616164626462343666363763306662303363613230383437633232376339313130343739632e36623038666232373863653432656430373134633162396339653836643464623032333930653832326464373464303239313834316439633138363732366562c420a6beba4faeb2ff46f0abfa2d43f23c0653129a9047a64e424bacc97fd5be855193a75369676e616c730213",
                java.util.HexFormat.of().formatHex(codec.encode(add)));
        assertEquals("9402d98f6f7265736c616e672e656e756d2e393135616365616630616462333361333834366638356336343566616164626462343666363763306662303363613230383437633232376339313130343739632e34383864373162356364633238303231626565376264623935393232383038663932613262393237363339613664393830393331663237666231633662653864c420a6beba4faeb2ff46f0abfa2d43f23c0653129a9047a64e424bacc97fd5be855193a75369676e616c730304",
                java.util.HexFormat.of().formatHex(codec.encode(sub)));
        assertEquals(add, codec.decode(codec.encode(add)));
        assertEquals(sub, codec.decode(codec.encode(sub)));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decode(corruptTaggedIntEnumTag(codec.encode(add))));
    }

    private static byte[] corruptTaggedIntEnumTag(byte[] frame) {
        byte[] corrupted = frame.clone();
        byte[] owner = "Signals".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 1; i + owner.length < corrupted.length; i++) {
            if ((corrupted[i - 1] & 0xff) != 0xa7) continue;
            boolean same = true;
            for (int j = 0; j < owner.length; j++)
                if (corrupted[i + j] != owner[j]) { same = false; break; }
            if (same) {
                corrupted[i + owner.length] ^= 1;
                return corrupted;
            }
        }
        throw new IllegalStateException("missing trusted enum owner in fixture");
    }

    @Test
    void largeClosedPatternRoutesOneSchemaWithoutTrialDecoding() {
        // Hundreds of nominal alternatives share one structural payload
        // shape. A receiver must select exactly one compiler-registered
        // schema rather than decoding every candidate.
        java.util.ArrayList<TaggedMessageContracts.Case> alternatives =
                new java.util.ArrayList<>();
        for (int tag = 1; tag <= 384; tag++) {
            alternatives.add(new TaggedMessageContracts.Case(
                    "Events", "Case" + tag, tag,
                    Ast.TypeRef.simple("string"),
                    dev.oreslang.runtime.MessagePackCollectionCodec.string()));
        }
        var codec = new TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Events", alternatives));
        var value = new TaggedMessageCodec.TaggedValue(
                "Events", "Case383", 383, "late-alternative");
        byte[] wire = codec.encode(value);
        assertEquals(value, codec.decode(wire));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[dev.oreslang.runtime.MessagePackCollectionCodec.MAX_BYTES + 1]));
        byte[] invalidSchema = wire.clone();
        invalidSchema[12] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidSchema));
        byte[] alteredVersion = wire.clone();
        alteredVersion[1] = 1;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(alteredVersion));
        byte[] nonCanonicalSchema = wire.clone();
        nonCanonicalSchema[2] = (byte) 0xda; // str16 is invalid here
        assertThrows(IllegalArgumentException.class, () -> codec.decode(nonCanonicalSchema));
        assertEquals(value, codec.decode(wire));
    }

    @Test
    void closedTaggedDecoderRejectsOtherDomainWithoutTryingPayloadShapes() {
        var registered = TaggedMessageContracts.verify(Parser.parse(DECLARATIONS));
        var allowed = new TaggedMessageCodec(registered.requireDomain("Messages"));
        var foreign = new TaggedMessageCodec(new TaggedMessageContracts.Domain(
                "Foreign", registered.requireDomain("Messages").cases()));
        var original = new TaggedMessageCodec.TaggedValue(
                "Status", "Message2", 2, "same-shape");
        byte[] foreignWire = foreign.encode(original);
        assertThrows(IllegalArgumentException.class, () -> allowed.decode(foreignWire));
        assertEquals(original, foreign.decode(foreignWire));
    }


    @Test
    void pipelineRouteIdentityIncludesCaseTagsAndExactPayloadShapes() {
        // All four contracts use the SAME names and pattern. The previously
        // name-only identity accepted the incompatible tag and payload shapes.
        String common = """
                define pattern PipeMessages as
                    Signal.Data
                    Signal.Result
                end
                """;
        String declaration = """
                define enum Signal as
                    Data: int = 1
                    Result: int = 2
                end
                """;
        String reversedPattern = """
                define pattern PipeMessages as
                    Signal.Result
                    Signal.Data
                end
                """;
        String differentTag = """
                define enum Signal as
                    Data: int = 7
                    Result: int = 2
                end
                """;
        String differentShape = """
                define enum Signal as
                    Data: string = 1
                    Result: int = 2
                end
                """;
        String original = new TaggedMessageCodec(
                TaggedMessageContracts.verify(Parser.parse(declaration + common))
                        .requireDomain("PipeMessages")).closedDomainIdentity();
        String same = new TaggedMessageCodec(
                TaggedMessageContracts.verify(Parser.parse(declaration + reversedPattern))
                        .requireDomain("PipeMessages")).closedDomainIdentity();
        String tagChanged = new TaggedMessageCodec(
                TaggedMessageContracts.verify(Parser.parse(differentTag + common))
                        .requireDomain("PipeMessages")).closedDomainIdentity();
        String shapeChanged = new TaggedMessageCodec(
                TaggedMessageContracts.verify(Parser.parse(differentShape + common))
                        .requireDomain("PipeMessages")).closedDomainIdentity();
        assertEquals(original, same, "declaration order must not change the route contract");
        assertNotEquals(original, tagChanged,
                "pipeline binding must reject same names with different numeric tags");
        assertNotEquals(original, shapeChanged,
                "pipeline binding must reject same names with different payload shapes");
    }

    @Test
    void pipelineRoutingRequiresNominalVariantAndRejectsUnimplementedReplyOperations() {
        String source = DECLARATIONS + """
                define actor Worker as
                    receive(ActorMail<Messages> mail): void {
                        self.end();
                        return;
                    }
                end

                pub async routine main(): void {
                    val a = await spawn Worker();
                    a.send("hello" as Status.Message2);
                    return;
                }
                """;
        // A typed enum case is statically allowed; actual routing still
        // requires a runtime-bound pipeline actor receive turn.
        for (String operation : List.of("forward", "backward")) {
            String typed = source.replace("self.end();",
                    "self." + operation + "(Status.Message2(\"hello\"));");
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(typed)));
            String untyped = source.replace("self.end();",
                    "self." + operation + "(mail.value);");
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(untyped)));
            assertTrue(error.getMessage().contains("compiler-checked nominal"),
                    error.getMessage());
        }
        for (String operation : List.of("respond", "done")) {
            String attempted = source.replace("self.end();",
                    "self." + operation + "(mail.value);");
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(attempted)));
            assertTrue(error.getMessage().contains("authorized lowering"),
                    error.getMessage());
        }
    }

    @Test
    void sourceActorMailboxAdmissionEnforcesPortableSixtyFourVariantLimit() {
        for (int count : List.of(64, 65)) {
            StringBuilder program = new StringBuilder("define enum Signals as\n");
            for (int tag = 1; tag <= count; tag++) {
                program.append("    Case").append(tag).append(": int = ")
                        .append(tag).append('\n');
            }
            program.append("""
                    end
                    define actor Worker as
                        receive(ActorMail<Signals> mail): void {
                            self.end();
                            return;
                        }
                    end
                    pub async routine main(): void {
                        return;
                    }
                    """);
            if (count == 64) {
                assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program.toString())));
            } else {
                IllegalArgumentException error = assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(program.toString())));
                assertTrue(error.getMessage().contains("1..64"), error.getMessage());
            }
        }
    }

}
