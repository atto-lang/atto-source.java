package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContextualIntGuestTest {
    static String source(String context,String expr,String body){
        return "define class Scale as pub static fnc apply::("+context+")(int value) -> int { return "+expr+"; } end fnc main() -> int { "+body+" }";
    }
    static String single(String body){return source("int factor","factor * value",body);}
    static void guest(String src,long expected){
        try(Context c=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()){
            var value=c.eval(Source.newBuilder(OresLanguage.ID,src,"contextual-int.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
            assertEquals(expected,value.asLong(),src);
        }
    }
    @Test void directAndReusableGuest(){
        guest(single("return Scale::(6).apply(7);"),42);
        guest(single("const x = Scale::(2); const y = Scale::(3); return x.apply(4) + y.apply(4);"),20);
        guest(source("int factor, int offset","factor * value + offset","const x = Scale::(2,3); const y = Scale::(4,5); return x.apply(7) + y.apply(7);"),50);
        guest(single("const x = Scale::(6); const y = x; const z = y; return z.apply(7);"),42);
        guest(single("const x = Scale::(2 + 4); return x.apply(7);"),42);
    }
    @Test void malformedSourceFailsBeforeGuest(){
        for(String body:new String[]{
            "return Scale::(true).apply(7);",
            "return Scale::(2,3).apply(7);",
            "return Scale::(2).apply();",
            "return Scale::(2).other(7);",
            "const x = Scale::(2); return x;",
            "const x = Scale::(2); const x = x; return x.apply(7);",
            "const x = Scale::(2); const y = x; y = 3; return x.apply(7);",
            "return Scale::(factor=2).apply(7);",
            "return Scale::(2).apply([7);",
            "return atto_context_v1_member(2,7);",
            "const x = Scale::(6); return x.apply(7); const y = x;",
            "return Scale::(2).apply(\"7\");"
        })assertThrows(IllegalArgumentException.class,()->OresCompiler.parseAndTypeCheckNumericProfile(single(body),true),body);
    }
    @Test void ordinaryGuestUnaffected(){guest("pub fnc main():int { return 42; }",42);}
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ATTO_CONTEXT_CORPUS",matches=".+")
    void exactSharedSourceCorpus() throws Exception {
        var root=java.nio.file.Path.of(System.getenv("ATTO_CONTEXT_CORPUS"));
        var positive=java.util.Map.of("direct",42L,"independent-bindings",20L,
            "multi-independent",50L,"immutable-alias",42L);
        int executed=0,rejected=0,outside=0;
        for(String kind:new String[]{"pass","fail"}){
            try(var paths=java.nio.file.Files.list(root.resolve(kind))){
                for(var file:paths.sorted().toList()){
                    String id=file.getFileName().toString().replaceFirst("\\.atto$","");
                    byte[] bytes=java.nio.file.Files.readAllBytes(file);
                    var sha=java.security.MessageDigest.getInstance("SHA-1");
                    sha.update(("blob "+bytes.length+"\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    String blob=java.util.HexFormat.of().formatHex(sha.digest(bytes));
                    String src=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                    if(kind.equals("fail")){
                        var error=assertThrows(IllegalArgumentException.class,
                            ()->OresCompiler.parseAndTypeCheckNumericProfile(src,true),id);
                        System.out.println("SHARED_REJECT_UNCLASSIFIED "+id+" "+blob+" "+error.getMessage());
                        rejected++;
                    }else if(positive.containsKey(id)){
                        guest(src,positive.get(id));
                        System.out.println("SHARED_EXECUTED "+id+" "+blob+" "+positive.get(id));executed++;
                    }else{
                        try{
                            guest(src,id.equals("snapshot-reuse")?41L:id.equals("multi-snapshot")?34L:42L);
                            System.out.println("SHARED_EXECUTED "+id+" "+blob);executed++;
                        }catch(org.graalvm.polyglot.PolyglotException|IllegalArgumentException ex){
                            if(!java.util.Set.of("snapshot-reuse","multi-snapshot","legacy-body").contains(id))throw ex;
                            System.out.println("SHARED_OUTSIDE_PROFILE "+id+" "+blob+" "+ex.getMessage());outside++;
                        }
                    }
                }
            }
        }
        assertEquals(7,executed+outside);assertEquals(13,rejected);
        System.out.println("SHARED_SUMMARY executed="+executed+" rejectedUnclassified="+rejected+" outside="+outside);
    }

    @Test void storedNamedContexts(){
        guest(source("int factor, int offset","factor * value + offset",
            "const x = Scale::(offset=7,factor=5); return x.apply(7);"),42);
        for(String body:new String[]{
            "const x = Scale::(offset=7,offset=5); return x.apply(7);",
            "const x = Scale::(unknown=7,factor=5); return x.apply(7);",
            "const x = Scale::(offset=7,5); return x.apply(7);",
            "const x = Scale::(offset=7); return x.apply(7);"
        })assertThrows(IllegalArgumentException.class,()->OresCompiler.parseAndTypeCheckNumericProfile(
            source("int factor, int offset","factor * value + offset",body),true),body);
    }
}
