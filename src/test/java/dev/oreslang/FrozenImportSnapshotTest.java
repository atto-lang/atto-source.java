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
    @Test void frozenGuestLinksAndExecutesBothAliasSpellings() throws Exception {
        var snapshot=verify(MANIFEST,SOURCE);
        String header="import namespace network from pkg '"+NAME+"'; ";
        String body="pub fnc main() -> int { return web.answer(); }";
        for(String alias:List.of("define alias web = network;",
                "define alias network as web;",
                "define alias web = next; define alias next = network;")) {
            var guest=dev.oreslang.imports.FrozenScalarImportCompiler.compile(header+alias+body,List.of(snapshot));
            assertEquals(42,guest.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        }
        var subpath=dev.oreslang.imports.FrozenScalarImportCompiler.compile(
            "import namespace net as web from pkg '"+NAME+"/network/http'; "+body,List.of(snapshot));
        assertEquals(42,subpath.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        for(String prefix:List.of("package","project.package","org.project.package")) {
            var root=dev.oreslang.imports.FrozenScalarImportCompiler.compile(
                "import pkg '"+NAME+"'; define alias web = "+prefix+".network; "+body,List.of(snapshot));
            assertEquals(42,root.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        }
        var renamed=dev.oreslang.imports.FrozenScalarImportCompiler.compile(
            "import pkg '"+NAME+"' as root; define alias root.network as web; "+body,List.of(snapshot));
        assertEquals(42,renamed.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
    }

    @Test void frozenGuestRejectsPrivateMissingCyclicAndUnsupportedInputs() throws Exception {
        var snapshot=verify(MANIFEST,SOURCE);
        String header="import namespace network from pkg '"+NAME+"'; ";
        for(String bad:List.of(
            "import pkg '@unknown/project/package'; define alias a = a; pub fnc main() -> int { return a.answer(); }",
            "import pkg '"+NAME+"' as root; pub fnc main() -> int { return package.network.answer(); }",
            "import pkg '"+NAME+"'; pub fnc main() -> int { return package.answer(); }",
            header+"pub fnc main() -> int { return network.secret(); }",
            header+"pub fnc main() -> int { return network.missing(); }",
            header+"define alias a = b; define alias b = a; pub fnc main() -> int { return network.answer(); }",
            header+"define alias a = network.answer; pub fnc main() -> int { return a(); }",
            header+"pub fnc main() -> int { return network.answer(); } fnc extra(): int { return 1; }",
            header+"pub fnc main() -> int { let int network = 2; return network.answer(); }",
            "import namespace network from pkg '@wrong/project/package'; pub fnc main() -> int { return network.answer(); }",
            "import namespace network from pkg '"+NAME+"/src/network'; pub fnc main() -> int { return network.answer(); }"))
            assertThrows(RuntimeException.class,()->dev.oreslang.imports.FrozenScalarImportCompiler.compile(bad,List.of(snapshot)),bad);
    }
    @Test void frozenCallsCheckExactLiteralArgumentAbi() throws Exception {
        var snapshot=verify(MANIFEST,"use namespace network; pub fnc answer(int x, int y): int { return x - y; }");
        String header="import namespace network from pkg '"+NAME+"'; define alias web = network; ";
        for(String args:List.of("40, -2","4_0, -2")) {
            String source=header+"pub fnc main() -> int { return web.answer("+args+"); }";
            var guest=dev.oreslang.imports.FrozenScalarImportCompiler.compile(source,List.of(snapshot));
            assertEquals(42,guest.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        }
        for(String args:List.of("40","40, -2, 1","40, true","40, 2.0","40, 1/1",
                "9223372036854775808, 0","-9223372036854775809, 0","40, -2,",String.join(",",Collections.nCopies(17,"1")))) {
            String source=header+"pub fnc main() -> int { return web.answer("+args+"); }";
            assertThrows(RuntimeException.class,()->dev.oreslang.imports.FrozenScalarImportCompiler.compile(source,List.of(snapshot)),args);
        }
        var boolProvider=verify(MANIFEST,"use namespace network; pub fnc answer(bool x, int y): int { return y; }");
        var e=assertThrows(IllegalArgumentException.class,()->dev.oreslang.imports.FrozenScalarImportCompiler.compile(
            header+"pub fnc main() -> int { return web.answer(40, -2); }",List.of(boolProvider)));
        assertTrue(e.getMessage().contains("E_IMPORT_KIND"),e.getMessage());
    }
    @Test void frozenCallsSupportTypedExpressionsAndScalarLocals() throws Exception {
        var snapshot=verify(MANIFEST,"use namespace network; pub fnc answer(int x, int y): int { return x - y; }");
        String header="import namespace network from pkg '"+NAME+"'; define alias web = network; pub fnc main() -> int { ";
        for(String body:List.of(
            "return web.answer(2 + 19 * 2, -2);",
            "return web.answer((2 + 18) * 2, -2);",
            "val int x = 20; val int y = x * 2; return web.answer(y, -2);",
            "val int selected_call = 40; return web.answer(selected_call, -2);")) {
            var guest=dev.oreslang.imports.FrozenScalarImportCompiler.compile(header+body+" }",List.of(snapshot));
            assertEquals(42,guest.run(dev.oreslang.runtime.ExecutionProfile.parse("aot","server")));
        }
        for(String body:List.of(
            "val int x = x; return web.answer(x, -2);",
            "val int x = y; val int y = 40; return web.answer(x, -2);",
            "val int x = 20; val int x = 40; return web.answer(x, -2);",
            "val int web = 40; return web.answer(web, -2);",
            "val int x = 40; x = 41; return web.answer(x, -2);",
            "return web.answer(web.answer(40, -2), 0);",
            "return web.answer("+"(".repeat(17)+"40"+")".repeat(17)+", -2);",
            "return web.answer("+String.join("+",Collections.nCopies(65,"1"))+", -2);")) {
            assertThrows(RuntimeException.class,()->dev.oreslang.imports.FrozenScalarImportCompiler.compile(header+body+" }",List.of(snapshot)),body);
        }
    }
}
