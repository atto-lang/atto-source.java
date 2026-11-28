package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Draft manifest-driven bounded codec lowering; not live actor admission. */
public final class DurableBoundedSourceV2 {
    private DurableBoundedSourceV2() {}
    public enum Kind { INT(1), BOOL(2), STRING(3), BYTES(4); final int tag; Kind(int tag){this.tag=tag;} }
    public record Field(String name,Kind kind) {}
    private static IllegalArgumentException deny(){return new IllegalArgumentException("E_DURABLE_BOUNDED_SCHEMA_V2");}
    private static boolean nameOk(String s){return s!=null&&s.matches("[A-Za-z_][A-Za-z_0-9]{0,127}");}
    private static void sized(ByteArrayOutputStream b,String name){
        byte[] raw=name.getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<4;i++)b.write(raw.length>>>(8*i));b.writeBytes(raw);
    }
    private static long hash(byte[] bytes){long h=0xcbf29ce484222325L;for(byte b:bytes)h=(h^(b&255L))*0x100000001b3L;return h;}
    public static String emitJava(String actor,List<Field> fields){
        // This is compiler-owned trusted manifest data, not guest input.
        if(!nameOk(actor)||fields==null||fields.size()>256)throw deny();
        var names=new HashSet<String>();var sig=new ByteArrayOutputStream();
        sig.writeBytes("atto.actor.state.schema.v2".getBytes(StandardCharsets.US_ASCII));sig.write(0);sized(sig,actor);
        var tags=new StringBuilder();
        for(Field f:fields){
            if(f==null||!nameOk(f.name())||f.kind()==null||!names.add(f.name()))throw deny();
            sized(sig,f.name());sig.write(f.kind().tag);if(tags.length()>0)tags.append(',');tags.append(f.kind().tag);
        }
        // Only numeric constants are interpolated, never source text or names.
        return """
            import java.nio.*;
            import java.nio.charset.*;
            import java.util.Arrays;
            public final class AttoDurableBoundedV2 {
                private AttoDurableBoundedV2() {}
                private static final long SCHEMA=0x%sL;
                private static final int[] TAGS={%s};
                private static final int MAX=65536,FIELD_MAX=16384;
                private static IllegalArgumentException deny(){return new IllegalArgumentException("E_DURABLE_BOUNDED_CHECKPOINT_V2");}
                private static long hash(byte[] b,int end){long h=0xcbf29ce484222325L;for(int i=0;i<end;i++)h=(h^(b[i]&255L))*0x100000001b3L;return h;}
                private static byte[] utf8(String s){
                    if(s.length()>FIELD_MAX)throw deny();
                    try{ByteBuffer raw=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));if(raw.remaining()>FIELD_MAX)throw deny();byte[] out=new byte[raw.remaining()];raw.get(out);return out;}catch(CharacterCodingException e){throw deny();}
                }
                private static String string(byte[] b){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException e){throw deny();}}
                private static void room(ByteBuffer b,int n){if(n<0||n>b.remaining())throw deny();}
                public static byte[] encode(Object... values){
                    if(values==null||values.length!=TAGS.length)throw deny();Object[] owned=values.clone();
                    ByteBuffer b=ByteBuffer.allocate(MAX).order(ByteOrder.LITTLE_ENDIAN);b.limit(MAX-8);
                    b.putLong(0x323054534f545441L).put((byte)2).putLong(SCHEMA).putShort((short)TAGS.length);
                    for(int i=0;i<TAGS.length;i++){int tag=TAGS[i];Object x=owned[i];room(b,1);b.put((byte)tag);
                        if(tag==1){if(!(x instanceof Long v))throw deny();room(b,8);b.putLong(v);}
                        else if(tag==2){if(!(x instanceof Boolean v))throw deny();room(b,1);b.put((byte)(v?1:0));}
                        else {byte[] raw;if(tag==3){if(!(x instanceof String s))throw deny();raw=utf8(s);}else{if(!(x instanceof byte[] v)||v.length>FIELD_MAX)throw deny();raw=v.clone();}room(b,4+raw.length);b.putInt(raw.length).put(raw);}
                    }
                    int p=b.position();b.limit(MAX);b.putLong(hash(b.array(),p));return Arrays.copyOf(b.array(),b.position());
                }
                public static Object[] decode(byte[] input){
                    if(input==null||input.length<27||input.length>MAX)throw deny();byte[] bytes=input.clone();int end=bytes.length-8;
                    ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    if(b.getLong()!=0x323054534f545441L||b.get()!=2||b.getLong()!=SCHEMA||b.getShort()!=TAGS.length||b.getLong(end)!=hash(bytes,end))throw deny();b.limit(end);
                    Object[] out=new Object[TAGS.length];
                    for(int i=0;i<TAGS.length;i++){int tag=TAGS[i];room(b,1);if(b.get()!=tag)throw deny();
                        if(tag==1){room(b,8);out[i]=Long.valueOf(b.getLong());}
                        else if(tag==2){room(b,1);byte v=b.get();if(v!=0&&v!=1)throw deny();out[i]=Boolean.valueOf(v==1);}
                        else{room(b,4);int n=b.getInt();if(n<0||n>FIELD_MAX)throw deny();room(b,n);byte[] raw=new byte[n];b.get(raw);out[i]=tag==3?string(raw):raw;}
                    }
                    if(b.position()!=end)throw deny();return out;
                }
            }
            """.formatted(Long.toHexString(hash(sig.toByteArray())),tags.toString());
    }
}
