package atto.compiler.readonly;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.*;
import java.util.regex.*;

// Explicit closed source-to-Java profile. No general ownership or Native
// Image allocation-elision promise. No raw source identifier reaches output.
public final class AttoReadonlyReferenceCompiler {
    private static final Pattern TOKEN=Pattern.compile("[\\t\\r\\n\\f ]+|//[^\\r\\n]*|[A-Za-z][A-Za-z0-9_]*|[0-9]+|[{}():;,.=+\\-]");
    private static final Pattern HASH=Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RESERVED=Set.of("define","class","as","end","pub","let","int","fnc","main","return","new","mut","const","val","self","actor","unsafe","suspends","await","import","void","bool","float","string","true","false","null","if","else","while","for","match","defer","recover","borrow","take","copy","move","ref","deref","unref","task","spawn","send","critical","static","private","constructor","namespace","module","using","with","set","Option","Result","Array","Map","List","RawPointer","Storage");
    private static IllegalArgumentException reject(String reason){return new IllegalArgumentException("E_NATIVE_READONLY: "+reason);}
    private record Field(String name,long value){}
    private record Helper(String name,boolean forward,int index){}
    private static final class Parser{
        final List<String> tokens=new ArrayList<>();
        final List<Field> fields=new ArrayList<>();
        final List<Helper> helpers=new ArrayList<>();
        final List<Integer> calls=new ArrayList<>();
        final List<String> ops=new ArrayList<>();
        int at;
        Parser(String source){
            if(source.length()>16384||!source.lines().findFirst().orElse("").equals("// atto-ownership: borrow-default-v1"))throw reject("marker or source budget");
            for(int i=0;i<source.length();i++){char c=source.charAt(i);if(c>126||(c<32&&"\t\r\n\f".indexOf(c)<0))throw reject("source character");}
            var scanner=TOKEN.matcher(source);
            int offset=0;
            while(offset<source.length()){
                scanner.region(offset,source.length());if(!scanner.lookingAt())throw reject("token");
                String t=scanner.group();offset=scanner.end();
                if(!t.isBlank()&&!t.startsWith("//"))tokens.add(t);
                if(tokens.size()>1024)throw reject("token budget");
            }
        }
        String peek(){return at<tokens.size()?tokens.get(at):"";}
        String pop(){if(at>=tokens.size())throw reject("unexpected end");return tokens.get(at++);}
        void need(String s){if(!pop().equals(s))throw reject("expected "+s);}
        String name(){String s=pop();if(!s.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||RESERVED.contains(s))throw reject("identifier");return s;}
        long number(){String s=pop();if(!s.matches("[0-9]{1,19}"))throw reject("literal");return Long.parseLong(s);}
        int call(String owner){String id=name();int i=0;while(i<helpers.size()&&!helpers.get(i).name.equals(id))i++;if(i==helpers.size())throw reject("call graph");need("(");need(owner);need(")");return i;}
        void parse(){
            need("define");need("class");String cls=name();need("as");
            while(peek().equals("pub")){
                if(fields.size()>=8)throw reject("field budget");
                need("pub");need("let");need("int");String id=name();
                if(fields.stream().anyMatch(f->f.name.equals(id)))throw reject("duplicate field");
                need("=");long value=number();need(";");fields.add(new Field(id,value));
            }
            if(fields.isEmpty())throw reject("fields");need("end");
            while(peek().equals("fnc")){
                if(helpers.size()>=8)throw reject("helper budget");
                need("fnc");String id=name();if(id.equals(cls)||helpers.stream().anyMatch(h->h.name.equals(id)))throw reject("duplicate helper");
                need("(");need(cls);String p=name();need(")");need(":");need("int");need("{");need("return");
                if(peek().equals(p)){
                    need(p);need(".");String field=name();int i=0;while(i<fields.size()&&!fields.get(i).name.equals(field))i++;
                    if(i==fields.size())throw reject("unknown field");
                    helpers.add(new Helper(id,false,i));
                }else helpers.add(new Helper(id,true,call(p)));
                need(";");need("}");
            }
            if(helpers.isEmpty())throw reject("helpers");
            need("pub");need("fnc");need("main");need("(");need(")");need(":");need("int");need("{");
            need("let");String owner=name();need("=");need("new");need(cls);need("(");need(")");need(";");
            need("return");calls.add(call(owner));
            while(peek().equals("+")||peek().equals("-")){if(calls.size()>=8)throw reject("call budget");ops.add(pop());calls.add(call(owner));}
            need(";");need("}");if(at!=tokens.size())throw reject("trailing source");
        }
    }
    public static String emit(String source){
        Parser p=new Parser(source);p.parse();
        StringBuilder out=new StringBuilder("public final class AttoReadonlyGuest {\nprivate static final class Obj {\n");
        for(int i=0;i<p.fields.size();i++)out.append("final long f").append(i).append("=").append(p.fields.get(i).value).append("L;\n");
        out.append("}\n");
        for(int i=0;i<p.helpers.size();i++){Helper h=p.helpers.get(i);out.append("private static long f").append(i).append("(Obj p){return ").append(h.forward?"f"+h.index+"(p)":"p.f"+h.index).append(";}\n");}
        out.append("public static long run(){Obj p=new Obj();long r=f").append(p.calls.get(0)).append("(p);\n");
        for(int i=1;i<p.calls.size();i++)out.append("r=Math.").append(p.ops.get(i-1).equals("+")?"addExact":"subtractExact").append("(r,f").append(p.calls.get(i)).append("(p));\n");
        return out.append("return r;}\npublic static void main(String[] args){System.out.println(run());}\n}\n").toString();
    }
    public static int compile(Path source, String expectedSha, Path output) {
        Path temporary = null;
        try {
            Objects.requireNonNull(source);
            Objects.requireNonNull(output);
            if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !source.toString().endsWith(".atto"))
                throw reject("source must be regular nonsymlinked .atto");
            Path parent = output.toAbsolutePath().getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                throw reject("output parent must be a real directory");
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
                throw reject("output already exists");
            long size = Files.size(source);
            if (size < 1 || size > 16384) throw reject("source byte budget");
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length != size) throw reject("source changed during admission");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (expectedSha == null || !HASH.matcher(expectedSha).matches()
                    || !actual.equals(expectedSha)) throw reject("source SHA-256 mismatch");
            String code = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            String javaCode = emit(code);
            temporary = Files.createTempFile(parent, ".atto-readonly-java-", ".tmp");
            Files.writeString(temporary, javaCode, StandardCharsets.UTF_8,
                              StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Atomic same-directory publish, no replace of attacker-planted file/symlink.
            Files.createLink(output, temporary);
            System.out.println("ATTO_READONLY_JAVA_COMPILE_PASS sha256=" + actual
                              );
            return 0;
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException ex) {
            System.err.println("ATTO_READONLY_JAVA_REJECT: " + ex.getMessage());
            return 2;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { System.err.println("ATTO_M1_TEMP_CLEANUP_FAILED"); }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 5 || !args[1].equals("--source-sha256")
                || !args[3].equals("--out")) {
            System.err.println("Usage: <source.atto> --source-sha256 <64 hex> --out <new.java>");
            System.exit(2);
        }
        System.exit(compile(Path.of(args[0]), args[2], Path.of(args[4])));
    }
}
