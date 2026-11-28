package dev.oreslang.compiler;
import java.util.*;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CompilerArtifactReceiptTest {
    private static final String KEY="6d0658c41d4e45ede029ca0c7654333c5aa0bc117f3179f12832aa7b1d22b657";
    private static byte[] secret(){byte[] s=new byte[32];for(int i=0;i<32;i++)s[i]=(byte)i;return s;}
    private static byte[] payload(){return HexFormat.of().parseHex("6174746f2d61727469666163742d763100ff");}
    @Test void fixedVectorAndTamperRejection()throws Exception{
        byte[] bytes=payload(), sealed=CompilerArtifactReceipt.seal(KEY,bytes,secret());
        assertEquals("3653396c79c689b07bb08274d60b4de17d57d0c9dd4fd22180aa7af88940b524",HexFormat.of().formatHex(sealed,80,112));
        assertArrayEquals(bytes,CompilerArtifactReceipt.verify(KEY,sealed,secret()));
        for(int i:new int[]{0,8,40,48,80,112,sealed.length-1}){
            byte[] changed=sealed.clone();changed[i]^=1;
            assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.verify(KEY,changed,secret()));
        }
        byte[] forged=sealed.clone();forged[112]^=1;
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(forged,112,forged.length)),0,forged,48,32);
        assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.verify(KEY,forged,secret()));
        assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.verify("0".repeat(64),sealed,secret()));
        assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.verify(KEY,sealed,new byte[32]));
        for(byte[] bad:List.of(Arrays.copyOf(sealed,111),Arrays.copyOf(sealed,sealed.length+1),Arrays.copyOf(sealed,sealed.length-1),new byte[112+CompilerArtifactReceipt.MAX_ARTIFACT+1]))
            assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.verify(KEY,bad,secret()));
        assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.seal(KEY,bytes,new byte[31]));
        assertThrows(IllegalArgumentException.class,()->CompilerArtifactReceipt.seal(KEY,new byte[CompilerArtifactReceipt.MAX_ARTIFACT+1],secret()));
        bytes[0]^=1;assertArrayEquals(payload(),CompilerArtifactReceipt.verify(KEY,sealed,secret()));
        var returned=CompilerArtifactReceipt.verify(KEY,sealed,secret());returned[0]^=1;
        assertArrayEquals(payload(),CompilerArtifactReceipt.verify(KEY,sealed,secret()));
        assertArrayEquals(new byte[0],CompilerArtifactReceipt.verify(KEY,CompilerArtifactReceipt.seal(KEY,new byte[0],secret()),secret()));
    }
}
