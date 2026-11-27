package dev.oreslang.compiler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CompilerCacheKeyTest {
    private static String h(int n){return String.format("%02x",n).repeat(32);}
    private static final String EXPECTED="6d0658c41d4e45ede029ca0c7654333c5aa0bc117f3179f12832aa7b1d22b657";
    private static final List<CompilerCacheKey.Dependency> DEPS=List.of(
        new CompilerCacheKey.Dependency("@org/project/b",h(7)),new CompilerCacheKey.Dependency("@org/project/c",h(8)));
    private static String key(String stage,String owner,String kind,String unit,String[] hashes,String generated,List<CompilerCacheKey.Dependency> deps) {
        return CompilerCacheKey.encode(new CompilerCacheKey.Input(stage,owner,kind,unit,
            hashes[0],hashes[1],hashes[2],hashes[3],hashes[4],hashes[5],generated,deps));
    }
    private static String[] hashes(){return new String[]{h(1),h(2),h(3),h(4),h(5),h(6)};}
    private static String base(String[] hashes,String generated,List<CompilerCacheKey.Dependency> deps) {
        return key("typed-ir","@org/project/a","namespace","network",hashes,generated,deps);
    }
    @Test void vectorAndFieldDrift(){
        assertEquals(EXPECTED,base(hashes(),null,DEPS));
        assertEquals(EXPECTED,base(hashes(),null,DEPS.reversed()));
        for(int i=0;i<6;i++){var changed=hashes();changed[i]=h(9);assertNotEquals(EXPECTED,base(changed,null,DEPS));}
        assertNotEquals(EXPECTED,base(hashes(),h(0),DEPS));
        assertNotEquals(EXPECTED,base(hashes(),null,List.of()));
        assertNotEquals(EXPECTED,base(hashes(),null,List.of(new CompilerCacheKey.Dependency("@org/project/b",h(9)),DEPS.get(1))));
        assertNotEquals(EXPECTED,key("native-code","@org/project/a","namespace","network",hashes(),null,DEPS));
        assertNotEquals(EXPECTED,key("typed-ir","@org/project/z","namespace","network",hashes(),null,DEPS));
        assertNotEquals(EXPECTED,key("typed-ir","@org/project/a","source-unit","network",hashes(),null,DEPS));
        assertNotEquals(EXPECTED,key("typed-ir","@org/project/a","namespace","other",hashes(),null,DEPS));
        assertNotEquals(key("typed-ir","ab","namespace","c",hashes(),null,DEPS),
            key("typed-ir","a","namespace","bc",hashes(),null,DEPS));
    }
    @Test void rejectsMalformedAndSnapshotsInventory(){
        for(String owner:List.of("","a\n","a\0b","\ud800","x".repeat(513)))
            assertThrows(IllegalArgumentException.class,()->key("typed-ir",owner,"namespace","network",hashes(),null,DEPS));
        assertThrows(IllegalArgumentException.class,()->key("typed-ir","a","package","network",hashes(),null,DEPS));
        for(String bad:List.of("","F".repeat(64),h(1)+"\n")){
            assertThrows(IllegalArgumentException.class,()->base(hashes(),bad,DEPS));
            var changed=hashes();changed[0]=bad;assertThrows(IllegalArgumentException.class,()->base(changed,null,DEPS));
        }
        assertThrows(IllegalArgumentException.class,()->base(hashes(),null,List.of(DEPS.getFirst(),DEPS.getFirst())));
        assertThrows(IllegalArgumentException.class,()->base(hashes(),null,Collections.nCopies(129,DEPS.getFirst())));
        assertThrows(IllegalArgumentException.class,()->base(hashes(),null,Arrays.asList((CompilerCacheKey.Dependency)null)));
        var mutable=new ArrayList<>(DEPS);
        var input=new CompilerCacheKey.Input("typed-ir","@org/project/a","namespace","network",h(1),h(2),h(3),h(4),h(5),h(6),null,mutable);
        mutable.clear();assertEquals(EXPECTED,CompilerCacheKey.encode(input));
    }
}
