package dev.oreslang.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone JDK-only regression: no Graal, Maven, network, or private checkout. */
public final class PinnedNamespaceInitializationPlanSelfTest {
    private static int checks;

    private PinnedNamespaceInitializationPlanSelfTest() { }

    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void rejects(Runnable action, String diagnostic) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException accepted) {
            if (!accepted.getMessage().contains(diagnostic))
                throw new AssertionError("wrong rejection: " + accepted.getMessage());
            return;
        }
        throw new AssertionError("accepted invalid initialization plan: " + diagnostic);
    }

    private static List<List<String>> plan(
            Map<String, Set<String>> edges, List<List<String>> order, String entry) {
        return PinnedNamespaceInitializationPlan.reachableGroups(edges, order, entry);
    }

    public static void main(String[] args) {
        // 1. No dependencies: unrelated package remains validated but dormant.
        equal(List.of(List.of("app")),
                plan(Map.of("app", Set.of(), "foreign", Set.of()),
                        List.of(List.of("app"), List.of("foreign")), "app"),
                "unreachable package must not execute its init hook");

        // 2. All cross-file members of a referenced namespace are initialized.
        equal(List.of(List.of("foo/add"), List.of("foo/double"), List.of("app")),
                plan(Map.of("app", Set.of("foo/double"), "foo/double", Set.of("foo/add"),
                                "foo/add", Set.of(), "foreign", Set.of()),
                        List.of(List.of("foo/add"), List.of("foo/double"),
                                List.of("foreign"), List.of("app")), "app"),
                "transitive namespace dependency-first order");

        // 3. Unrelated package can contain identical namespace spellings.
        equal(List.of(List.of("foo/a"), List.of("app")),
                plan(Map.of("app", Set.of("foo/a"), "foo/a", Set.of(),
                                "other/a", Set.of()),
                        List.of(List.of("foo/a"), List.of("other/a"),
                                List.of("app")), "app"),
                "unrelated package with identical name never starts");

        // 4. Reachable mutual-dependency cycle runs once per identity.
        equal(List.of(List.of("foo/a", "foo/b"), List.of("app")),
                plan(Map.of("app", Set.of("foo/a"), "foo/a", Set.of("foo/b"),
                                "foo/b", Set.of("foo/a")),
                        List.of(List.of("foo/a", "foo/b"), List.of("app")), "app"),
                "same-SCC members execute once each");

        // 5. A self-cycle is not allowed to initialize twice.
        equal(List.of(List.of("foo/a"), List.of("app")),
                plan(Map.of("app", Set.of("foo/a"), "foo/a", Set.of("foo/a")),
                        List.of(List.of("foo/a"), List.of("app")), "app"),
                "self-reference must not repeat initialization");

        // 6-10. All inconsistent host/compiler plans are rejected before init.
        rejects(() -> plan(Map.of("app", Set.of()), List.of(List.of("app")), "missing"),
                "entry absent");
        rejects(() -> plan(Map.of("app", Set.of("missing")),
                List.of(List.of("app")), "app"), "dependency outside");
        rejects(() -> plan(Map.of("app", Set.of()),
                List.of(List.of("app"), List.of("app")), "app"), "duplicate");
        rejects(() -> plan(Map.of("app", Set.of(), "foo", Set.of()),
                List.of(List.of("app")), "app"), "omits");
        rejects(() -> plan(Map.of("app", Set.of("foo"), "foo", Set.of()),
                List.of(List.of("app"), List.of("foo")), "app"), "order violated");

        // 11-12. No synthetic empty or unknown source can enter the plan.
        rejects(() -> plan(Map.of("app", Set.of()),
                List.of(List.of("app"), List.of()), "app"), "empty verified");
        rejects(() -> plan(Map.of("app", Set.of()),
                List.of(List.of("app", "foreign")), "app"), "outside verified");

        // 13. A forged SCC must not hide one-way initialization ordering.
        rejects(() -> plan(Map.of("app", Set.of("foo"), "foo", Set.of()),
                List.of(List.of("app", "foo")), "app"), "strongly connected");

        // 14. Neither caller inventory insertion order nor unrelated package
        // order determines which guest init code is admitted.
        equal(List.of(List.of("foo"), List.of("app")),
                plan(Map.of("foreign", Set.of(), "app", Set.of("foo"),
                                "foo", Set.of()),
                        List.of(List.of("foreign"), List.of("foo"),
                                List.of("app")), "app"),
                "foreign insertion order cannot authorize execution");

        System.out.println("PINNED_NAMESPACE_INIT_SOURCE_ONLY_PASS checks=" + checks);
    }
}
