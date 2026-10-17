package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone, strict MessagePack v2 DATA wire primitive matching LLVM's
 * MessagePackWireV2.hpp. No mailbox admission, Sendable proof, codec registry
 * registration or supervisor CONTROL authority is provided by this class.
 * Callers must pin the schema and shape from trusted compiler metadata.
 */
public final class MessagePackWireV2 {
    private MessagePackWireV2() { }
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_NODES = 100_000;
    public static final int MAX_ELEMENTS = 100_000;
    public static final int MAX_DEPTH = 64;
    public enum Kind { BOOL, I64, F64, STRING, TUPLE, FIXED_ARRAY, FIXED_LIST, ARRAY, LIST }
    public record Shape(Kind kind, List<Shape> members) {
        public Shape {
            require(kind != null && members != null, "missing shape kind or members");
            // Bound hostile or incorrectly compiler-generated descriptors before
            // List.copyOf traverses/allocates from caller-supplied list sizes.
            int admittedCount = members.size();
            require(admittedCount >= 0 && admittedCount <= MAX_ELEMENTS, "shape member budget exceeded");
            // Copy exactly the admitted number of elements by indexed access.
            // List.copyOf(members) may traverse a hostile iterator whose
            // element count grows after the size preflight, or use a second
            // attacker-controlled size to allocate an oversized backing array.
            List<Shape> snapshot = new ArrayList<>(admittedCount);
            for (int i = 0; i < admittedCount; i++) {
                Shape element;
                try {
                    element = members.get(i);
                } catch (IndexOutOfBoundsException changed) {
                    throw bad("shape members changed during snapshot");
                }
                require(element != null, "null shape member");
                snapshot.add(element);
            }
            members = List.copyOf(snapshot);
        }
        public static Shape bool() { return new Shape(Kind.BOOL, List.of()); }
        public static Shape i64() { return new Shape(Kind.I64, List.of()); }
        public static Shape f64() { return new Shape(Kind.F64, List.of()); }
        public static Shape string() { return new Shape(Kind.STRING, List.of()); }
        public static Shape tuple(Shape... xs) { return new Shape(Kind.TUPLE, List.of(xs)); }
        public static Shape fixedArray(Shape... xs) { return new Shape(Kind.FIXED_ARRAY, List.of(xs)); }
        public static Shape fixedList(Shape... xs) { return new Shape(Kind.FIXED_LIST, List.of(xs)); }
        public static Shape array(Shape x) { return new Shape(Kind.ARRAY, List.of(x)); }
        public static Shape list(Shape x) { return new Shape(Kind.LIST, List.of(x)); }
    }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException(why); }
    private static void require(boolean ok, String why) { if (!ok) throw bad(why); }
    private static boolean fixed(Kind k) { return k == Kind.TUPLE || k == Kind.FIXED_ARRAY || k == Kind.FIXED_LIST; }
    private static boolean scalar(Kind k) { return k == Kind.BOOL || k == Kind.I64 || k == Kind.F64 || k == Kind.STRING; }
    private static void schema(String id) {
        require(id != null && !id.isEmpty() && id.length() <= 256, "invalid schema ID");
        for (int i = 0; i < id.length(); i++) require(id.charAt(i) >= 33 && id.charAt(i) <= 126, "non-ASCII schema ID");
    }
    private static void descriptor(Shape shape, StringBuilder out, int depth, int[] nodes) {
        require(depth <= MAX_DEPTH && ++nodes[0] <= MAX_NODES, "shape budget exceeded");
        Kind kind = shape.kind(); List<Shape> ms = shape.members();
        if (scalar(kind)) {
            require(ms.isEmpty(), "scalar has members");
            out.append(switch (kind) { case BOOL -> 'B'; case I64 -> 'I'; case F64 -> 'F'; default -> 'S'; });
            return;
        }
        require(ms.size() <= MAX_ELEMENTS && (fixed(kind) || ms.size() == 1), "invalid shape arity");
        out.append(switch (kind) { case TUPLE -> "T["; case FIXED_ARRAY -> "FA["; case FIXED_LIST -> "FL["; case ARRAY -> "A["; default -> "L["; });
        for (int i = 0; i < ms.size(); i++) {
            if (i > 0) out.append(',');
            descriptor(ms.get(i), out, depth + 1, nodes);
            require(out.length() <= MAX_BYTES, "shape descriptor too large");
        }
        out.append(']');
    }
    private static byte[] digest(Shape shape) {
        StringBuilder descriptor = new StringBuilder("oreslang.actor.shape.v2/");
        descriptor(shape, descriptor, 0, new int[1]);
        try { return MessageDigest.getInstance("SHA-256").digest(descriptor.toString().getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] utf8(String s) {
        // Check exact UTF-8 byte length before the encoder allocates. A
        // rejected oversized network value must not allocate a larger
        // temporary buffer first; malformed surrogate pairs fail closed.
        require(s.length() <= MAX_BYTES, "string byte budget");
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x80) ++bytes;
            else if (ch < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(ch)) {
                require(i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1)),
                        "invalid Unicode scalar");
                ++i;
                bytes += 4;
            } else {
                require(!Character.isLowSurrogate(ch), "invalid Unicode scalar");
                bytes += 3;
            }
            require(bytes <= MAX_BYTES, "string byte budget");
        }
        try {
            ByteBuffer buf = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(s));
            byte[] encoded = new byte[buf.remaining()]; buf.get(encoded); return encoded;
        } catch (CharacterCodingException e) { throw bad("invalid Unicode scalar"); }
    }
    private static String utf8(byte[] bytes, int offset, int length) {
        try { return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString(); }
        catch (CharacterCodingException e) { throw bad("malformed UTF-8"); }
    }
    private static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(); int nodes;
        void byte1(int b) { require(out.size() < MAX_BYTES, "wire byte budget"); out.write(b & 255); }
        void raw(byte[] bytes) { require(bytes.length <= MAX_BYTES - out.size(), "wire byte budget"); out.writeBytes(bytes); }
        void uint(long x, int n) { for (int i = n - 1; i >= 0; i--) byte1((int)(x >>> (i * 8))); }
        void array(int n) { require(n >= 0 && n <= MAX_ELEMENTS, "array budget"); if (n <= 15) byte1(0x90 | n); else if (n <= 65535) { byte1(0xdc); uint(n,2); } else { byte1(0xdd); uint(n,4); } }
        void integer(long x) {
            if (x >= -32 && x <= 127) byte1((int)x);
            else if (x >= 0 && x <= 255) { byte1(0xcc); uint(x,1); }
            else if (x >= 0 && x <= 65535) { byte1(0xcd); uint(x,2); }
            else if (x >= 0 && x <= 0xffff_ffffL) { byte1(0xce); uint(x,4); }
            else if (x >= 0) { byte1(0xcf); uint(x,8); }
            else if (x >= -128) { byte1(0xd0); uint(x,1); }
            else if (x >= -32768) { byte1(0xd1); uint(x,2); }
            else if (x >= Integer.MIN_VALUE) { byte1(0xd2); uint(x,4); }
            else { byte1(0xd3); uint(x,8); }
        }
        void string(String s) {
            byte[] bytes = utf8(s); require(bytes.length <= MAX_BYTES, "string budget");
            int n = bytes.length;
            if (n <= 31) byte1(0xa0 | n); else if (n <= 255) { byte1(0xd9); uint(n,1); }
            else if (n <= 65535) { byte1(0xda); uint(n,2); } else { byte1(0xdb); uint(n,4); }
            raw(bytes);
        }
        void value(Shape shape, Object value, int depth) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES, "value budget exceeded");
            switch (shape.kind()) {
                case BOOL -> { require(value instanceof Boolean, "expected bool"); byte1((Boolean)value ? 0xc3 : 0xc2); return; }
                case I64 -> { require(value instanceof Long, "expected signed i64"); integer((Long)value); return; }
                case F64 -> { require(value instanceof Double && Double.isFinite((Double)value), "expected finite f64"); byte1(0xcb); uint(Double.doubleToRawLongBits((Double)value),8); return; }
                case STRING -> { require(value instanceof String, "expected string"); string((String)value); return; }
                default -> { }
            }
            require(value instanceof List<?>, "expected sequence"); List<?> values = (List<?>)value;
            // Capture one admitted logical size. Re-reading a caller-owned
            // List.size() after emitting the array header can produce a
            // noncanonical frame if a malicious/mutable list changes size.
            int count = values.size();
            require(count >= 0 && count <= MAX_ELEMENTS && (!fixed(shape.kind()) || count == shape.members().size()), "sequence arity");
            array(count);
            for (int i = 0; i < count; i++) value(shape.members().get(fixed(shape.kind()) ? i : 0), values.get(i), depth + 1);
        }
    }
    private static final class Reader {
        final byte[] bytes; int at; int nodes;
        Reader(byte[] bytes) { require(bytes != null && bytes.length <= MAX_BYTES, "wire byte budget"); this.bytes = bytes; }
        int take() { require(at < bytes.length, "truncated message"); return bytes[at++] & 255; }
        long uint(int n) { require(n <= bytes.length - at, "truncated number"); long x = 0; for (int i=0;i<n;i++) x=(x<<8)|take(); return x; }
        long signed(int n) { long x=uint(n); if (n < 8 && (x & (1L << (n*8-1))) != 0) x |= -1L << (n*8); return x; }
        int array() {
            int b=take(); long n;
            if ((b&0xf0)==0x90) n=b&15;
            else if (b==0xdc) { n=uint(2); require(n>15,"noncanonical array16"); }
            else if (b==0xdd) { n=uint(4); require(n>65535,"noncanonical array32"); }
            else throw bad("expected array");
            require(n <= MAX_ELEMENTS, "oversized array"); return (int)n;
        }
        long integer() {
            int b=take(); if (b <= 127) return b; if (b >= 224) return (byte)b;
            return switch (b) {
                case 0xcc -> {long x=uint(1); require(x>127,"noncanonical uint8"); yield x;}
                case 0xcd -> {long x=uint(2); require(x>255,"noncanonical uint16"); yield x;}
                case 0xce -> {long x=uint(4); require(x>65535,"noncanonical uint32"); yield x;}
                case 0xcf -> {long x=uint(8); require(x>0xffff_ffffL,"noncanonical/out-of-range uint64"); yield x;}
                case 0xd0 -> {long x=signed(1); require(x < -32,"noncanonical int8"); yield x;}
                case 0xd1 -> {long x=signed(2); require(x < -128,"noncanonical int16"); yield x;}
                case 0xd2 -> {long x=signed(4); require(x < -32768,"noncanonical int32"); yield x;}
                case 0xd3 -> {long x=signed(8); require(x < Integer.MIN_VALUE,"noncanonical int64"); yield x;}
                default -> throw bad("expected integer");
            };
        }
        String string() {
            int b=take(); long n;
            if ((b&0xe0)==0xa0) n=b&31;
            else if (b==0xd9) {n=uint(1);require(n>31,"noncanonical str8");}
            else if (b==0xda) {n=uint(2);require(n>255,"noncanonical str16");}
            else if (b==0xdb) {n=uint(4);require(n>65535,"noncanonical str32");}
            else throw bad("expected string");
            require(n <= MAX_BYTES && n <= bytes.length-at,"truncated string");
            String s=utf8(bytes,at,(int)n); at+=(int)n; return s;
        }
        Object value(Shape shape, int depth) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES,"value budget exceeded");
            return switch (shape.kind()) {
                case BOOL -> {int b=take(); require(b==0xc2||b==0xc3,"expected bool"); yield b==0xc3;}
                case I64 -> integer();
                case F64 -> {require(take()==0xcb,"expected f64"); double d=Double.longBitsToDouble(uint(8)); require(Double.isFinite(d),"nonfinite f64"); yield d;}
                case STRING -> string();
                default -> {
                    int n=array(); require(!fixed(shape.kind())||n==shape.members().size(),"fixed sequence arity");
                    require(n <= bytes.length-at && n <= MAX_NODES-nodes,"array exceeds available bytes/nodes");
                    List<Object> values=new ArrayList<>(n);
                    for (int i=0;i<n;i++) values.add(value(shape.members().get(fixed(shape.kind())?i:0), depth+1));
                    yield values;
                }
            };
        }
    }
    public static byte[] encode(String pinnedSchemaId, Shape pinnedShape, Object ownedValue) {
        schema(pinnedSchemaId); byte[] hash=digest(pinnedShape);
        Writer w=new Writer(); w.array(4); w.integer(2); w.string(pinnedSchemaId);
        w.byte1(0xc4); w.byte1(32); w.raw(hash); w.value(pinnedShape,ownedValue,0); return w.out.toByteArray();
    }
    public static Object decode(String pinnedSchemaId, Shape pinnedShape, byte[] untrustedBytes) {
        schema(pinnedSchemaId); byte[] hash=digest(pinnedShape);
        Reader r=new Reader(untrustedBytes); require(r.array()==4,"wrong envelope arity");
        require(r.integer()==2,"wrong version"); require(r.string().equals(pinnedSchemaId),"wrong schema ID");
        require(r.take()==0xc4 && r.take()==32,"wrong digest encoding");
        byte[] actual=new byte[32]; for(int i=0;i<32;i++)actual[i]=(byte)r.take();
        require(MessageDigest.isEqual(actual,hash),"wrong shape digest");
        Object result=r.value(pinnedShape,0); require(r.at==untrustedBytes.length,"trailing bytes"); return result;
    }
}
