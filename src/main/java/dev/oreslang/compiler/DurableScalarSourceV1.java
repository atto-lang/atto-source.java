package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Restricted schema-only lowering; does not admit live actors or grant disk access. */
public final class DurableScalarSourceV1 {
    private DurableScalarSourceV1() {}
    private static final Pattern TOKEN=Pattern.compile("[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[:;=\\-]");
    private static final Set<String> RESERVED=Set.of("define","pub","private","durable","persistent","actor","as","state","end","int","bool","true","false","self","receive","init","fnc","mtd","let","val","mut","const","return","await","suspends","susp","async","critical","using","with","import","namespace","module","class","struct","contract","enum","new","spawn","stop","if","else","match","on","default","void");
    private static IllegalArgumentException deny(){return new IllegalArgumentException("E_DURABLE_SCALAR_SCHEMA_V1");}
    private static final class Cursor {
        final List<String> tokens=new ArrayList<>(); int i;
        Cursor(String s){
            if(s==null||s.length()>16384)throw deny();
            var m=TOKEN.matcher(s);
            for(int p=0;p<s.length();){
                char c=s.charAt(p);
                if(c==' '||c=='\n'||c=='\r'||c=='\t'){p++;continue;}
                m.region(p,s.length());
                if(tokens.size()>=4096||!m.lookingAt())throw deny();
                tokens.add(m.group());p=m.end();
            }
        }
        String peek(){return i<tokens.size()?tokens.get(i):"";}
        String next(){String t=peek();if(t.isEmpty())throw deny();i++;return t;}
        void expect(String... words){for(String w:words)if(!next().equals(w))throw deny();}
        String name(){String n=next();if(!n.matches("[A-Za-z_][A-Za-z_0-9]{0,127}")||RESERVED.contains(n))throw deny();return n;}
    }
    private static void sized(ByteArrayOutputStream s,String name){
        byte[] b=name.getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<4;i++)s.write(b.length>>>(8*i));s.writeBytes(b);
    }
    private static long fnv(byte[] b){long h=0xcbf29ce484222325L;for(byte x:b)h=(h^(x&255L))*0x100000001b3L;return h;}
    public static String emitJava(String source){
        Cursor c=new Cursor(source);c.expect("define","durable","actor");String actor=c.name();c.expect("as");
        ByteArrayOutputStream sig=new ByteArrayOutputStream();sig.writeBytes("atto.actor.state.schema.v1".getBytes(StandardCharsets.US_ASCII));sig.write(0);sized(sig,actor);
        var names=new HashSet<String>();var tags=new ArrayList<String>();var values=new ArrayList<String>();int size=27;
        while(c.peek().equals("state")){
            if(tags.size()>=256)throw deny();c.expect("state");String name=c.name();if(!names.add(name))throw deny();c.expect(":");String type=c.next();c.expect("=");
            sized(sig,name);
            if(type.equals("int")){
                boolean neg=c.peek().equals("-");if(neg)c.next();String literal=c.next();if(!literal.matches("0|[1-9][0-9]{0,18}"))throw deny();
                long n;try{n=Long.parseLong((neg?"-":"")+literal);}catch(NumberFormatException e){throw deny();}
                tags.add("1");values.add("Long.valueOf("+n+"L)");sig.write(1);size+=9;
            }else if(type.equals("bool")){
                String b=c.next();if(!b.equals("true")&&!b.equals("false"))throw deny();tags.add("2");values.add("Boolean.valueOf("+b+")");sig.write(2);size+=2;
            }else throw deny();c.expect(";");
        }
        c.expect("end");if(!c.peek().isEmpty())throw deny();
        return """
            import java.nio.ByteBuffer;
            import java.nio.ByteOrder;
            public final class AttoDurableScalarV1 {
                private AttoDurableScalarV1() {}
                private static final int[] TAGS={%s};
                private static final int SIZE=%d;
                private static final long SCHEMA=0x%sL;
                private static IllegalArgumentException deny(){return new IllegalArgumentException("E_DURABLE_SCALAR_CHECKPOINT_V1");}
                private static long fnv(byte[] b,int end){long h=0xcbf29ce484222325L;for(int i=0;i<end;i++)h=(h^(b[i]&255L))*0x100000001b3L;return h;}
                public static byte[] encode(Object... values){
                    if(values==null||values.length!=TAGS.length)throw deny();
                    Object[] owned=values.clone();
                    ByteBuffer b=ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
                    b.putLong(0x313054534f545441L).put((byte)1).putLong(SCHEMA).putShort((short)TAGS.length);
                    for(int i=0;i<TAGS.length;i++){
                        int tag=TAGS[i];b.put((byte)tag);Object x=owned[i];
                        if(tag==1){if(!(x instanceof Long n))throw deny();b.putLong(n);}
                        else{if(!(x instanceof Boolean v))throw deny();b.put((byte)(v?1:0));}
                    }
                    b.putLong(fnv(b.array(),SIZE-8));return b.array();
                }
                public static Object[] decode(byte[] input){
                    if(input==null||input.length!=SIZE)throw deny();byte[] bytes=input.clone();
                    ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    if(b.getLong()!=0x313054534f545441L||b.get()!=1||b.getLong()!=SCHEMA||b.getShort()!=TAGS.length||b.getLong(SIZE-8)!=fnv(bytes,SIZE-8))throw deny();
                    Object[] out=new Object[TAGS.length];
                    for(int i=0;i<TAGS.length;i++){
                        int tag=TAGS[i];if(b.get()!=tag)throw deny();
                        if(tag==1)out[i]=Long.valueOf(b.getLong());
                        else{byte x=b.get();if(x!=0&&x!=1)throw deny();out[i]=Boolean.valueOf(x==1);}
                    }
                    if(b.position()!=SIZE-8)throw deny();return out;
                }
                public static byte[] initial(){return encode(%s);}
            }
            """.formatted(String.join(",",tags),size,Long.toHexString(fnv(sig.toByteArray())),String.join(",",values));
    }
}
