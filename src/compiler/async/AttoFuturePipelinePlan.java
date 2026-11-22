package atto.compiler.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed pre-HIR planner for the restricted Future<int> pipeline.
 * No source lexer, generated guest, dynamic eval, or real scheduler is exposed.
 */
public final class AttoFuturePipelinePlan {
    public enum Type { INT, FUTURE_INT, VOID }
    public enum Kind { TASK, AWAIT, PLUS_ONE, CAPTURE_VAL, RETURN }
    public enum Scope { CALLABLE, TASK_BODY, LAMBDA_BODY }
    public record Stage(Kind kind, boolean susp, String name) {
        public Stage { Objects.requireNonNull(kind); }
    }
    public record Guard(boolean hasElse, boolean failBranchAlwaysReturns) {}
    public record CheckedStage(Kind kind, Type input, Type output) {}
    public record Plan(List<CheckedStage> stages, String capture, boolean futureConsumed,
                       boolean guardCheckedBeforeTask, boolean returnsInt) {
        public Plan { stages = List.copyOf(stages); }
    }
    private AttoFuturePipelinePlan() {}

    /** A lexical ^ exits exactly the closest callable/task/lambda, never its enclosing parent. */
    public static Scope caret_target(List<Scope> innermostFirst) {
        Objects.requireNonNull(innermostFirst);
        if (innermostFirst.isEmpty()) throw new IllegalArgumentException("E_CARET_SCOPE");
        return Objects.requireNonNull(innermostFirst.get(0));
    }

    public static Plan check(boolean enclosingSusp, Guard guard, List<Stage> pipeline) {
        Objects.requireNonNull(guard);
        Objects.requireNonNull(pipeline);
        if (guard.hasElse()) throw new IllegalArgumentException("E_GUARD_ELSE_NOT_REQUIRED");
        if (!guard.failBranchAlwaysReturns())
            throw new IllegalArgumentException("E_GUARD_FAIL_MUST_RETURN");
        if (pipeline.isEmpty() || pipeline.size() > 32)
            throw new IllegalArgumentException("E_PIPELINE_BUDGET");
        Type current = Type.INT;
        int taskCount = 0;
        int awaitCount = 0;
        String capture = null;
        boolean returned = false;
        List<CheckedStage> accepted = new ArrayList<>();
        Set<String> bound = new HashSet<>();
        for (int i = 0; i < pipeline.size(); i++) {
            Stage s = Objects.requireNonNull(pipeline.get(i));
            if (returned) throw new IllegalArgumentException("E_PIPELINE_AFTER_RETURN");
            Type incoming = current;
            switch (s.kind()) {
                case TASK -> {
                    if (++taskCount != 1 || current != Type.INT)
                        throw new IllegalArgumentException("E_TASK_OWNERSHIP");
                    if (s.susp()) throw new IllegalArgumentException("E_TASK_EFFECT");
                    current = Type.FUTURE_INT;
                }
                case AWAIT -> {
                    if (!enclosingSusp || !s.susp())
                        throw new IllegalArgumentException("E_AWAIT_REQUIRES_SUSP");
                    if (current != Type.FUTURE_INT || ++awaitCount != 1)
                        throw new IllegalArgumentException("E_FUTURE_CONSUMED");
                    current = Type.INT;
                }
                case PLUS_ONE -> {
                    if (s.susp() || current != Type.INT)
                        throw new IllegalArgumentException("E_PIPELINE_TYPE");
                    current = Type.INT;
                }
                case CAPTURE_VAL -> {
                    if (s.susp() || current != Type.INT)
                        throw new IllegalArgumentException("E_CAPTURE_TYPE");
                    if (s.name() == null || s.name().length() > 128
                            || !s.name().matches("[A-Za-z_][A-Za-z_0-9]*")
                            || !bound.add(s.name()))
                        throw new IllegalArgumentException("E_CAPTURE_NAME");
                    capture = s.name();
                }
                case RETURN -> {
                    if (current != Type.INT || s.susp() || i != pipeline.size() - 1)
                        throw new IllegalArgumentException("E_RETURN_TYPE");
                    if (taskCount != 1 || awaitCount != 1)
                        throw new IllegalArgumentException("E_PIPELINE_INCOMPLETE");
                    returned = true;
                    current = Type.VOID;
                }
            }
            accepted.add(new CheckedStage(s.kind(), incoming, current));
        }
        if (!returned || capture == null) throw new IllegalArgumentException("E_PIPELINE_INCOMPLETE");
        return new Plan(accepted, capture, true, true, true);
    }
}
