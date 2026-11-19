package atto.compiler.async;

import java.util.List;
import static atto.compiler.async.AttoFuturePipelinePlan.*;

/** Source-independent static type/effect/ownership gate, not async guest execution. */
public final class AttoFuturePipelinePlanProbe {
    private static int checks;
    private static void ok(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static void reject(String code, Runnable run) {
        try { run.run(); }
        catch (RuntimeException ex) {
            ok(ex.getMessage() != null && ex.getMessage().contains(code), "wrong error " + code);
            return;
        }
        throw new AssertionError("allowed bad Future pipeline " + code);
    }
    private static List<Stage> good() {
        return List.of(new Stage(Kind.TASK, false, null),
                       new Stage(Kind.AWAIT, true, null),
                       new Stage(Kind.PLUS_ONE, false, null),
                       new Stage(Kind.CAPTURE_VAL, false, "answer"),
                       new Stage(Kind.RETURN, false, null));
    }
    public static void main(String[] ignored) {
        Guard valid = new Guard(false, true);
        Plan p = check(true, valid, good());
        ok(p.futureConsumed() && p.returnsInt(), "future single consume + result");
        ok(p.guardCheckedBeforeTask(), "guard dominates task construction");
        ok(p.capture().equals("answer"), "terminal typed capture");
        ok(p.stages().get(0).output() == Type.FUTURE_INT, "task future typed");
        ok(p.stages().get(1).input() == Type.FUTURE_INT
           && p.stages().get(1).output() == Type.INT, "await unwrap explicitly");
        ok(caret_target(List.of(Scope.LAMBDA_BODY, Scope.CALLABLE))
           == Scope.LAMBDA_BODY, "lexical ^ stays in lambda");
        ok(caret_target(List.of(Scope.TASK_BODY, Scope.CALLABLE))
           == Scope.TASK_BODY, "lexical ^ stays in task");
        reject("E_CARET_SCOPE", () -> caret_target(List.of()));
        reject("E_GUARD_ELSE_NOT_REQUIRED", () -> check(true,
            new Guard(true, true), good()));
        reject("E_GUARD_FAIL_MUST_RETURN", () -> check(true,
            new Guard(false, false), good()));
        reject("E_AWAIT_REQUIRES_SUSP", () -> check(false, valid, good()));
        reject("E_AWAIT_REQUIRES_SUSP", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, false, null))));
        reject("E_PIPELINE_TYPE", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.PLUS_ONE, false, null))));
        reject("E_FUTURE_CONSUMED", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, true, null),
            new Stage(Kind.AWAIT, true, null))));
        reject("E_PIPELINE_INCOMPLETE", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, true, null))));
        reject("E_CAPTURE_TYPE", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null),
            new Stage(Kind.CAPTURE_VAL, false, "bad"))));
        reject("E_CAPTURE_NAME", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, true, null),
            new Stage(Kind.CAPTURE_VAL, false, "bad name"))));
        reject("E_RETURN_TYPE", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, true, null),
            new Stage(Kind.CAPTURE_VAL, false, "answer"),
            new Stage(Kind.RETURN, true, null))));
        reject("E_RETURN_TYPE", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.AWAIT, true, null),
            new Stage(Kind.CAPTURE_VAL, false, "answer"),
            new Stage(Kind.RETURN, false, null), new Stage(Kind.PLUS_ONE, false, null))));
        reject("E_TASK_OWNERSHIP", () -> check(true, valid, List.of(
            new Stage(Kind.TASK, false, null), new Stage(Kind.TASK, false, null))));
        System.out.println("ATTO_FUTURE_PIPELINE_PREHIR_PASS checks=" + checks);
    }
}
