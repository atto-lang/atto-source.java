package dev.oreslang.compiler;

import java.util.List;
import java.util.Set;

/** Portable JDK-only negative and positive proof-model tests; no VM SDK. */
public final class RealtimeRegionEffectModelSelfTest {
    private static final String ACTOR = "actor-7";
    private static final String DOMAIN = "trusted-core";

    private static RealtimeRegionEffectModel.Region actor() {
        return new RealtimeRegionEffectModel.Region("arena",
                RealtimeRegionEffectModel.RegionKind.ACTOR_ARENA, ACTOR, null, 12, 2, 64);
    }
    private static RealtimeRegionEffectModel.Region scope() {
        return new RealtimeRegionEffectModel.Region("scratch",
                RealtimeRegionEffectModel.RegionKind.SCOPED, ACTOR, null, 3, 1, 24);
    }
    private static RealtimeRegionEffectModel.Region shared() {
        return new RealtimeRegionEffectModel.Region("shared",
                RealtimeRegionEffectModel.RegionKind.SHARED_IMMUTABLE, null, DOMAIN, 8, 3, 128);
    }
    private static RealtimeRegionEffectModel.Region managed() {
        return new RealtimeRegionEffectModel.Region("managed",
                RealtimeRegionEffectModel.RegionKind.MANAGED_HEAP, null, null, 0, 4, 256);
    }
    private static RealtimeRegionEffectModel.Plan plan(
            List<RealtimeRegionEffectModel.Region> regions, Set<String> domains,
            RealtimeRegionEffectModel.Budget budget,
            RealtimeRegionEffectModel.Function... functions) {
        return new RealtimeRegionEffectModel.Plan(ACTOR, "run", regions, domains,
                List.of(functions), budget);
    }
    private static RealtimeRegionEffectModel.Function fn(
            String name, RealtimeRegionEffectModel.Op... steps) {
        return new RealtimeRegionEffectModel.Function(name, List.of(steps));
    }
    private static final RealtimeRegionEffectModel.Budget NORMAL =
            new RealtimeRegionEffectModel.Budget(64, 32, 100, 4);

    private static RealtimeRegionEffectModel.Plan basic(RealtimeRegionEffectModel.Op... instructions) {
        return plan(List.of(actor(), scope(), shared(), managed()), Set.of(DOMAIN),
                NORMAL, fn("run", instructions));
    }
    private static int negatives;

    private static void rejects(String label, RealtimeRegionEffectModel.Plan p) {
        try {
            RealtimeRegionEffectModel.check(p);
            throw new AssertionError("unsafe plan was admitted: " + label);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().startsWith("RT-REGION:")) {
                throw new AssertionError("non-policy failure for " + label, e);
            }
            negatives++;
        }
    }

    public static void main(String[] ignored) {
        RealtimeRegionEffectModel.Plan valid = plan(
                List.of(actor(), scope(), shared()), Set.of(DOMAIN), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("a", "arena", 8, true),
                        new RealtimeRegionEffectModel.Borrow("read-a", "a", false),
                        new RealtimeRegionEffectModel.EndBorrow("read-a"),
                        new RealtimeRegionEffectModel.Move("a2", "a"),
                        new RealtimeRegionEffectModel.Allocate("tmp", "scratch", 4, false),
                        new RealtimeRegionEffectModel.Drop("tmp"),
                        new RealtimeRegionEffectModel.EndScope("scratch"),
                        new RealtimeRegionEffectModel.ImportShared("frozen", "shared"),
                        new RealtimeRegionEffectModel.Send("frozen", DOMAIN),
                        new RealtimeRegionEffectModel.Call("helper"),
                        new RealtimeRegionEffectModel.Drop("a2")),
                fn("helper", new RealtimeRegionEffectModel.CopyBytes(12),
                        new RealtimeRegionEffectModel.Suspend()));
        var report = RealtimeRegionEffectModel.check(valid);
        if (report.arenaBytesAllocated() != 12 || report.copiedBytes() != 12
                || report.maxCallDepth() != 2 || report.modeledOperations() != 13
                || !report.assurance().startsWith("SYMBOLIC_REGION_MODEL_ONLY")) {
            throw new AssertionError("model report mismatch: " + report);
        }
        rejects("private mailbox export", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, false),
                new RealtimeRegionEffectModel.Send("x", DOMAIN)));
        rejects("unknown trust domain", plan(List.of(actor(), shared()), Set.of(), NORMAL,
                fn("run", new RealtimeRegionEffectModel.ImportShared("x", "shared"))));
        rejects("region exhaustion", basic(
                new RealtimeRegionEffectModel.Allocate("x", "scratch", 25, false)));
        rejects("total allocation budget", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 40, false),
                new RealtimeRegionEffectModel.Allocate("y", "arena", 40, false)));
        rejects("negative allocation", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", -1, false)));
        rejects("move while borrowed", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, true),
                new RealtimeRegionEffectModel.Borrow("b", "x", true),
                new RealtimeRegionEffectModel.Move("y", "x")));
        rejects("borrow across suspension", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, false),
                new RealtimeRegionEffectModel.Borrow("b", "x", false),
                new RealtimeRegionEffectModel.Suspend()));
        rejects("mutable borrow overlap", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, true),
                new RealtimeRegionEffectModel.Borrow("a", "x", false),
                new RealtimeRegionEffectModel.Borrow("b", "x", true)));
        rejects("mutable borrow of immutable", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, false),
                new RealtimeRegionEffectModel.Borrow("b", "x", true)));
        rejects("scope closed with live value", basic(
                new RealtimeRegionEffectModel.Allocate("x", "scratch", 1, false),
                new RealtimeRegionEffectModel.EndScope("scratch")));
        rejects("short lifetime escaped", basic(
                new RealtimeRegionEffectModel.Allocate("x", "scratch", 1, false),
                new RealtimeRegionEffectModel.Store("x", "arena")));
        rejects("actor pointer into shared region", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, false),
                new RealtimeRegionEffectModel.Store("x", "shared")));
        rejects("actor pointer into managed heap", basic(
                new RealtimeRegionEffectModel.Allocate("x", "arena", 1, false),
                new RealtimeRegionEffectModel.Store("x", "managed")));
        rejects("unknown external effect", basic(
                new RealtimeRegionEffectModel.UnknownEffect("native_io")));
        rejects("unknown callee", basic(new RealtimeRegionEffectModel.Call("not-linked")));
        rejects("recursion", plan(List.of(actor()), Set.of(), NORMAL,
                fn("run", new RealtimeRegionEffectModel.Call("run"))));
        rejects("copy budget", basic(new RealtimeRegionEffectModel.CopyBytes(33)));
        rejects("copy overflow", plan(List.of(actor()), Set.of(),
                new RealtimeRegionEffectModel.Budget(64, Long.MAX_VALUE, 100, 4),
                fn("run", new RealtimeRegionEffectModel.CopyBytes(Long.MAX_VALUE),
                        new RealtimeRegionEffectModel.CopyBytes(1))));
        rejects("unknown binding", basic(new RealtimeRegionEffectModel.Drop("ghost")));
        rejects("stale region after scope end", basic(
                new RealtimeRegionEffectModel.EndScope("scratch"),
                new RealtimeRegionEffectModel.Allocate("x", "scratch", 1, false)));
        rejects("foreign actor arena", plan(List.of(
                new RealtimeRegionEffectModel.Region("foreign",
                        RealtimeRegionEffectModel.RegionKind.ACTOR_ARENA,
                        "actor-8", null, 0, 2, 64)), Set.of(), NORMAL,
                fn("run", new RealtimeRegionEffectModel.Allocate("x", "foreign", 1, false))));
        rejects("dynamic call graph depth", plan(List.of(actor()), Set.of(),
                new RealtimeRegionEffectModel.Budget(64, 32, 100, 1),
                fn("run", new RealtimeRegionEffectModel.Call("child")),
                fn("child", new RealtimeRegionEffectModel.CopyBytes(1))));
        rejects("modeled operations limit", plan(List.of(actor()), Set.of(),
                new RealtimeRegionEffectModel.Budget(64, 32, 1, 3),
                fn("run", new RealtimeRegionEffectModel.CopyBytes(1),
                        new RealtimeRegionEffectModel.CopyBytes(1))));
        // Scope cleanup obligations cannot disappear when no binding remains.
        rejects("unclosed empty scope", plan(
                List.of(actor(), scope()), Set.of(), NORMAL,
                fn("run", new RealtimeRegionEffectModel.CopyBytes(0))));
        rejects("unclosed dropped binding scope", basic(
                new RealtimeRegionEffectModel.Allocate("tmp", "scratch", 3, false),
                new RealtimeRegionEffectModel.Drop("tmp")));

        RealtimeRegionEffectModel.Region inner =
                new RealtimeRegionEffectModel.Region("inner",
                        RealtimeRegionEffectModel.RegionKind.SCOPED, ACTOR, null, 2, 1, 16);
        RealtimeRegionEffectModel.Region outer =
                new RealtimeRegionEffectModel.Region("outer",
                        RealtimeRegionEffectModel.RegionKind.SCOPED, ACTOR, null, 1, 1, 16);
        RealtimeRegionEffectModel.Region equal =
                new RealtimeRegionEffectModel.Region("equal",
                        RealtimeRegionEffectModel.RegionKind.SCOPED, ACTOR, null, 0, 1, 16);

        // A retained pointer must survive Drop of the *source binding*.
        rejects("stored pointer dangling after source scope closes", plan(
                List.of(actor(), inner, outer), Set.of(), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("local", "inner", 4, false),
                        new RealtimeRegionEffectModel.Store("local", "outer"),
                        new RealtimeRegionEffectModel.Drop("local"),
                        new RealtimeRegionEffectModel.EndScope("inner"),
                        new RealtimeRegionEffectModel.EndScope("outer"))));
        rejects("equal rank does not prove scope nesting", plan(
                List.of(actor(), inner, equal), Set.of(), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("local", "inner", 4, false),
                        new RealtimeRegionEffectModel.Store("local", "equal"),
                        new RealtimeRegionEffectModel.Drop("local"),
                        new RealtimeRegionEffectModel.EndScope("inner"),
                        new RealtimeRegionEffectModel.EndScope("equal"))));
        rejects("scope already ended cannot receive reference", basic(
                new RealtimeRegionEffectModel.EndScope("scratch"),
                new RealtimeRegionEffectModel.Allocate("live", "arena", 2, false),
                new RealtimeRegionEffectModel.Store("live", "scratch")));
        rejects("duplicate region must not override original", plan(
                List.of(actor(), actor()), Set.of(), NORMAL,
                fn("run", new RealtimeRegionEffectModel.CopyBytes(0))));

        // Closing the destination first is safe in this symbolic model:
        // no surviving region retains a pointer into the source at its end.
        var ordered = plan(List.of(actor(), inner, outer), Set.of(), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("v", "inner", 4, false),
                        new RealtimeRegionEffectModel.Store("v", "outer"),
                        new RealtimeRegionEffectModel.EndScope("outer"),
                        new RealtimeRegionEffectModel.Drop("v"),
                        new RealtimeRegionEffectModel.EndScope("inner")));
        if (!RealtimeRegionEffectModel.check(ordered).assurance().startsWith("SYMBOLIC_REGION_MODEL_ONLY")) {
            throw new AssertionError("valid scope closure order was not modeled");
        }
        // A destructor could release an object even when the arena itself
        // persists. Once an address was stored, Drop and Move fail closed.
        var selfContained = plan(List.of(actor(), inner), Set.of(), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("v", "inner", 2, false),
                        new RealtimeRegionEffectModel.Store("v", "inner"),
                        new RealtimeRegionEffectModel.Drop("v"),
                        new RealtimeRegionEffectModel.EndScope("inner")));
        rejects("self-retained object cannot be dropped before clearing store", selfContained);
        rejects("move of retained value requires explicit relocation proof", plan(
                List.of(actor(), inner, outer), Set.of(), NORMAL,
                fn("run",
                        new RealtimeRegionEffectModel.Allocate("x", "inner", 2, false),
                        new RealtimeRegionEffectModel.Store("x", "outer"),
                        new RealtimeRegionEffectModel.Move("y", "x"),
                        new RealtimeRegionEffectModel.EndScope("outer"),
                        new RealtimeRegionEffectModel.EndScope("inner"))));

        if (negatives != 31) throw new AssertionError("expected 31 negative checks, got " + negatives);
        System.out.println("PASS: RT symbolic region model, transitive resource accounting, and "
                + negatives + " denied unsafe plans");
    }
}
