// M9 JVM/GraalVM host supervisor receipt parity for the restricted compiled actor.
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public final class M9JavaSupervisorNoticeProbe {
    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    private static AttoM7CooperativeRuntime.Frame done(long result) {
        return new AttoM7CooperativeRuntime.Frame() {
            private int pc;
            @Override public AttoM7CooperativeRuntime.Step advance(
                    AttoM7CooperativeRuntime.Slice slice) {
                pc = 4;
                return AttoM7CooperativeRuntime.Step.DONE;
            }
            @Override public long result() { return result; }
            @Override public int resume_pc() { return pc; }
        };
    }
    public static void run() throws Exception {
        try (var rt = new AttoM7CooperativeRuntime(2, 8, 4, 100)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var fault = rt.spawn("m9-fault", 2, value ->
                new AttoM7CooperativeRuntime.Frame() {
                    @Override public AttoM7CooperativeRuntime.Step advance(
                            AttoM7CooperativeRuntime.Slice slice) throws Exception {
                        entered.countDown();
                        require(release.await(10, TimeUnit.SECONDS),
                            "faulting guest callback release deadline");
                        throw new ArithmeticException("checked i64 trap");
                    }
                    @Override public long result() { return value; }
                    @Override public int resume_pc() { return 0; }
                }, 777);
            try {
                require(rt.try_send(fault, 1) == AttoM7CooperativeRuntime.Admission.ACCEPTED,
                    "faulting send not admitted");
                require(entered.await(5, TimeUnit.SECONDS), "guest callback did not start");
                require(rt.try_send(fault, 2) == AttoM7CooperativeRuntime.Admission.ACCEPTED,
                    "queued DATA 2 not accepted");
                require(rt.try_send(fault, 3) == AttoM7CooperativeRuntime.Admission.ACCEPTED,
                    "queued DATA 3 not accepted");
                require(rt.try_send(fault, 4) == AttoM7CooperativeRuntime.Admission.FULL,
                    "saturated bounded DATA was incorrectly admitted");
                require(rt.request_stop(fault) && rt.request_stop(fault),
                    "duplicate STOP incorrectly failed");
                var stop = rt.await_supervisor_notice(5_000);
                require(stop != null && stop.kind() == AttoM7CooperativeRuntime.ControlKind.STOP &&
                    stop.actorName().equals("m9-fault") && stop.generation() == 1 &&
                    stop.supervisorId() == 777 && stop.messageId() == 0 &&
                    stop.faultCode() == 0, "STOP not delivered through independent CONTROL: " + stop);
                require(rt.try_send(fault, 99) != AttoM7CooperativeRuntime.Admission.ACCEPTED,
                    "STOP did not fence fresh DATA");
                AttoM7CooperativeRuntime.check_conservation(rt.snapshot(fault));
                release.countDown();
                var panic = rt.await_supervisor_notice(10_000);
                require(panic != null &&
                    panic.kind() == AttoM7CooperativeRuntime.ControlKind.PANIC &&
                    panic.actorName().equals("m9-fault") &&
                    panic.generation() == 1 && panic.supervisorId() == 777 &&
                    panic.messageId() == 1 && panic.faultCode() == 1 &&
                    panic.reason().equals("checked arithmetic"),
                    "PANIC notice missing fault/actor/parent/message identity: " + panic);
                require(rt.await_terminal(fault, 10_000), "faulting actor did not retire");
                var snapshot = rt.snapshot(fault);
                require(snapshot.accepted() == 3 && snapshot.completed() == 0 &&
                    snapshot.failed() == 1 && snapshot.abandoned() == 2 &&
                    snapshot.controlsDelivered() == 2,
                    "STOP/PANIC conservation or coalescing violated: " + snapshot);
                AttoM7CooperativeRuntime.check_conservation(snapshot);
                require(rt.poll_supervisor_notice() == null,
                    "unexpected duplicate CONTROL notice");
                require(rt.try_send(fault, 7) == AttoM7CooperativeRuntime.Admission.STALE,
                    "retired actor identity regained mailbox authority");
                var reincarnated = rt.spawn("m9-fault", 2,
                    M9JavaSupervisorNoticeProbe::done, 778);
                require(reincarnated.generation() == 2,
                    "actor incarnation generation failed to increment");
                require(rt.try_send(fault, 1) == AttoM7CooperativeRuntime.Admission.STALE,
                    "stale generation entered replacement actor");
                require(rt.try_send(reincarnated, 8) ==
                    AttoM7CooperativeRuntime.Admission.ACCEPTED,
                    "new generation cannot process DATA");
                require(rt.request_stop(reincarnated), "new incarnation STOP rejected");
                var secondStop = rt.await_supervisor_notice(5_000);
                require(secondStop != null && secondStop.generation() == 2 &&
                    secondStop.supervisorId() == 778 &&
                    secondStop.kind() == AttoM7CooperativeRuntime.ControlKind.STOP,
                    "new incarnation supervisor notice malformed: " + secondStop);
                require(rt.await_terminal(reincarnated, 10_000),
                    "replacement actor did not retire");
                var next = rt.snapshot(reincarnated);
                require(next.accepted() == 1 && next.completed() == 1 &&
                    next.resultSum() == 8, "new actor lost DATA: " + next);
                AttoM7CooperativeRuntime.check_conservation(next);
            } finally {
                release.countDown();
            }
        }
        System.out.println("M9_JAVA_PRIORITY_STOP_THEN_PANIC_NOTICE=PASS");
        System.out.println("M9_JAVA_SATURATED_DATA_CONTROL_INDEPENDENT=PASS");
        System.out.println("M9_JAVA_FAULT_MESSAGE_GENERATION_METADATA=PASS");
        System.out.println("M9_JAVA_ACCEPTED_FAILED_ABANDONED_CONSERVATION=PASS");
        System.out.println("M9_JAVA_REINCARNATION_FENCE=PASS");
        System.out.println("M9_JAVA_SCOPE=HOST_CONTROL_NOT_JAVA_COMPILER_FULL_ACTOR_PARITY");
    }
}
