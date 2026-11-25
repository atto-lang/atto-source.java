package dev.oreslang;

import dev.oreslang.imports.FrozenImportSnapshot;
import dev.oreslang.imports.StaticImportGraph;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class FrozenImportSnapshotTest {
    private static final String NAME="@org/project/package", VERSION="1.0.0", PATH="src/network/namespace.atto";
    private static final String MANIFEST="[package]\nname = \""+NAME+"\"\nversion = \""+VERSION+"\"\n[namespaces]\nnetwork = \"src/network/\"\n[exports]\n\"network/http\" = \"network\"\n";
    private static final String SOURCE="use namespace network; pub fnc answer(): int { return 42; } fnc secret(): int { return 7; }";
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String sha(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static byte[] lock(String manifest,String path,String source) throws Exception {
        return bytes("[lock]\nmanifest_sha256 = \""+sha(bytes(manifest))+"\"\n[files]\n\""+path+"\" = \""+sha(bytes(source))+"\"\n");
    }
    private static FrozenImportSnapshot verify(String manifest,String source) throws Exception {
        byte[] l=lock(manifest,PATH,source);
        return FrozenImportSnapshot.verify(NAME,VERSION,sha(l),bytes(manifest),l,Map.of(PATH,bytes(source)));
    }
    @Test void verifiedBytesBecomeParserDerivedFunctionView() throws Exception {
        byte[] source=bytes(SOURCE), l=lock(MANIFEST,PATH,SOURCE);
        var snapshot=FrozenImportSnapshot.verify(NAME,VERSION,sha(l),bytes(MANIFEST),l,Map.of(PATH,source));
        Arrays.fill(source,(byte)'x');
        assertEquals(SOURCE,snapshot.providerSource("network"));
        assertEquals("network",snapshot.namespaceForSubpath("network/http"));
        assertEquals("network",snapshot.selectNamespace(NAME,"api.network"));
        assertEquals("network",snapshot.selectNamespace(NAME+"/network/http","other"));
        assertThrows(IllegalArgumentException.class,()->snapshot.selectNamespace(NAME+"/src/network","network"));
        assertThrows(IllegalArgumentException.class,()->snapshot.selectNamespace("@wrong/project/package","network"));
        var scope=new StaticImportGraph();
        var view=snapshot.functionView();
        scope.bindRoot(view,"p",StaticImportGraph.Tier.DELIBERATE);
        assertEquals("network.answer",scope.resolve("p.network.answer").path());
        snapshot.bindNamespace(scope,NAME+"/network/http","net","api",true);
        assertSame(view,snapshot.functionView());
        assertEquals("network.answer",scope.resolve("api.answer").path());
        assertThrows(IllegalArgumentException.class,()->snapshot.bindNamespace(scope,NAME,"network","hiddenRename",false));
        assertTrue(assertThrows(IllegalArgumentException.class,()->scope.resolve("p.network.secret")).getMessage().startsWith("E_PRIVATE"));
        assertThrows(IllegalArgumentException.class,()->snapshot.namespaceForSubpath("src/network"));
    }
    @Test void independentPinsRejectTamperingAndMissingInventory() throws Exception {
        byte[] l=lock(MANIFEST,PATH,SOURCE); String hash=sha(l);
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,VERSION,"0".repeat(64),bytes(MANIFEST),l,Map.of(PATH,bytes(SOURCE))));
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,VERSION,hash,bytes(MANIFEST+"\n"),l,Map.of(PATH,bytes(SOURCE))));
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,VERSION,hash,bytes(MANIFEST),l,Map.of(PATH,bytes(SOURCE+" "))));
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,VERSION,hash,bytes(MANIFEST),l,Map.of()));
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,"2.0.0",hash,bytes(MANIFEST),l,Map.of(PATH,bytes(SOURCE))));
        assertThrows(IllegalArgumentException.class,()->FrozenImportSnapshot.verify(NAME,VERSION,hash,bytes(MANIFEST),l,Map.of(PATH.toUpperCase(Locale.ROOT),bytes(SOURCE))));
    }
    @Test void evenPinnedMalformedMetadataAndSourceCannotGrantAuthority() throws Exception {
        for(String invalid:List.of(MANIFEST.replace("src/network/","../network/"),
            MANIFEST.replace("\"network/http\" = \"network\"","\"network/http\" = \"missing\""),
            MANIFEST.replace("[exports]","other = \"src/network/\"\n[exports]"),
            MANIFEST+"[exports]\n",MANIFEST.replace("\n","\r")))
            assertThrows(IllegalArgumentException.class,()->verify(invalid,SOURCE));
        var mismatch=verify(MANIFEST,SOURCE.replace("namespace network","namespace other"));
        assertThrows(IllegalArgumentException.class,mismatch::functionView);
        var importing=verify(MANIFEST,"use namespace network; import fnc f from './unverified.atto'; pub fnc answer(): int { return 42; }");
        assertThrows(IllegalArgumentException.class,importing::functionView);
    }
}
