package dev.oreslang.compiler;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
final class PinnedExactPackageLayoutTest {
 @Test void exactOwnershipAndNoImplicitNamespace(){
  String p="p_"+"a".repeat(64),q="p_"+"b".repeat(64);
  String a="/project/src/local0.ores",b="/project/src/local1.ores",foreign="/project/src/foreign.ores";
  var layout=PackageLayout.pinned(Map.of(a,p,b,p));
  assertEquals("pkg:"+p,layout.packageId(a));
  assertEquals(layout.packageId(a),layout.packageId(b));
  assertEquals("file:"+foreign,layout.packageId(foreign));
  assertNull(layout.effectiveNamespace(a,null));
  assertEquals("billing",layout.effectiveNamespace(a,"billing"));
  var compiler=new IncrementalCompiler();
  var sources=Map.of(a,"pub fnc local(): int { return 1; }",b,"pub fnc local(): int { return 2; }");
  var first=compiler.compile(sources,Map.of(),layout);
  assertEquals(2,first.rebuiltUnits().size());
  assertNull(first.units().get(a).namespace());
  assertEquals("pkg:"+p,first.units().get(b).packageId());
  assertEquals(0,compiler.compile(sources,Map.of(),layout).rebuiltUnits().size());
  assertEquals(2,compiler.compile(sources,Map.of(),PackageLayout.pinned(Map.of(a,q,b,q))).rebuiltUnits().size());
  assertThrows(IllegalArgumentException.class,()->PackageLayout.pinned(Map.of("/project/src/../a.ores",p)));
  assertThrows(IllegalArgumentException.class,()->new PackageLayout(Map.of("src","example"),Map.of(a,p)));
 }
}
