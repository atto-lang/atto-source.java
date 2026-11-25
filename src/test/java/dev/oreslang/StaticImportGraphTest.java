package dev.oreslang;

import dev.oreslang.imports.StaticImportGraph;
import dev.oreslang.imports.StaticImportGraph.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class StaticImportGraphTest {
    private static Origin origin(Domain domain, String name, char digest) {
        return new Origin(domain,name,String.valueOf(digest).repeat(64));
    }
    private static View view(Origin id, boolean hasGet) {
        var entries = new ArrayList<>(List.of(
            new Export("net",Kind.NAMESPACE,true),
            new Export("net.http",Kind.NAMESPACE,true),
            new Export("net.http.secret",Kind.FNC,false),
            new Export("hidden",Kind.NAMESPACE,false),
            new Export("hidden.open",Kind.FNC,true),
            new Export("scalar",Kind.CONST,true),
            new Export("scalar.child",Kind.FNC,true)));
        if (hasGet) entries.add(new Export("net.http.get",Kind.FNC,true));
        return new View(id,entries);
    }
    private static void deny(String code, Runnable action) {
        var e=assertThrows(IllegalArgumentException.class,action::run);
        assertTrue(e.getMessage().startsWith(code+":"),e.getMessage());
    }
    @Test void ambiguityAndTierNeverUseMemberExistenceToChooseOrigin() {
        var a=view(origin(Domain.PACKAGE,"@a/b/net",'a'),true);
        var b=view(origin(Domain.PACKAGE,"@x/y/net",'b'),false);
        for (boolean reverse : new boolean[]{false,true}) {
            var scope=new StaticImportGraph();
            scope.bindRoot(reverse?b:a,"p",Tier.DELIBERATE);
            scope.bindRoot(reverse?a:b,"p",Tier.DELIBERATE);
            deny("E_AMBIGUOUS",()->scope.resolve("p.net.http.get"));
            scope.bindRoot(b,"p",Tier.EXPLICIT);
            deny("E_UNBOUND",()->scope.resolve("p.net.http.get"));
            deny("E_DUPLICATE",()->scope.bindRoot(a,"p",Tier.EXPLICIT));
        }
    }
    @Test void typedSelectionRollsBackAndCannotImpersonateBuiltin() {
        var ext=view(origin(Domain.PACKAGE,"core.num",'a'),true);
        var builtin=origin(Domain.BUILTIN,"core.num",'a');
        var scope=new StaticImportGraph();
        var get=new Selection("net.http.get","fetch",Kind.FNC,true);
        deny("E_IMPORT_PATH",()->scope.bindGroup(builtin,ext,List.of(get)));
        for (var bad : List.of(new Selection("net.http.secret","bad",Kind.FNC,true),
                new Selection("hidden.open","bad",Kind.FNC,true))) {
            deny("E_PRIVATE",()->scope.bindGroup(ext.origin(),ext,List.of(get,bad)));
            deny("E_UNBOUND",()->scope.resolve("fetch"));
        }
        deny("E_IMPORT_KIND",()->scope.bindGroup(ext.origin(),ext,List.of(get,new Selection("scalar.child","bad",Kind.FNC,true))));
        deny("E_IMPORT_KIND",()->scope.bindGroup(ext.origin(),ext,List.of(get,new Selection("net.http","bad",Kind.CLASS,true))));
        deny("E_DUPLICATE",()->scope.bindGroup(ext.origin(),ext,List.of(get,get)));
        deny("E_UNBOUND",()->scope.resolve("fetch"));
        scope.bindGroup(ext.origin(),ext,List.of(get));
        assertSame(ext,scope.resolve("fetch").view());
    }
    @Test void forwardAliasesAndCyclesAreAtomic() {
        var a=view(origin(Domain.PACKAGE,"@a/b/net",'a'),true);
        var scope=new StaticImportGraph();
        scope.bindRoot(a,"p",Tier.DELIBERATE);
        scope.bindAliases(List.of(new Alias("first","second"),new Alias("second","p.net.http")));
        assertEquals("net.http.get",scope.resolve("first.get").path());
        var cycle=List.of(new Alias("c","a"),new Alias("a","b.child"),new Alias("b","c"));
        var ex=assertThrows(IllegalArgumentException.class,()->scope.bindAliases(cycle));
        assertEquals("E_IMPORT_CYCLE: a -> b -> c -> a",ex.getMessage());
        deny("E_UNBOUND",()->scope.resolve("a"));
        deny("E_UNBOUND",()->scope.bindAliases(List.of(new Alias("good","p.net"),new Alias("zz","missing"))));
        deny("E_UNBOUND",()->scope.resolve("good"));
        deny("E_IMPORT_KIND",()->scope.bindAliases(List.of(new Alias("bad","p.scalar"))));
        deny("E_DUPLICATE",()->scope.bindAliases(List.of(new Alias("x","p"),new Alias("x","p"))));
    }
    @Test void budgetsAndConflictingViewsFailClosed() {
        var id=origin(Domain.PACKAGE,"@a/b/net",'a');
        var a=view(id,true);
        var scope=new StaticImportGraph();
        scope.bindRoot(a,"p",Tier.DELIBERATE);
        deny("E_AMBIGUOUS",()->scope.bindRoot(view(id,false),"q",Tier.DELIBERATE));
        var chain=new ArrayList<Alias>();
        for (int i=0;i<255;i++) chain.add(new Alias("a"+i,i==254?"p":"a"+(i+1)));
        scope.bindAliases(chain);
        assertEquals("net.http.get",scope.resolve("a0.net.http.get").path());
        deny("E_SOURCE_LIMIT",()->scope.bindAliases(List.of(new Alias("extra","p"))));
        deny("E_SOURCE_LIMIT",()->StaticImportGraph.precheckAliases(Collections.nCopies(257,new Alias("x","p"))));
    }
}
