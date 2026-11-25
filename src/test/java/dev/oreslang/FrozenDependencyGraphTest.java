package dev.oreslang;

import dev.oreslang.imports.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class FrozenDependencyGraphTest {
    private static final String A="@org/project/a",B="@org/project/b",C="@org/project/c",D="@org/project/d";
    private static final String PATH="src/network.atto", SOURCE="use namespace network; pub fnc answer():int{return 42;}";
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static FrozenImportSnapshot snapshot(String name,String version,Map<String,String> declared,Map<String,String> pins)throws Exception{
        return snapshot(name,version,declared,pins,SOURCE);
    }
    private static FrozenImportSnapshot snapshot(String name,String version,Map<String,String> declared,Map<String,String> pins,String source)throws Exception{
        var m=new StringBuilder("[package]\nname = \""+name+"\"\nversion = \""+version+"\"\n[namespaces]\nnetwork = \""+PATH+"\"\n[exports]\n\"api\" = \"network\"\n");
        if(!declared.isEmpty()){
            m.append("[dependencies]\n");
            new TreeMap<>(declared).forEach((k,v)->m.append("\"").append(k).append("\" = \"").append(v).append("\"\n"));
        }
        var l=new StringBuilder("[lock]\nmanifest_sha256 = \""+sha(bytes(m.toString()))+"\"\n[files]\n\""+PATH+"\" = \""+sha(bytes(source))+"\"\n");
        if(!pins.isEmpty()){
            l.append("[dependencies]\n");
            new TreeMap<>(pins).forEach((k,v)->l.append("\"").append(k).append("\" = \"").append(v).append("\"\n"));
        }
        byte[] lock=bytes(l.toString());
        return FrozenImportSnapshot.verify(name,version,sha(lock),bytes(m.toString()),lock,Map.of(PATH,bytes(source)));
    }
    private static FrozenImportSnapshot withDeps(String name,FrozenImportSnapshot... dependencies)throws Exception{
        var versions=new TreeMap<String,String>();var pins=new TreeMap<String,String>();
        for(var p:dependencies){versions.put(p.name(),p.version());pins.put(p.name(),p.lockDigest());}
        return snapshot(name,"1.0.0",versions,pins);
    }
    private static void deny(String code,org.junit.jupiter.api.function.Executable operation){
        var e=assertThrows(IllegalArgumentException.class,operation);
        assertTrue(e.getMessage().startsWith(code+":"),e.getMessage());
    }
    @Test void diamondClosureIsDeterministicAndDoesNotGrantTransitiveAuthority()throws Exception{
        var c=withDeps(C);var b=withDeps(B,c);var d=withDeps(D,c);var a=withDeps(A,b,d);
        for(var list:List.of(List.of(a,b,c,d),List.of(d,c,b,a))){
            var graph=FrozenDependencyGraph.verify(list);
            assertEquals(List.of(C,B,D,A),graph.dependencyOrder());
            assertSame(b,graph.requireDependency(A,B));
            assertSame(c,graph.requireDependency(B,C));
            deny("E_UNBOUND",()->graph.requireDependency(A,C));
        }
        assertThrows(UnsupportedOperationException.class,()->a.dependencies().clear());
        deny("E_UNBOUND",()->FrozenDependencyGraph.verify(List.of(a,b,d)));
        deny("E_AMBIGUOUS",()->FrozenDependencyGraph.verify(List.of(a,b,c,d,c)));
        var changed=snapshot(C,"2.0.0",Map.of(),Map.of());
        deny("E_LOCK_HASH",()->FrozenDependencyGraph.verify(List.of(a,b,changed,d)));
        var wrongPin=snapshot(B,"1.0.0",Map.of(C,"1.0.0"),Map.of(C,"0".repeat(64)));
        deny("E_LOCK_HASH",()->FrozenDependencyGraph.verify(List.of(wrongPin,c)));
        deny("E_LOCK",()->snapshot(B,"1.0.0",Map.of(C,"1.0.0"),Map.of()));
        deny("E_LOCK",()->snapshot(B,"1.0.0",Map.of(),Map.of(C,c.lockDigest())));
        deny("E_LOCK",()->snapshot(B,"1.0.0",Map.of("../escape","1.0.0"),Map.of("../escape",c.lockDigest())));
    }
    @Test void cycleDiagnosticsPrecedeInconsistentEdgeHashes()throws Exception{
        // A cycle cannot honestly pin its own complete lock digest. These
        // independently verified malformed snapshots intentionally use dummy
        // edge pins; structural cycles must be diagnosed before pin mismatch.
        var a=snapshot(A,"1.0.0",Map.of(B,"1.0.0"),Map.of(B,"0".repeat(64)));
        var b=snapshot(B,"1.0.0",Map.of(A,"1.0.0"),Map.of(A,"0".repeat(64)));
        for(var list:List.of(List.of(a,b),List.of(b,a))){
            var e=assertThrows(IllegalArgumentException.class,()->FrozenDependencyGraph.verify(list));
            assertEquals("E_IMPORT_CYCLE: "+A+" -> "+B+" -> "+A,e.getMessage());
        }
        var self=snapshot(A,"1.0.0",Map.of(A,"1.0.0"),Map.of(A,"0".repeat(64)));
        deny("E_IMPORT_CYCLE",()->FrozenDependencyGraph.verify(List.of(self)));
    }
    @Test void compilerRejectsIncompleteClosureBeforeProviderLinking()throws Exception{
        var c=withDeps(C);var b=withDeps(B,c);var a=withDeps(A,b);
        String guest="import pkg '"+A+"' as p; pub fnc main()->int{return p.network.answer();}";
        deny("E_UNBOUND",()->FrozenScalarImportCompiler.compile(guest,List.of(a,b)));
        var checked=FrozenScalarImportCompiler.compile(guest,List.of(a,b,c));
        assertEquals(42,checked.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
    }
    @Test void actualGuestCallsAcrossThreePinnedProviders()throws Exception {
        var c=snapshot(C,"1.0.0",Map.of(),Map.of(),
            "use namespace network; fnc secret():int{return 99;} pub fnc answer(int x):int{return x*2;}");
        var b=snapshot(B,"1.0.0",Map.of(C,c.version()),Map.of(C,c.lockDigest()),
            "use namespace network; import fnc answer as twice from pkg '"+C+"/api'; pub fnc answer(int x):int{return twice(x)+1;}");
        var a=snapshot(A,"1.0.0",Map.of(B,b.version()),Map.of(B,b.lockDigest()),
            "use namespace network; import fnc answer as next from pkg '"+B+"/api'; pub fnc answer(int x):int{return next(x)+1;}");
        String guest="import pkg '"+A+"' as p; pub fnc main()->int{return p.network.answer(20);}";
        for(var list:List.of(List.of(a,b,c),List.of(c,b,a))) {
            var program=FrozenScalarImportCompiler.compile(guest,list);
            assertEquals(42,program.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        }
        deny("E_UNBOUND",()->FrozenScalarImportCompiler.compile(guest,List.of(a,b)));
        var jump=snapshot(A,"1.0.0",Map.of(B,b.version()),Map.of(B,b.lockDigest()),
            "use namespace network; import fnc answer as next from pkg '"+C+"/api'; pub fnc answer(int x):int{return next(x);}");
        deny("E_UNBOUND",()->FrozenScalarImportCompiler.compile(guest,List.of(jump,b,c)));
        var hidden=snapshot(B,"1.0.0",Map.of(C,c.version()),Map.of(C,c.lockDigest()),
            "use namespace network; import fnc secret from pkg '"+C+"/api'; pub fnc answer(int x):int{return secret();}");
        String fromB="import pkg '"+B+"' as p; pub fnc main()->int{return p.network.answer(20);}";
        deny("E_PRIVATE",()->FrozenScalarImportCompiler.compile(fromB,List.of(hidden,c)));
        deny("E_UNBOUND",()->FrozenScalarImportCompiler.compile(fromB.replace("answer","twice"),List.of(b,c)));
        var physical=snapshot(B,"1.0.0",Map.of(C,c.version()),Map.of(C,c.lockDigest()),
            "use namespace network; import fnc answer from pkg '"+C+"/src/network'; pub fnc other(int x):int{return answer(x);}");
        deny("E_UNBOUND",()->FrozenScalarImportCompiler.compile(fromB,List.of(physical,c)));
    }
}
