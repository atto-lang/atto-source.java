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

    private static SendSyncTraits trusted(Ast.ClassDecl... classes) {
        return new SendSyncTraits(new Ast.Program(
                List.of(new Ast.ModuleDecl("send_sync", List.of(classes)))),
                java.util.Set.of("Arc", "Weak", "Shared", "Mutex", "RwLock",
                        "Array", "List", "InvertedArray", "Option", "Result"));
    }

    @Test
    void structuralScalarGenericAndBorrowRules() {
        SendSyncTraits checker = traits(
                klass("Safe", List.of(), field("v", t("i64"), false)),
                klass("Mutable", List.of(), field("v", t("i64"), true)),
                klass("Boxed", List.of("T"), field("v", t("T"), false)));
        assertEquals(new SendSyncTraits.Traits(true, true), checker.derivePreflight(t("Safe")));
        assertEquals(new SendSyncTraits.Traits(true, false), checker.derivePreflight(t("Mutable")));
        assertTrue(checker.mayBeSyncAfterResolution(t("Boxed", t("Safe"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("Boxed", t("Mutable"))));
        assertFalse(checker.mayBeSendAfterResolution(t("Boxed", t("UnknownNative"))));
        assertFalse(checker.mayBeSendAfterResolution(Ast.TypeRef.borrowed(t("Mutable"), false)));
        assertTrue(checker.mayBeSendAfterResolution(Ast.TypeRef.borrowed(t("Mutable"), true)));
        assertFalse(checker.mayBeSyncAfterResolution(Ast.TypeRef.borrowed(t("Mutable"), true)));
    }

    @Test
    void standardReferenceWrappersAndInteriorCells() {
        SendSyncTraits checker = trusted(klass("Mutable", List.of(),
                field("v", t("int"), true)));
        assertTrue(checker.mayBeSyncAfterResolution(t("Arc", t("int"))));
        assertFalse(checker.mayBeSendAfterResolution(t("Arc", t("Mutable"))));
        assertTrue(checker.mayBeSyncAfterResolution(t("Mutex", t("Mutable"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("RwLock", t("Mutable"))));
        assertFalse(checker.mayBeSendAfterResolution(t("Rc", t("int"))));
        assertFalse(checker.mayBeSendAfterResolution(t("Cell", t("int"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("Cell", t("int"))));
        assertTrue(checker.mayBeSendAfterResolution(t("Array", t("int"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("InvertedArray", t("int"))));
        assertFalse(checker.mayBeSendAfterResolution(t("MutexGuard", t("int"))));
    }

    @Test
    void genericFieldCyclesAndAmbiguousNominalsFailClosed() {
        SendSyncTraits checker = traits(
                klass("Node", List.of(), field("next", t("Node"), false)),
                klass("Duplicate", List.of(), field("v", t("int"), false)),
                klass("Duplicate", List.of(), field("v", t("int"), false)));
        assertFalse(checker.mayBeSendAfterResolution(t("Node")));
        assertFalse(checker.mayBeSyncAfterResolution(t("Node")));
        assertFalse(checker.mayBeSendAfterResolution(t("Duplicate")));
        assertFalse(checker.mayBeSendAfterResolution(t("UnknownForeign")));
        assertFalse(checker.mayBeSendAfterResolution(t("NativeHandle")));
        assertFalse(checker.mayBeSendAfterResolution(t("RawPointer", t("int"))));
        assertFalse(checker.mayBeSendAfterResolution(t("ExecutionPermit", t("int"))));
        assertFalse(checker.mayBeSendAfterResolution(Ast.TypeRef.inferred()));
    }

    @Test
    void preflightNeverClaimsPermissionOrActorAuthority() {
        SendSyncTraits checker = trusted();
        assertTrue(checker.mayBeSendAfterResolution(
                t("Result", t("int"), t("String"))),
                "positive AST preflight is only a candidate for later semantic proof");
        assertFalse(checker.mayBeSyncAfterResolution(
                t("Result", t("int"), t("Cell", t("int")))));
        assertFalse(checker.mayBeSendAfterResolution(t("Rc", t("int"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("Cell", t("int"))));
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

    @Test
    void unloweredIntrinsicsAndUserDefinedNameSpoofingFailClosed() {
        SendSyncTraits defaultChecker = traits();
        for (String intrinsic : List.of("Arc", "Weak", "Shared", "Mutex",
                "RwLock", "Array", "List", "InvertedArray", "Option")) {
            assertFalse(defaultChecker.mayBeSendAfterResolution(t(intrinsic, t("int"))),
                    "unlowered " + intrinsic + " cannot claim Send");
        }
        assertFalse(defaultChecker.mayBeSendAfterResolution(t("Result", t("int"), t("int"))));
        SendSyncTraits spoofed = trusted(
                klass("Arc", List.of("T"), field("hostPtr", t("NativeHandle"), false)),
                klass("Mutex", List.of("T"), field("hostPtr", t("NativeHandle"), false)),
                klass("Option", List.of("T"), field("hostPtr", t("NativeHandle"), false)),
                klass("Result", List.of("T", "E"), field("hostPtr", t("NativeHandle"), false)));
        assertFalse(spoofed.mayBeSendAfterResolution(t("Arc", t("int"))));
        assertFalse(spoofed.mayBeSyncAfterResolution(t("Mutex", t("int"))));
        assertFalse(spoofed.mayBeSendAfterResolution(t("Option", t("int"))));
        assertFalse(spoofed.mayBeSendAfterResolution(t("Result", t("int"), t("int"))));
        assertThrows(IllegalArgumentException.class, () ->
                new SendSyncTraits(new Ast.Program(List.of()),
                        java.util.Set.of("ForgedCompilerIntrinsic")));
    }

    @Test
    void guestNominalNamesCannotForgeActorOrTaskCapabilities() {
        SendSyncTraits forged = trusted(
                klass("ActorRef", List.of("T"), field("id", t("int"), false)),
                klass("Task", List.of("T"), field("id", t("int"), false)),
                klass("Channel", List.of("T"), field("id", t("int"), false)),
                klass("ThreadBound", List.of("T"), field("id", t("int"), false)));
        for (String denied : List.of("ActorRef", "Task", "Channel", "ThreadBound")) {
            assertFalse(forged.mayBeSendAfterResolution(t(denied, t("int"))),
                    "capability spoof admitted Send: " + denied);
            assertFalse(forged.mayBeSyncAfterResolution(t(denied, t("int"))),
                    "capability spoof admitted Sync: " + denied);
        }
        SendSyncTraits ordinary = traits(
                klass("SendableRecord", List.of(), field("id", t("int"), false)));
        assertTrue(ordinary.mayBeSendAfterResolution(t("SendableRecord")),
                "normal transparent value types still derive Send");
    }

    @Test
    void staleGenerationsCannotHideBehindEarlierConflict() {
        var first = new RegionAccessPlanner.SystemAccess("first", List.of(
                new RegionAccessPlanner.Access(11, 3, "x", 0, 10, RegionAccessPlanner.Mode.WRITE),
                new RegionAccessPlanner.Access(12, 4, "y", 0, 10, RegionAccessPlanner.Mode.WRITE)));
        var stale = new RegionAccessPlanner.SystemAccess("stale", List.of(
                new RegionAccessPlanner.Access(11, 3, "x", 0, 10, RegionAccessPlanner.Mode.READ),
                new RegionAccessPlanner.Access(12, 5, "y", 20, 30, RegionAccessPlanner.Mode.READ)));
        // The x write/read conflicts first; that cannot conceal the stale y generation.
        assertThrows(IllegalArgumentException.class,
                () -> RegionAccessPlanner.conflicts(first, stale));
        assertThrows(IllegalArgumentException.class,
                () -> RegionAccessPlanner.plan(List.of(first, stale)));
        assertThrows(IllegalArgumentException.class, () ->
                new RegionAccessPlanner.SystemAccess("self-conflict", List.of(
                        new RegionAccessPlanner.Access(11, 3, "x", 0, 10, RegionAccessPlanner.Mode.WRITE),
                        new RegionAccessPlanner.Access(11, 3, "x", 2, 5, RegionAccessPlanner.Mode.READ))));
        // A read/read overlap is harmless; a half-open adjacent write is disjoint.
        var valid = new RegionAccessPlanner.SystemAccess("valid", List.of(
                new RegionAccessPlanner.Access(11, 3, "x", 0, 10, RegionAccessPlanner.Mode.READ),
                new RegionAccessPlanner.Access(11, 3, "x", 2, 5, RegionAccessPlanner.Mode.READ),
                new RegionAccessPlanner.Access(11, 3, "x", 10, 20, RegionAccessPlanner.Mode.WRITE)));
        assertEquals(3, valid.accesses().size());
    }

    @Test
    void recursiveGenericExpansionIsBoundedAndFailsClosed() {
        // Each recursion expands Node<T> into Node<Option<T>>; the distinct
        // generic instantiations must not defeat ordinary cycle detection.
        var recursive = klass("Expanding", List.of("T"),
                field("next", t("Expanding", t("Option", t("T"))), false));
        SendSyncTraits checker = new SendSyncTraits(
                new Ast.Program(List.of(new Ast.ModuleDecl("recursive", List.of(recursive)))),
                java.util.Set.of("Option"));
        assertFalse(checker.mayBeSendAfterResolution(t("Expanding", t("int"))));
        assertFalse(checker.mayBeSyncAfterResolution(t("Expanding", t("int"))));
    }

    @Test
    void excessivelyDeepLinkedTypesFailBeforeRecursiveHashing() {
        Ast.TypeRef depthBomb = t("int");
        for (int i = 0; i < 12_000; i++) {
            depthBomb = t("Option", depthBomb);
        }
        SendSyncTraits checker = trusted();
        assertFalse(checker.mayBeSendAfterResolution(depthBomb));
        assertFalse(checker.mayBeSyncAfterResolution(depthBomb));
    }
}
