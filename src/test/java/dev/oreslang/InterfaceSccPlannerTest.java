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
}
