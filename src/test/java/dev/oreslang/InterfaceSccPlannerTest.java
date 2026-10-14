package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.InterfaceSccPlanner;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class InterfaceSccPlannerTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "jdk21", "linux-x64", "safe");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static ExportInterfaceCodec.InterfaceFile exported(String sig) {
        return ExportInterfaceCodec.encode(ENV, List.of(
            new ExportInterfaceCodec.Entry("api", ExportInterfaceCodec.Kind.FUNCTION, "work", sig)));
    }
    private static Map<String,Set<String>> edges() {
        return Map.of("a",Set.of("b"),"b",Set.of("a"),"consumer",Set.of("a"),"other",Set.of());
    }
    private static Map<String,ExportInterfaceCodec.InterfaceFile> apis() {
        return Map.of("a",exported("()int"),"b",exported("()int"),
                "consumer",exported("()int"),"other",exported("()int"));
    }
    private static Map<String,Map<String,String>> pins() {
        return Map.of("a",Map.of(),"b",Map.of(),"consumer",Map.of("vendor",HASH_A),
                "other",Map.of());
    }
    @Test void cycleIsAtomicAndChangeInvalidatesOnlyAffectedClosure() {
        var before=InterfaceSccPlanner.compute(edges(),apis(),pins());
        assertEquals(before.unitToGroupFingerprint().get("a"),
                     before.unitToGroupFingerprint().get("b"));
        var changed=new LinkedHashMap<>(apis());
        changed.put("a",exported("(String)int"));
        var after=InterfaceSccPlanner.compute(edges(),changed,pins());
        assertNotEquals(before.unitFingerprint().get("a"),after.unitFingerprint().get("a"));
        assertNotEquals(before.unitFingerprint().get("b"),after.unitFingerprint().get("b"));
        assertNotEquals(before.unitFingerprint().get("consumer"),
                        after.unitFingerprint().get("consumer"));
        assertEquals(before.unitFingerprint().get("other"),after.unitFingerprint().get("other"));
    }
    @Test void sourceOrderDoesNotChangeFingerprintsOrSccMembership() {
        var original=InterfaceSccPlanner.compute(edges(),apis(),pins());
        Map<String,Set<String>> rotated=new LinkedHashMap<>();
        rotated.put("other",Set.of());rotated.put("consumer",Set.of("a"));
        rotated.put("b",Set.of("a"));rotated.put("a",Set.of("b"));
        assertEquals(original.unitFingerprint(),
                InterfaceSccPlanner.compute(rotated,apis(),pins()).unitFingerprint());
        assertThrows(UnsupportedOperationException.class,()->original.unitFingerprint().clear());
    }
    @Test void invalidEdgesAndUnverifiedExternalPinsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->InterfaceSccPlanner.compute(
                Map.of("a",Set.of("missing")),Map.of("a",exported("x")),Map.of("a",Map.of())));
        assertThrows(IllegalArgumentException.class,()->InterfaceSccPlanner.compute(
                Map.of("a",Set.of("a")),Map.of("a",exported("x")),Map.of("a",Map.of())));
        assertThrows(IllegalArgumentException.class,()->InterfaceSccPlanner.compute(
                Map.of("a",Set.of()),Map.of("a",exported("x")),Map.of("a",Map.of("vendor","not-sha"))));
    }
    private static Ast.ImportDecl use(String path) {
        return new Ast.ImportDecl(Ast.ImportKind.FUNCTION,List.of("work"),false,null,path);
    }
    private static Ast.Program source(Ast.ImportDecl... imports) {
        var fn=new Ast.FunctionDecl("work",Ast.Visibility.PUBLIC,false,List.of(),List.of(),
                Ast.TypeRef.simple("int"),List.of(),List.of());
        return new Ast.Program(null,List.of(imports),List.of(new Ast.ModuleDecl("api",List.of(fn))));
    }
    @Test void actualImportBindingSwapMustInvalidateEvenWhenDependencySetIsSame() {
        var checked=Map.of("a.ores",source(use("./b.ores"),use("./c.ores")),
                "b.ores",source(use("./a.ores")),"c.ores",source(use("./a.ores")));
        var exposed=Map.of("a.ores",exported("()int"),"b.ores",exported("()int"),
                "c.ores",exported("()int"));
        var external=Map.of("a.ores",Map.<String,String>of(),"b.ores",Map.<String,String>of(),
                "c.ores",Map.<String,String>of());
        var before=InterfaceSccPlanner.fromValidatedImportGraph(checked,exposed,Map.of(),external);
        var after=InterfaceSccPlanner.fromValidatedImportGraph(checked,exposed,
                Map.of("a.ores",Map.of("./b.ores","c.ores","./c.ores","b.ores")),external);
        assertNotEquals(before.unitFingerprint().get("a.ores"),after.unitFingerprint().get("a.ores"));
        assertNotEquals(before.unitFingerprint().get("b.ores"),after.unitFingerprint().get("b.ores"));
    }
    @Test void rejectDanglingOverridesAndUnknownImporterOverrides() {
        var linked = Map.of("a.ores",source(use("./b.ores")),
                            "b.ores",source());
        var api = Map.of("a.ores",exported("()int"),"b.ores",exported("()int"));
        var pins = Map.of("a.ores",Map.<String,String>of(),
                          "b.ores",Map.<String,String>of());
        assertThrows(IllegalArgumentException.class, () ->
                InterfaceSccPlanner.fromValidatedImportGraph(linked,api,
                        Map.of("a.ores",Map.of("./nonexistent.ores","b.ores")),pins));
        assertThrows(IllegalArgumentException.class, () ->
                InterfaceSccPlanner.fromValidatedImportGraph(linked,api,
                        Map.of("unknown.ores",Map.of("./b.ores","b.ores")),pins));
    }

    @Test void rejectNoncanonicalUnitAndNamespaceMismatch() {
        var noncanonical="dir/../a.ores";
        assertThrows(IllegalArgumentException.class, () ->
                InterfaceSccPlanner.compute(
                        Map.of(noncanonical,Set.of()),
                        Map.of(noncanonical,exported("()int")),
                        Map.of(noncanonical,Map.<String,String>of())));
        var program=source();
        var altered=new Ast.Program("unexpected",program.imports(),program.modules());
        assertThrows(IllegalArgumentException.class, () ->
                InterfaceSccPlanner.fromValidatedImportGraph(
                        Map.of("a.ores",altered),
                        Map.of("a.ores",exported("()int")),
                        Map.of(),Map.of("a.ores",Map.<String,String>of())));
    }

    @Test void rejectExcessiveImportsBeforeLinking() {
        var many=new java.util.ArrayList<Ast.ImportDecl>();
        for(int i=0;i<4097;i++)many.add(use("./b.ores"));
        var program=new Ast.Program(List.copyOf(many), source().modules());
        var linked=Map.of("a.ores",program,"b.ores",source());
        assertThrows(IllegalArgumentException.class, () ->
                InterfaceSccPlanner.fromValidatedImportGraph(linked,
                        Map.of("a.ores",exported("()int"),"b.ores",exported("()int")),
                        Map.of(),Map.of("a.ores",Map.<String,String>of(),
                                     "b.ores",Map.<String,String>of())));
    }

    @Test void externalSelectorChangeInvalidatesEvenWithUnchangedProviderDigest() {
        var first=new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("original"),false,null,"vendor/external");
        var second=new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("renamed"),false,null,"vendor/external");
        var api=Map.of("a.ores",exported("()int"));
        var pins=Map.of("a.ores",Map.of("vendor/external",HASH_A));
        var original=InterfaceSccPlanner.fromValidatedImportGraph(
                Map.of("a.ores",source(first)),api,Map.of(),pins);
        var renamed=InterfaceSccPlanner.fromValidatedImportGraph(
                Map.of("a.ores",source(second)),api,Map.of(),pins);
        assertNotEquals(original.unitFingerprint(),renamed.unitFingerprint());
    }

    @Test void exactReverseReachabilityMatchesInvalidationForDeterministicRandomGraphs() {
        java.util.Random random=new java.util.Random(0x0C0F2026L);
        for (int round=0;round<32;round++) {
            int nodes=6+random.nextInt(10);
            var deps=new LinkedHashMap<String,Set<String>>();
            var exposed=new LinkedHashMap<String,ExportInterfaceCodec.InterfaceFile>();
            var pinned=new LinkedHashMap<String,Map<String,String>>();
            for (int i=0;i<nodes;i++) {
                var upstream=new java.util.HashSet<String>();
                for (int j=0;j<i;j++)if(random.nextInt(5)==0)upstream.add("unit"+j);
                deps.put("unit"+i,Set.copyOf(upstream));
                exposed.put("unit"+i,exported("()int"));
                pinned.put("unit"+i,Map.of());
            }
            var before=InterfaceSccPlanner.compute(deps,exposed,pinned);
            String edited="unit"+random.nextInt(nodes);
            exposed.put(edited,exported("()string"));
            var after=InterfaceSccPlanner.compute(deps,exposed,pinned);
            for (String unit:deps.keySet()) {
                var queue=new java.util.ArrayDeque<String>();
                var visited=new java.util.HashSet<String>();
                queue.add(unit);
                boolean transitivelyDepends=false;
                while(!queue.isEmpty()){
                    String current=queue.removeFirst();
                    if(current.equals(edited)){transitivelyDepends=true;break;}
                    if(visited.add(current))queue.addAll(deps.get(current));
                }
                assertEquals(transitivelyDepends,
                        !before.unitFingerprint().get(unit).equals(after.unitFingerprint().get(unit)),
                        "invalidation must equal reverse reachability for "+unit);
            }
        }
    }

}
