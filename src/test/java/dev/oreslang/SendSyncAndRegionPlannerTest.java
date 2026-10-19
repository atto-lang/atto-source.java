package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.SendSyncTraits;
import dev.oreslang.types.RegionAccessPlanner;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class SendSyncAndRegionPlannerTest {
    private static Ast.TypeRef t(String name, Ast.TypeRef... args) {
        return new Ast.TypeRef(name, List.of(args), false);
    }

    private static Ast.FieldDecl field(String name, Ast.TypeRef type, boolean mutableReferent) {
        return new Ast.FieldDecl(name, Ast.Visibility.PUBLIC, Ast.BindingKind.CONST,
                type, List.of(), null, mutableReferent);
    }

    private static Ast.ClassDecl klass(String name, List<String> parameters,
                                       Ast.FieldDecl... fields) {
        return new Ast.ClassDecl(name, false, Ast.ActorKind.NONE,
                parameters, List.of(), List.of(), List.of(fields), List.of());
    }

    private static SendSyncTraits traits(Ast.ClassDecl... classes) {
        return new SendSyncTraits(new Ast.Program(
                List.of(new Ast.ModuleDecl("send_sync", List.of(classes)))));
    }

    @Test
    void structuralScalarGenericAndBorrowRules() {
        SendSyncTraits checker = traits(
                klass("Safe", List.of(), field("v", t("i64"), false)),
                klass("Mutable", List.of(), field("v", t("i64"), true)),
                klass("Boxed", List.of("T"), field("v", t("T"), false)));
        assertEquals(new SendSyncTraits.Traits(true, true), checker.derive(t("Safe")));
        assertEquals(new SendSyncTraits.Traits(true, false), checker.derive(t("Mutable")));
        assertTrue(checker.isSync(t("Boxed", t("Safe"))));
        assertFalse(checker.isSync(t("Boxed", t("Mutable"))));
        assertFalse(checker.isSend(t("Boxed", t("UnknownNative"))));
        assertFalse(checker.isSend(Ast.TypeRef.borrowed(t("Mutable"), false)));
        assertTrue(checker.isSend(Ast.TypeRef.borrowed(t("Mutable"), true)));
        assertFalse(checker.isSync(Ast.TypeRef.borrowed(t("Mutable"), true)));
    }

    @Test
    void standardReferenceWrappersAndInteriorCells() {
        SendSyncTraits checker = traits(klass("Mutable", List.of(),
                field("v", t("int"), true)));
        assertTrue(checker.isSync(t("Arc", t("int"))));
        assertFalse(checker.isSend(t("Arc", t("Mutable"))));
        assertTrue(checker.isSync(t("Mutex", t("Mutable"))));
        assertFalse(checker.isSync(t("RwLock", t("Mutable"))));
        assertFalse(checker.isSend(t("Rc", t("int"))));
        assertTrue(checker.isSend(t("Cell", t("int"))));
        assertFalse(checker.isSync(t("Cell", t("int"))));
        assertTrue(checker.isSend(t("Array", t("int"))));
        assertFalse(checker.isSync(t("InvertedArray", t("int"))));
        assertFalse(checker.isSend(t("MutexGuard", t("int"))));
    }

    @Test
    void genericFieldCyclesAndAmbiguousNominalsFailClosed() {
        SendSyncTraits checker = traits(
                klass("Node", List.of(), field("next", t("Node"), false)),
                klass("Duplicate", List.of(), field("v", t("int"), false)),
                klass("Duplicate", List.of(), field("v", t("int"), false)));
        assertFalse(checker.isSend(t("Node")));
        assertFalse(checker.isSync(t("Node")));
        assertFalse(checker.isSend(t("Duplicate")));
        assertFalse(checker.isSend(t("UnknownForeign")));
        assertFalse(checker.isSend(t("NativeHandle")));
        assertFalse(checker.isSend(t("RawPointer", t("int"))));
        assertFalse(checker.isSend(t("ExecutionPermit", t("int"))));
        assertFalse(checker.isSend(Ast.TypeRef.inferred()));
    }

    @Test
    void errorsNameTheRequiredTraitAndDoNotGrantActorPermission() {
        SendSyncTraits checker = traits();
        assertTrue(checker.isSend(t("Result", t("int"), t("String"))));
        assertFalse(checker.isSync(t("Result", t("int"), t("Cell", t("int")))));
        assertThrows(IllegalArgumentException.class, () -> checker.requireSend(t("Rc", t("int"))));
        assertThrows(IllegalArgumentException.class, () -> checker.requireSync(t("Cell", t("int"))));
    }

    private static RegionAccessPlanner.SystemAccess sys(
            String name, String column, long start, long end,
            RegionAccessPlanner.Mode mode) {
        return new RegionAccessPlanner.SystemAccess(name, List.of(
                new RegionAccessPlanner.Access(11, 3, column, start, end, mode)));
    }

    @Test
    void fieldAndRowDisjointnessAllowSafeParallelStages() {
        var a = sys("integrate", "position", 0, 100, RegionAccessPlanner.Mode.WRITE);
        var b = sys("poison", "health", 0, 100, RegionAccessPlanner.Mode.WRITE);
        var c = sys("observe", "position", 0, 100, RegionAccessPlanner.Mode.READ);
        var d = sys("partition", "position", 100, 200, RegionAccessPlanner.Mode.WRITE);
        assertFalse(RegionAccessPlanner.conflicts(a, b));
        assertFalse(RegionAccessPlanner.conflicts(a, d));
        assertTrue(RegionAccessPlanner.conflicts(a, c));
        var stages = RegionAccessPlanner.plan(List.of(a, b, c, d));
        assertEquals(List.of(a, b, d), stages.getFirst());
        assertEquals(List.of(c), stages.get(1));
    }

    @Test
    void conflictOrderTransitiveDependenciesAndStructuralMutations() {
        var a = sys("A", "x", 0, 10, RegionAccessPlanner.Mode.WRITE);
        var b = new RegionAccessPlanner.SystemAccess("B", List.of(
                new RegionAccessPlanner.Access(11, 3, "x", 0, 10, RegionAccessPlanner.Mode.READ),
                new RegionAccessPlanner.Access(11, 3, "y", 0, 10, RegionAccessPlanner.Mode.WRITE)));
        var c = sys("C", "y", 0, 10, RegionAccessPlanner.Mode.READ);
        assertEquals(3, RegionAccessPlanner.plan(List.of(a, b, c)).size());
        var structural = sys("erase_swap", "*", 0, 1, RegionAccessPlanner.Mode.STRUCTURAL);
        assertTrue(RegionAccessPlanner.conflicts(structural, a));
        assertTrue(RegionAccessPlanner.conflicts(structural, c));
    }

    @Test
    void staleRegionGenerationsAndForgedWildcardAccessFailClosed() {
        var a = sys("A", "position", 0, 10, RegionAccessPlanner.Mode.WRITE);
        var stale = new RegionAccessPlanner.SystemAccess("stale", List.of(
                new RegionAccessPlanner.Access(11, 4, "position", 0, 10, RegionAccessPlanner.Mode.READ)));
        assertThrows(IllegalArgumentException.class, () -> RegionAccessPlanner.plan(List.of(a, stale)));
        assertThrows(IllegalArgumentException.class, () ->
                new RegionAccessPlanner.Access(11, 3, "*", 0, 10, RegionAccessPlanner.Mode.READ));
        assertThrows(IllegalArgumentException.class, () ->
                new RegionAccessPlanner.Access(11, 3, "health", 0, 10, RegionAccessPlanner.Mode.STRUCTURAL));
        assertThrows(IllegalArgumentException.class, () ->
                new RegionAccessPlanner.Access(11, 0, "health", 0, 10, RegionAccessPlanner.Mode.WRITE));
        assertThrows(IllegalArgumentException.class, () ->
                RegionAccessPlanner.plan(List.of(new RegionAccessPlanner.SystemAccess("empty", List.of()))));
        assertThrows(IllegalArgumentException.class, () ->
                new RegionAccessPlanner.SystemAccess("mixed", List.of(
                        new RegionAccessPlanner.Access(11, 3, "health", 0, 10, RegionAccessPlanner.Mode.WRITE),
                        new RegionAccessPlanner.Access(11, 4, "position", 0, 10, RegionAccessPlanner.Mode.READ))));
        assertThrows(IllegalArgumentException.class, () ->
                RegionAccessPlanner.plan(List.of(a, a)));
    }

    @Test
    void randomizedStagesNeverOverlapWritersOrReorderDependencies() {
        java.util.Random random = new java.util.Random(19931018L);
        for (int iteration = 0; iteration < 500; iteration++) {
            java.util.List<RegionAccessPlanner.SystemAccess> systems = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                long start = random.nextInt(40);
                long end = start + random.nextInt(40) + 1;
                String column = random.nextBoolean() ? "position" : "health";
                RegionAccessPlanner.Mode mode = random.nextBoolean()
                        ? RegionAccessPlanner.Mode.READ : RegionAccessPlanner.Mode.WRITE;
                systems.add(sys("system" + i, column, start, end, mode));
            }
            var stages = RegionAccessPlanner.plan(systems);
            java.util.Map<String, Integer> level = new java.util.HashMap<>();
            for (int i = 0; i < stages.size(); i++) {
                for (var system : stages.get(i)) {
                    assertNull(level.put(system.name(), i), "system scheduled more than once");
                    for (var other : stages.get(i)) {
                        if (system != other) {
                            assertFalse(RegionAccessPlanner.conflicts(system, other),
                                    "conflicting systems admitted into one stage");
                        }
                    }
                }
            }
            assertEquals(12, level.size());
            for (int i = 0; i < systems.size(); i++) {
                for (int j = i + 1; j < systems.size(); j++) {
                    if (RegionAccessPlanner.conflicts(systems.get(i), systems.get(j))) {
                        assertTrue(level.get(systems.get(i).name())
                                < level.get(systems.get(j).name()),
                                "dependent system overtook an earlier writer");
                    }
                }
            }
        }
    }
}
