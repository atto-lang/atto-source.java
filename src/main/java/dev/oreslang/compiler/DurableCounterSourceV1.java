package dev.oreslang.compiler;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

// Opt-in broker-owned Counter profile. No generic actor or storage authority.
// Uses the production lexer; the entire token sequence must be consumed.
public final class DurableCounterSourceV1 {
    private DurableCounterSourceV1() {}

    private static IllegalArgumentException deny() {
        return new IllegalArgumentException("E_DURABLE_COUNTER_ACTOR_ABI: unsupported source");
    }
    private static final class Cursor {
        final List<Token> tokens;
        int i;
        Cursor(List<Token> tokens) { this.tokens=tokens; }
        String peek() {
            if(i>=tokens.size()) throw deny();
            Token t=tokens.get(i);
            if(t.type()==Token.Type.STRING || t.type()==Token.Type.TEMPLATE_LITERAL ||
               t.type()==Token.Type.SYMBOL_LITERAL) throw deny();
            return t.lexeme();
        }
        void expect(String... words) {
            for(String word:words) {if(!peek().equals(word))throw deny();i++;}
        }
        void finish() {
            if(i+1!=tokens.size()||tokens.get(i).type()!=Token.Type.EOF)throw deny();
        }
    }
    private static long fnv(byte[] bytes,int size) {
        long h=0xcbf29ce484222325L;
        for(int i=0;i<size;i++)h=(h^(bytes[i]&255L))*0x100000001b3L;
        return h;
    }
    private static void sized(ByteArrayOutputStream out,String name) {
        byte[] bytes=name.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
    public static String emitJava(String source) {
        if(source==null||source.length()>16384)throw deny();
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(c>=127||(c<32&&c!='\n'&&c!='\r'&&c!='\t'))throw deny();
        }
        Cursor c=new Cursor(new Lexer(source).scan());
        c.expect("define","durable","actor","Counter","as","state");
        String field=c.peek();
        if(!field.equals("count")&&!field.equals("balance"))throw deny();
        c.expect(field,":","int","=","0",";","receive","(","ActorMail","<","int",">");
        String mail=c.peek();
        if(!mail.equals("mail")&&!mail.equals("event"))throw deny();
        c.expect(mail,")","->","void","{","self",".",field,"=","self",".",field,"+",mail,".","value",";","}","end");
        c.finish();

        ByteArrayOutputStream sig=new ByteArrayOutputStream();
        sig.writeBytes("atto.actor.state.schema.v1".getBytes(StandardCharsets.US_ASCII));
        sig.write(0);sized(sig,"Counter");sized(sig,field);
        sig.write(1);byte[] signature=sig.toByteArray();
        ByteBuffer snapshot=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        snapshot.put("ATTOST01".getBytes(StandardCharsets.US_ASCII)).put((byte)1)
            .putLong(fnv(signature,signature.length)).putShort((short)1).put((byte)1).putLong(0);
        snapshot.putLong(fnv(snapshot.array(),28));
        ByteArrayOutputStream descriptor=new ByteArrayOutputStream();
        descriptor.write(1);descriptor.write(7);
        descriptor.writeBytes("Counter".getBytes(StandardCharsets.US_ASCII));
        descriptor.write(field.length());descriptor.writeBytes(field.getBytes(StandardCharsets.US_ASCII));
        descriptor.write(1);descriptor.write(36);descriptor.writeBytes(snapshot.array());
        String hex=HexFormat.of().formatHex(descriptor.toByteArray());
        // Fixed lowering, not source interpolation. Only computed hex enters output.
        return """
            public final class AttoDurableCounterV1 {
                private AttoDurableCounterV1() {}
                public static byte[] descriptor() {
                    return java.util.HexFormat.of().parseHex("%s");
                }
                public static long transition(long state,long op,long delta) {
                    final long maximum=1000000000L;
                    if(state<0||state>maximum)return -1;
                    if(op==2)return delta==0?state:-1;
                    if(op!=1||delta<=0||delta>maximum||state>maximum-delta)return -1;
                    return state+delta;
                }
            }
            """.formatted(hex);
    }
}
