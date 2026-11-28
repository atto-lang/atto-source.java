package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Actual typed unit reuse and trusted JVM execution; not strict native-isolate proof. */
final class IncrementalGuestDifferentialTest {
    private static long run(IncrementalCompiler.CompiledUnit unit) throws Exception {
        var source=Source.newBuilder(OresLanguage.ID,unit.sourceText(),unit.unitId())
                .mimeType(OresLanguage.MIME_TYPE).cached(false).build();
        try(var context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build();
            var lease=OresLanguage.openPrecheckedProgram(unit.unitId(),unit.sourceText(),unit.program())){
            return context.eval(source).asLong();
        }
    }
    private static Map<String,String> sources(){
        var inputs=new LinkedHashMap<String,String>();
        inputs.put("src/main.atto","pub fnc main(): int { return 42; }");
        inputs.put("src/other.atto","pub fnc other(): int { return 7; }");
        for(int i=0;i<10;i++){
            String ns="group"+(i%3);
            inputs.put("src/"+ns+"/f"+i+".atto","namespace "+ns+"; pub fnc f"+i+"(): int { return "+i+"; }");
        }
        return inputs;
    }
    @Test void twelveSourceActualCheckedGuestColdWarmEditRollback() throws Exception {
        var compiler=new IncrementalCompiler();var inputs=sources();
        var cold=compiler.compile(inputs);assertEquals(12,cold.rebuiltUnits().size());assertEquals(12,cold.reparsedUnits().size());
        assertEquals(42,run(cold.units().get("src/main.atto")));
        var warm=compiler.compile(inputs);assertEquals(12,warm.reusedUnits().size());assertTrue(warm.rebuiltUnits().isEmpty());
        assertSame(cold.units().get("src/main.atto"),warm.units().get("src/main.atto"));assertEquals(42,run(warm.units().get("src/main.atto")));
        var changed=new LinkedHashMap<>(inputs);changed.put("src/main.atto","pub fnc main(): int { return 43; }");
        var edited=compiler.compile(changed);assertEquals(Set.of("src/main.atto"),edited.reparsedUnits());
        assertEquals(Set.of("src/main.atto"),edited.rebuiltUnits());assertEquals(11,edited.reusedUnits().size());assertEquals(43,run(edited.units().get("src/main.atto")));
        var clean=new IncrementalCompiler().compile(changed);assertEquals(43,run(clean.units().get("src/main.atto")));
        var bad=new LinkedHashMap<>(changed);bad.put("src/main.atto","pub fnc main(): int { return \"bad\"; }");
        assertThrows(IllegalArgumentException.class,()->compiler.compile(bad));
        var recovered=compiler.compile(changed);assertEquals(12,recovered.reusedUnits().size());assertSame(edited.units().get("src/main.atto"),recovered.units().get("src/main.atto"));
        System.out.println("INCREMENTAL_JAVA_JVM|checked-units=12/0,0/12,1/11|actual-prechecked-guest=42/43|rollback");
    }
}
