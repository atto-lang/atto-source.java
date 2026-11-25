package dev.oreslang.benchmarks;

import dev.oreslang.runtime.AllocJsonText;
import dev.oreslang.runtime.OresJsonValue;
import dev.oreslang.runtime.OresBytes;
import dev.oreslang.runtime.Utf8StringAbi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Host-runtime diagnostics only: not compiled Atto guest or SharkBench evidence. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class RuntimeDiagnostics {
    @State(Scope.Thread)
    public static class JsonState {
        @Param({"16", "128", "512"})
        public int entries;
        String source;
        OresJsonValue root;
        OresJsonValue immutableRoot;
        String[] keys;
        int next;
        @Setup(Level.Trial)
        public void prepare() {
            var text = new StringBuilder("{");
            keys = new String[entries];
            for (int i = 0; i < entries; i++) {
                keys[i] = "key" + i;
                if (i != 0) text.append(',');
                text.append('"').append(keys[i]).append("\":{\"name\":\"Hélium😀\",\"number\":")
                    .append(i).append(",\"group\":18}");
            }
            source = text.append('}').toString();
            root = AllocJsonText.parse(source);
            immutableRoot = OresJsonValue.parseImmutable(source);
            if (!OresJsonValue.getOwned(root, keys[0]).isPresent()
                    || OresJsonValue.getOwned(root, "missing").isPresent()
                    || !source.equals(OresJsonValue.stringify(root))
                    || !source.equals(OresJsonValue.stringify(immutableRoot))
                    || !OresJsonValue.stringify(OresJsonValue.getOwned(root, keys[0]).orElseThrow()).equals(
                        OresJsonValue.stringify(OresJsonValue.getOwned(immutableRoot, keys[0]).orElseThrow()))
                    || OresJsonValue.getOwned(immutableRoot, "missing").isPresent()) {
                throw new IllegalStateException("JSON benchmark fixture failed correctness");
            }
        }
        String key() { return keys[next++ & (entries - 1)]; }
    }

    @State(Scope.Thread)
    public static class BytesState {
        @Param({"1024", "16384", "131072"})
        public int size;
        @Param({"256", "1024"})
        public int chunkSize;
        String text;
        byte[] encoded;
        List<OresBytes> chunks;
        @Setup(Level.Trial)
        public void prepare() {
            // Each repeated unit is exactly 8 UTF-8 bytes, including non-ASCII.
            text = "aé😀z".repeat(size / 8);
            encoded = Utf8StringAbi.encode(text).bytes();
            if (encoded.length != size) throw new IllegalStateException("byte length");
            chunks = new ArrayList<>();
            for (int at = 0; at < encoded.length; at += chunkSize) {
                chunks.add(OresBytes.copyOf(Arrays.copyOfRange(encoded, at,
                        Math.min(encoded.length, at + chunkSize))));
            }
            try (var joined = OresBytes.join(chunks)) {
                if (!Arrays.equals(encoded, joined.toArrayCopy())
                        || !text.equals(joined.toUtf8OrNull())) {
                    throw new IllegalStateException("byte fixture failed correctness");
                }
            }
        }
        @TearDown(Level.Trial)
        public void close() { if (chunks != null) chunks.forEach(OresBytes::close); }
    }

    @Benchmark public OresJsonValue jsonParse(JsonState s) {
        return AllocJsonText.parse(s.source);
    }
    @Benchmark public String jsonStringify(JsonState s) {
        return OresJsonValue.stringify(s.root);
    }
    @Benchmark public Optional<OresJsonValue> jsonLookupHit(JsonState s) {
        return OresJsonValue.getOwned(s.root, s.key());
    }
    @Benchmark public Optional<OresJsonValue> jsonLookupMiss(JsonState s) {
        return OresJsonValue.getOwned(s.root, "missing");
    }
    @Benchmark public OresJsonValue jsonImmutableParse(JsonState s) {
        return OresJsonValue.parseImmutable(s.source);
    }
    @Benchmark public Optional<OresJsonValue> jsonImmutableLookupHit(JsonState s) {
        return OresJsonValue.getOwned(s.immutableRoot, s.key());
    }
    @Benchmark public Optional<OresJsonValue> jsonImmutableLookupMiss(JsonState s) {
        return OresJsonValue.getOwned(s.immutableRoot, "missing");
    }
    @Benchmark public byte[] utf8Encode(BytesState s) {
        return Utf8StringAbi.encode(s.text).bytes();
    }
    @Benchmark public String utf8Decode(BytesState s) {
        return Utf8StringAbi.decode(s.encoded, s.encoded.length);
    }
    // Both assembly diagnostics return a strict UTF-8 String; input chunks are
    // identical. Includes ownership cleanup on the Bytes path, not network I/O.
    @Benchmark public String nativeBytesJoinDecode(BytesState s) {
        try (var joined = OresBytes.join(s.chunks)) { return joined.toUtf8OrNull(); }
    }
    @Benchmark public String boxedListAssembleDecode(BytesState s) {
        var values = new ArrayList<Object>();
        for (var part : s.chunks) values.addAll(part.toList());
        byte[] raw = new byte[values.size()];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) ((Long) values.get(i)).longValue();
        return Utf8StringAbi.decode(raw, raw.length);
    }
}
