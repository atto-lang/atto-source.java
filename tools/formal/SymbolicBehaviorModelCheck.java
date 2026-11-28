import dev.oreslang.runtime.OresSymbol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded reference-model checks for proposed symbol-keyed Statecharts and Petri nets.
 * This is NOT a guest runtime implementation or proof of unbounded liveness.
 */
public final class SymbolicBehaviorModelCheck {
    private SymbolicBehaviorModelCheck() {}

    private static OresSymbol sym(String name) { return OresSymbol.process(name); }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    record StateRule(OresSymbol source, OresSymbol event, OresSymbol destination) {}

    static final class Chart {
        private final Set<OresSymbol> states;
        private final Map<OresSymbol, OresSymbol> parents;
        private final Map<OresSymbol, Map<OresSymbol, OresSymbol>> rules = new HashMap<>();

        Chart(Set<OresSymbol> states, Map<OresSymbol, OresSymbol> parents, List<StateRule> transitions) {
            this.states = Set.copyOf(states);
            this.parents = Map.copyOf(parents);
            require(!this.states.isEmpty(), "chart has no states");
            for (var entry : this.parents.entrySet()) {
                require(states.contains(entry.getKey()) && states.contains(entry.getValue()), "unknown chart parent");
                require(!entry.getKey().equals(entry.getValue()), "self-parent chart state");
            }
            for (OresSymbol state : states) {
                Set<OresSymbol> seen = new HashSet<>();
                for (OresSymbol cursor = state; cursor != null; cursor = this.parents.get(cursor)) {
                    require(seen.add(cursor), "cyclic chart ancestry");
                }
            }
            for (StateRule rule : transitions) {
                require(rule != null && rule.source() != null && rule.event() != null
                        && rule.destination() != null, "null chart rule or Symbol");
                require(states.contains(rule.source()) && states.contains(rule.destination()), "unknown transition state");
                var perEvent = rules.computeIfAbsent(rule.source(), ignored -> new HashMap<>());
                require(perEvent.putIfAbsent(rule.event(), rule.destination()) == null,
                        "ambiguous chart transition for state/event");
            }
        }

        OresSymbol step(OresSymbol state, OresSymbol event) {
            require(states.contains(state), "unknown current chart state");
            require(event != null, "null chart event");
            // A leaf transition overrides an ancestor transition; event handling is one atomic step.
            for (OresSymbol cursor = state; cursor != null; cursor = parents.get(cursor)) {
                OresSymbol target = rules.getOrDefault(cursor, Map.of()).get(event);
                if (target != null) return target;
            }
            return state; // Explicit stutter for an unhandled event.
        }
    }

    record NetRule(OresSymbol name, int[] consume, int[] produce) {
        NetRule {
            require(name != null && consume != null && produce != null,
                    "null net rule or arc vector");
            consume = consume.clone();
            produce = produce.clone();
        }
        @Override public int[] consume() { return consume.clone(); }
        @Override public int[] produce() { return produce.clone(); }
    }

    static final class Net {
        private final List<OresSymbol> places;
        private final List<NetRule> transitions;
        private final int[][] conservation;

        Net(List<OresSymbol> places, List<NetRule> transitions, int[][] conservation) {
            require(places != null && transitions != null && conservation != null,
                    "null net definition");
            require(!places.isEmpty() && places.size() <= 64
                    && transitions.size() <= 256 && conservation.length <= 64,
                    "empty or excessive finite net definition");
            // Check dimensions before copying: cloning a null row otherwise
            // leaks an unchecked NPE instead of a fail-closed admission error.
            for (int[] invariant : conservation)
                require(invariant != null && invariant.length == places.size(),
                        "invalid P-invariant row");
            this.places = List.copyOf(places);
            this.transitions = List.copyOf(transitions);
            this.conservation = Arrays.stream(conservation).map(int[]::clone).toArray(int[][]::new);
            require(new HashSet<>(places).size() == places.size(), "duplicate places");
            Set<OresSymbol> names = new HashSet<>();
            for (NetRule rule : transitions) {
                require(names.add(rule.name()), "duplicate net transition symbol");
                require(rule.consume().length == places.size() && rule.produce().length == places.size(),
                        "arc dimension mismatch");
                for (int i = 0; i < places.size(); i++) {
                    require(rule.consume()[i] >= 0 && rule.produce()[i] >= 0, "negative arc weight");
                }
                for (int[] weights : this.conservation) {
                    require(weights.length == places.size(), "invariant dimension mismatch");
                    long delta = 0;
                    for (int i = 0; i < weights.length; i++) {
                        try {
                            delta = Math.addExact(delta, Math.multiplyExact(
                                    (long) weights[i],
                                    (long) rule.produce()[i] - rule.consume()[i]));
                        } catch (ArithmeticException overflow) {
                            throw new IllegalArgumentException("P-invariant arithmetic overflow", overflow);
                        }
                    }
                    require(delta == 0, "transition violates claimed P-invariant");
                }
            }
        }

        int[] fire(int[] marking, NetRule transition) {
            require(marking != null && marking.length == places.size(),
                    "marking dimension mismatch");
            require(transition != null && transitions.contains(transition),
                    "unregistered net transition");
            int[] input = transition.consume();
            int[] output = transition.produce();
            // Validate the complete marking before checking whether firing is disabled;
            // otherwise a shortage in an earlier place masks malformed later tokens.
            for (int tokens : marking) require(tokens >= 0, "negative marking");
            for (int i = 0; i < marking.length; i++) {
                if (marking[i] < input[i]) return null; // Disabled; consumes nothing.
            }
            int[] result = new int[marking.length];
            for (int i = 0; i < marking.length; i++) {
                long tokens = (long) marking[i] - input[i] + output[i];
                require(tokens >= 0 && tokens <= Integer.MAX_VALUE, "marking overflow");
                result[i] = (int) tokens;
            }
            return result; // Transition consumption/production is atomic.
        }

        long[] totals(int[] marking) {
            require(marking != null && marking.length == places.size(), "marking dimension mismatch");
            for (int tokens : marking) require(tokens >= 0, "negative marking");
            long[] totals = new long[conservation.length];
            for (int row = 0; row < conservation.length; row++) {
                for (int i = 0; i < marking.length; i++) {
                    try {
                        totals[row] = Math.addExact(totals[row], Math.multiplyExact(
                                (long) conservation[row][i], (long) marking[i]));
                    } catch (ArithmeticException overflow) {
                        throw new IllegalArgumentException("marking invariant arithmetic overflow", overflow);
                    }
                }
            }
            return totals;
        }
    }

    static void checkStatecharts() {
        OresSymbol root = sym("running"), idle = sym("idle"), busy = sym("busy"), suspended = sym("suspended");
        OresSymbol start = sym("start"), stop = sym("stop"), reset = sym("reset"), pause = sym("pause");
        Set<OresSymbol> states = Set.of(root, idle, busy, suspended);
        Chart chart = new Chart(states, Map.of(idle, root, busy, root, suspended, root), List.of(
                new StateRule(idle, start, busy), new StateRule(busy, stop, idle),
                new StateRule(busy, pause, suspended), new StateRule(root, reset, idle)));
        check(chart.step(busy, reset).equals(idle), "parent reset not selected");
        check(chart.step(busy, pause).equals(suspended), "leaf handler not selected");
        check(chart.step(idle, pause).equals(idle), "unhandled event did not stutter");
        check(chart.step(sym("busy"), sym("pause")).equals(suspended), "symbol equality is not logical");
        rejects(() -> new Chart(states, Map.of(idle, root), List.of(
                new StateRule(idle, start, busy), new StateRule(idle, start, suspended))),
                "ambiguous state/event pair accepted");
        rejects(() -> new Chart(states, Map.of(idle, busy, busy, idle), List.of()),
                "cyclic state hierarchy accepted");
        rejects(() -> chart.step(idle, null), "null chart event accepted");
        rejects(() -> new Chart(states, Map.of(), List.of(
                new StateRule(idle, null, busy))), "null chart rule Symbol accepted");

        List<OresSymbol> events = List.of(start, stop, reset, pause, sym("unknown"));
        int traces = 0;
        for (OresSymbol initial : states) {
            var frontier = new ArrayList<OresSymbol>();
            frontier.add(initial);
            for (int depth = 0; depth < 6; depth++) {
                var next = new ArrayList<OresSymbol>();
                for (OresSymbol state : frontier) {
                    for (OresSymbol event : events) {
                        OresSymbol actual = chart.step(state, event);
                        check(states.contains(actual), "chart escaped declared states");
                        check(chart.step(state, event).equals(actual), "chart is nondeterministic");
                        next.add(actual);
                        traces++;
                    }
                }
                frontier = next;
            }
        }
        check(traces == 78120, "statechart trace enumeration incomplete: " + traces);
        System.out.println("Statechart: " + traces + " bounded transitions checked");
    }

    private static String key(int[] marking) { return Arrays.toString(marking); }

    static void checkPetriNets() {
        OresSymbol fa = sym("free_a"), ha = sym("held_a"), fb = sym("free_b"), hb = sym("held_b");
        NetRule acquireA = new NetRule(sym("acquire_a"), new int[]{1, 0, 0, 0}, new int[]{0, 1, 0, 0});
        NetRule releaseA = new NetRule(sym("release_a"), new int[]{0, 1, 0, 0}, new int[]{1, 0, 0, 0});
        NetRule acquireB = new NetRule(sym("acquire_b"), new int[]{0, 0, 1, 0}, new int[]{0, 0, 0, 1});
        NetRule releaseB = new NetRule(sym("release_b"), new int[]{0, 0, 0, 1}, new int[]{0, 0, 1, 0});
        List<NetRule> rules = List.of(acquireA, releaseA, acquireB, releaseB);
        Net net = new Net(List.of(fa, ha, fb, hb), rules,
                new int[][]{{1, 1, 0, 0}, {0, 0, 1, 1}});
        int[] initial = {1, 0, 1, 0};
        int[] afterA = net.fire(initial, acquireA);
        check(afterA != null && net.fire(afterA, acquireA) == null, "capacity violated on repeated acquire");
        check(Arrays.equals(net.fire(afterA, acquireB), net.fire(net.fire(initial, acquireB), acquireA)),
                "independent transitions did not commute");
        check(Arrays.equals(initial, new int[]{1, 0, 1, 0}), "firing mutated input marking");
        rejects(() -> new Net(List.of(fa, ha), List.of(
                new NetRule(sym("leak"), new int[]{1, 0}, new int[]{0, 0})),
                new int[][]{{1, 1}}), "invalid P-invariant accepted");
        rejects(() -> new Net(List.of(fa, fa), List.of(), new int[0][]), "duplicate places accepted");
        rejects(() -> new Net(List.of(fa, ha), List.of(), new int[][]{null}),
                "null P-invariant row accepted");
        rejects(() -> new Net(List.of(fa, ha), List.of(), null),
                "null P-invariant list accepted");
        rejects(() -> net.fire(null, acquireA),
                "null marking accepted by Petri transition");
        rejects(() -> net.fire(new int[]{0, -1, 1, 0}, acquireA),
                "disabled transition masked malformed marking");
        rejects(() -> net.totals(new int[]{1, -1, 1, 0}),
                "negative marking accepted by invariant totals");
        rejects(() -> new Net(List.of(fa, ha, fb),
                List.of(new NetRule(sym("huge"), new int[]{
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                        new int[]{0, 0, 0})),
                new int[][]{{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}}),
                "overflowing invariant coefficients admitted");

        Map<String, int[]> visited = new HashMap<>();
        var queue = new ArrayDeque<int[]>();
        queue.add(initial.clone());
        visited.put(key(initial), initial.clone());
        int edges = 0;
        while (!queue.isEmpty()) {
            int[] marking = queue.remove();
            check(Arrays.equals(net.totals(marking), new long[]{1, 1}), "P-invariant broken");
            int enabled = 0;
            for (NetRule rule : rules) {
                int[] after = net.fire(marking, rule);
                if (after == null) continue;
                enabled++;
                edges++;
                if (visited.putIfAbsent(key(after), after) == null) queue.add(after);
            }
            check(enabled == 2, "unexpected deadlock or enabled-count violation");
        }
        check(visited.size() == 4 && edges == 8,
                "unexpected reachable marking graph: " + visited.size() + " states, " + edges + " edges");
        for (int[] marking : visited.values()) {
            // Existential recoverability: every marking can reach the all-free marking.
            var todo = new ArrayDeque<int[]>();
            var seen = new HashSet<String>();
            todo.add(marking);
            seen.add(key(marking));
            boolean recovered = false;
            while (!todo.isEmpty()) {
                int[] state = todo.remove();
                if (Arrays.equals(state, initial)) { recovered = true; break; }
                for (NetRule rule : rules) {
                    int[] next = net.fire(state, rule);
                    if (next != null && seen.add(key(next))) todo.add(next);
                }
            }
            check(recovered, "unrecoverable reachable marking");
        }
        System.out.println("Petri net: " + visited.size() + " reachable markings and " + edges + " edges checked");
    }

    public static void main(String[] args) {
        checkStatecharts();
        checkPetriNets();
        System.out.println("PASS: bounded symbolic behavior reference models");
    }
}
