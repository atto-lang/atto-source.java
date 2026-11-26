package dev.oreslang;
import dev.oreslang.compiler.PinnedNamespaceInventory;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class PinnedNamespaceInventoryTest {
    private static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static final String MANIFEST="schema_version = \"2\"\n[project]\nroot = \".\"\n[packages.checkout]\nfiles = [\"src/a.ores\",\"src/b.ores\",\"src/c.ores\"]\n";
    private static final Map<String,String> SOURCES=Map.of("src/a.ores","namespace billing; pub fnc a(): int { return 1; }","src/b.ores","namespace billing; pub fnc b(): int { return 2; }","src/c.ores","pub fnc c(): int { return 3; }");
    private static String consumer(Map<String,String> sources)throws Exception{
        var pin=new StringBuilder();for(var e:new TreeMap<>(sources).entrySet())pin.append(e.getKey()).append('\n').append(hash(e.getValue())).append('\n');
        return "schema_version = \"2\"\n[project]\nroot = \".\"\n[namespace_dependencies.checkout]\nmanifest = \"../checkout/.ores.pkg.toml\"\npackage = \"checkout\"\nmanifest_sha256 = \""+hash(MANIFEST)+"\"\nsources_sha256 = \""+hash(pin.toString())+"\"\n";
    }
    private static dev.oreslang.ast.Ast.ImportDecl selector(String ns){return Parser.parse("import namespace "+ns+" as p from 'checkout'; pub fnc main(): int { return 0; }").imports().getFirst();}
    @Test void selectsMultipleExactProvidersWithoutImplicitNamespace()throws Exception{
        var result=PinnedNamespaceInventory.admit(selector("billing"),consumer(SOURCES),MANIFEST,SOURCES);
        assertEquals(List.of("src/a.ores","src/b.ores"),result.providers().stream().map(PinnedNamespaceInventory.Provider::path).toList());
        assertThrows(IllegalArgumentException.class,()->PinnedNamespaceInventory.admit(selector("checkout"),consumer(SOURCES),MANIFEST,SOURCES));
        assertThrows(UnsupportedOperationException.class,()->result.providers().clear());
    }
    @Test void rejectsStalePinBeforeParsingAndUnlistedMembership()throws Exception{
        var changed=new HashMap<>(SOURCES);changed.put("src/a.ores","not valid source");
        var ex=assertThrows(IllegalArgumentException.class,()->PinnedNamespaceInventory.admit(selector("billing"),consumer(SOURCES),MANIFEST,changed));
        assertTrue(ex.getMessage().contains("source pin mismatch"));
        changed.put("src/injected.ores","pub fnc leak(): int { return 9; }");
        assertThrows(IllegalArgumentException.class,()->PinnedNamespaceInventory.admit(selector("billing"),consumer(SOURCES),MANIFEST,changed));
        assertThrows(IllegalArgumentException.class,()->PinnedNamespaceInventory.admit(selector("billing"),consumer(SOURCES),MANIFEST+"# modified",SOURCES));
    }
    @Test void rejectsDuplicateProviderEvenWithNewPin()throws Exception{
        var changed=new HashMap<>(SOURCES);changed.put("src/b.ores",SOURCES.get("src/a.ores"));
        assertThrows(IllegalArgumentException.class,()->PinnedNamespaceInventory.admit(selector("billing"),consumer(changed),MANIFEST,changed));
        System.out.println("JAVA_NAMESPACE_INVENTORY|multi-provider|pins|no-implicit-namespace|duplicates-denied");
    }
    @Test void pinnedConsumerAndUnrelatedEditReuseCheckedUnits()throws Exception{
        var session=new dev.oreslang.runtime.PinnedScalarNamespaceSession();
        String source="namespace app; import namespace billing as p from 'checkout'; pub fnc main(): void { stdio.println(p.a()); return; }";
        String cm=consumer(SOURCES);
        var out=new java.io.ByteArrayOutputStream();
        var err=new java.io.ByteArrayOutputStream();
        var cold=session.runPinned(source,cm,MANIFEST,SOURCES,hash(source),hash(cm),out,err);
        assertEquals("1",out.toString(StandardCharsets.UTF_8).strip());
        assertEquals(3,cold.inventoryParsed());
        var local=new HashMap<>(SOURCES);local.put("src/c.ores","pub fnc c(): int { return 900; }");
        String repinned=consumer(local);out.reset();
        assertThrows(IllegalArgumentException.class,()->session.runPinned(source,repinned,MANIFEST,local,hash(source),hash(cm),out,err));
        assertThrows(IllegalArgumentException.class,()->session.runPinned(source,cm,MANIFEST,local,hash(source),hash(cm),out,err));
        assertEquals(0,out.size());
        var warm=session.runPinned(source,repinned,MANIFEST,local,hash(source),hash(repinned),out,err);
        assertEquals("1",out.toString(StandardCharsets.UTF_8).strip());
        assertEquals(1,warm.inventoryParsed());assertEquals(2,warm.inventoryReused());
        assertEquals(0,warm.consumerParsed());
        assertTrue(warm.build().rebuilt().isEmpty());
        assertEquals(cold.build().units().keySet(),warm.build().units().keySet());
        System.out.println("JAVA_PINNED_BOUND_CHECK|fresh-pins|local-edit|zero-rebuilt-units|actual-guest");
    }
    @Test void forkedCompilerDoesNotPublishToAcceptedSession(){
        var accepted=new dev.oreslang.compiler.IncrementalCompiler();
        var initial=Map.of("main.ores","pub fnc main(): int { return 42; }");
        var edited=Map.of("main.ores","pub fnc main(): int { return 43; }");
        accepted.compile(initial);
        var candidate=accepted.fork();
        assertFalse(candidate.compile(edited).rebuilt().isEmpty());
        assertTrue(accepted.compile(initial).rebuilt().isEmpty());
        assertTrue(candidate.compile(edited).rebuilt().isEmpty());
        candidate.compile(Map.of());
        assertTrue(accepted.compile(initial).rebuilt().isEmpty());
        System.out.println("JAVA_CHECK_TRANSACTION|fork-isolation|discard|deletion-isolation");
    }
}
