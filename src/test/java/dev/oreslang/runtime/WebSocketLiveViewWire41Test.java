package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared 41-vector first-party LiveView wire profile, not guest or actor evidence.
 * It tests the real draft Java RFC6455 codec, not a source-free JS imitation.
 * The generic codec permits binary frames; the LiveView profile rejects them.
 * Close-code mapping remains a separate actor/transport integration gate.
 */
final class WebSocketLiveViewWire41Test {
    private record Vector(String id,String wireHex,boolean shouldReject,String[] expected) {}
    private static final Vector[] VECTORS={
            new Vector("masked-hello", "81850102030469676f686e", false, new String[]{"text:hello"}),
            new Vector("masked-unicode", "818701020304696b23f49e8e93", false, new String[]{"text:hi 🌐"}),
            new Vector("fragmented-unicode", "018201020304f19d8082010203048d92", false, new String[]{"text:🌐"}),
            new Vector("interleaved-control", "0183010203046e6c668981010203042080830102030475756c", false, new String[]{"ping:21", "text:onetwo"}),
            new Vector("ping-and-pong", "898201020304696b8a8301020304637b66", false, new String[]{"ping:6869", "pong:627965"}),
            new Vector("masked-close", "88820102030402ea", false, new String[]{"close:03e8"}),
            new Vector("coalesced-text", "8181010203044081810102030443", false, new String[]{"text:A", "text:B"}),
            new Vector("unmasked-rejected", "810141", true, new String[]{}),
            new Vector("rsv-rejected", "c18001020304", true, new String[]{}),
            new Vector("binary-rejected", "828601020304636b6d65737b", true, new String[]{}),
            new Vector("invalid-utf8-rejected", "818101020304fe", true, new String[]{}),
            new Vector("invalid-close-rejected", "88820102030402ef", true, new String[]{}),
            new Vector("orphan-continuation-rejected", "8082010203046f6d", true, new String[]{}),
            new Vector("non-minimal-length-rejected", "81fe00010102030400", true, new String[]{}),
            new Vector("empty-masked-text", "818001020304", false, new String[]{"text:"}),
            new Vector("empty-masked-ping", "898001020304", false, new String[]{"ping:"}),
            new Vector("empty-masked-pong", "8a8001020304", false, new String[]{"pong:"}),
            new Vector("fragmented-euro-utf8", "018201020304e380808101020304ad", false, new String[]{"text:€"}),
            new Vector("valid-close-reason", "88850102030402ea617d64", false, new String[]{"close:03e8627965"}),
            new Vector("fragment-empty-then-text", "01800102030480810102030440", false, new String[]{"text:A"}),
            new Vector("large-masked-control", "89fd010203045b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b58595e5b", false, new String[]{"ping:5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"}),
            new Vector("unmasked-ping-rejected", "8900", true, new String[]{}),
            new Vector("reserved-opcode-rejected", "83810102030440", true, new String[]{}),
            new Vector("fragmented-ping-rejected", "09810102030440", true, new String[]{}),
            new Vector("fragmented-close-rejected", "08820102030402ea", true, new String[]{}),
            new Vector("close-one-byte-rejected", "88810102030402", true, new String[]{}),
            new Vector("close-code-1005-rejected", "88820102030402ef", true, new String[]{}),
            new Vector("close-code-1006-rejected", "88820102030402ec", true, new String[]{}),
            new Vector("close-code-1015-rejected", "88820102030402f5", true, new String[]{}),
            new Vector("close-code-2000-rejected", "88820102030406d2", true, new String[]{}),
            new Vector("close-code-999-rejected", "88820102030402e5", true, new String[]{}),
            new Vector("invalid-close-reason-utf8", "88830102030402eac0", true, new String[]{}),
            new Vector("invalid-text-overlong-utf8", "818201020304c182", true, new String[]{}),
            new Vector("invalid-text-surrogate-utf8", "818301020304eca283", true, new String[]{}),
            new Vector("invalid-text-incomplete-utf8", "818201020304e380", true, new String[]{}),
            new Vector("unexpected-new-text-during-fragment", "0181010203044081810102030443", true, new String[]{}),
            new Vector("invalid-control-length-126", "89fe007e01020304", true, new String[]{}),
            new Vector("nonminimal-length-126", "81fe007d01020304", true, new String[]{}),
            new Vector("nonminimal-length-127", "81ff000000000000ffff01020304", true, new String[]{}),
            new Vector("high-bit-64bit-length", "81ff800000000000000001020304", true, new String[]{}),
            new Vector("oversized-64bit-length", "81ff000000000001000101020304", true, new String[]{})
    };
    @Test
    void canonicalFixtureHas41UniqueCaseIds() {
        assertEquals(41,VECTORS.length);
        assertEquals(41,java.util.Arrays.stream(VECTORS).map(Vector::id).distinct().count());
    }
    @Test
    void rawWireProfileAcceptsOrRejectsIdenticallyUnderShortReads() throws Exception {
        for (Vector vector:VECTORS) for (boolean shortReads:new boolean[]{false,true}) {
            final byte[] raw=HexFormat.of().parseHex(vector.wireHex());
            final String display=vector.id()+(shortReads?" / single-byte reads":" / whole stream");
            if(vector.shouldReject()) {
                assertThrows(IllegalArgumentException.class, ()->decode(raw,shortReads),display);
            } else {
                assertEquals(List.of(vector.expected()),decode(raw,shortReads),display);
            }
        }
    }
    private static List<String> decode(byte[] raw,boolean shortReads) throws IOException {
        InputStream source=new ByteArrayInputStream(raw);
        InputStream input=shortReads?new FilterInputStream(source) {
            @Override public int read(byte[] target,int off,int len) throws IOException {
                return super.read(target,off,Math.min(1,len));
            }
        }:source;
        var assembler=new WebSocketFrameCodec.MessageAssembler(65536);
        var actual=new ArrayList<String>();
        while(source.available()>0) {
            var frame=WebSocketFrameCodec.readClientFrame(input,65536);
            int op=frame.opcode();
            if(op==WebSocketFrameCodec.OPCODE_BINARY) {
                // RFC6455 permits binary, but ores-liveview-v1 disallows it.
                throw new IllegalArgumentException("LiveView binary opcode not permitted");
            }
            if(op==WebSocketFrameCodec.OPCODE_PING||op==WebSocketFrameCodec.OPCODE_PONG
                ||op==WebSocketFrameCodec.OPCODE_CLOSE) {
                actual.add((op==WebSocketFrameCodec.OPCODE_PING?"ping:":op==WebSocketFrameCodec.OPCODE_PONG?"pong:":"close:")
                    +HexFormat.of().formatHex(frame.payload()));
                if(op!=WebSocketFrameCodec.OPCODE_CLOSE)assertTrue(assembler.accept(frame).isEmpty());
                continue;
            }
            var message=assembler.accept(frame);
            message.ifPresent(m->actual.add("text:"+new String(m.payload(),StandardCharsets.UTF_8)));
        }
        assertFalse(assembler.fragmentedMessagePending(),"unfinished fragmented message");
        return actual;
    }
}
