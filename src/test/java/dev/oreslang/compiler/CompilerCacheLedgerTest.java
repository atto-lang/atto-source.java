package dev.oreslang.compiler;
import java.util.*;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CompilerCacheLedgerTest {
    @Test void crossBackendVectorAndCurrentAnchor()throws Exception{
        byte[] master=new byte[32];Arrays.fill(master,(byte)7);
        byte[] record=CompilerCacheLedger.record("tenant/a",2,master);
        byte[] pin=MessageDigest.getInstance("SHA-256").digest(record);
        assertEquals("4154544f4c4544310000000874656e616e742f610000000000000002a63c8f18ca3245c9d319f61b2ca45a2a6605ea3293664ad236792133ce088462",HexFormat.of().formatHex(record));
        assertEquals("95c69714073e8a9a4c845fa54d0f87706f718fed1f5879016723780618b83309",HexFormat.of().formatHex(pin));
        assertEquals("d2153371616fc59b235d24aff7417a37fea38a6fdc5391503a54ea33f429a90e",HexFormat.of().formatHex(CompilerCacheLedger.admit("tenant/a",record,pin,master)));
        byte[] old=CompilerCacheLedger.record("tenant/a",1,master);
        assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/a",old,pin,master));
        assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/b",record,pin,master));
        assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/a",record,pin,new byte[32]));
        byte[] wrong=master.clone();wrong[0]^=1;
        assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/a",record,pin,wrong));
        for(int i:new int[]{0,8,12,20,record.length-1}){
            byte[] bad=record.clone();bad[i]^=1;
            assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/a",bad,pin,master));
        }
        for(int n:new int[]{0,1,record.length-1,record.length+1,565}){
            byte[] bad=Arrays.copyOf(record,n);
            assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.admit("tenant/a",bad,pin,master));
        }
        byte[] high=CompilerCacheLedger.record("tenant/a",-1L,master);
        assertEquals(32,CompilerCacheLedger.admit("tenant/a",high,MessageDigest.getInstance("SHA-256").digest(high),master).length);
        assertThrows(IllegalArgumentException.class,()->CompilerCacheLedger.record("tenant/a",0,master));
    }
}
