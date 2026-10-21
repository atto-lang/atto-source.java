import dev.oreslang.runtime.OresSymbol;
import java.util.*;

/** Bounded host-only reference for layout-independent symbolic behavior; not a guest engine. */
public final class BehaviorLayoutModel {
    private static OresSymbol s(String name) { return OresSymbol.process(name); }
    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    enum Layout { AOS, SOA, AOSOA }
    record Edge(OresSymbol from, OresSymbol event, OresSymbol to) { }
    record Key(int from, OresSymbol event) { }
    record Row(int entity, int state) { }
    private record View(int index, long epoch, Object owningStore) { }

    static final class Chart {
        final Map<OresSymbol, Integer> ids = new LinkedHashMap<>();
        final int[] parent;
        final Map<Key, Integer> edges = new HashMap<>();
        // Closed vocabulary, precomputed once: zero per-event Key allocations.
        private final Map<OresSymbol, int[]> compiledDispatch = new HashMap<>();
        Chart(List<OresSymbol> states, Map<OresSymbol, OresSymbol> parents, List<Edge> rules) {
            require(!states.isEmpty() && states.size() <= 4096, "empty or excessive states");
            for (OresSymbol state : states) {
                require(state != null, "null state Symbol");
                require(ids.putIfAbsent(state, ids.size()) == null, "duplicate state");
            }
            parent = new int[ids.size()];
            Arrays.fill(parent, -1);
            for (var entry : parents.entrySet()) {
                require(ids.containsKey(entry.getKey()) && ids.containsKey(entry.getValue()), "unknown parent");
                parent[ids.get(entry.getKey())] = ids.get(entry.getValue());
            }
            for (int state = 0; state < parent.length; state++) {
                Set<Integer> seen = new HashSet<>();
                for (int p = state; p != -1; p = parent[p]) require(seen.add(p), "parent cycle");
            }
            Set<OresSymbol> events = new HashSet<>();
            for (Edge rule : rules) {
                require(rule != null && rule.event() != null, "null transition/event Symbol");
                require(ids.containsKey(rule.from()) && ids.containsKey(rule.to()), "unknown transition state");
                Key key = new Key(ids.get(rule.from()), rule.event());
                require(edges.putIfAbsent(key, ids.get(rule.to())) == null, "duplicate transition key");
                events.add(rule.event());
            }
            // Strict static-memory admission: avoid an S*E table bomb in a
            // closed AOT model. Real guest storage uses the alloc/provider layer.
            require((long) ids.size() * events.size() <= 1_048_576L,
                    "compiled transition table exceeds memory budget");
            for (OresSymbol event : events) {
                int[] targets = new int[parent.length];
                for (int active = 0; active < parent.length; active++) {
                    targets[active] = slowStep(active, event);
                }
                compiledDispatch.put(event, targets);
            }
        }
        int id(OresSymbol state) {
            Integer id = ids.get(state);
            require(id != null, "unknown state");
            return id;
        }
        int slowStep(int active, OresSymbol event) {
            for (int p = active; p != -1; p = parent[p]) {
                Integer target = edges.get(new Key(p, event));
                if (target != null) return target;
            }
            return active;
        }
        int step(int active, OresSymbol event) {
            require(active >= 0 && active < parent.length, "state out of range");
            require(event != null, "null chart event");
            int[] targets = compiledDispatch.get(event);
            return targets == null ? active : targets[active];
        }
    }

    /** Exactly one authoritative layout; migrations invalidate epoch-bound views. */
    static final class Cohort {
        final int size, tile;
        private final Object storeIdentity = new Object();
        Layout layout;
        long epoch;
        Row[] aos;
        int[] soaEntity, soaState;
        int[][] tilesEntity, tilesState;

        Cohort(List<Row> rows, Layout layout, int tile) {
            require(tile > 0 && tile <= 64, "invalid tile width");
            require(!rows.isEmpty() && rows.size() <= 4096, "invalid cohort size");
            this.size = rows.size();
            this.tile = tile;
            Set<Integer> identifiers = new HashSet<>();
            for (Row row : rows) {
                require(row != null && row.entity() >= 0 && row.state() >= 0, "invalid cohort row");
                require(identifiers.add(row.entity()), "duplicate cohort entity ID");
            }
            install(rows, layout);
        }
        private void install(List<Row> source, Layout destination) {
            Row[] nextAos = null;
            int[] nextEntity = null, nextState = null;
            int[][] nextTilesEntity = null, nextTilesState = null;
            switch (destination) {
                case AOS -> nextAos = source.toArray(Row[]::new);
                case SOA -> {
                    nextEntity = new int[size]; nextState = new int[size];
                    for (int i = 0; i < size; i++) {
                        nextEntity[i] = source.get(i).entity(); nextState[i] = source.get(i).state();
                    }
                }
                case AOSOA -> {
                    int count = (size + tile - 1) / tile;
                    nextTilesEntity = new int[count][tile]; nextTilesState = new int[count][tile];
                    for (int[] lanes : nextTilesEntity) Arrays.fill(lanes, -1);
                    for (int[] lanes : nextTilesState) Arrays.fill(lanes, -1);
                    for (int i = 0; i < size; i++) {
                        nextTilesEntity[i / tile][i % tile] = source.get(i).entity();
                        nextTilesState[i / tile][i % tile] = source.get(i).state();
                    }
                }
            }
            aos = nextAos; soaEntity = nextEntity; soaState = nextState;
            tilesEntity = nextTilesEntity; tilesState = nextTilesState;
            layout = destination;
        }
        View view(int index) { bounds(index); return new View(index, epoch, storeIdentity); }
        Row read(View handle) {
            require(handle != null && handle.owningStore() == storeIdentity, "foreign layout view");
            require(handle.epoch() == epoch, "stale layout view");
            return get(handle.index());
        }
        void bounds(int i) { require(i >= 0 && i < size, "row index out of bounds"); }
        Row get(int i) {
            bounds(i);
            return switch (layout) {
                case AOS -> aos[i];
                case SOA -> new Row(soaEntity[i], soaState[i]);
                case AOSOA -> new Row(tilesEntity[i / tile][i % tile], tilesState[i / tile][i % tile]);
            };
        }
        void setState(int i, int state) {
            bounds(i); require(state >= 0, "negative state ordinal");
            switch (layout) {
                case AOS -> aos[i] = new Row(aos[i].entity(), state);
                case SOA -> soaState[i] = state;
                case AOSOA -> tilesState[i / tile][i % tile] = state;
            }
        }
        void migrate(Layout destination) {
            if (layout == destination) return;
            require(epoch < Long.MAX_VALUE, "layout epoch overflow");
            List<Row> snapshot = new ArrayList<>(size);
            for (int i = 0; i < size; i++) snapshot.add(get(i));
            install(snapshot, destination);
            epoch++;
        }
        void checkPadding() {
            if (layout != Layout.AOSOA) return;
            for (int i = size; i < tilesEntity.length * tile; i++) {
                ok(tilesEntity[i / tile][i % tile] == -1, "padded entity exposed");
                ok(tilesState[i / tile][i % tile] == -1, "padded state exposed");
            }
        }
    }

    /** Per-instance Petri markings, reoriented without changing logical values. */
    static final class Markings {
        final int instances, places, tile;
        Layout layout;
        int[][] aos, soa;
        int[][][] aosoa;
        Markings(int[][] rows, Layout destination, int tile) {
            require(rows != null && rows.length > 0 && rows.length <= 4096
                    && tile > 0 && tile <= 64, "invalid marking geometry");
            require(rows[0] != null, "null initial marking row");
            this.instances = rows.length; this.places = rows[0].length; this.tile = tile;
            require(places > 0 && places <= 256, "invalid place count");
            install(rows, destination);
        }
        void install(int[][] rows, Layout destination) {
            require(rows.length == instances, "instance dimension mismatch");
            int[][] naos = null, nsoa = null;
            int[][][] ntiled = null;
            for (int[] row : rows) {
                require(row != null && row.length == places, "place dimension mismatch");
                for (int value : row) require(value >= 0, "negative tokens");
            }
            switch (destination) {
                case AOS -> {
                    naos = new int[instances][places];
                    for (int i = 0; i < instances; i++) naos[i] = rows[i].clone();
                }
                case SOA -> {
                    nsoa = new int[places][instances];
                    for (int i = 0; i < instances; i++) for (int p = 0; p < places; p++) nsoa[p][i] = rows[i][p];
                }
                case AOSOA -> {
                    ntiled = new int[(instances + tile - 1) / tile][places][tile];
                    for (int i = 0; i < instances; i++) for (int p = 0; p < places; p++)
                        ntiled[i / tile][p][i % tile] = rows[i][p];
                }
            }
            aos = naos; soa = nsoa; aosoa = ntiled; layout = destination;
        }
        int[] get(int i) {
            require(i >= 0 && i < instances, "marking index out of bounds");
            int[] result = new int[places];
            for (int p = 0; p < places; p++) result[p] = switch (layout) {
                case AOS -> aos[i][p];
                case SOA -> soa[p][i];
                case AOSOA -> aosoa[i / tile][p][i % tile];
            };
            return result;
        }
        void set(int i, int[] values) {
            require(i >= 0 && i < instances && values.length == places, "bad marking update");
            for (int value : values) require(value >= 0, "negative token update");
            for (int p = 0; p < places; p++) switch (layout) {
                case AOS -> aos[i][p] = values[p];
                case SOA -> soa[p][i] = values[p];
                case AOSOA -> aosoa[i / tile][p][i % tile] = values[p];
            }
        }
        void migrate(Layout destination) {
            if (layout == destination) return;
            int[][] values = new int[instances][];
            for (int i = 0; i < instances; i++) values[i] = get(i);
            install(values, destination);
        }
    }

    record Arc(int place, int weight) { }
    record Rule(OresSymbol name, List<Arc> pre, List<Arc> post) {
        Rule { pre = List.copyOf(pre); post = List.copyOf(post); }
    }
    static final class Net {
        final int places;
        final Map<OresSymbol, Integer> placeIds;
        final List<Rule> rules;
        final int[][] invariants;
        Net(List<OresSymbol> namedPlaces, List<Rule> rules, int[][] invariants) {
            require(!namedPlaces.isEmpty() && namedPlaces.size() <= 256, "invalid net size");
            Map<OresSymbol, Integer> ids = new LinkedHashMap<>();
            for (OresSymbol symbol : namedPlaces) {
                require(symbol != null && ids.putIfAbsent(symbol, ids.size()) == null, "duplicate/invalid place Symbol");
            }
            this.placeIds = Map.copyOf(ids);
            this.places = namedPlaces.size(); this.rules = List.copyOf(rules);
            require(invariants != null && invariants.length <= 256, "invalid invariant count");
            // Validate before cloning, otherwise a null row escapes as an
            // unchecked NPE instead of the documented admission refusal.
            for (int[] invariant : invariants)
                require(invariant != null && invariant.length == places,
                        "bad invariant dimension");
            this.invariants = Arrays.stream(invariants).map(int[]::clone).toArray(int[][]::new);
            Set<OresSymbol> names = new HashSet<>();
            for (Rule rule : rules) {
                require(rule.name() != null && names.add(rule.name()), "duplicate/invalid net transition Symbol");
                for (List<Arc> arcs : List.of(rule.pre(), rule.post())) {
                    Set<Integer> seen = new HashSet<>();
                    for (Arc arc : arcs) {
                        require(arc.place() >= 0 && arc.place() < places && arc.weight() > 0, "invalid arc");
                        require(seen.add(arc.place()), "duplicate arc");
                    }
                }
                long[] delta = new long[places];
                for (Arc arc : rule.pre()) delta[arc.place()] -= arc.weight();
                for (Arc arc : rule.post()) delta[arc.place()] += arc.weight();
                for (int[] invariant : this.invariants) {
                    require(invariant.length == places, "bad invariant dimension");
                    long sum = 0;
                    for (int i = 0; i < places; i++) {
                        try {
                            sum = Math.addExact(sum, Math.multiplyExact((long) invariant[i], delta[i]));
                        } catch (ArithmeticException overflow) {
                            throw new IllegalArgumentException("P-invariant arithmetic overflow", overflow);
                        }
                    }
                    require(sum == 0, "P-invariant violated by transition");
                }
            }
        }
        int placeId(OresSymbol symbol) {
            Integer found = placeIds.get(symbol);
            require(found != null, "unknown Petri place Symbol");
            return found;
        }
        /** Returns null if disabled; computes a complete next marking before committing. */
        int[] fire(int[] current, Rule rule) {
            require(current != null && rule != null && current.length == places
                    && rules.contains(rule), "bad transition request");
            for (int count : current) require(count >= 0, "invalid marking");
            for (Arc arc : rule.pre()) if (current[arc.place()] < arc.weight()) return null;
            long[] next = new long[places];
            for (int i = 0; i < places; i++) next[i] = current[i];
            for (Arc arc : rule.pre()) next[arc.place()] -= arc.weight();
            for (Arc arc : rule.post()) next[arc.place()] += arc.weight();
            int[] result = new int[places];
            for (int i = 0; i < places; i++) {
                require(next[i] >= 0 && next[i] <= Integer.MAX_VALUE, "token overflow");
                result[i] = (int) next[i];
            }
            return result;
        }
    }

    /** Abstract ECS access-set compatibility; not a compiler-issued provenance capability. */
    /** Synthetic unforgeable-in-this-reference-model allocation ticket, NOT the real compiler proof. */
    static final class AllocationIdentity { private AllocationIdentity() { } }
    private static AllocationIdentity issuedReferenceAllocation() { return new AllocationIdentity(); }
    record Access(Set<OresSymbol> reads, Set<OresSymbol> writes, AllocationIdentity owner) {
        Access {
            reads = Set.copyOf(reads); writes = Set.copyOf(writes);
            require(Collections.disjoint(reads, writes), "contradictory ECS read/write effects");
        }
    }
    static boolean conflict(Access a, Access b) {
        require(a.owner() != null && b.owner() != null, "unknown ECS provenance");
        if (a.owner() != b.owner()) return false;
        for (OresSymbol key : a.writes()) if (b.writes().contains(key) || b.reads().contains(key)) return true;
        for (OresSymbol key : b.writes()) if (a.reads().contains(key)) return true;
        return false;
    }

    static void testCharts() {
        OresSymbol running = s("running"), idle = s("idle"), busy = s("busy");
        OresSymbol begin = s("begin"), end = s("end"), reset = s("reset");
        Chart chart = new Chart(List.of(running, idle, busy), Map.of(idle, running, busy, running), List.of(
                new Edge(idle, begin, busy), new Edge(busy, end, idle), new Edge(running, reset, idle)));
        List<Row> seed = new ArrayList<>();
        for (int i = 0; i < 11; i++) seed.add(new Row(1000 + i, chart.id(idle)));
        Cohort[] cohorts = Arrays.stream(Layout.values()).map(layout -> new Cohort(seed, layout, 4)).toArray(Cohort[]::new);
        List<OresSymbol> events = List.of(s("begin"), s("ignored"), s("reset"), s("end"));
        int operations = 0;
        for (int step = 0; step < 128; step++) {
            for (int i = 0; i < seed.size(); i++) {
                OresSymbol event = events.get((step + i) % events.size());
                for (Cohort cohort : cohorts) cohort.setState(i, chart.step(cohort.get(i).state(), event));
                Row expected = cohorts[0].get(i);
                for (Cohort cohort : cohorts) ok(cohort.get(i).equals(expected), "chart layout divergence");
                operations++;
            }
            if (step % 9 == 0) {
                for (Cohort cohort : cohorts) {
                    View before = cohort.view(0);
                    Layout next = Layout.values()[(cohort.layout.ordinal() + 1) % Layout.values().length];
                    cohort.migrate(next);
                    rejects(() -> cohort.read(before), "stale view accepted after migration");
                    cohort.checkPadding();
                }
            }
        }
        for (Cohort cohort : cohorts) {
            ok(cohort.get(3).entity() == 1003, "entity ID lost through migration");
            cohort.checkPadding();
        }
        rejects(() -> cohorts[1].read(cohorts[0].view(0)), "foreign layout view accepted");
        rejects(() -> new Cohort(List.of(new Row(1, 0), new Row(1, 0)), Layout.SOA, 4),
                "duplicate entity admitted");
        rejects(() -> new Cohort(List.of(new Row(-1, 0)), Layout.AOS, 4),
                "negative entity ID admitted");
        ok(chart.step(chart.id(busy), s("reset")) == chart.id(idle), "ancestor rule lost");
        for (int state = 0; state < chart.parent.length; state++)
            for (OresSymbol event : List.of(begin, end, reset, s("ignored")))
                ok(chart.step(state, event) == chart.slowStep(state, event),
                        "compiled dispatch diverged from ancestor oracle");
        rejects(() -> new Chart(List.of(idle, busy), Map.of(idle, busy, busy, idle), List.of()), "cycle accepted");
        rejects(() -> new Chart(List.of(idle, busy), Map.of(), List.of(
                new Edge(idle, begin, busy), new Edge(idle, begin, busy))), "duplicate accepted");
        ok(!s("private_state").publicToUntrustedCoreV1(), "private Symbol was auto-exported");
        rejects(() -> new Chart(List.of(idle, busy), Map.of(), List.of(
                new Edge(idle, null, busy))), "null event Symbol admitted");
        rejects(() -> chart.step(chart.id(idle), null), "null event accepted at chart step");
        System.out.println("chart: " + operations + " identical cohort updates across layouts");
    }

    static void testPetri() {
        Rule acquireA = new Rule(s("acquire_a"), List.of(new Arc(0, 1)), List.of(new Arc(1, 1)));
        Rule releaseA = new Rule(s("release_a"), List.of(new Arc(1, 1)), List.of(new Arc(0, 1)));
        Rule acquireB = new Rule(s("acquire_b"), List.of(new Arc(2, 1)), List.of(new Arc(3, 1)));
        Rule releaseB = new Rule(s("release_b"), List.of(new Arc(3, 1)), List.of(new Arc(2, 1)));
        Net net = new Net(List.of(s("free_a"), s("held_a"), s("free_b"), s("held_b")), List.of(acquireA, releaseA, acquireB, releaseB),
                new int[][]{{1, 1, 0, 0}, {0, 0, 1, 1}});
        ok(net.placeId(s("held_b")) == 3, "logical place Symbol lookup failed");
        rejects(() -> net.placeId(s("unknown")), "unknown place Symbol admitted");
        int[][] seed = new int[7][4];
        for (int[] marking : seed) { marking[0] = 1; marking[2] = 1; }
        Markings[] stores = Arrays.stream(Layout.values()).map(layout -> new Markings(seed, layout, 4)).toArray(Markings[]::new);
        List<Rule> events = List.of(acquireA, acquireB, acquireA, releaseB, releaseA, acquireB, releaseA, releaseB);
        int transitions = 0;
        for (int step = 0; step < 80; step++) {
            Rule rule = events.get(step % events.size());
            int index = step % 7;
            for (Markings store : stores) {
                int[] before = store.get(index), after = net.fire(before, rule);
                if (after != null) { store.set(index, after); transitions++; }
                ok(before[0] + before[1] == 1 && before[2] + before[3] == 1, "initial invariant failed");
                int[] actual = store.get(index);
                ok(actual[0] + actual[1] == 1 && actual[2] + actual[3] == 1, "P-invariant violated");
            }
            int[] expected = stores[0].get(index);
            for (Markings store : stores) ok(Arrays.equals(expected, store.get(index)), "Petri layout divergence");
            if (step % 11 == 0) for (Markings store : stores)
                store.migrate(Layout.values()[(store.layout.ordinal() + 1) % 3]);
        }
        rejects(() -> new Net(List.of(s("free"), s("held")), List.of(new Rule(s("leak"), List.of(new Arc(0, 1)), List.of())),
                new int[][]{{1, 1}}), "nonconserving arc accepted");
        rejects(() -> new Net(List.of(s("free"), s("held")), List.of(new Rule(s("duplicate"),
                List.of(new Arc(0, 1), new Arc(0, 2)), List.of())), new int[0][]), "duplicate arc accepted");
        rejects(() -> new Net(List.of(s("free"), s("free")), List.of(), new int[0][]),
                "duplicate Petri place Symbol admitted");
        rejects(() -> new Net(List.of(s("free"), s("held")), List.of(),
                new int[][]{{1}}), "empty-rule net admitted wrong invariant dimension");
        rejects(() -> new Net(List.of(s("free"), s("held")), List.of(),
                new int[][]{null}), "empty-rule net admitted null invariant row");
        rejects(() -> new Markings(new int[][]{null}, Layout.AOS, 4),
                "null first marking row should fail admission");
        rejects(() -> new Markings(new int[][]{{1, 0}, null}, Layout.SOA, 4),
                "null subsequent marking row should fail admission");
        rejects(() -> net.fire(null, acquireA),
                "null marking should fail admission");
        rejects(() -> new Net(List.of(s("free"), s("held"), s("extra")),
                List.of(new Rule(s("huge"), List.of(new Arc(0, Integer.MAX_VALUE),
                        new Arc(1, Integer.MAX_VALUE), new Arc(2, Integer.MAX_VALUE)),
                        List.of())),
                new int[][]{{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}}),
                "overflowed P-invariant arithmetic should fail admission");
        Net overflow = new Net(List.of(s("free")), List.of(new Rule(s("over"), List.of(), List.of(new Arc(0, 1)))), new int[0][]);
        rejects(() -> overflow.fire(new int[]{Integer.MAX_VALUE}, overflow.rules.getFirst()), "token overflow accepted");
        ok(net.fire(new int[]{0, 1, 1, 0}, acquireA) == null, "disabled transition fired");

        Map<String, int[]> visited = new HashMap<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        int[] initial = {1, 0, 1, 0};
        visited.put(Arrays.toString(initial), initial); todo.add(initial);
        int edges = 0;
        while (!todo.isEmpty()) {
            int[] current = todo.remove();
            int enabled = 0;
            for (Rule rule : net.rules) {
                int[] next = net.fire(current, rule);
                if (next == null) continue;
                enabled++; edges++;
                if (visited.putIfAbsent(Arrays.toString(next), next) == null) todo.add(next);
            }
            ok(enabled == 2, "unexpected deadlock");
        }
        ok(visited.size() == 4 && edges == 8, "wrong Petri reachable graph");
        System.out.println("Petri: " + transitions + " batched attempts accepted; 4 states / 8 edges checked");
    }

    static void testEcs() {
        OresSymbol behavior = s("behavior_state"), motion = s("motion"), effects = s("effects");
        AllocationIdentity owned = issuedReferenceAllocation();
        AllocationIdentity unrelated = issuedReferenceAllocation();
        Access step = new Access(Set.of(motion), Set.of(behavior), owned);
        Access sample = new Access(Set.of(behavior), Set.of(), owned);
        Access render = new Access(Set.of(motion), Set.of(effects), owned);
        ok(conflict(step, sample), "ECS write/read conflict missed");
        ok(!conflict(step, render), "disjoint ECS system incorrectly serialized");
        rejects(() -> conflict(step, new Access(Set.of(motion), Set.of(), null)), "unknown provenance admitted");
        ok(!conflict(step, new Access(Set.of(behavior), Set.of(), unrelated)), "disjoint allocations incorrectly serialized");
        ok(conflict(step, new Access(Set.of(), Set.of(s("behavior_state")), owned)),
                "logical Symbol equality lost across descriptors");
        rejects(() -> new Access(Set.of(motion), Set.of(motion), owned), "conflicting ECS effect declaration admitted");
        System.out.println("ECS: conservative access conflicts and unknown provenance checked");
    }

    static void testRandomizedLayoutParity() {
        Random random = new Random(0x5eed_1638L);
        OresSymbol idle = s("idle"), busy = s("busy"), parked = s("parked");
        Chart chart = new Chart(List.of(idle, busy, parked), Map.of(), List.of(
                new Edge(idle, s("start"), busy),
                new Edge(busy, s("pause"), parked),
                new Edge(parked, s("resume"), busy),
                new Edge(busy, s("stop"), idle)));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 23; i++) rows.add(new Row(i + 100, chart.id(idle)));
        Row[] expectedRows = rows.toArray(Row[]::new);
        Cohort[] cohorts = {
            new Cohort(rows, Layout.AOS, 3),
            new Cohort(rows, Layout.SOA, 7),
            new Cohort(rows, Layout.AOSOA, 16)
        };
        List<OresSymbol> events = List.of(s("start"), s("pause"), s("resume"), s("stop"), s("unknown"));
        Rule acquire = new Rule(s("take"), List.of(new Arc(0, 1)), List.of(new Arc(1, 1)));
        Rule release = new Rule(s("give"), List.of(new Arc(1, 1)), List.of(new Arc(0, 1)));
        Net net = new Net(List.of(s("free"), s("taken")), List.of(acquire, release),
                new int[][]{{1, 1}});
        int[][] seed = new int[19][2];
        for (int[] m : seed) m[0] = 1;
        int[][] expectedMarkings = Arrays.stream(seed).map(int[]::clone).toArray(int[][]::new);
        Markings[] stores = {
            new Markings(seed, Layout.AOS, 3),
            new Markings(seed, Layout.SOA, 7),
            new Markings(seed, Layout.AOSOA, 16)
        };

        for (int trial = 0; trial < 2000; trial++) {
            int row = random.nextInt(rows.size());
            OresSymbol event = events.get(random.nextInt(events.size()));
            ok(chart.step(expectedRows[row].state(), event)
                    == chart.slowStep(expectedRows[row].state(), event),
                    "randomized compiled dispatch divergence");
            expectedRows[row] = new Row(expectedRows[row].entity(),
                    chart.slowStep(expectedRows[row].state(), event));
            for (Cohort cohort : cohorts) cohort.setState(row, chart.step(cohort.get(row).state(), event));

            int instance = random.nextInt(seed.length);
            Rule rule = random.nextBoolean() ? acquire : release;
            int[] after = net.fire(expectedMarkings[instance], rule);
            if (after != null) expectedMarkings[instance] = after;
            for (Markings store : stores) {
                int[] actual = net.fire(store.get(instance), rule);
                if (actual != null) store.set(instance, actual);
            }

            if (trial % 11 == 0) {
                for (Cohort cohort : cohorts) {
                    Layout destination = Layout.values()[random.nextInt(Layout.values().length)];
                    View before = cohort.view(row);
                    Layout from = cohort.layout;
                    cohort.migrate(destination);
                    if (from != destination) rejects(() -> cohort.read(before), "stale randomized view accepted");
                }
                for (Markings store : stores)
                    store.migrate(Layout.values()[random.nextInt(Layout.values().length)]);
            }
            for (Cohort cohort : cohorts) {
                for (int i = 0; i < rows.size(); i++)
                    ok(cohort.get(i).equals(expectedRows[i]), "randomized chart data/layout mismatch");
                cohort.checkPadding();
            }
            for (Markings store : stores) {
                for (int i = 0; i < seed.length; i++)
                    ok(Arrays.equals(store.get(i), expectedMarkings[i]), "randomized Petri layout mismatch");
            }
        }
        System.out.println("randomized: 2000 deterministic chart/Petri operations x 3 layouts with migrations");
    }

    public static void main(String[] args) {
        testCharts(); testPetri(); testEcs(); testRandomizedLayoutParity();
        System.out.println("PASS: behavior layout host reference");
    }
}
