package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.GroupedSpawnPlan;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class GroupedSpawnPlanTest {
    private static final GroupedSpawnPlan.Ceiling LIMIT =
        new GroupedSpawnPlan.Ceiling(128L*1024*1024,128*1024,2048,8,false);
    private static final String SCHEMA="init { string name; int seed; bool enabled; }";
    private static GroupedSpawnPlan.Preview check(String source,String schema) {
        return GroupedSpawnPlan.check(Parser.parseGroupedSpawnPreview(source),
            Parser.parseActorInitSchemaPreview(schema),LIMIT);
    }
    private static void deny(String source,String schema,String diagnostic) {
        assertTrue(assertThrows(IllegalArgumentException.class,()->check(source,schema)).getMessage().contains(diagnostic));
    }
    @Test void canonicalExpressionRetainsOrderAndTypes() {
        var p=check("await spawn Worker() with { schedule { max_parallelism = 2; affinity = sticky; } resources { heap_size = 64*1024*1024; stack_size = 64*1024; mailbox_capacity = 1024; } init { seed = 42; name = \"worker-1\"; enabled = true; } }",SCHEMA);
        assertTrue(p.awaited()); assertEquals("Worker",p.actor().name());
        assertEquals(List.of("max_parallelism","affinity","heap_size","stack_size","mailbox_capacity","seed","name","enabled"),p.values().stream().map(GroupedSpawnPlan.Value::key).toList());
        assertEquals(67108864L,p.values().get(2).value());
        assertEquals(65536L,p.values().get(3).value());
        assertEquals(42L,p.values().get(5).value());
        assertEquals("worker-1",p.values().get(6).value());
        assertEquals(true,p.values().get(7).value());
    }
    @Test void limitsAndTypesFailBeforeExecution() {
        deny("spawn Worker() with { resources { heap_size = 0; } }","init {}","E_SPAWN_QUOTA");
        deny("spawn Worker() with { resources { heap_size = 9223372036854775807 + 1; } }","init {}","E_SPAWN_CONST_OVERFLOW");
        deny("spawn Worker() with { resources { stack_size = 999999; } }","init {}","E_SPAWN_QUOTA");
        deny("spawn Worker() with { schedule { affinity = pinned; max_parallelism = 2; } }","init {}","E_SPAWN_POLICY");
        deny("spawn Worker() with { init { seed = f(); } }","init { int seed; }","E_SPAWN_CONST_UNSUPPORTED");
        deny("spawn Worker() with { init { seed = 42; } }","init { bool seed; }","E_SPAWN_INIT_TYPE");
        deny("spawn Worker() with { init {} }",SCHEMA,"E_SPAWN_INIT_FIELDS");
        deny("spawn Worker() with { init { extra = 42; } }","init {}","E_SPAWN_INIT_FIELDS");
        deny("spawn Worker(42) with {}","init {}","E_SPAWN_POSITIONAL_UNSUPPORTED");
    }
    @Test void sourceDeclarationDeterminesNominalSchema() {
        String declarations = "define actor Worker as init { int seed; } end "
                + "define actor Decoy as init { bool seed; } end";
        var p=Parser.checkDeclaredGroupedSpawnPreview(declarations,
                "spawn Worker() with { init { seed = 42; } }",LIMIT);
        assertEquals(42L,p.values().getFirst().value());
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                declarations,"spawn Decoy() with { init { seed = 42; } }",LIMIT));
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                declarations,"spawn Missing() with {}",LIMIT));
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                "define actor Worker as init { int seed; } init { int seed; } end",
                "spawn Worker() with { init { seed = 42; } }",LIMIT));
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                "define class Worker as end","spawn Worker() with {}",LIMIT));
        assertThrows(IllegalArgumentException.class,()->Parser.parse(declarations));
    }

    @Test void ambiguousActorNamesRequireQualifiedResolution() {
        String declarations = "define module firstMod as define actor Worker as init { int seed; } end end "
                + "define module secondMod as define actor Worker as init { bool seed; } end end";
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                declarations,"spawn Worker() with { init { seed = 42; } }",LIMIT));
        var p=Parser.checkDeclaredGroupedSpawnPreview(declarations,
                "spawn firstMod.Worker() with { init { seed = 42; } }",LIMIT);
        assertEquals(42L,p.values().getFirst().value());
        assertThrows(IllegalArgumentException.class,()->Parser.checkDeclaredGroupedSpawnPreview(
                declarations,"spawn secondMod.Worker() with { init { seed = 42; } }",LIMIT));
    }

    @Test void initSchemaCannotEnterExecutableAst() {
        assertEquals(3,Parser.parseActorInitSchemaPreview(SCHEMA).size());
        assertThrows(IllegalArgumentException.class,()->Parser.parseActorInitSchemaPreview("init { int seed; bool seed; }"));
        assertThrows(IllegalArgumentException.class,()->Parser.parseActorInitSchemaPreview("init { Array<int> data; }"));
        var failure=assertThrows(IllegalArgumentException.class,()->Parser.parse("define actor Worker as init { int seed; } end"));
        assertTrue(failure.getMessage().contains("E_SPAWN_INIT_SCHEMA_UNSUPPORTED"),failure::getMessage);
        assertThrows(IllegalArgumentException.class,()->Parser.parseGroupedSpawnPreview("spawn Worker() with {} + 1"));
    }
}
