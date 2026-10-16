#!/usr/bin/env sh
# Strict JDK 21 host API/authorization smoke for the PRODUCTION router source.
# Local ActorRuntime/RoutePlan doubles test call semantics and compilation;
# they DO NOT prove true ActorRuntime mailbox delivery, ownership or STOP.
set -eu
cd "$(dirname "$0")/.."
test -f src/main/java/dev/oreslang/runtime/ActorPipelineMailboxRouter.java
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/src/dev/oreslang/runtime" "$tmp/classes"
cat > "$tmp/src/dev/oreslang/runtime/ActorRuntime.java" <<'JAVA'
package dev.oreslang.runtime;
import java.util.*;
public final class ActorRuntime {
    public enum ActorKind { SHARED, PRIVATE, UNTRUSTED }
    public record ActorId(UUID value) {}
    public static final class ActorRef<M> {
        private final ActorId id = new ActorId(UUID.randomUUID());
        private boolean alive = true;
        private final List<M> received = Collections.synchronizedList(new ArrayList<>());
        public ActorId id() { return id; }
        public boolean isAlive() { return alive; }
        public void stop() { alive = false; }
        public List<M> received() { return received; }
    }
    private static final ThreadLocal<ActorRuntime> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<ActorId> ID = new ThreadLocal<>();
    private static final ThreadLocal<ActorKind> KIND = new ThreadLocal<>();
    public static boolean inActorExecution() { return ACTIVE.get()!=null; }
    public static boolean inActorGroupMailmanExecution() { return false; }
    public static ActorRuntime currentActorRuntime() { return ACTIVE.get(); }
    public static ActorKind currentActorKind() { return KIND.get(); }
    public static Optional<ActorId> currentActorId() {
        return Optional.ofNullable(ID.get());
    }
    void requirePipelineRegistrationHostAuthority() {
        if (inActorExecution()) throw new SecurityException("supervisor only");
    }
    void requireCurrentPipelineReceiveTurn() {
        if (!inActorExecution()) throw new SecurityException("no receive turn");
    }
    public <M> boolean isAlive(ActorRef<M> ref) { return ref.isAlive(); }
    public <M> void send(ActorRef<M> ref, M value) {
        if (!ref.isAlive()) throw new IllegalStateException("stopped mailbox");
        ref.received().add(value);
    }
    public void enter(ActorRef<?> ref, ActorKind kind) {
        ACTIVE.set(this); ID.set(ref.id()); KIND.set(kind);
    }
    public void leave() { ACTIVE.remove(); ID.remove(); KIND.remove(); }
}
JAVA
cat > "$tmp/src/dev/oreslang/runtime/OresScheduler.java" <<'JAVA'
package dev.oreslang.runtime;
public final class OresScheduler {
    public static Object current() { return null; }
}
JAVA
cat > "$tmp/src/dev/oreslang/runtime/ActorPipelineRoutePlan.java" <<'JAVA'
package dev.oreslang.runtime;
import java.util.*;
public final class ActorPipelineRoutePlan {
    public record Edge(String source, String target, String payloadType) { }
    private final List<Edge> edges;
    public ActorPipelineRoutePlan(List<Edge> edges) {
        this.edges = List.copyOf(edges);
    }
    public List<Edge> edges() { return edges; }
    public List<Edge> forwardAll(String source) {
        var out = edges.stream().filter(e -> e.source().equals(source)).toList();
        if (out.isEmpty()) throw new IllegalArgumentException("no edge");
        return out;
    }
    public Edge forward(String source) {
        var out = forwardAll(source);
        if (out.size() != 1) throw new IllegalArgumentException("ambiguous");
        return out.getFirst();
    }
    public Edge forwardTo(String source, String target) {
        return forwardAll(source).stream().filter(e -> e.target().equals(target))
                .findFirst().orElseThrow(IllegalArgumentException::new);
    }
    public Edge forwardByKey(String source, String key) {
        var out = forwardAll(source);
        return out.get(Math.floorMod(key.hashCode(), out.size()));
    }
}
JAVA
cat > "$tmp/src/dev/oreslang/runtime/RouterHarness.java" <<'JAVA'
package dev.oreslang.runtime;
import java.util.*;
public final class RouterHarness {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static void denied(Runnable action,
            Class<? extends RuntimeException> kind) {
        checks++;
        try {
            action.run();
            throw new AssertionError("operation was not denied");
        } catch (RuntimeException failure) {
            if (!kind.isInstance(failure)) throw new AssertionError(failure);
        }
    }
    public static void main(String[] args) {
        var runtime = new ActorRuntime();
        ActorRuntime.ActorRef<String> source = new ActorRuntime.ActorRef<>();
        ActorRuntime.ActorRef<String> a = new ActorRuntime.ActorRef<>();
        ActorRuntime.ActorRef<String> b = new ActorRuntime.ActorRef<>();
        var graph = new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Edge("src", "a", "Event"),
                new ActorPipelineRoutePlan.Edge("src", "b", "Event")));
        var bound = Map.of("src", source, "a", a, "b", b);
        var route = new ActorPipelineMailboxRouter<>(runtime, graph, bound);
        denied(() -> route.forwardAll("missing actor"), SecurityException.class);
        runtime.enter(source, ActorRuntime.ActorKind.SHARED);
        denied(() -> new ActorPipelineMailboxRouter<>(runtime, graph, bound),
                SecurityException.class);
        var receipts = route.forwardAll("all");
        check(receipts.size() == 2
                && receipts.stream().allMatch(
                    ActorPipelineMailboxRouter.Admission::admitted),
                "fanout must admit two independent copies");
        route.forwardRoundRobin("rr1");
        route.forwardRoundRobin("rr2");
        check(a.received().equals(List.of("all", "rr1")), "first round robin");
        check(b.received().equals(List.of("all", "rr2")), "second round robin");
        runtime.leave();
        var fresh = new ActorPipelineMailboxRouter<>(runtime, graph, bound);
        runtime.enter(source, ActorRuntime.ActorKind.SHARED);
        fresh.forwardRoundRobin("fresh");
        check(a.received().getLast().equals("fresh"),
                "new graph binding must reset its cursor");
        runtime.leave();
        var foreign = new ActorRuntime.ActorRef<String>();
        runtime.enter(foreign, ActorRuntime.ActorKind.SHARED);
        denied(() -> route.forwardAll("spoofed"), SecurityException.class);
        runtime.leave();
        runtime.enter(source, ActorRuntime.ActorKind.PRIVATE);
        denied(() -> route.forwardRoundRobin("private"), SecurityException.class);
        runtime.leave();
        runtime.enter(source, ActorRuntime.ActorKind.UNTRUSTED);
        denied(() -> route.forwardRoundRobin("untrusted"), SecurityException.class);
        runtime.leave();
        b.stop();
        runtime.enter(source, ActorRuntime.ActorKind.SHARED);
        var partial = route.forwardAll("partial");
        check(partial.get(0).admitted() && !partial.get(1).admitted()
                && partial.get(1).failure() != null,
                "fanout must report independent failures");
        denied(() -> route.forwardTo("arbitrary", "bad"),
                IllegalArgumentException.class);
        runtime.leave();
        route.close();
        runtime.enter(source, ActorRuntime.ActorKind.SHARED);
        denied(() -> route.forwardAll("retired"), IllegalStateException.class);
        denied(route::close, SecurityException.class);
        runtime.leave();
        System.out.println("ORES_PIPELINE_ROUTER_TEST_DOUBLE_PASS checks=" + checks);
    }
}
JAVA
# Synthetic multi-carrier race (test double only): verifies router's read/write
# retirement barrier, NOT the runtime's single-flight actor execution.
cat > "$tmp/src/dev/oreslang/runtime/RetirementRaceHarness.java" <<'JAVA'
package dev.oreslang.runtime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class RetirementRaceHarness {
    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 40; round++) {
            var runtime = new ActorRuntime();
            var source = new ActorRuntime.ActorRef<String>();
            var a = new ActorRuntime.ActorRef<String>();
            var b = new ActorRuntime.ActorRef<String>();
            var graph = new ActorPipelineRoutePlan(List.of(
                    new ActorPipelineRoutePlan.Edge("src", "a", "Event"),
                    new ActorPipelineRoutePlan.Edge("src", "b", "Event")));
            var router = new ActorPipelineMailboxRouter<>(runtime, graph,
                    Map.of("src", source, "a", a, "b", b));
            var closed = new AtomicBoolean();
            var acceptedAfterClose = new AtomicInteger();
            var unexpected = new AtomicInteger();
            var start = new CountDownLatch(1);
            var workers = new ArrayList<Thread>();
            for (int t = 0; t < 8; t++) {
                var worker = new Thread(() -> {
                    runtime.enter(source, ActorRuntime.ActorKind.SHARED);
                    try {
                        start.await();
                        for (int i = 0; i < 1200; i++) {
                            boolean startedAfterClose = closed.get();
                            try {
                                router.forwardRoundRobin("value");
                                if (startedAfterClose) acceptedAfterClose.incrementAndGet();
                            } catch (IllegalStateException expected) {
                                // Retiring/retired state must deny, never wait.
                            } catch (RuntimeException wrong) {
                                unexpected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        unexpected.incrementAndGet();
                    } finally {
                        runtime.leave();
                    }
                });
                workers.add(worker);
                worker.start();
            }
            start.countDown();
            Thread.sleep(1);
            router.close();
            closed.set(true);
            for (Thread worker : workers) worker.join();
            if (acceptedAfterClose.get() != 0 || unexpected.get() != 0) {
                throw new AssertionError("post-close routed admission at round " + round);
            }
        }
        System.out.println(
                "ORES_PIPELINE_ROUTER_CLOSE_RACE_PASS rounds=40 threads=8 attempts=384000");
    }
}
JAVA
# Compile the real production router source, never a copy of its methods.
javac --release 21 -Xlint:all -Werror -d "$tmp/classes" \
    "$tmp/src/dev/oreslang/runtime/ActorRuntime.java" \
    "$tmp/src/dev/oreslang/runtime/OresScheduler.java" \
    "$tmp/src/dev/oreslang/runtime/ActorPipelineRoutePlan.java" \
    src/main/java/dev/oreslang/runtime/ActorPipelineMailboxRouter.java \
    "$tmp/src/dev/oreslang/runtime/RouterHarness.java" \
    "$tmp/src/dev/oreslang/runtime/RetirementRaceHarness.java"
java -cp "$tmp/classes" dev.oreslang.runtime.RouterHarness
java -cp "$tmp/classes" dev.oreslang.runtime.RetirementRaceHarness
