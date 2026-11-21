package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compiler-only, deliberately tiny executable Task frame for owned scalar values.
 *
 * <p>This is a host-side lowering target, NOT a source-level Task admission
 * primitive. It does not capture an Env, AST closure, ActorRef, arbitrary
 * object, borrow, runtime handle, or guest callback. Only the compiler may
 * decide whether a guest expression maps to one of these pure operations.
 * Only the narrowly validated immediate root scalar await is admitted;
 * all other guest Task shapes remain gated by TypeChecker.
 *
 * <p>Scalar values are snapshotted before scheduling, so subsequent changes
 * to the parent's variable bindings cannot change the worker's input.
 * The caller still owns admission, code pinning, structured scope, and
 * physical quiescence via OresTaskScope.startCompilerFrame.
 */
final class OresScalarTaskFrame implements OresScheduler.Task<Object> {
    private static final int MAX_CAPTURES = 16;
    // Temporary guest scalar v0 bound until compiler-issued memory quotas exist.
    private static final int MAX_STRING_CODE_UNITS = 1_048_576;
    private enum Operation { RETURN_CAPTURE, ADD_INTS, RETURN_CONSTANT }

    private final Object[] captures;
    private final Operation operation;
    private final int left;
    private final int right;
    private final Object constant;
    private final AtomicBoolean entered = new AtomicBoolean();

    private OresScalarTaskFrame(
            Operation operation, List<?> values, int left, int right, Object constant) {
        this.operation = Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(values, "captures");
        if (values.size() > MAX_CAPTURES) {
            throw new IllegalArgumentException("scalar Task capture limit exceeded");
        }
        captures = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) {
            captures[i] = snapshot(values.get(i));
        }
        this.left = left;
        this.right = right;
        this.constant = constant == null ? null : snapshot(constant);
        if (operation == Operation.RETURN_CAPTURE) {
            requireIndex(left);
        } else if (operation == Operation.ADD_INTS) {
            requireIndex(left);
            requireIndex(right);
            if (!(captures[left] instanceof Long) || !(captures[right] instanceof Long)) {
                throw new IllegalArgumentException("scalar Task integer addition requires int captures");
            }
        } else if (operation == Operation.RETURN_CONSTANT && this.constant == null) {
            throw new IllegalArgumentException("scalar Task constant must be a scalar");
        }
    }

    static OresScalarTaskFrame returnCapture(List<?> captures, int index) {
        return new OresScalarTaskFrame(Operation.RETURN_CAPTURE, captures, index, -1, null);
    }

    static OresScalarTaskFrame addInts(List<?> captures, int left, int right) {
        return new OresScalarTaskFrame(Operation.ADD_INTS, captures, left, right, null);
    }

    static OresScalarTaskFrame returnConstant(Object scalar) {
        return new OresScalarTaskFrame(Operation.RETURN_CONSTANT, List.of(), -1, -1, scalar);
    }

    private void requireIndex(int index) {
        if (index < 0 || index >= captures.length) {
            throw new IllegalArgumentException("scalar Task capture index out of bounds");
        }
    }

    /**
     * Strict exact-class whitelist. Interface/nominal trait claims, runtime
     * wrappers, mutable aggregates, host handles and null cannot spoof a scalar.
     */
    private static Object snapshot(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("scalar Task captures cannot be null");
        }
        Class<?> kind = value.getClass();
        if (kind == Long.class || kind == Double.class || kind == Boolean.class) {
            return value; // immutable final Java value; not an owned guest pointer
        }
        if (kind == String.class) {
            String scalar = (String) value;
            if (scalar.length() > MAX_STRING_CODE_UNITS) {
                throw new IllegalArgumentException("scalar Task string exceeds copy quota");
            }
            return new String(scalar);
        }
        throw new IllegalArgumentException("scalar Task capture requires an owned primitive or string");
    }

    @Override
    public OresScheduler.Step<Object> resume(OresScheduler.Resume resume) {
        if (!entered.compareAndSet(false, true)) {
            throw new IllegalStateException("terminal scalar Task frame resumed twice");
        }
        Object result = switch (operation) {
            case RETURN_CAPTURE -> captures[left];
            case RETURN_CONSTANT -> constant;
            case ADD_INTS -> Math.addExact((Long) captures[left], (Long) captures[right]);
        };
        return OresScheduler.done(result);
    }
}
