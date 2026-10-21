package dev.oreslang.runtime.formal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class RuntimeLifecycleModelCheckerTest {
    private enum Vm { NEW, STARTING, STARTED, FAILED, CLOSED }
    private enum Dispatcher { DOWN, STARTING, UP, FAILED, CLOSED }
    private enum Context { NEW, OPEN, CLOSED, FAILED }
    private enum Scheduler { NEW, RUNNING, CLOSED }
    private enum Task { NONE, QUEUED, RUNNING, SETTLED }
    private enum Future { NEW, PENDING, SUCCEEDED, FAILED, CANCELLED }
    private enum Actor { NONE, CREATED, RUNNING, STOPPING, TERMINATED }
    private enum Channel { OPEN, CLOSED }

    private record State(Vm vm, Dispatcher dispatcher, boolean binding, Context context,
                         Scheduler scheduler, Task task, Future future,
                         Actor actor, Channel channel) {}

    @Test
    void exhaustivelyChecksLifecycleSafety() {
        State initial = new State(Vm.NEW, Dispatcher.DOWN, false, Context.NEW,
                Scheduler.NEW, Task.NONE, Future.NEW, Actor.NONE, Channel.OPEN);
        ArrayDeque<State> work = new ArrayDeque<>();
        Set<State> seen = new HashSet<>();
        work.add(initial);
        int transitions = 0;

        while (!work.isEmpty()) {
            State state = work.removeFirst();
            if (!seen.add(state)) continue;
            assertSafety(state);
            for (State next : next(state)) {
                transitions++;
                assertSafety(next);
                if (!seen.contains(next)) work.addLast(next);
            }
        }

        assertTrue(seen.size() > 20, "model exploration was unexpectedly small");
        assertTrue(transitions >= seen.size(), "transition relation was not explored");
    }

    private static Set<State> next(State s) {
        Set<State> out = new HashSet<>();
        if (s.vm == Vm.NEW)
            out.add(withVm(s, Vm.STARTING));
        if (s.vm == Vm.STARTING && s.dispatcher == Dispatcher.DOWN)
            out.add(withDispatcher(s, Dispatcher.STARTING));
        if (s.vm == Vm.STARTING && s.dispatcher == Dispatcher.STARTING) {
            out.add(new State(Vm.STARTED, Dispatcher.UP, s.binding, s.context,
                    s.scheduler, s.task, s.future, s.actor, s.channel));
            out.add(new State(Vm.FAILED, Dispatcher.FAILED, s.binding, s.context,
                    s.scheduler, s.task, s.future, s.actor, s.channel));
        }
        if (s.vm == Vm.STARTED && s.dispatcher == Dispatcher.UP && !s.binding)
            out.add(new State(s.vm, s.dispatcher, true, s.context, s.scheduler,
                    s.task, s.future, s.actor, s.channel));
        if (s.vm == Vm.STARTED && s.dispatcher == Dispatcher.UP && s.context == Context.NEW)
            out.add(new State(s.vm, s.dispatcher, s.binding, Context.OPEN, s.scheduler,
                    s.task, s.future, s.actor, s.channel));
        if (s.context == Context.OPEN && s.scheduler == Scheduler.NEW)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, Scheduler.RUNNING,
                    s.task, s.future, s.actor, s.channel));
        if (s.context == Context.OPEN && s.scheduler == Scheduler.RUNNING && s.task == Task.NONE)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    Task.QUEUED, Future.PENDING, s.actor, s.channel));
        if (s.task == Task.QUEUED)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    Task.RUNNING, s.future, s.actor, s.channel));
        if (s.task == Task.RUNNING)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    Task.SETTLED, Future.SUCCEEDED, s.actor, s.channel));
        if ((s.task == Task.QUEUED || s.task == Task.RUNNING) && s.future == Future.PENDING)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    Task.SETTLED, Future.CANCELLED, s.actor, s.channel));
        if (s.context == Context.OPEN && s.scheduler == Scheduler.RUNNING
                && s.actor == Actor.NONE && s.channel == Channel.OPEN)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    s.task, s.future, Actor.CREATED, s.channel));
        if (s.actor == Actor.CREATED)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    s.task, s.future, Actor.RUNNING, s.channel));
        if (s.actor == Actor.CREATED || s.actor == Actor.RUNNING)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    s.task, s.future, Actor.STOPPING, s.channel));
        if (s.actor == Actor.STOPPING)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    s.task, s.future, Actor.TERMINATED, s.channel));
        if (s.channel == Channel.OPEN
                && (s.actor == Actor.NONE || s.actor == Actor.TERMINATED))
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, s.scheduler,
                    s.task, s.future, s.actor, Channel.CLOSED));
        if (s.scheduler == Scheduler.RUNNING && s.task == Task.SETTLED && s.actor == Actor.NONE)
            out.add(new State(s.vm, s.dispatcher, s.binding, s.context, Scheduler.CLOSED,
                    s.task, s.future, s.actor, s.channel));
        if (s.context == Context.OPEN && s.scheduler == Scheduler.CLOSED && s.actor == Actor.NONE)
            out.add(new State(s.vm, s.dispatcher, s.binding, Context.CLOSED, s.scheduler,
                    s.task, s.future, s.actor, s.channel));
        if ((s.vm == Vm.STARTED || s.vm == Vm.FAILED)
                && s.context != Context.OPEN && s.scheduler != Scheduler.RUNNING
                && s.actor != Actor.RUNNING && s.actor != Actor.STOPPING)
            out.add(new State(Vm.CLOSED, Dispatcher.CLOSED, false,
                    s.context == Context.NEW ? Context.NEW : Context.CLOSED,
                    s.scheduler, s.task, s.future, s.actor, s.channel));
        return out;
    }

    private static void assertSafety(State s) {
        assertTrue(!s.binding || (s.vm == Vm.STARTED && s.dispatcher == Dispatcher.UP),
                "published VM binding escaped startup");
        assertTrue(s.context != Context.OPEN
                        || (s.vm == Vm.STARTED && s.dispatcher == Dispatcher.UP),
                "context opened before VM startup");
        assertTrue(s.scheduler != Scheduler.RUNNING || s.context == Context.OPEN,
                "scheduler escaped context ownership");
        assertTrue(s.task != Task.QUEUED && s.task != Task.RUNNING
                        || (s.context == Context.OPEN && s.scheduler == Scheduler.RUNNING),
                "live task escaped scheduler/context ownership");
        assertTrue(s.future != Future.SUCCEEDED && s.future != Future.FAILED
                        && s.future != Future.CANCELLED || s.task == Task.SETTLED,
                "Future settled before task");
        assertTrue(s.actor == Actor.NONE || s.actor == Actor.TERMINATED
                        || s.context == Context.OPEN,
                "live actor escaped context ownership");
        assertTrue(s.channel != Channel.CLOSED
                        || (s.actor != Actor.RUNNING && s.actor != Actor.STOPPING),
                "live actor survived channel closure");
    }

    private static State withVm(State s, Vm vm) {
        return new State(vm, s.dispatcher, s.binding, s.context, s.scheduler,
                s.task, s.future, s.actor, s.channel);
    }

    private static State withDispatcher(State s, Dispatcher dispatcher) {
        return new State(s.vm, dispatcher, s.binding, s.context, s.scheduler,
                s.task, s.future, s.actor, s.channel);
    }
}
